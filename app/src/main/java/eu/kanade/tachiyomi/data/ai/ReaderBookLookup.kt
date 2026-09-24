package eu.kanade.tachiyomi.data.ai

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.ocr.ContentAutoPreset
import mihon.data.ocr.OcrContentType
import mihon.data.ocr.OcrRegionRules
import mihon.data.ocr.OcrTuning
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

object ReaderBookLookup {

    private const val FRAME_MAX_EDGE = 1400
    private const val OCR_TEXT_LIMIT = 900

    data class Frame(
        val width: Int,
        val height: Int,
        val rtl: Boolean,
        val webtoon: Boolean,
        val vertical: Boolean,
    )

    data class Verdict(
        val type: OcrContentType,
        val readingOrder: String,
    )

    suspend fun inspect(
        context: Context,
        mangaId: Long,
        mangaTitle: String,
        chapterName: String?,
        bitmap: Bitmap?,
    ): String? = withContext(Dispatchers.IO) {
        val prefs = runCatching { Injekt.get<OcrPreferences>() }.getOrNull() ?: return@withContext null
        if (prefs.aiBookLookup().get() == "off") return@withContext null
        val book = BookKnowledge.load(context, mangaId)
        if (book.title.isBlank() && mangaTitle.isNotBlank()) book.title = mangaTitle
        if (book.researched) return@withContext null

        val frame = frameOf(bitmap)
        val ocrText = bitmap?.let { ocrOf(context, it) }.orEmpty()
        val verdict = classify(context, prefs, mangaTitle, frame, ocrText)
        if (verdict != null) {
            applyVerdict(prefs, mangaId, verdict)
            book.contentType = verdict.type.id
            book.readingOrder = verdict.readingOrder
            BookKnowledge.save(context, book)
        }

        val researched = research(context, prefs, mangaId, mangaTitle, chapterName, book)
        if (researched != null) {
            BookKnowledge.render(context, mangaId)
        } else {
            verdict?.let {
                "Тип: ${it.type.title}, порядок: ${OcrRegionRules.orderTitle(it.readingOrder)}. " +
                    "Интернет недоступен — детали не проверены."
            }
        }
    }

    private fun frameOf(bitmap: Bitmap?): Frame? {
        val ctx = mihon.data.ocr.ReaderContextBus.current.value
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null
        val ratio = bitmap.height.toFloat() / bitmap.width
        return Frame(
            width = bitmap.width,
            height = bitmap.height,
            rtl = ctx?.rtl ?: false,
            webtoon = ctx?.webtoon ?: (ratio >= 1.8f),
            vertical = ctx?.vertical ?: (ratio >= 1.8f),
        )
    }

    private suspend fun ocrOf(context: Context, bitmap: Bitmap): String {
        val file = File(context.cacheDir, "ai_probe_frame.jpg")
        val saved = runCatching {
            val scaled = if (maxOf(bitmap.width, bitmap.height) > FRAME_MAX_EDGE) {
                val scale = FRAME_MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            true
        }.getOrDefault(false)
        if (!saved) return ""
        return AiAgent.ocrAttachment(file).orEmpty().take(OCR_TEXT_LIMIT)
    }

    private suspend fun classify(
        context: Context,
        prefs: OcrPreferences,
        title: String,
        frame: Frame?,
        ocrText: String,
    ): Verdict? {
        val resolution = AiBackends.resolve(context, prefs.aiBackend().get())
        val chat = resolution.chat ?: return null
        val geometry = if (frame == null) {
            "кадр недоступен"
        } else {
            "размер кадра ${frame.width}x${frame.height}, " +
                "соотношение %.2f, ".format(frame.height.toFloat() / frame.width) +
                "обложка/страница читается справа налево=${frame.rtl}, " +
                "вертикальная=${frame.vertical}, вебтун=${frame.webtoon}"
        }
        val prompt = buildString {
            append("Определи тип контента и направление чтения по кадру страницы.\n")
            append("ТИП: manga (японская манга), manhwa (корейский веб-комикс), ")
            append("manhua (китайские вертикальные колонки), comic (западный комикс) или UNKNOWN.\n")
            append("ПОРЯДОК ЧТЕНИЯ: rtl (справа налево), ltr (слева направо) или vertical (сверху вниз).\n")
            append("Важно: направление определяй по самому кадру — пузы и панели могут идти ")
            append("и справа налево, и слева направо, не суди только по типу.\n")
            if (title.isNotBlank()) append("Название: $title\n")
            append("Геометрия: $geometry\n")
            if (ocrText.isNotBlank()) append("Распознанный текст кадра: ").append(ocrText.take(400)).append('\n')
            append("Ответь ДВУМЯ строками без пояснений: первая — тип, вторая — порядок чтения.")
        }
        val reply = runCatching {
            chat(
                prompt,
                "Ты классификатор страниц в manga-читалке. Определяешь тип контента и направление чтения. Отвечай двумя строками.",
            )
        }.getOrNull() ?: return null
        val text = reply?.content.orEmpty().uppercase()
        val type = when {
            text.contains("MANHWA") -> OcrContentType.MANHWA
            text.contains("MANHUA") -> OcrContentType.MANHUA
            text.contains("COMIC") -> OcrContentType.COMIC
            text.contains("MANGA") -> OcrContentType.MANGA
            else -> return null
        }
        val order = when {
            text.contains("VERTICAL") -> "vertical"
            text.contains("LTR") -> "ltr"
            text.contains("RTL") -> "rtl"
            else -> OcrTuning.preset(type).readingOrder
        }
        return Verdict(type = type, readingOrder = order)
    }

    private fun applyVerdict(prefs: OcrPreferences, mangaId: Long, verdict: Verdict) {
        val type = verdict.type
        if (type == OcrContentType.BALANCED) return
        val current = prefs.contentType().get()
        if (current != "balanced" && current != type.id) return
        prefs.contentType().set(type.id)
        prefs.scanReadingOrder().set(verdict.readingOrder)
        ContentAutoPreset.rememberManual(mangaId, type.id, prefs)
    }

    private suspend fun research(
        context: Context,
        prefs: OcrPreferences,
        mangaId: Long,
        title: String,
        chapterName: String?,
        book: BookKnowledge.Book,
    ): String? {
        val resolution = AiBackends.resolve(context, prefs.aiBackend().get())
        val chat = resolution.chat ?: return null
        val task = buildString {
            append("Изучи книгу и сохрани знание о ней.\n")
            if (title.isNotBlank()) append("Название: $title\n")
            if (!chapterName.isNullOrBlank()) append("Открыта глава: $chapterName\n")
            if (book.contentType.isNotBlank()) append("Тип контента: ").append(book.contentType).append('\n')
            append("Сделай по порядку:\n")
            append("1) web_search — найди, где эту книгу читают, и официальную страницу.\n")
            append("2) web_fetch — открой найденную страницу и возьми оттуда факты.\n")
            append("3) book_remember kind=site — куда читать (ссылка).\n")
            append("4) book_remember kind=summary — о чём книга, 1-2 предложения.\n")
            append("5) book_remember kind=fact — 2-4 факта: издание, автор, жанр, по чему читать.\n")
            append("Если интернет недоступен — скажи об этом прямо и не выдумывай ссылки.\n")
            append("Ответ пользователю — 2-3 предложения.")
        }
        val reply = AiAgent.run(
            context = context,
            userText = task,
            history = emptyList(),
            chatFn = chat,
            mangaId = mangaId,
            bookContext = BookKnowledge.render(context, mangaId).ifBlank { null },
        )
        return reply.text.takeIf { it.isNotBlank() }
    }
}
