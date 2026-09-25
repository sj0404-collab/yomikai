package eu.kanade.tachiyomi.data.tts

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Ремарки и междометия в озвучке.
 *
 * Проверяются три вещи, которые были сломаны: звук из ремарки произносится,
 * а не выбрасывается; «а», «аа» и «ааа» различаются подачей; состояние
 * персонажа меняет голос всей реплики, а не проглатывается.
 */
class SpeechCueTest {

    // --- звук произносится ---

    @Test
    fun `sigh is spoken instead of being dropped`() {
        val d = SpeechCue.deliveries("(вздох) Я не выдержу.")

        d shouldHaveSize 2
        // Раньше ремарка попадала в `SpeechMarkup.strip` и исчезала, а
        // вздох читался словом «вздох». Теперь это вытянутое «А-а-а».
        d.first().text shouldBe "А-а-а..."
        d.first().pitch < 1f shouldBe true
        d.first().rate < 1f shouldBe true
        d.last().text shouldBe "Я не выдержу."
    }

    @Test
    fun `every built-in sound has a spoken form`() {
        for (remark in listOf(
            "вздох", "вдох", "стон", "крик", "смеётся", "кашель", "крик помощи",
        )) {
            val d = SpeechCue.deliveries("($remark) Текст.")
            d.first().text.isNotBlank() shouldBe true
        }
    }

    @Test
    fun `cry for help is louder than a sigh`() {
        val cry = SpeechCue.deliveries("(крик помощи)").first()
        val sigh = SpeechCue.deliveries("(вздох)").first()

        cry.pitch shouldBe 1.28f
        cry.rate shouldBe 1.10f
        cry.pitch > sigh.pitch shouldBe true
    }

    @Test
    fun `sigh is checked before gasp so an inhale is not read as an exhale`() {
        // Порядок таблицы: «вдох» идёт раньше «вздоха», но «вздох» не должен
        // перехватывать «вдох» из-за общего слова «дышит».
        SpeechCue.deliveries("(вдох)").first().text shouldBe "А-а-а!"
        SpeechCue.deliveries("(вздох)").first().text shouldBe "А-а-а..."
    }

    @Test
    fun `unknown remark is removed and not read as a word`() {
        val d = SpeechCue.deliveries("(неизвестно) Текст.")

        d shouldHaveSize 1
        d.first().text shouldBe "Текст."
    }

    @Test
    fun `interjection in brackets survives as a sound`() {
        val d = SpeechCue.deliveries("[ааа] Отпусти!")

        d shouldHaveSize 2
        d.first().text shouldBe "Ааа"
        d.first().pitch > 1f shouldBe true
    }

    // --- «а», «аа», «ааа» означают разное ---

    @Test
    fun `short and long a get different delivery`() {
        val one = SpeechCue.deliveries("А!").first()
        val two = SpeechCue.deliveries("Аа!").first()
        val three = SpeechCue.deliveries("Ааа!").first()

        // Удивление → попытка закричать → крик.
        one.pitch < two.pitch shouldBe true
        two.pitch < three.pitch shouldBe true
        two.rate < one.rate shouldBe true
        three.rate > two.rate shouldBe true
        one.pauseAfterMs < three.pauseAfterMs shouldBe true
    }

    @Test
    fun `drawn a is treated as a drawn cry`() {
        val drawn = SpeechCue.deliveries("А-а-а!").first()
        val triple = SpeechCue.deliveries("Ааа!").first()

        drawn.pitch shouldBe triple.pitch
        drawn.rate shouldBe triple.rate
    }

    @Test
    fun `interjection is spoken as its own utterance`() {
        // Раньше «Ааа! Отпусти!» уходило в движок одним куском с общим
        // тоном фразы, и крик не читался криком.
        val d = SpeechCue.deliveries("Ааа! Отпусти!")

        d shouldHaveSize 2
        d.first().text shouldBe "Ааа!"
        d.last().text shouldBe "Отпусти!"
    }

    @Test
    fun `trailing punctuation stays with the interjection`() {
        val d = SpeechCue.deliveries("А-а!")

        d shouldHaveSize 1
        d.first().text shouldBe "А-а!"
    }

    @Test
    fun `breath a with h is not a shout`() {
        val breath = SpeechCue.deliveries("Аах!").first()
        val shout = SpeechCue.deliveries("Ааа!").first()

        breath.pitch < shout.pitch shouldBe true
        breath.rate < shout.rate shouldBe true
    }

