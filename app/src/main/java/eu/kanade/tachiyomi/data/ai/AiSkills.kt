package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Навыки, которые агент создаёт сам.
 *
 * Читатель просил инструмент, которого нет, и не хочет ждать, пока его
 * напишут вручную. Навык — это **рецепт**: список шагов из уже встроенных
 * инструментов ([AiAgent] умеет исполнять `@tool`), сохраняемый в
 * `workspace/skills/<имя>.json` и вызываемый одним движением.
 *
 * ## Почему рецепт, а не код
 *
 * Наивный «агент пишет и исполняет свой код» на Android означает либо
 * интерпретатор внутри приложения, либо запуск шелл-скриптов — и то и другое
 * здесь неприемлемо. Рецепт ограничен тем, что уже есть в приложении, поэтому
 * новый навык не может ни уйти в сеть по чужому адресу, ни испортить систему.
 * Всё, что он делает, уже разрешено пользователю отдельными инструментами.
 *
 * Шаги исполняются по порядку, остановившись на первом `error` — чтобы
 * следующий шаг не работал с несуществующим файлом.
 */
object AiSkills {

    /** Один шаг рецепта: имя инструмента и его аргументы. */
    data class Step(val tool: String, val args: Map<String, String>)

    data class Skill(
        val name: String,
        val description: String,
        val steps: List<Step>,
    ) {
        /** Подстановки `{вход}` в аргументах — то, ради чего навык заводят. */
        fun withInput(input: String): Skill = copy(
            steps = steps.map { step ->
                step.copy(args = step.args.mapValues { (_, v) -> v.replace(INPUT_TOKEN, input) })
            },
        )

        companion object {
            const val INPUT_TOKEN = "{вход}"
        }
    }

    /** Результат выполнения навыка: текст каждого шага и всё, что создано. */
    data class RunResult(
        val lines: List<String>,
        val files: List<File>,
        val failed: Boolean,
    ) {
        val summary: String
            get() = buildString {
                append(if (failed) "Навык остановился с ошибкой" else "Навык выполнен")
                if (files.isNotEmpty()) {
                    append(". Создано файлов: ${files.size}")
                }
            }
    }

    private fun dir(context: Context): File =
        File(AiWorkspace.root(context), "skills").apply { mkdirs() }

    private fun fileOf(context: Context, name: String): File =
        File(dir(context), AiWorkspace.sanitize(name) + ".json")

    fun list(context: Context): List<Skill> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedBy { it.name }
            .orEmpty()

    fun get(context: Context, name: String): Skill? =
        fileOf(context, name).takeIf { it.isFile }
            ?.let { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun save(context: Context, s: Skill): Boolean {
        if (s.name.isBlank() || s.steps.isEmpty()) return false
        return runCatching {
            fileOf(context, s.name).writeText(toJson(s).toString(2))
            true
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiSkills save failed for ${s.name}" }
            false
        }
    }

    fun delete(context: Context, name: String): Boolean = fileOf(context, name).delete()

    /**
     * Разбор описания навыка от модели.
     *
     * Модель присылает JSON одним куском, и разбирать его надёжнее вручную с
     * проверкой каждого поля: упавший `optJSONArray` молча оставил бы навык
     * без шагов, и он «сохранился», но ничего не делает.
     */
    fun parse(raw: String): Skill? = runCatching {
        val obj = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val name = obj.optString("name").trim()
        if (name.isBlank()) return null
        val arr = obj.optJSONArray("steps") ?: return null
        val steps = buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val tool = s.optString("tool").trim()
                if (tool.isBlank()) continue
                val argsObj = s.optJSONObject("args")
                val args = buildMap {
                    if (argsObj != null) {
                        for (k in argsObj.keys()) put(k, argsObj.optString(k))
                    }
                }
                add(Step(tool, args))
            }
        }
        if (steps.isEmpty()) return null
        Skill(name, obj.optString("description").trim(), steps)
    }.getOrNull()

    /** Короткий список навыков для промпта: имена и что они делают. */
    fun promptLines(context: Context): String {
        val list = list(context)
        if (list.isEmpty()) {
            return "Навыков пока нет. Создать новый: skill_create {\"name\":\"...\",\"description\":\"...\",\"steps\":[{\"tool\":\"write_file\",\"args\":{...}}]}"
        }
        return "Свои навыки (skill_run {\"name\":\"...\",\"input\":\"...\"}):\n" +
            list.joinToString("\n") { "• ${it.name} — ${it.description} (шагов: ${it.steps.size})" }
    }

    /** Разбор одного шага в вызов инструмента. */
    fun toCall(step: Step): AiAgent.ToolCall {
        val args = JSONObject()
        step.args.forEach { (k, v) -> args.put(k, v) }
        return AiAgent.ToolCall(step.tool, args)
    }

    private fun toJson(s: Skill): JSONObject = JSONObject().apply {
        put("name", s.name)
        put("description", s.description)
        put(
            "steps",
            JSONArray().apply {
                s.steps.forEach { step ->
                    put(
                        JSONObject().apply {
                            put("tool", step.tool)
                            put(
                                "args",
                                JSONObject().apply { step.args.forEach { (k, v) -> put(k, v) } },
                            )
                        },
                    )
                }
            },
        )
    }

    private fun fromJson(obj: JSONObject): Skill {
        val arr = obj.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val tool = s.optString("tool").trim()
                if (tool.isBlank()) continue
                val argsObj = s.optJSONObject("args")
                val args = buildMap {
                    if (argsObj != null) {
                        for (k in argsObj.keys()) put(k, argsObj.optString(k))
                    }
                }
                add(Step(tool, args))
            }
        }
        return Skill(obj.optString("name"), obj.optString("description"), steps)
    }

    /**
     * Открыть созданный файл. Отдельная функция, а не кнопка в списке, потому
     * что «отправить» и «открыть» — разные намерения: первое делится файлом
     * наружу, второе показывает его в галерее/читалке.
     */
    fun openIntent(context: Context, f: File) = runCatching {
        f.getUriCompat(context).toShareIntent(
            context = context,
            type = "application/octet-stream",
            message = f.name,
        )
    }.getOrNull()
}
