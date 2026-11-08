package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * Озвучка страницы и главы В ФАЙЛ — инструменты, которых в приложении не было.
 *
 * Почему они живут здесь, а не в ReaderActivity: «прочти книгу и пришли
 * аудиофайл» — это не то же самое, что озвучка в колонке. Авточтение
 * (`speak_chapter`) и кнопка «озвучить страницу» (`speak_page`) играют текст
 * В ЧИТАЛКЕ и возвращаются мгновенно, файла за ними не остаётся, поэтому
 * читатель просил файл и не получал его. Единственный инструмент, который
 * делал mp3, — `render_audio`, а он умеет только произвольный ТЕКСТ: прочитать
 * им страницу нельзя.
 *
 * Здесь оба новых действия идут в обход регистрации в Activity: текст страницы
 * берётся ТЕМ ЖЕ OCR, что у `page_text` (реестр [ReaderAiActions]), синтез —
 * ТЕМ ЖЕ, что у `render_audio` ([AiChatTools], Edge TTS в workspace/audio/).
 * Новый аудио-бэкенд не изобретается, регистрация в ReaderActivity не нужна.
 */
object ReaderAudioExport {

    const val ACTION_PAGE_AUDIO = "page_audio"
    const val ACTION_CHAPTER_AUDIO = "chapter_audio"
    const val ACTION_WAIT_IDLE = "wait_idle"

    /** Действия, которые обслуживает этот объект, а не реестр [ReaderAiActions]. */
    val ACTION_NAMES = listOf(ACTION_PAGE_AUDIO, ACTION_CHAPTER_AUDIO, ACTION_WAIT_IDLE)

    /** Результат действия: текст для модели и, если получилось, mp3 для чата. */
    data class Outcome(val output: String, val file: File? = null)

    /**
     * Служебные ответы реестра читалки, которые не являются текстом страницы.
     *
     * `page_text` на пустом кадре отдаёт не пустую строку, а словесную заглушку
     * «На странице не распознан текст». Синтезировать её — значит озвучить
     * читателю техническое сообщение вместо книги.
     */
    private val NOT_TEXT = listOf("не распознан текст", "не удалось получить кадр")

    /**
     * Сколько страниц главы озвучивать по умолчанию. Много — долго: на каждой
     * странице отдельный вызов OCR плюс синтез, а Edge TTS едет по accumulating
     * тексту. Читатель «озвучь мне главу» обычно имеет в виду текущую главу, а
     * не том на 200 кадров, поэтому потолок небольшой и его видно в ответе.
     */
    private const val DEFAULT_MAX_PAGES = 20
    private const val MAX_PAGES_LIMIT = 60

    /**
     * Бюджет сбора текста главы. Синтез длинного текста идёт ПОСЛЕ сбора, и
     * таймаут инструмента в [AiAgent] общий, поэтому сборка обязана закончиться
     * сама и вернуть что успела, а не висеть до отсечки.
     */
    private const val DEFAULT_BUDGET_MS = 240_000L
    private const val MAX_BUDGET_MS = 600_000L

    /**
     * Потолок собранного текста в символах.
     *
     * Синтез Edge TTS идёт последовательно по чанкам, и глава на 200 кадров —
     * это десятки мегабайт аудио и десятки минут работы. Обрезаем на ~20 000
     * символов (примерно 25 минут озвучки) и честно пишем в ответе, что текст
     * обрезан: лучше короткий целый файл, чем mp3, который не дождался.
     */
    private const val MAX_TEXT_CHARS = 20_000

    /** Пауза после turn_page: страница должна отрисоваться до следующего OCR. */
    private const val PAGE_SETTLE_MS = 900L

    /** Как часто опрашиваем движок озвучки в wait_idle. */
    private const val POLL_MS = 250L

    /**
     * Сколько непрерывной тишины считаем концом озвучки.
     *
     * Не 0: между страницами авточтения движок замолкает примерно на 1.3 с
     * (листание + отрисовка + захват кадра), и нулевой порог объявил бы конец
     * чтения на середине главы. 3 секунды тишины подряд — уже не «пауза между
     * репликами», а реальная остановка.
     */
    private const val QUIET_SETTLE_MS = 3_000L

    /** Сколько ждать начала речи, если `speak_page` только что вернулся. */
    private const val DEFAULT_START_MS = 6_000L

    private const val DEFAULT_WAIT_MS = 60_000L

    /**
     * Выполнить действие. null — действие не наше, вызывающий код отдаёт его
     * в реестр [ReaderAiActions] как раньше.
     */
    suspend fun run(context: Context, action: String, args: JSONObject): Outcome? = when (action) {
        ACTION_PAGE_AUDIO -> pageAudio(context, args)
        ACTION_CHAPTER_AUDIO -> chapterAudio(context, args)
        ACTION_WAIT_IDLE -> waitIdle(args)
        else -> null
    }