    @Test
    fun `agreement and thinking sounds are not shouts`() {
        SpeechCue.deliveries("Ага!").first().pitch shouldBe 1.04f
        SpeechCue.deliveries("М-м-м...").first().rate shouldBe 0.86f
        SpeechCue.deliveries("М-м-м...").first().pitch shouldBe 0.96f
        SpeechCue.deliveries("Ой!").first().pitch shouldBe 1.18f
    }

    // --- обычные слова не должны стать междометиями ---

    @Test
    fun `real words made of interjection letters stay words`() {
        // Под «любую последовательность из а, у, о, м, х» попали бы «еху»,
        // «уху», «аму», «хам», «уа», «ому» — поэтому список явный.
        for (word in listOf("Хам", "уху", "еху", "аму", "ому", "уа", "мам", "ом")) {
            SpeechCue.isInterjection(word) shouldBe false
        }
        SpeechCue.isInterjection("Аах") shouldBe true
        SpeechCue.isInterjection("а-а-а") shouldBe true
    }

    @Test
    fun `interjection inside a word is not detected`() {
        // «ах» в «махать» и «ух» в «ухудший» стоят внутри слова, поэтому
        // границы заданы явно, а не «любой набор букв».
        SpeechCue.deliveries("Он махать не устал").none { it.text.contains("Ах") } shouldBe true
        SpeechCue.deliveries("Погода хухудшится").none { it.text.contains("Ух") } shouldBe true
    }

    @Test
    fun `standalone interjections between words are still detected`() {
        // Обратная сторона границ: отдельное «ах» — это выдох, а не часть
        // слова, и режется на кусок.
        val d = SpeechCue.deliveries("Скажи ах и ух.")
        d.map { it.text } shouldBe listOf("Скажи", "Ах", "и", "Ух.")
    }

    @Test
    fun `long word starting with a is not a shout`() {
        val d = SpeechCue.deliveries("Ахен}")

        d shouldHaveSize 1
        d.first().text shouldBe "Ахен}"
    }

    // --- состояние меняет голос всей реплики ---

    @Test
    fun `whisper slows the whole line and is not spoken`() {
        val d = SpeechCue.deliveries("(шёпотом) Подожди меня тут.")

        d shouldHaveSize 1
        d.first().text shouldBe "Подожди меня тут."
        d.first().rate < 1f shouldBe true
    }

    @Test
    fun `running speeds up and exhaustion slows down`() {
        val run = SpeechCue.deliveries("(бегом) Быстрее!").first()
        val tired = SpeechCue.deliveries("(устал) Еле иду.").first()
        val weak = SpeechCue.deliveries("(слабость) Не могу.").first()

        run.rate > 1f shouldBe true
        tired.rate < 1f shouldBe true
        weak.rate < tired.rate shouldBe true
        weak.pitch < tired.pitch shouldBe true
    }

    @Test
    fun `state applies to interjections inside the same line`() {
        // Множитель должен доставать и до «ааа», иначе крик внутри шёпота
        // остался бы во всю громкость.
        val d = SpeechCue.deliveries("(шёпотом) Ааа! Прости.")

        d shouldHaveSize 2
        val shout = d.first()
        shout.pitch shouldBe 1.16f * 1.0f
        shout.rate < 0.9f shouldBe true
    }

    @Test
    fun `most noticeable state wins when several are mentioned`() {
        val d = SpeechCue.deliveries("(устал) (бегом) Вперёд!").first()

        d.rate > 1f shouldBe true
    }

    // --- мусор не попадает в синтез ---

    @Test
    fun `orphan formatting marks are not spoken`() {
        val d = SpeechCue.deliveries("*(смеётся) Ха!")

        d.none { it.text == "*" } shouldBe true
        d.any { it.text == "Ха-ха-ха!" } shouldBe true
    }

    @Test
    fun `blank line produces nothing`() {
        SpeechCue.deliveries("   ") shouldHaveSize 0
    }

    @Test
    fun `plain text keeps its own delivery`() {
        val d = SpeechCue.deliveries("Обычный текст без ремарок.")

        d shouldHaveSize 1
        d.first().text shouldBe "Обычный текст без ремарок."
        d.first().pitch shouldBe 1.0f
        d.first().rate shouldBe 1.0f
    }

    @Test
    fun `render keeps plain text untouched`() {
        SpeechCue.render("Обычный текст.") shouldBe "Обычный текст."
    }
}
