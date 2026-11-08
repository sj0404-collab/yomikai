package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.ocr.OcrScreenshotBuffer
import mihon.domain.ocr.model.OcrRegion
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Снимки прочитанных страниц KOMA-читалки.
 *
 * Раньше кадры складывала только прежняя читалка: после каждой прочитанной
 * страницы во вкладке «Скриншоты» появлялась картинка с разметкой реплик.
 * В KOMA-читалке этого не было вовсе — теперь снимок делается на каждой
 * странице, которую читает авточтение, и попадает в ТУ ЖЕ вкладку: буфер
 * общий ([OcrScreenshotBuffer]).
 *
 * Геометрия обязательна: без размеров исходного файла и окна кадра буфер не
 * может положить рамки обратно на картинку, поэтому снимок пишется только
 * тогда, когда удалось прочитать размеры страницы.
 */
object KomaScreenshots {

    /**
     * Глава из идентификатора книги: «chapter/<id>» (новый вид) или
     * «chapter-<id>» (старый) → <id>. Книга локальной библиотеки — не глава.
     */
    private fun chapterIdOf(bookId: String): Long = when {
        bookId.startsWith("chapter/") -> bookId.removePrefix("chapter/").toLongOrNull() ?: -1L
        bookId.startsWith("chapter-") -> bookId.removePrefix("chapter-").toLongOrNull() ?: -1L
        else -> -1L
    }

    /**
     * Сохранить кадр страницы [pageIndex] с её репликами.
     *
     * @param regions реплики в нормализованных координатах страницы
     */
    suspend fun save(
        context: Context,
        bookId: String,
        pageIndex: Int,
        pagePath: String,
        regions: List<OcrRegion>,
        engineName: String,
    ) {
        if (regions.isEmpty()) return
        val chapterId = chapterIdOf(bookId)
        if (chapterId <= 0) return
        withContext(Dispatchers.IO) {
            runCatching {
                // Размеры берём без декодирования — снимок не должен стоить
                // памяти ещё на одну полную страницу.
                // Страница может лежать в архиве/папке: путь koma:// читается
                // через KomaPageStore, а не как файл.
                val bytes = pageBytes(pagePath) ?: return@runCatching
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // Размеры берём из БАЙТОВ, а не из потока: поток архива,
                // закрытый другим потоком под нативным декодером, — это
                // SIGSEGV (падение 2026-11-03), а не исключение.
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching

                val jpeg = encodePreview(pagePath)
                OcrScreenshotBuffer.add(
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    scrollFraction = 0f,
                    regions = regions,
                    engineUsed = engineName,
                    imageWidth = 0,
                    imageHeight = 0,
                    imageJpeg = jpeg,
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                )
            }
        }
    }

    /**
     * Байты страницы: файл читается целиком, страница архива — через
     * [KomaPageStore.openBytes]. Поток наружу не отдаётся — см. ниже.
     */
    private fun pageBytes(path: String): ByteArray? =
        if (KomaPageStore.isVirtual(path)) {
            KomaPageStore.openBytes(path)
        } else {
            runCatching { File(path.removePrefix("file://")).readBytes() }.getOrNull()
        }

    /** Уменьшенная копия страницы: полная страница вебтуна в JPEG — это мегабайты. */
    private fun encodePreview(path: String): ByteArray? = runCatching {
        val bytes = pageBytes(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 2400) sample *= 2
        val bitmap: Bitmap = runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull() ?: return null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            if (!bitmap.isRecycled) bitmap.recycle()
            out.toByteArray()
        }
    }.getOrNull()
}
