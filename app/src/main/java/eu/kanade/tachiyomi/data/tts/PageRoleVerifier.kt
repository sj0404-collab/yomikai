package eu.kanade.tachiyomi.data.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка страницы моделью с картинкой: кто говорит каждую реплику, что
 * пропущено, где OCR ошибся.
 *
 * Локальный OCR различает ТЕКСТ, но не понимает РИСУНОК: хвостик облачка,
 * кому он указывает, где мысль, а где реплика. Поэтому голоса в сцене без
 * подписей назначаются по кругу. Единственный источник этой информации —
 * модель, которая видит картинку.
 *
 * Чего вызов НЕ делает:
 *  • не выдумывает текст: непрочитанное остаётся как есть, спорное помечается
 *    флагом [LineCheck.uncertain] — иначе получаются правдоподобные
 *    «разiiiнение» и «сахар-самаар», уже зафиксированные в todo.md;
 *  • не трогает ничего вне книги: ответ приходит страницей, применяется
 *    страницей, книжные роли пишутся в словарь этой книги.
 *
 * Без ключа/при ошибке возвращает null, и вызывающая сторона оставляет
 * локальный разбор: озвучка и порядок чтения не ломаются.
 */
object PageRoleVerifier {

    /**
     * Реплика после проверки.
     *
     * [kept] = false — модель решила, что это не реплика (титул, примечание
     * переводчика, пустая рамка). Такие строки не произносятся, но остаются в
     * отчёте, чтобы ничего не пропало молча.
     */
    data class LineCheck(
        val role: String,
        val text: String,
        val thought: Boolean,
        val kept: Boolean,
        /** true — модель не уверена; строка принимается, но помечается. */
        val uncertain: Boolean,
    )

    data class PageCheck(
        val lines: List<LineCheck>,
        /** Что страница не учла: титры, примечания, звуки. Только для отчёта. */
        val dropped: List<String>,
        /** Модель отвечает по картинке (false — вызова не было). */
        val checked: Boolean,
        /** Причина отказа, если [checked] = false. */
        val reason: String?,
    )

    /** Ответ без проверки: локальный разбор остаётся как есть. */
    fun unavailable(reason: String): PageCheck =
        PageCheck(lines = emptyList(), dropped = emptyList(), checked = false, reason = reason)