    /**
     * MP3 текущей страницы.
     *
     * Текст берётся из `page_text` — это ровно тот же кадр и тот же OCR, что
     * у `see_page`, новый распознаватель не заводится. Если модель уже
     * получила текст страницы и не хочет повторного OCR, может передать его
     * аргументом `text`.
     */
    private suspend fun pageAudio(
        context: Context,
        args: JSONObject,
    ): Outcome {
        val own = args.optString("text").trim()
        val text = if (own.isNotBlank()) own else pageText()
        if (isFailure(text)) return Outcome(text)
        if (isNotText(text)) {
            return Outcome("ОШИБКА: на текущей странице не распознан текст — озвучивать нечего")
        }
        val name = args.optString("name").ifBlank { defaultName("страница") }
        val voice = args.optString("voice").ifBlank { null }
        val rendered = AiChatTools.renderAudio(context, text, voice, name)
        return Outcome(
            "Страница озвучена в файл. ${rendered.output}",
            rendered.file,
        )
    }

    /**
     * MP3 главы.
     *
     * Текста главы у агента нет и взять его больше неоткуда: `book_recall`
     * хранит правила и заметки, а не содержимое страниц. Поэтому глава
     * собирается существующими средствами — `page_text` на текущей странице и
     * `turn_page` дальше, — и это описано в промпте, чтобы модель не пыталась
     * листать вместо инструмента.
     *
     * Позиция читателя восстанавливается: после сбора возвращаем её на
     * исходную страницу, иначе книга осталась бы на середине главы.
     */
    private suspend fun chapterAudio(
        context: Context,
        args: JSONObject,
    ): Outcome {
        val scope = args.optString("scope").trim().lowercase()
        if (scope == "page" || scope == "страница") {
            return pageAudio(context, args)
        }

        val maxPages = args.optInt("max_pages", DEFAULT_MAX_PAGES)
            .coerceIn(1, MAX_PAGES_LIMIT)
        val budgetMs = args.optLong("budget_ms", DEFAULT_BUDGET_MS)
            .coerceIn(20_000L, MAX_BUDGET_MS)

        val started = System.currentTimeMillis()
        val start = pagePosition()
            ?: return Outcome("ОШИБКА: читалка не ответила про страницы. ${ReaderAiActions.describe()}")

        val collected = StringBuilder()
        var page = start.current
        var emptyInARow = 0
        var taken = 0
        var cutByBudget = false
        var cutByText = false

        loop@ while (taken < maxPages) {
            if (System.currentTimeMillis() - started > budgetMs) {
                cutByBudget = true
                break@loop
            }
            val text = pageText()
            if (isFailure(text)) {
                if (taken == 0) {
                    // Сбор не начался, но страницу мы уже могли пролистать —
                    // возвращать надо и на этом пути, иначе читатель останется
                    // не там, где открыл книгу.
                    val back = restorePage(start.current)
                    val where = if (back) {
                        "Читалка возвращена на страницу ${start.current}."
                    } else {
                        "ВНИМАНИЕ: вернуть книгу на страницу ${start.current} не вышло — проверьте, где она остановилась."
                    }
                    return Outcome("$text $where")
                }
                // Страница не распозналась — это не повод бросать главу, но
                // три подряд пустых кадра означают, что дальше идти незачем.
                emptyInARow++
                if (emptyInARow >= 3) break@loop
            } else if (isNotText(text)) {
                emptyInARow++
                if (emptyInARow >= 3) break@loop
            } else {
                emptyInARow = 0
                collected.append(text.trim()).append("\n\n")
                taken++
                if (collected.length >= MAX_TEXT_CHARS) {
                    cutByText = true
                    break@loop
                }
            }

            if (start.total > 0 && page >= start.total) break@loop
            val turned = turn("next")
            if (isFailure(turned) || turned.contains("Нельзя перейти") ||
                turned.contains("прыгать на саму страницу")
            ) {
                break@loop
            }
            val next = firstNumber(turned)
            if (next == null || next == page) break@loop
            page = next
            delay(PAGE_SETTLE_MS)
        }

        val restored = restorePage(start.current)

        if (collected.isBlank()) {
            return Outcome("ОШИБКА: текст главы не распознан — озвучивать нечего")
        }
        val name = args.optString("name").ifBlank { defaultName("глава") }
        val voice = args.optString("voice").ifBlank { null }
        val rendered = AiChatTools.renderAudio(context, collected.toString(), voice, name)
        val tail = buildString {
            append("Собрано страниц: $taken")
            if (start.total > 0) append(" из ${start.total}")
            // Не обещаем возврата на страницу, если он не удался: читатель
            // должен знать, что книга осталась там, где остановилась.
            if (restored) {
                append(". Читалка возвращена на страницу ${start.current}.")
            } else {
                append(". ВНИМАНИЕ: вернуть книгу на страницу ${start.current} не вышло — она осталась там, где остановилась.")
            }
            if (cutByText) {
                append(" Текст обрезан на ${MAX_TEXT_CHARS} символах — остальное добери вторым вызовом.")
            }
            if (cutByBudget) append(" Остановлено по лимиту времени, остальное добери вторым вызовом.")
        }
        return Outcome("$tail ${rendered.output}", rendered.file)
    }

