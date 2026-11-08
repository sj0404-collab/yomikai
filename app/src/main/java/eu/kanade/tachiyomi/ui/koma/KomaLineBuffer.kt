package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Буфер строк — то, что распознавание уже вернуло, сохранённое на диск.
 *
 * Зачем он нужен:
 *  1. **Не пересчитывать OCR ради правки правил.** Распознавание страницы —
 *     6–12 секунд. Если поменялся порог или порядок чтения, правильный ответ
 *     уже есть в буфере: «Пересчитать порядок из буфера» применяет новые
 *     правила к сохранённым строкам, не трогая картинку и модель.
 *  2. **Видно, что именно вернул OCR.** Строка, рамка, уверенность и версия
 *     правил, при которых она получена. Без этого спор «он не видит серый
 *     текст» ведётся по скриншотам и догадкам.
 *  3. **Ничего не теряется между полосами и страницами.** Реплика, обрезанная
 *     на стыке полос, остаётся в буфере и договаривается со следующей.
 *
 * Формат — обычный JSONL (`koma-buffer.jsonl`), по строке на реплику. Файл
 * можно открыть, посмотреть и поправить руками; испорченные строки просто
 * пропускаются при чтении.
 */
@Serializable
data class BufferedLine(
    val book: String,
    val page: Int,
    /** Номер полосы экрана: 0 — вся страница или первая полоса. */
    val band: Int = 0,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float? = null,
    val kind: String = "Текст",
    /** Версия правил, при которой получена эта строка. */
    @SerialName("rules") val rulesVersion: String = KomaRules.DEFAULT_VERSION,
    /** Метка времени, мс: по ней видно, из какого прогона строка. */
    val at: Long = System.currentTimeMillis(),
) {
    fun toRegion(): OcrRegion =
        OcrRegion(text = text, left = left, top = top, right = right, bottom = bottom, confidence = confidence, kind = kind)

    fun toLine(): KomaReadingOrder.Line =
        KomaReadingOrder.Line(text = text, left = left, top = top, right = right, bottom = bottom)
}

object KomaLineBuffer {

    private const val FILE = "koma-buffer.jsonl"
    /** Предел, после которого старые строки уходят: буфер — не архив главы. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun file(context: Context): File = File(context.filesDir, FILE)

    /** Дописать строки страницы. Повторный прогон той же страницы заменяет её. */
    fun put(
        context: Context,
        book: String,
        page: Int,
        band: Int,
        lines: List<BufferedLine>,
    ) {
        runCatching {
            val keep = readAll(context).filterNot { it.book == book && it.page == page && it.band == band }
            write(context, keep + lines)
        }
    }

    /** Сохранить результат распознавания (уже в порядке чтения). */
    fun putRegions(
        context: Context,
        book: String,
        page: Int,
        band: Int,
        regions: List<OcrRegion>,
        rulesVersion: String,
    ) {
        put(
            context = context,
            book = book,
            page = page,
            band = band,
            lines = regions
                .filter { it.text.isNotBlank() }
                .map {
                    BufferedLine(
                        book = book,
                        page = page,
                        band = band,
                        text = it.text,
                        left = it.left,
                        top = it.top,
                        right = it.right,
                        bottom = it.bottom,
                        confidence = it.confidence,
                        kind = it.kind,
                        rulesVersion = rulesVersion,
                    )
                },
        )
    }

    fun load(context: Context, book: String, page: Int): List<BufferedLine> =
        readAll(context)
            .filter { it.book == book && it.page == page }
            .sortedWith(compareBy({ it.band }, { it.top }, { it.left }))

    fun loadPage(context: Context, book: String, page: Int, band: Int): List<BufferedLine> =
        load(context, book, page).filter { it.band == band }

    /** Все строки книги — для «пересчитать всю главу из буфера». */
    fun loadBook(context: Context, book: String): List<BufferedLine> =
        readAll(context).filter { it.book == book }

    fun pages(context: Context, book: String): List<Int> =
        readAll(context).filter { it.book == book }.map { it.page }.distinct().sorted()

    fun size(context: Context): Int = readAll(context).size

    fun clear(context: Context) {
        file(context).delete()
    }

    fun clearBook(context: Context, book: String) {
        runCatching { write(context, readAll(context).filterNot { it.book == book }) }
    }

    /** Выгрузить буфер в понятный текст: «страница · полоса · рамка · текст». */
    fun exportText(context: Context, book: String? = null): String {
        val all = readAll(context).filter { book == null || it.book == book }
        if (all.isEmpty()) return "Буфер строк пуст."
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return buildString {
            append("Буфер строк · записей: ${all.size}\n\n")
            all.sortedWith(compareBy({ it.book }, { it.page }, { it.band }, { it.top })).forEach {
                append("стр. ${it.page + 1} · полоса ${it.band + 1} · r${it.rulesVersion} · ${fmt.format(Date(it.at))}")
                append("\n  рамка: %.3f,%.3f – %.3f,%.3f".format(it.left, it.top, it.right, it.bottom))
                append("\n  ${it.text}")
                append("\n\n")
            }
        }
    }

    private fun readAll(context: Context): List<BufferedLine> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines()
                .asSequence()
                .filter { it.isNotBlank() }
                // Одна испорченная строка (ручная правка, обрыв записи) не должна
                // обнулять весь буфер.
                .mapNotNull { line -> runCatching { json.decodeFromString<BufferedLine>(line) }.getOrNull() }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, lines: List<BufferedLine>) {
        val f = file(context)
        if (lines.isEmpty()) {
            f.delete()
            return
        }
        // Переполнение: оставляем свежую половину, а не копим мегабайты.
        var kept = lines
        var text = kept.joinToString("\n") { json.encodeToString(it) }
        if (text.toByteArray().size > MAX_BYTES) {
            kept = lines.sortedBy { it.at }.takeLast(lines.size / 2 + 1)
            text = kept.joinToString("\n") { json.encodeToString(it) }
        }
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}
