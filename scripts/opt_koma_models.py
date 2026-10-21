#!/usr/bin/env python3
"""Оптимизация моделей KOMA под onnxruntime-mobile.

onnxruntime-mobile собирается с урезанным набором операторов (см.
tools/ci_build/github/android/mobile_package.required_operators.config).
В списке НЕТ BatchNormalization, HardSwish и Constant — а обе модели KOMA их
используют, поэтому сессия падала («ONNX не работает») или роняла процесс.

Что делаем без потери качества:
  * Constant-узлы поднимаем в инициализаторы графа;
  * BatchNormalization сворачиваем в предшествующий Conv (это ещё и быстрее:
    один слой вместо двух);
  * HardSwish раскладываем на HardSigmoid(alpha=1/6, beta=0.5) + Mul — это
    ровно его определение.

Использование: python3 scripts_opt_models.py <каталог с моделями>
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper

ALLOWED = set("""Abs,Add,And,ArgMax,ArgMin,AveragePool,Cast,Ceil,Clip,Concat,ConstantOfShape,Conv,
ConvTranspose,Cos,CumSum,DepthToSpace,DequantizeLinear,Div,DynamicQuantizeLinear,Elu,Equal,Exp,
Expand,Flatten,Floor,Gather,GatherND,Gemm,Greater,GreaterOrEqual,Identity,If,LRN,LeakyRelu,Less,
LessOrEqual,Log,LogSoftmax,Loop,MatMul,Max,MaxPool,Mean,Min,Mul,Neg,NonMaxSuppression,NonZero,Not,
Or,PRelu,Pad,Pow,QuantizeLinear,Range,Reciprocal,ReduceMax,ReduceMean,ReduceMin,ReduceProd,ReduceSum,
Relu,Reshape,Resize,ReverseSequence,Round,ScatterND,Shape,Sigmoid,Sin,Size,Slice,Softmax,SpaceToDepth,
Split,Sqrt,Squeeze,Sub,Sum,Tanh,ThresholdedRelu,Tile,TopK,Transpose,Unique,Unsqueeze,Where,Erf,
GlobalAveragePool,InstanceNormalization,HardSigmoid,Scan,LayerNormalization""".replace("\n", "").split(","))


def lift_constants(graph):
    """Constant -> инициализатор: у mobile-сборки нет ядра Constant."""
    inits, keep = [], []
    for node in graph.node:
        if node.op_type != "Constant":
            keep.append(node)
            continue
        attrs = {a.name: a for a in node.attribute}
        if "value" in attrs:
            tensor = attrs["value"].t
        elif "value_float" in attrs:
            tensor = numpy_helper.from_array(np.array(attrs["value_float"].f, dtype=np.float32))
        elif "value_floats" in attrs:
            tensor = numpy_helper.from_array(np.array(attrs["value_floats"].floats, dtype=np.float32))
        elif "value_int" in attrs:
            tensor = numpy_helper.from_array(np.array(attrs["value_int"].i, dtype=np.int64))
        elif "value_ints" in attrs:
            tensor = numpy_helper.from_array(np.array(attrs["value_ints"].ints, dtype=np.int64))
        else:
            keep.append(node)
            continue
        tensor.name = node.output[0]
        inits.append(tensor)
    del graph.node[:]
    graph.node.extend(keep)
    graph.initializer.extend(inits)


def fold_batch_norm(graph):
    """BatchNormalization(gamma, beta, mean, var) -> правка весов Conv.

    Работаем по индексам: protobuf отдаёт новую обёртку на каждое обращение к
    graph.node, поэтому id() для узлов использовать нельзя.
    """
    inits = {i.name: i for i in graph.initializer}
    nodes = list(graph.node)
    consumers = {}
    for node in nodes:
        for name in node.input:
            consumers.setdefault(name, []).append(node)

    folded = 0
    drop = set()
    for index, node in enumerate(nodes):
        if node.op_type != "BatchNormalization":
            continue
        x, scale_name, bias_name, mean_name, var_name = node.input[:5]
        if any(p not in inits for p in (scale_name, bias_name, mean_name, var_name)):
            continue
        producer = next(
            (n for n in nodes if node.input[0] in n.output and n.op_type in ("Conv", "ConvTranspose")),
            None,
        )
        if producer is None or len(consumers.get(producer.output[0], [])) != 1:
            continue  # выход свёртки нужен кому-то ещё — не трогаем
        gamma = numpy_helper.to_array(inits[scale_name]).astype(np.float64)
        beta = numpy_helper.to_array(inits[bias_name]).astype(np.float64)
        mean = numpy_helper.to_array(inits[mean_name]).astype(np.float64)
        var = numpy_helper.to_array(inits[var_name]).astype(np.float64)
        eps = next((a.f for a in node.attribute if a.name == "epsilon"), 1e-5)

        scale = gamma / np.sqrt(var + eps)
        weight = numpy_helper.to_array(inits[producer.input[1]]).astype(np.float64)
        if weight.shape[0] != scale.size:
            continue
        weight = weight * scale.reshape((-1,) + (1,) * (weight.ndim - 1))

        if len(producer.input) > 2 and producer.input[2]:
            conv_bias = numpy_helper.to_array(inits[producer.input[2]]).astype(np.float64)
        else:
            conv_bias = np.zeros_like(scale)
            bias_tensor_name = producer.output[0] + "_conv_bias"
            producer.input.append(bias_tensor_name)
            inits[bias_tensor_name] = numpy_helper.from_array(conv_bias.astype(np.float32), bias_tensor_name)
            graph.initializer.append(numpy_helper.from_array(conv_bias.astype(np.float32), bias_tensor_name))
            inits[bias_tensor_name] = graph.initializer[-1]
        conv_bias = (conv_bias - mean) * scale + beta

        inits[producer.input[1]].CopyFrom(
            numpy_helper.from_array(weight.astype(np.float32), producer.input[1]))
        inits[producer.input[2]].CopyFrom(
            numpy_helper.from_array(conv_bias.astype(np.float32), producer.input[2]))

        # Выход свёртки нужен только этой нормализации (проверено выше),
        # поэтому переименовываем его в имя выхода BN — соседей не трогаем.
        producer.output[0] = node.output[0]
        drop.add(index)
        folded += 1

    del graph.node[:]
    graph.node.extend([node for index, node in enumerate(nodes) if index not in drop])
    return folded


def decompose_batch_norm(graph, counter=[0]):
    """Оставшиеся BN -> Mul(x, scale) + Add(..., shift) с константами [C,1,1].

    Свёртку в Conv так делает не всегда: нормализация может стоять после Add
    или её вход нужен нескольким узлам. Формула та же, а Mul и Add в
    mobile-сборке есть.
    """
    inits = {i.name: i for i in graph.initializer}
    new_nodes, count = [], 0
    for node in graph.node:
        if node.op_type != "BatchNormalization":
            new_nodes.append(node)
            continue
        scale_name, bias_name, mean_name, var_name = node.input[1:5]
        if any(p not in inits for p in (scale_name, bias_name, mean_name, var_name)):
            new_nodes.append(node)
            continue
        gamma = numpy_helper.to_array(inits[scale_name]).astype(np.float64)
        beta = numpy_helper.to_array(inits[bias_name]).astype(np.float64)
        mean = numpy_helper.to_array(inits[mean_name]).astype(np.float64)
        var = numpy_helper.to_array(inits[var_name]).astype(np.float64)
        eps = next((a.f for a in node.attribute if a.name == "epsilon"), 1e-5)
        scale = gamma / np.sqrt(var + eps)
        shift = beta - mean * scale
        counter[0] += 1
        suffix = counter[0]
        scale_tensor = numpy_helper.from_array(scale.reshape(-1, 1, 1).astype(np.float32), f"bn_scale_{suffix}")
        shift_tensor = numpy_helper.from_array(shift.reshape(-1, 1, 1).astype(np.float32), f"bn_shift_{suffix}")
        graph.initializer.extend([scale_tensor, shift_tensor])
        mul_out = f"bn_mul_{suffix}"
        new_nodes.append(helper.make_node("Mul", [node.input[0], scale_tensor.name], [mul_out], name=f"bn_mul_node_{suffix}"))
        new_nodes.append(helper.make_node("Add", [mul_out, shift_tensor.name], list(node.output), name=f"bn_add_node_{suffix}"))
        count += 1
    del graph.node[:]
    graph.node.extend(new_nodes)
    return count


def decompose_hard_swish(graph, counter=[0]):
    """HardSwish(x) = x * HardSigmoid(x, alpha=1/6, beta=0.5)."""
    new_nodes, count = [], 0
    for node in graph.node:
        if node.op_type == "HardSwish":
            counter[0] += 1
            x = node.input[0]
            hs = f"{x}_hardswish_sigmoid_{counter[0]}"
            new_nodes.append(
                helper.make_node("HardSigmoid", [x], [hs], alpha=1.0 / 6.0, beta=0.5,
                                 name=f"HardSwish_sigmoid_{counter[0]}"))
            new_nodes.append(helper.make_node("Mul", [x, hs], list(node.output),
                                              name=f"HardSwish_mul_{counter[0]}"))
            count += 1
        else:
            new_nodes.append(node)
    del graph.node[:]
    graph.node.extend(new_nodes)
    return count


def optimize(path, out_path):
    model = onnx.load(path)
    graph = model.graph
    lift_constants(graph)
    folded = fold_batch_norm(graph)
    rest_bn = decompose_batch_norm(graph)
    swish = decompose_hard_swish(graph)
    # Мусор после свёртки: веса BatchNorm больше не нужны ни одному узлу.
    used = {name for node in graph.node for name in node.input if name}
    used |= {out.name for out in graph.output}
    keep = [i for i in graph.initializer if i.name in used or not i.name]
    if len(keep) != len(graph.initializer):
        del graph.initializer[:]
        graph.initializer.extend(keep)
    ops = sorted({n.op_type for n in graph.node})
    missing = [op for op in ops if op not in ALLOWED]
    onnx.checker.check_model(model)
    onnx.save(model, out_path)
    return ops, missing, folded, (swish, rest_bn)


if __name__ == "__main__":
    folder = sys.argv[1]
    for name in ("detection.onnx", "cyrillic.onnx"):
        src = f"{folder}/{name}"
        ops, missing, folded, (swish, rest_bn) = optimize(src, src + ".tmp")
        print(f"{name}: BatchNorm свёрнуто в Conv {folded}, разложено {rest_bn}, HardSwish разложено {swish}")
        print(f"  операторы: {', '.join(ops)}")
        print(f"  нет в mobile-сборке: {missing or 'нет'}")
        import os
        os.replace(src + ".tmp", src)
