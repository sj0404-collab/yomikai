package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Порядок чтения и подсказка вьюера.
 *
 * Раньше здесь проверялось, что подсказка вьюера совпадает с порядком чтения,
 * который задаёт пресет типа контента («Манга» → справа налево, «Манхва» →
 * сверху вниз). Пресеты удалены: порядок чтения выводится из настроек
 * читалки, поэтому теперь проверяется именно это соответствие, и то, что
 * единственный оставшийся профиль не навязывает вьюер.
 */
class OcrViewerHintTest {

    @Test
    fun `content type no longer dictates the viewer`() {
        // Профиль один, и он не выбирает режим чтения за читателя.
        OcrContentType.entries shouldBe listOf(OcrContentType.BALANCED)
        OcrContentType.BALANCED.viewer shouldBe OcrViewerHint.KEEP
    }

    @Test
    fun `legacy preset ids fall back to the single profile`() {
        // У пользователей в настройках остались значения «manga»/«manhwa»/
        // «manhwa»/«comic»: они обязаны разбираться, а не ломать разбор.
        listOf("manga", "manhwa", "manhua", "comic", "balanced", "", null, "что-то")
            .forEach { id -> OcrContentType.fromId(id) shouldBe OcrContentType.BALANCED }
    }

    @Test
    fun `reading order follows reader settings`() {
        // Вертикальная читалка читается сверху вниз, RTL — справа налево,
        // всё остальное — слева направо. Именно это заменило пресеты.
        OcrTuning.readingOrderFor(vertical = true, rtl = true) shouldBe "vertical"
        OcrTuning.readingOrderFor(vertical = true, rtl = false) shouldBe "vertical"
        OcrTuning.readingOrderFor(vertical = false, rtl = true) shouldBe "rtl"
        OcrTuning.readingOrderFor(vertical = false, rtl = false) shouldBe "ltr"
    }

    @Test
    fun `every reading order has a matching viewer hint`() {
        OcrTuning.READING_ORDERS.forEach { order ->
            (OcrViewerHint.forReadingOrder(order) != OcrViewerHint.KEEP) shouldBe true
        }
    }

    @Test
    fun `preset keeps the previous default parameters`() {
        // Профиль повторяет прежние константы движка, включая область скана,
        // которую по-прежнему выбирает пользователь.
        val tuning = OcrTuning.preset(OcrContentType.BALANCED)
        tuning.detectorThreshold shouldBe OcrTuning.DEFAULT.detectorThreshold
        tuning.minComponentArea shouldBe OcrTuning.DEFAULT.minComponentArea
        tuning.maxTextBoxes shouldBe OcrTuning.DEFAULT.maxTextBoxes
    }

    @Test
    fun `hint ids are unique and unknown values fall back to keep`() {
        OcrViewerHint.entries.map { it.id }.distinct().size shouldBe OcrViewerHint.entries.size
        OcrViewerHint.entries.forEach { OcrViewerHint.fromId(it.id) shouldBe it }
        // id нормализуется: регистр и пробелы по краям не ломают поиск.
        OcrViewerHint.fromId("  WEBTOON  ") shouldBe OcrViewerHint.WEBTOON
        OcrViewerHint.fromId(null) shouldBe OcrViewerHint.KEEP
        OcrViewerHint.fromId("") shouldBe OcrViewerHint.KEEP
        OcrViewerHint.fromId("что-то-новое") shouldBe OcrViewerHint.KEEP
    }

    @Test
    fun `every hint has a user-facing title`() {
        OcrViewerHint.entries.forEach { hint -> hint.title.isNotBlank() shouldBe true }
    }
}
