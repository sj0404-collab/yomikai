import os, time, wave
import numpy as np
import torch
from qwen_tts import Qwen3TTSModel

torch.set_num_threads(os.cpu_count() or 4)
OUT = "out"
os.makedirs(OUT, exist_ok=True)
SR_KEEP = 24000

EMO = [
    ("vivian", "Привет! Я — локальный голос студии. Проверяю настоящие живые эмоции.",
     "Speak naturally and friendly, like an introduction."),
    ("serena", "Как же здорово! Наконец-то всё получилось — просто праздник!",
     "Speak it very cheerfully, bright and excited, like great news just arrived."),
    ("vivian", "Ого! Серьёзно?! И это совершенно бесплатно?! Не может быть!",
     "Speak with big astonishment, surprised and laughing slightly."),
    ("serena", "Тише. Слышишь? За дверью кто-то ходит. Опять! Сердце колотится.",
     "Speak in a frightened whispered urgency, tense and low."),
    ("sohee", "Хватит! Я сто раз просила не трогать мои настройки!",
     "Speak angrily and frustrated, loud and sharp."),
    ("vivian", "А потом всё кончилось. Шаги замерли вдали, и никто не позвонил.",
     "Speak sadly and quietly, with sorrow."),
    ("serena", "Но не расстраивайся. Всё будет хорошо — я рядом.",
     "Speak gently and warmly, comforting."),
    ("vivian", "Я могу читать сказки, статьи и длинные главы — спокойно и размеренно.",
     "Speak calmly and steadily, like a storyteller on a quiet evening."),
    ("serena", "Ну как тебе? Понравился голос? Тогда слушаем дальше!",
     "Speak happily and inviting, energetic."),
]

DIC = [
    ("ryan", "Ба — барабан, па — парус. Брат Боря бил в барабан, а парус плыл по ветру. "
             "Ва — волна, фа — фонарь. Галка гоготала гулко, курица кудахтала кутерьмой."),
    ("ryan", "Жа — жук, ша — шарф. Жук жужжал жадно, шарф шуршал шёлком. За — зонт, са — сова. "
             "Да — дуб, та — туча. Сова свистела сонно в соснах."),
    ("ryan", "Мама мыла Машу мылом, Юрий юлил у юрты. Цапля цыкнула, чайка чиркнула крылом. "
             "Рома радостно раскричался радуге. Всё слышно? Ни одного тихого ша-ша-ша — "
             "только чистые, звонкие слова!"),
]
DIC_INSTR = "Speak loudly and very clearly, like a diction lesson, steady tempo."


def save(path, x, sr):
    w = wave.open(path, "wb")
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(sr)
    w.writeframes(x.astype(np.int16).tobytes())
    w.close()


def gen(model, speaker, text, instruct, tries=2):
    for a in range(tries):
        try:
            wavs, sr = model.generate_custom_voice(
                text=text, speaker=speaker, language="Russian", instruct=instruct)
            return wavs[0], sr
        except Exception as e:
            print("RETRY", a, repr(e)[:120], flush=True)
            time.sleep(20)
    raise RuntimeError("gen failed")


def to_i16(x):
    return (np.clip(x, -1, 1) * 32767).astype(np.int16)


def norm(cat):
    p = int(np.abs(cat.astype(np.int64)).max())
    g = 32767 * 0.92 / p if p else 1.0
    return np.clip(cat.astype(np.float64) * g, -32768, 32767).astype(np.int16)


print("loading model...", flush=True)
t0 = time.time()
model = Qwen3TTSModel.from_pretrained("Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
                                      device_map="cpu", dtype=torch.float32)
print(f"loaded in {time.time()-t0:.0f}s", flush=True)

parts = []
for i, (sp, tx, ins) in enumerate(EMO):
    t0 = time.time()
    a, sr = gen(model, sp, tx, ins)
    save(f"{OUT}/emo_{i:02d}_{sp}.wav", to_i16(a), sr)
    parts.append(to_i16(a))
    print(f"EMO {i}: {(time.time()-t0):.0f}s dur={len(a)/sr:.1f}s", flush=True)
gap = np.zeros(int(SR_KEEP * 0.45), dtype=np.int16)
emo = np.concatenate([y for p in parts for y in (p, gap)][:-1])
save(f"{OUT}/qwen_runner_emotsii.wav", norm(emo), SR_KEEP)
print(f"EMOTII TOTAL {len(emo)/SR_KEEP:.1f}s", flush=True)

parts = []
for i, (sp, tx) in enumerate(DIC):
    t0 = time.time()
    a, sr = gen(model, sp, tx, DIC_INSTR)
    save(f"{OUT}/dic_{i:02d}.wav", to_i16(a), sr)
    parts.append(to_i16(a))
    print(f"DIC {i}: {(time.time()-t0):.0f}s dur={len(a)/sr:.1f}s", flush=True)
dic = np.concatenate([y for p in parts for y in (p, gap)][:-1])
save(f"{OUT}/qwen_runner_dikciya.wav", norm(dic), SR_KEEP)
print(f"DIKCIYA TOTAL {len(dic)/SR_KEEP:.1f}s", flush=True)
print("ALL DONE", flush=True)
