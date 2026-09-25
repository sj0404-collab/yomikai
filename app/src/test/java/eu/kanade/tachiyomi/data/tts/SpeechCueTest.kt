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
        (d.first().pitch < 1f) shouldBe true
        (d.first().rate < 1f) shouldBe true
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
        (cry.pitch > sigh.pitch) shouldBe true
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
        (d.first().pitch > 1f) shouldBe true
    }

    // --- «а», «аа», «ааа» означают разное ---

    @Test
    fun `short and long a get different delivery`() {
        val one = SpeechCue.deliveries("А!").first()
        val two = SpeechCue.deliveries("Аа!").first()
        val three = SpeechCue.deliveries("Ааа!").first()

        // Удивление → попытка закричать → крик.
        (one.pitch < two.pitch) shouldBe true
        (two.pitch < three.pitch) shouldBe true
        (two.rate < one.rate) shouldBe true
        (three.rate > two.rate) shouldBe true
        (one.pauseAfterMs < three.pauseAfterMs) shouldBe true
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

        (breath.pitch < shout.pitch) shouldBe true
        (breath.rate < shout.rate) shouldBe true
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
        (d.first().rate < 1f) shouldBe true
    }

    @Test
    fun `running speeds up and exhaustion slows down`() {
        val run = SpeechCue.deliveries("(бегом) Быстрее!").first()
        val tired = SpeechCue.deliveries("(устал) Еле иду.").first()
        val weak = SpeechCue.deliveries("(слабость) Не могу.").first()

        (run.rate > 1f) shouldBe true
        (tired.rate < 1f) shouldBe true
        (weak.rate < tired.rate) shouldBe true
        (weak.pitch < tired.pitch) shouldBe true
    }

    @Test
    fun `state applies to interjections inside the same line`() {
        // Множитель должен доставать и до «ааа», иначе крик внутри шёпота
        // остался бы во всю громкость.
        val d = SpeechCue.deliveries("(шёпотом) Ааа! Прости.")

        d shouldHaveSize 2
        val shout = d.first()
        // Питч крика не меняется: шёпот — это темп и громкость, а не высота
        // вокала. Темп при этом должен упасть с 1.12 до 0.81.
        shout.pitch shouldBe 1.28f
        (shout.rate < 0.9f) shouldBe true
        // Соседняя фраза без ремарки тоже тише и медленнее.
        (d.last().rate < 1f) shouldBe true
    }

    @Test
    fun `most noticeable state wins when several are mentioned`() {
        val d = SpeechCue.deliveries("(устал) (бегом) Вперёд!").first()

        (d.rate > 1f) shouldBe true
    }

    // --- мусор не попадает в синтез ---

    @Test
    fun `orphan formatting marks are not spoken`() {
        val d = SpeechCue.deliveries("*(смеётся) Ха!")

        (d.none { it.text == "*" }) shouldBe true
        (d.any { it.text == "Ха-ха-ха!" }) shouldBe true
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

    @Test
    fun `starred remark is parsed like a plain one`() {
        // `(*шёпотом*)` и `(шёпотом)` — одна и та же ремарка. Раньше `*` внутри
        // скобок ломал разбор, и на движок уходили отдельными вызовами «(» и «)».
        val starred = SpeechCue.deliveries("(*шёпотом*) Прости.")
        val plain = SpeechCue.deliveries("(шёпотом) Прости.")

        starred.map { it.text } shouldBe plain.map { it.text }
        starred.map { it.rate } shouldBe plain.map { it.rate }
    }

    @Test
    fun `no unit is ever made of bare punctuation`() {
        // Ни один кусок не должен уйти в движок без букв или цифр: скобки от
        // незакрытой разметки он либо пропустит, либо прочтёт вслух.
        for (line in listOf("(*шёпотом*)", "(шёпотом)", "*Ааа!*", "()", "*~*", "(вздох)")) {
            val units = SpeechCue.deliveries(line)
            units.map { it.text }.filterNot { SpeechCue.isAudible(it) } shouldBe emptyList()
        }
    }

    @Test
    fun `interjection is recognized despite trailing punctuation`() {
        // В наборе лежит «Ааа»; «Ааа!» — то же междометие с восклицательным
        // знаком. Раньше `*Ааа!*` и `[Ааа!]` молчали целиком.
        for (token in listOf("Ааа!", "Ааа!", "ах…", "ах", "А")) {
            SpeechCue.isInterjection(token) shouldBe true
        }
        SpeechCue.isInterjection("Прости.") shouldBe false

        val d = SpeechCue.deliveries("*Ааа!*")
        d shouldHaveSize 1
        d.first().fromCue shouldBe true
    }

    @Test
    fun `audibility requires a letter or a digit`() {
        SpeechCue.isAudible("Ааа!") shouldBe true
        SpeechCue.isAudible("2024") shouldBe true
        SpeechCue.isAudible("(") shouldBe false
        SpeechCue.isAudible(" ) ") shouldBe false
        SpeechCue.isAudible("") shouldBe false
    }

    @Test
    fun `starred word is text and must not be dropped`() {
        // Регрессия: `*Привет* мир` терял слово «Привет» — звёздочки читались как
        // неизвестная ремарка и выбрасывались вместе с текстом.
        val d = SpeechCue.deliveries("*Привет* мир")
        d.map { it.text } shouldBe listOf("Привет", "мир")

        // На экране (`render`) и в озвучке (`deliveries`) должно совпадать.
        SpeechCue.render("*Привет* мир") shouldBe "Привет мир"
        SpeechCue.deliveries("**Кричи**").map { it.text } shouldBe listOf("Кричи")
    }

    @Test
    fun `unknown remark in brackets is still dropped`() {
        // Скобки — это указание говорящему, неизвестное значение не читается.
        SpeechCue.deliveries("(неизвестно) Текст.").map { it.text } shouldBe listOf("Текст.")
        // А вот состояние подачу меняет, хоть и не произносится.
        val w = SpeechCue.deliveries("(шёпотом) Текст.").first()
        w.text shouldBe "Текст."
        (w.rate < 1f) shouldBe true
    }

    @Test
    fun `only real sounds are marked for the plugin bridge`() {
        // Мост отправляет в плагин только звуки — то, что обычный голос
        // произнести не может. Междометия и обычный текст остаются на голосе,
        // иначе плагин забирал бы весь текст книги.
        val sound = SpeechCue.deliveries("(вздох)").first()
        sound.isSound shouldBe true
        sound.soundId shouldBe SpeechCue.SoundId.SIGH

        // Междометие пришло из ремарки, но междометием не является.
        val interjection = SpeechCue.deliveries("(*Ааа!*)").first()
        interjection.fromCue shouldBe true
        interjection.isSound shouldBe false
        interjection.soundId shouldBe null

        // Обычный текст — вообще не из ремарки.
        val text = SpeechCue.deliveries("Прости.").first()
        text.fromCue shouldBe false
        text.isSound shouldBe false
        text.soundId shouldBe null
    }

    @Test
    fun `every built-in sound has a stable id`() {
        // Мост ищет звук по типу, поэтому у каждого типа должен быть свой ключ,
        // иначе плагин не сможет отличить вздох от кашля.
        val byRemark = mapOf(
            "(крик помощи)" to SpeechCue.SoundId.CRY_FOR_HELP,
            "(крик)" to SpeechCue.SoundId.CRY,
            "(стон)" to SpeechCue.SoundId.MOAN,
            "(вдох)" to SpeechCue.SoundId.GASP,
            "(вздох)" to SpeechCue.SoundId.SIGH,
            "(смех)" to SpeechCue.SoundId.LAUGH,
            "(кашель)" to SpeechCue.SoundId.COUGH,
        )
        for ((remark, id) in byRemark) {
            val d = SpeechCue.deliveries(remark).first()
            d.soundId shouldBe id
            d.isSound shouldBe true
        }
    }
}
