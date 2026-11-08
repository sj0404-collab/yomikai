package mihon.domain.ocr.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Правило хода резервной цепочки распознавания.
 *
 * Регрессия, которую тест закрывает: пустой результат движка считался таким же
 * поводом идти дальше, как и его отказ. На пресете с онлайном каждая
 * иллюстрация уходила в GLENS/ZEN_FREE/Google с загрузкой картинки, авточтение
 * на ней стояло секунды и «очень медленно листало».
 */
class PageScanChainPolicyTest {

    @Test
    fun `движок нашёл текст — цепочка заканчивается`() {
        pageScanStepAfterEngine(
            failed = false,
            regionCount = 3,
            skipUntitledPages = true,
        ) shouldBe PageScanStep.STOP
    }

    @Test
    fun `движок упал — идём к следующему, фолбэк для этого и нужен`() {
        pageScanStepAfterEngine(
            failed = true,
            regionCount = 0,
            skipUntitledPages = true,
        ) shouldBe PageScanStep.CONTINUE
    }

    @Test
    fun `текста нет и страница считается картинкой — дальше не идём`() {
        pageScanStepAfterEngine(
            failed = false,
            regionCount = 0,
            skipUntitledPages = true,
        ) shouldBe PageScanStep.STOP
    }

    @Test
    fun `настройка выключена — пустой результат снова ищет дальше`() {
        pageScanStepAfterEngine(
            failed = false,
            regionCount = 0,
            skipUntitledPages = false,
        ) shouldBe PageScanStep.CONTINUE
    }

    @Test
    fun `настройка не отменяет различение отказа и пустого результата`() {
        // Даже с выключенной настройкой упавший движок обязан уступить место
        // следующему: иначе «резервирование» перестаёт работать совсем.
        pageScanStepAfterEngine(
            failed = true,
            regionCount = 0,
            skipUntitledPages = false,
        ) shouldBe PageScanStep.CONTINUE
    }
}