    suspend fun verify(
        imageJpeg: ByteArray,
        pageName: String,
        draftLines: List<String>,
        prefs: OcrPreferences,
    ): PageCheck = withContext(Dispatchers.IO) {
        if (draftLines.isEmpty()) return@withContext unavailable("нет строк для проверки")
        val apiKey = prefs.googleApiKey().get()
        if (apiKey.isBlank()) return@withContext unavailable("нет ключа Google AI")

        try {
            val model = prefs.googleModel().get().ifBlank { "gemini-2.5-flash" }
            val body = JSONObject().apply {
                put("generationConfig", JSONObject().put("temperature", 0.0))
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt(pageName, draftLines)))
                                .put(
                                    JSONObject().put(
                                        "inline_data",
                                        JSONObject()
                                            .put("mime_type", "image/jpeg")
                                            .put(
                                                "data",
                                                android.util.Base64.encodeToString(
                                                    imageJpeg,
                                                    android.util.Base64.NO_WRAP,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
                )
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
            }
            val response = try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) {
                    logcat(LogPriority.WARN) { "Page verify HTTP $code: ${text.take(160)}" }
                    return@withContext unavailable("HTTP $code от модели")
                }
                text
            } finally {
                conn.disconnect()
            }

            val answer = JSONObject(response)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text").orEmpty()
            parse(answer, draftLines)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Page role verification failed" }
            unavailable("ошибка: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Разбор ответа модели.
     *
     * Модель отдаёт `{"pages":[{"page":"…","lines":[…]}]}`. Разбор терпимый к
     * мусору и Markdown-заборам: если что-то не распозналось, возвращается
     * `checked = false`, а НЕ частичный правдоподобный результат — иначе
     * половина реплик получила бы роли, а половина осталась бы с догадками
     * локального разбора, и отладить это было бы невозможно.
     */
    internal fun parse(answer: String, draftLines: List<String>): PageCheck {
        val cleaned = answer.replace("```json", "").replace("```", "").trim()
        if (cleaned.isEmpty()) return unavailable("пустой ответ модели")

        // Разбор без org.json: в юнит-тестах app-модуля он не замокан и
        // бросает, а проверять разбор модели нужно именно тестами. Свой
        // разбор уже есть в VoiceJson — он же умеет вложенные массивы.
        val pagesText = VoiceJson.extractArray(cleaned, "pages") ?: return unavailable("ответ без поля pages")
        val page = VoiceJson.parseObjects(pagesText).firstOrNull() ?: return unavailable("пустой pages")
        val linesText = VoiceJson.extractArray(page["lines"].orEmpty(), "lines")
            ?: page["lines"]?.takeIf { it.trim().startsWith("[") }
            ?: return unavailable("ответ без lines")

        val lineObjects = VoiceJson.parseObjects(linesText)
        if (lineObjects.isEmpty()) return unavailable("модель не вернула ни одной реплики")

        val out = ArrayList<LineCheck>(lineObjects.size)
        for (o in lineObjects) {
            val text = o["text"].orEmpty().trim()
            if (text.isEmpty()) continue
            // Честность важнее правдоподобия: модель не должна «улучшать» текст.
            val replaced = o["draft_text"].orEmpty().trim()
            out += LineCheck(
                role = o["role"].orEmpty().trim().ifEmpty { UNKNOWN_ROLE },
                text = text,
                thought = o["thought"].equals("true", ignoreCase = true),
                kept = o["kept"]?.equals("false", ignoreCase = true) != true,
                // Строка, которой в черновике не было, — её модель либо
                // прочитала лучше OCR, либо сочинила. Помечаем как спорную,
                // чтобы её можно было откатить, не выбрасывая страницу.
                uncertain = replaced.isNotEmpty() && replaced != text,
            )
        }

        // "dropped" лежит на уровне страницы, рядом с "lines": то, что модель
        // не считала репликой (титры, примечания, звуки). Возвращается
        // списком, чтобы пропажу нельзя было спутать с опечаткой модели.
        val dropped = page["dropped"]
            ?.let { VoiceJson.extractArray(it, "dropped") ?: it }
            ?.let { VoiceJson.parseArrayTokens(it) }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (out.isEmpty()) return unavailable("все строки ответа пустые")
        return PageCheck(lines = out, dropped = dropped, checked = true, reason = null)
    }

    /** Роль, когда модель не назвала говорящего. */
    const val UNKNOWN_ROLE = "?"

    /**
     * Промпт.
     *
     * Написан так, чтобы работать на любом жанре и стиле: правила про формы
     * реплик, а не про конкретную мангу. Отдельно вынесено то, что в прошлых
     * правках приводило к выдуманным словам: модели запрещено «улучшать»
     * текст, непрочитанное помечается, а пропуск фиксируется в `dropped`,
     * чтобы его нельзя было спутать с опечаткой модели.
     */
    internal fun prompt(pageName: String, draftLines: List<String>): String {
        val numbered = draftLines.mapIndexed { i, t -> "${i + 1}. ${t.take(200)}" }.joinToString("\n")
        return """
            Ты проверяешь страницу манги/комикса/веб-комикса по КАРТИНКЕ.
            Жанр, стиль и язык любые — правила ниже универсальны.

            ЧЕРНОВИК РАСПОЗНАВАНИЯ (строки уже извлечены OCR, они могут быть
            ошибочны, склеены или обрезаны):

            $numbered

            ВЫПОЛНИ ЧЕТЫРЕ ПРОВЕРКИ:

            1) АТРИБУЦИЯ. У каждого речевого облачка есть хвостик, он указывает
               на говорящего. Определи, КТО говорит, и запиши его имя (или
               "?" если персонаж без подписи и определить нельзя).
               ФОРМЫ РЕПЛИК:
               - прямоугольная надпись ВНЕ облачков = нарратор (роль "narrator");
               - обычный эллипс/скруглённая рамка с хвостиком = речь;
               - эллипс БЕЗ хвостика (облачко мысли) = thought: true;
               - рваная/взрывная рамка, капля, шип = выкрик, это речь, а не мысль;
               - прямоугольник с прямой репликой без хвостика = тоже речь.
               Если в сцене один персонаж — у всех его реплик одна и та же роль.

            2) ПОЛНОТА. Добавь реплики, мысли и тексты рамок, которых НЕТ в
               черновике (OCR их пропустил). Для добавленных строк укажи
               draft_text = "" (их не было в черновике).
               НЕ добавляй: SFX/звуки, титулы, примечания переводчика, рамки
               без слов, подписи «глава N», Credits.

            3) ТОЧНОСТЬ ТЕКСТА. Исправь явные ошибки распознавания.
               ЖЁСТКОЕ ОГРАНИЧЕНИЕ: не додумывай, не пересказывай, не
               фразеологизмруй и не исправляй орфографию ради красоты.
               Если слово неразборчиво — оставь как распознано, лучше с
               вопросительным знаком. Выдуманное слово хуже опечатки.

            4) НЕ ЛИШНЕЕ. То, что не реплика, перенеси в "dropped" списком
               строками, а не удаляй молча.

            "page" — ровно "$pageName" (не выдумывай имя файла).
            "role" для персонажа — его имя как в сцене, для нарратора
            "narrator", для неизвестного "?".

            ВЕРНИ ТОЛЬКО JSON, без пояснений и без Markdown-заборов:
            {"pages":[{"page":"$pageName","lines":[{"role":"...","text":"...","thought":false,"kept":true}],"dropped":["..."]}]}
            Для добавленных OCR строк добавь поле "draft_text".
        """.trimIndent()
    }
}