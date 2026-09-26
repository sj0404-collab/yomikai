package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Метрика проверки локального OCR.
 *
 * Сам движок на JVM не запускается (TFLite), поэтому здесь проверяется только
 * то, что можно посчитать без устройства: нормализация и CER. Ошибка в этой
 * арифметике тихо исказила бы весь отчёт на телефоне — «прочитал идеально»
 * при полностью неверном тексте.
 */
class OcrEvalMetricsTest {

    @Test
    fun `punctuation and case do not count as errors`() {
        // Движок ставит свои тире и кавычки, а регистр зависит от CTC-логитов.
        // Считать это ошибками нельзя, иначе метрика врёт.
        OcrEvalMetrics.cerPercent("Привет, мир!", "привет мир") shouldBe 0
        OcrEvalMetrics.cerPercent("«Да» — слышу.", "да слышу") shouldBe 0
    }

    @Test
    fun `yo and ye are the same letter for comparison`() {
        OcrEvalMetrics.cerPercent("Съешь ещё этих мягких булок", "съешь еще этих мягких булок") shouldBe 0
    }

    @Test
    fun `missing words raise the error rate`() {
        // «Слышишь» потеряно целиком: 7 символов из 14.
        OcrEvalMetrics.cerPercent("Ты меня слышишь", "ты меня") shouldBe 53
    }

    @Test
    fun `an empty result against a non empty sample is a total failure`() {
        OcrEvalMetrics.cerPercent("Проверка", "") shouldBe 100
    }

    @Test
    fun `two empty strings are not a failure`() {
        OcrEvalMetrics.cerPercent("", "") shouldBe 0
    }

    @Test
    fun `whitespace never becomes an error`() {
        OcrEvalMetrics.normalize("  Два   слова\n\tи перенос  ") shouldBe "два слова и перенос"
    }
}
