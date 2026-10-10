package mihon.data.ocr

import kotlinx.serialization.Serializable
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion

/**
 * Метаданные одного «скриншота» авточтения — лёгкая запись (~1-5 KB),
 * содержащая только распознанный текст с позициями, без картинок.
 *
 * Вся запись — это JSON с текстовыми регионами. Физических файлов
 * изображений НЕТ — галерея рисует текст по координатам из [regions],
 * фонон берёт из кэша загруженных страниц (coil/SSIV).
 *
 * @param id              Уникальный id (timestamp, чтобы сортировка = порядок).
 * @param chapterId       Id главы в БД manga.
 * @param pageIndex       Индекс страницы внутри главы (0-based).
 * @param scrollFraction  Доля прокрутки экрана (0..1) при моменте захвата.
 * @param timestamp       millis UTC.
 * @param regions         Регионы OCR (текст + box) на момент захвата.
 * @param engineUsed      Какой движок OCR дал regions: "local", "glens", "google" и т.д.
 * @param summaryText     Сжатый текст всех регионов для поиска (одна строка).
 * @param imageWidth      Ширина исходного изображения (координаты regions нормализованы 0..1).
 * @param imageHeight     Высота исходного изображения.
 * @param imagePath       Абсолютный путь к JPEG-картинке кадра (там, где скриншот
 *                        сохраняют как изображение, а не только как текст). null —
 *                        только текст/координаты.
 * @param scanRegion      Тип сканирования: "full" (вся страница) или "viewport" (видимая область).
 * @param sourceWidth     Ширина ФАЙЛА страницы, к которому относятся координаты regions.
 * @param sourceHeight    Высота ФАЙЛА страницы.
 * @param scanCrop        Какая часть файла страницы попала в кадр OCR (0..1).
 */
@Serializable
data class OcrScreenshotEntry(
    val id: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val scrollFraction: Float = 0f,
    val timestamp: Long,
    val regions: List<SerializableOcrRegion> = emptyList(),
    val engineUsed: String = "local",
    val summaryText: String = "",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val imagePath: String? = null,
    /**
     * Uri опубликованной копии в галерее (`Pictures/Yomikai`), если запись
     * отправляли туда. Нужен, чтобы удаление скриншота убирало его «насовсем»:
     * и приватный файл, и общедоступную копию (раньше копия в галерее
     * оставалась висеть после удаления записи).
     */
    val galleryUri: String? = null,
    val scanRegion: String = "viewport",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val scanCrop: SerializableNormalizedRect = SerializableNormalizedRect(),
) {
    /**
     * Есть ли чем вернуть регион на страницу.
     *
     * Нужны все три вещи: размеры файла страницы, окно кадра и запись, снятая
     * не с выделенной области. Старые записи JSON этих полей не содержат —
     * там остаются консервативные 0/0/0/0 и 0,0,1,1, и проекция для них
     * просто не выполняется (ничего не ломается, ничего не рисуется).
     */
    val hasSourceGeometry: Boolean
        get() = sourceWidth > 0 && sourceHeight > 0 && scanCrop.isUsable()

    /**
     * Прямоугольник региона в нормализованных координатах ФАЙЛА страницы.
     *
     * Регион нормализован к OCR-битмапу, а он уменьшен (длинная сторона ≤1600)
     * и обрезан по видимой области, поэтому напрямую по координатам страницы
     * его не нарисовать: сперва переносим координаты в окно кадра [scanCrop],
     * и только из него — в файл страницы.
     *
     * null — геометрии в записи нет (старый JSON, выделенная область, кадр,
     * склеенный из нескольких страниц): рисовать по нему нечего.
     */
    fun regionFileBoundingBox(region: SerializableOcrRegion): OcrBoundingBox? {
        if (!hasSourceGeometry) return null
        val width = scanCrop.right - scanCrop.left
        val height = scanCrop.bottom - scanCrop.top
        return OcrBoundingBox(
            left = scanCrop.left + region.left * width,
            top = scanCrop.top + region.top * height,
            right = scanCrop.left + region.right * width,
            bottom = scanCrop.top + region.bottom * height,
        )
    }
}

/**
 * Нормализованный (0..1) прямоугольник в координатах файла страницы.
 *
 * Значения по умолчанию — «вся страница»: у старых записей JSON этого поля
 * просто нет, и они читаются ровно как раньше.
 */
@Serializable
data class SerializableNormalizedRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    /**
     * Прямоугольник годится для проекции: он не вырожденный и не выехал за
     * страницу. Допуск нужен, потому что границы приходят из целочисленных
     * пикселей файла и на делении округляются.
     */
    fun isUsable(): Boolean =
        left >= -CROP_EPSILON && top >= -CROP_EPSILON &&
            right <= 1f + CROP_EPSILON && bottom <= 1f + CROP_EPSILON &&
            (right - left) > 0.001f && (bottom - top) > 0.001f
}

private const val CROP_EPSILON = 0.01f

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