    /**
     * Дождаться конца текущей озвучки.
     *
     * Без этого инструмента модель не может читать книгу постранично:
     * `speak_page` возвращается мгновенно (озвучка только запускается), и
     * следующий вызов обрывает предыдущую. Здесь мы честно ждём и говорим, что
     * вышло: «тишина» (закончилось или не началось) либо «идёт» (ещё звучит).
     */
    private suspend fun waitIdle(args: JSONObject): Outcome {
        val waitMs = args.optLong("wait_ms", DEFAULT_WAIT_MS).coerceIn(1_000L, 900_000L)
        val startMs = args.optLong("start_ms", DEFAULT_START_MS).coerceIn(0L, waitMs)
        val quietMs = args.optLong("quiet_ms", QUIET_SETTLE_MS).coerceIn(500L, 30_000L)

        val started = System.currentTimeMillis()
        var heard = false
        var quietSince = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val elapsed = now - started
            if (TtsSpeaker.isSpeaking) {
                heard = true
                quietSince = 0L
            } else {
                if (quietSince == 0L) quietSince = now
                val quiet = now - quietSince
                if (heard && quiet >= quietMs) {
                    return Outcome("Тишина: озвучка закончилась (ждал ${elapsed / 1000} с). Можно листать дальше.")
                }
                if (!heard && elapsed >= startMs) {
                    return Outcome(
                        "Тишина: озвучка так и не началась за ${startMs / 1000} с — " +
                            "на странице, похоже, нечего читать. Листай сам через turn_page.",
                    )
                }
            }
            if (elapsed >= waitMs) {
                return Outcome(
                    "Идёт: через ${waitMs / 1000} с озвучка всё ещё звучит. " +
                        "Позови wait_idle ещё раз или прерви через stop_speak.",
                )
            }
            delay(POLL_MS)
        }
    }

    /** Текст текущей страницы существующим OCR читалки. */
    private suspend fun pageText(): String =
        ReaderAiActions.run("page_text", JSONObject()).trim()

    private suspend fun turn(target: String): String =
        ReaderAiActions.run("turn_page", JSONObject().put("to", target)).trim()

    private data class Position(val current: Int, val total: Int)

    /** «Страниц в главе: 20, открыта: 3» → current=3, total=20. */
    private suspend fun pagePosition(): Position? {
        val raw = ReaderAiActions.run("page_count", JSONObject()).trim()
        if (isFailure(raw)) return null
        val numbers = raw.matchNumbers()
        val total = numbers.getOrNull(0) ?: 0
        val current = numbers.getOrNull(1) ?: 1
        return Position(current = current.coerceAtLeast(1), total = total.coerceAtLeast(0))
    }

    /**
     * Вернуть книгу на исходную страницу.
     *
     * Отмена здесь намеренно ловится: неудалось вернуть страницу — не повод
     * выбрасывать уже собранный текст и mp3, о котором читатель просил.
     * Но сказать «вернулась» при этом нельзя, поэтому возвращаем признак.
     */
    private suspend fun restorePage(page: Int): Boolean {
        return try {
            turn(page.toString())
            delay(PAGE_SETTLE_MS)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Реестр читалки возвращает и служебные строки («ОШИБКА действия…»). */
    private fun isFailure(text: String): Boolean =
        text.startsWith("ОШИБКА") || text.startsWith("ДЕЙСТВИЕ")

    /** Служебная заглушка читалки вместо текста страницы. */
    private fun isNotText(text: String): Boolean =
        text.isBlank() || NOT_TEXT.any { text.contains(it, ignoreCase = true) }

    /**
     * Имя файла по умолчанию. С хвостом времени, иначе два вызова подряд
     * («озвучь страницу», «озвучь ещё») затёрли бы друг друга и читатель получил
     * бы один файл вместо двух.
     */
    private fun defaultName(prefix: String): String =
        "${prefix}_${System.currentTimeMillis() / 1000 % 100_000}"

    private fun String.matchNumbers(): List<Int> =
        Regex("\\d+").findAll(this).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun firstNumber(text: String): Int? = text.matchNumbers().firstOrNull()
}
