package mihon.data.ocr

import kotlinx.serialization.Serializable
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion

/**
 * Метаданные одного скриншота, сделанного во время авточтения.
 *
 * Физический файл JPEG лежит в [imagePath] (абсолютный путь к файлу на
 * диске: `context.filesDir/screenshots/<id>.jpg`). Галерея скринов читает
 * эти файлы и рисует текстовые оверлеи поверх по координатам из [regions].
 *
 * @param id              Уникальный id (timestamp-based, чтобы сортировка = порядок).
 * @param chapterId       Id главы в БД manga.
 * @param pageIndex       Индекс страницы внутри главы (0-based).
 * @param scrollFraction  Доля прокрутки экрана (0..1) при моменте скриншота.
 * @param timestamp       millis UTC.
 * @param imagePath       Абсолютный путь к JPEG-файлу скриншота.
 * @param regions         Регионы OCR (текст + box) на момент захвата.
 * @param engineUsed      Какой движок OCR дал regions: "local", "glens", "google" и т.д.
 * @param summaryText     Сжатый текст всех регионов для поиска (одна строка).
 * @param imageWidth      Ширина исходного изображения в пикселях.
 * @param imageHeight     Высота исходного изображения в пикселях.
 * @param scanRegion      Тип сканирования: "full", "viewport" — что именно
 *                        было захвачено (вся страница или видимая область).
 */
@Serializable
data class OcrScreenshotEntry(
    val id: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val scrollFraction: Float = 0f,
    val timestamp: Long,
    val imagePath: String,
    val regions: List<SerializableOcrRegion> = emptyList(),
    val engineUsed: String = "local",
    val summaryText: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val scanRegion: String = "viewport",
)

/**
 * Сериализуемая версия [OcrRegion] для JSON-хранения.
 */
@Serializable
data class SerializableOcrRegion(
    val order: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val orientation: String = "horizontal",
) {
    fun toOcrRegion(): OcrRegion = OcrRegion(
        order = order,
        text = text,
        boundingBox = OcrBoundingBox(left = left, top = top, right = right, bottom = bottom),
        textOrientation = when (orientation) {
            "vertical" -> mihon.domain.ocr.model.OcrTextOrientation.Vertical
            else -> mihon.domain.ocr.model.OcrTextOrientation.Horizontal
        },
    )

    companion object {
        fun fromOcrRegion(r: OcrRegion): SerializableOcrRegion = SerializableOcrRegion(
            order = r.order,
            text = r.text,
            left = r.boundingBox.left,
            top = r.boundingBox.top,
            right = r.boundingBox.right,
            bottom = r.boundingBox.bottom,
            orientation = when (r.textOrientation) {
                mihon.domain.ocr.model.OcrTextOrientation.Vertical -> "vertical"
                else -> "horizontal"
            },
        )
    }
}
