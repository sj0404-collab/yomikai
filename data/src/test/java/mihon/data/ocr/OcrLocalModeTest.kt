package mihon.data.ocr

import io.kotest.matchers.shouldBe
import mihon.domain.ocr.service.ScanRegion
import org.junit.jupiter.api.Test

/**
 * Два режима локального распознавания и фильтр ML Kit.
 *
 * Инварианты, ради которых режимы и фильтр существуют:
 * • «Точно» не может быть медленнее «Быстро» — иначе переключатель вводит
 *   в заблуждение;
 * • в кириллической цепочке не должно быть латинского ML Kit: он не «медленнее»,
 *   он читает русский текст латиницей вслух.
 */
class OcrLocalModeTest {

    @Test
    fun `accurate mode keeps every cross check`() {
        val tuning = OcrTuning.preset(OcrContentType.BALANCED).withLocalMode(OcrLocalMode.ACCURATE)
        tuning.verifierEnabled shouldBe true
        tuning.contrastRetryEnabled shouldBe true
    }

    @Test
    fun `fast mode drops the expensive passes only`() {
        val base = OcrTuning.preset(OcrContentType.BALANCED, ScanRegion.FULL_PAGE)
        val fast = base.withLocalMode(OcrLocalMode.FAST)
        fast.verifierEnabled shouldBe false
        fast.contrastRetryEnabled shouldBe false
        // Параметры детектора и разбиения на строки обязаны остаться прежними:
        // «Быстро» отличается числом проходов, а не качеством рамок.
        fast.detectorThreshold shouldBe base.detectorThreshold
        fast.minComponentArea shouldBe base.minComponentArea
        fast.maxTextBoxes shouldBe base.maxTextBoxes
        fast.lineSplitMinInkRatio shouldBe base.lineSplitMinInkRatio
    }

    @Test
    fun `default mode is the accurate one`() {
        OcrLocalMode.fromId(null) shouldBe OcrLocalMode.ACCURATE
        OcrLocalMode.fromId("") shouldBe OcrLocalMode.ACCURATE
        OcrLocalMode.fromId("nonsense") shouldBe OcrLocalMode.ACCURATE
        OcrLocalMode.fromId("fast") shouldBe OcrLocalMode.FAST
    }

    @Test
    fun `profile applies mode over the base profile`() {
        val profile = OcrRegionProfile(
            contentType = OcrContentType.BALANCED,
            localMode = OcrLocalMode.FAST,
        )
        val tuning = profile.tuning()
        tuning.verifierEnabled shouldBe false
        // Параметры детектора при этом сохраняются.
        tuning.detectorThreshold shouldBe OcrTuning.preset(OcrContentType.BALANCED).detectorThreshold
    }

    @Test
    fun `cyrillic reading languages are recognized`() {
        listOf("ru", "uk", "be", "bg", "sr", "mk", "RU", " uk ").forEach {
            isCyrillicOcrLanguage(it) shouldBe true
        }
    }

    @Test
    fun `latin and unknown languages are not cyrillic`() {
        listOf(null, "", "en", "ja", "fr", "unknown").forEach {
            isCyrillicOcrLanguage(it) shouldBe false
        }
    }
}
