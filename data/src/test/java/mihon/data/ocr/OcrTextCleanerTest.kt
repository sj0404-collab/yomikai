package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Постобработка OCR-текста: склейка переносов (онлайн-модели отдают текст
 * построчно), пословная правка омоглифов (чтобы TTS не читал латынь по
 * буквам внутри русских слов) и фильтр «словарной лесенки» — признака
 * дрейфа CTC-декодера.
 */
class OcrTextCleanerTest {

    @Test
    fun `line hyphen is joined into one word`() {
        OcrTextCleaner.joinLineHyphens("пере-\nносится") shouldBe "переносится"
    }

    @Test
    fun `hyphen with spaces around the break is joined`() {
        OcrTextCleaner.joinLineHyphens("чело- \n век") shouldBe "человек"
    }

    @Test
    fun `local caption normalization joins device-reported false line hyphens before whitespace collapse`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption("МНЕ ХО-\nРОШО ЗНАКОМО ЭТО ИМЯ.") shouldBe
            "МНЕ ХОРОШО ЗНАКОМО ЭТО ИМЯ."
        OcrTextCleaner.normalizeLocalCyrillicCaption("НЕУПРАВ-\nЛЯЕМЫЙ... БЕС-\nПОЛЕЗНЫЙ") shouldBe
            "НЕУПРАВЛЯЕМЫЙ... БЕСПОЛЕЗНЫЙ"
    }

    @Test
    fun `inline hyphen stays`() {
        OcrTextCleaner.joinLineHyphens("из-за дома") shouldBe "из-за дома"
    }

    @Test
    fun `mixed word gets lookalikes fixed`() {
        OcrTextCleaner.fixLookalikesPerWord("cлишком") shouldBe "слишком"
    }

    @Test
    fun `pure latin word is kept so tts reads it as a word`() {
        OcrTextCleaner.fixLookalikesPerWord("SOS Wi-Fi") shouldBe "SOS Wi-Fi"
    }

    @Test
    fun `non-whitelisted pure latin is preserved instead of fabricated into russian`() {
        OcrTextCleaner.fixLookalikesPerWord("PEILENIE") shouldBe "PEILENIE"
    }

    @Test
    fun `mixed word gets the extended confusion map`() {
        OcrTextCleaner.fixLookalikesPerWord("NОЖНО") shouldBe "НОЖНО"
        OcrTextCleaner.fixLookalikesPerWord("ВАН5АСКЕРВWАВ") shouldBe "ВАНБАСКЕРВВАВ"
    }

    @Test
    fun `dictionary ramp is detected as garbage`() {
        OcrTextCleaner.looksLikeDictionaryRamp("0123456789:?LABCDEFGHIJKLM") shouldBe true
    }

    @Test
    fun `normal russian text is not a ramp`() {
        OcrTextCleaner.looksLikeDictionaryRamp("Идиот! Бежит...") shouldBe false
    }

    @Test
    fun `mixed visual lookalikes become acceptable cyrillic`() {
        val repaired = OcrTextCleaner.fixLookalikesPerWord("УMНO,")
        repaired shouldBe "УМНО,"
        OcrTextCleaner.isAcceptableCyrillicOcrText(repaired) shouldBe true
    }

    @Test
    fun `punctuation and latin-shaped garbage are rejected for russian local ocr`() {
        OcrTextCleaner.isAcceptableCyrillicOcrText("?!") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("Tele'axect.E") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("SOS") shouldBe true
    }

    @Test
    fun `restores caption spaces and ё without replacing unknown words`() {
        OcrTextCleaner.restoreKnownCaptionWords("ОНБЫЛ ЛОЖНООБВИНЕН В СГОВОРЕ СДЕМОНОМ") shouldBe
            "ОН БЫЛ ЛОЖНО ОБВИНЁН В СГОВОРЕ С ДЕМОНОМ"
        OcrTextCleaner.restoreKnownCaptionWords("«ОХОТНИЧИЙПЕС»ДОМАБАСКЕРВИЛЕЙ.") shouldBe
            "«ОХОТНИЧИЙ ПЁС» ДОМА БАСКЕРВИЛЕЙ."
        OcrTextCleaner.restoreKnownCaptionWords("ВИКИРВАНБАСКЕРВИЛЬ.") shouldBe
            "ВИКИР ВАН БАСКЕРВИЛЬ."
    }

    @Test
    fun `restores all known words in device-reported no-result white caption`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption(
            "ПОСЛОВАМ «ОХОТНИЧЬЕГОПСА», КОТОРЫЙПОСВЯТИЛ СЕБЯОТЦУИСЕМЬЕ,",
        ) shouldBe "ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА», КОТОРЫЙ ПОСВЯТИЛ СЕБЯ ОТЦУ И СЕМЬЕ,"
    }

    @Test
    fun `restores device-reported glued captions from user screenshots`() {
        OcrTextCleaner.restoreKnownCaptionWords("СЕГОДНЯЯНЕ СМОГКОСНУТЬСЯ КОНЧИКАВОЛОС ЭТОГОПАРНЯ!") shouldBe
            "СЕГОДНЯ Я НЕ СМОГ КОСНУТЬСЯ КОНЧИКА ВОЛОС ЭТОГО ПАРНЯ!"
        OcrTextCleaner.restoreKnownCaptionWords("УФ,КАКЖЕ ЖАРКО!") shouldBe "УФ, КАК ЖЕ ЖАРКО!"
        OcrTextCleaner.restoreKnownCaptionWords("НАСЕГОДНЯ ТРЕНИРОВКА ОКОНЧЕНА!") shouldBe
            "НА СЕГОДНЯ ТРЕНИРОВКА ОКОНЧЕНА!"
    }

    @Test
    fun `space is restored after a comma before a letter`() {
        OcrTextCleaner.restoreKnownCaptionWords("КОГДАЖЕ Я,НАКОНЕЦ, СМОГУПО") shouldBe
            "КОГДА ЖЕ Я, НАКОНЕЦ, СМОГУ ПО"
    }

    @Test
    fun `unknown cyrillic run is never split or rewritten`() {
        OcrTextCleaner.restoreKnownCaptionWords("НЕИЗВЕСТНОЕСЛОВО") shouldBe "НЕИЗВЕСТНОЕСЛОВО"
    }

    @Test
    fun `short valid russian utterances are accepted`() {
        OcrTextCleaner.isAcceptableCyrillicOcrText("а") shouldBe true
        OcrTextCleaner.isAcceptableCyrillicOcrText("а-а-а") shouldBe true
        OcrTextCleaner.isAcceptableCyrillicOcrText("а!") shouldBe true
        OcrTextCleaner.isAcceptableCyrillicOcrText("а...") shouldBe true
    }

    @Test
    fun `mixed latin lookalike garbage is never accepted as russian`() {
        OcrTextCleaner.isAcceptableCyrillicOcrText("разiiiнение") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("мама-naма") shouldBe false
        OcrTextCleaner.isAcceptableCyrillicOcrText("сахар-samaар") shouldBe false
    }

    @Test
    fun `uncertain cyrillic text is preserved rather than rewritten`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption("сахар-самаар") shouldBe "сахар-самаар"
        OcrTextCleaner.normalizeLocalCyrillicCaption("цвет-свек") shouldBe "цвет-свек"
    }

    @Test
    fun `one garbage token no longer erases the whole caption`() {
        OcrTextCleaner.filterGarbageTokens("И ПАЛ Tele'axect.E ПОД ЛЕЗВИЕМ") shouldBe "И ПАЛ ПОД ЛЕЗВИЕМ"
        OcrTextCleaner.filterGarbageTokens("ОН БЫЛ ЛОЖНО ОБВИНЁН В zz СГОВОРЕ С ДЕМОНОМ") shouldBe
            "ОН БЫЛ ЛОЖНО ОБВИНЁН В СГОВОРЕ С ДЕМОНОМ"
        // Один мусорный токен отбрасывается, остальные слова подписи живут.
        OcrTextCleaner.filterGarbageTokens("ПО СЛОВАМ axect «ОХОТНИЧЬЕГО ПСА»") shouldBe
            "ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА»"
    }

    @Test
    fun `whitelisted latin tokens survive the salvage pass`() {
        OcrTextCleaner.filterGarbageTokens("SOS ПОМОГИТЕ Wi-Fi") shouldBe "SOS ПОМОГИТЕ Wi-Fi"
    }

    @Test
    fun `a line without cyrillic is returned unchanged`() {
        OcrTextCleaner.filterGarbageTokens("OPEN") shouldBe "OPEN"
        OcrTextCleaner.filterGarbageTokens("SOS") shouldBe "SOS"
    }

    @Test
    fun `pure latin junk is dropped by the final gate instead of read out loud`() {
        // Регрессия: модель читала «ШУМ» как латиницу (v i m / V I M), и русский
        // TTS диктовал её по буквам. Чистая не-whitelist латынь теперь не выводится.
        OcrTextCleaner.acceptableAfterSalvage("v i m") shouldBe ""
        OcrTextCleaner.acceptableAfterSalvage("OPEN") shouldBe ""
        OcrTextCleaner.acceptableAfterSalvage("PEILENIE") shouldBe ""
    }

    @Test
    fun `whitelisted latin tokens survive the final gate`() {
        OcrTextCleaner.acceptableAfterSalvage("SOS") shouldBe "SOS"
        OcrTextCleaner.acceptableAfterSalvage("Wi-Fi ПОМОГИТЕ") shouldBe "Wi-Fi ПОМОГИТЕ"
    }

    @Test
    fun `cyrillic caption with one garbage token survives the final gate`() {
        OcrTextCleaner.acceptableAfterSalvage("И ПАЛ Tele'axect.E ПОД ЛЕЗВИЕМ") shouldBe
            "И ПАЛ ПОД ЛЕЗВИЕМ"
    }

    @Test
    fun `uncertain mixed-script lines are never partially salvaged`() {
        // «cлишком» — смешанный токен: строка остаётся как есть, а не
        // собирается заново из уцелевших слов.
        OcrTextCleaner.filterGarbageTokens("ОН cлишком ДЕМОНОМ") shouldBe "ОН cлишком ДЕМОНОМ"
    }

    @Test
    fun `blank text stays blank`() {
        OcrTextCleaner.filterGarbageTokens("") shouldBe ""
        OcrTextCleaner.filterGarbageTokens("   ") shouldBe "   "
    }

    @Test
    fun `single visible gap is treated as a word boundary`() {
        ocrWordGapThreshold(intArrayOf(7), wordGapFactor = 1.7f, minWordGapPx = 5) shouldBe 5
        ocrWordGapThreshold(intArrayOf(3), wordGapFactor = 1.7f, minWordGapPx = 5) shouldBe 5
    }

    @Test
    fun `word boundary uses the lower gap cluster instead of the widest gap`() {
        ocrWordGapThreshold(intArrayOf(2, 10), wordGapFactor = 1.7f, minWordGapPx = 5) shouldBe 5
        ocrWordGapThreshold(intArrayOf(2, 2, 2, 10, 10, 10), wordGapFactor = 1.7f, minWordGapPx = 5) shouldBe 5
    }

    @Test
    fun `equal word-sized gaps are not compared with themselves`() {
        ocrWordGapThreshold(intArrayOf(6, 6, 6), wordGapFactor = 1.7f, minWordGapPx = 5) shouldBe 5
        ocrWordGapThreshold(intArrayOf(4, 4, 4), wordGapFactor = 1.7f, minWordGapPx = 5) shouldBe 5
    }

    @Test
    fun `ml kit clean line keeps blank and pure-latin text`() {
        OcrTextCleaner.cleanMlKitLine("   ") shouldBe ""
        OcrTextCleaner.cleanMlKitLine("") shouldBe ""
        OcrTextCleaner.cleanMlKitLine("Are you ready?") shouldBe "Are you ready?"
        OcrTextCleaner.cleanMlKitLine("SOS") shouldBe "SOS"
        OcrTextCleaner.cleanMlKitLine("3D") shouldBe "3D"
    }

    @Test
    fun `ml kit clean line keeps clean cyrillic as-is`() {
        OcrTextCleaner.cleanMlKitLine("ПРИВЕТ!") shouldBe "ПРИВЕТ!"
        OcrTextCleaner.cleanMlKitLine("надо просто продолжать") shouldBe "надо просто продолжать"
    }

    @Test
    fun `ml kit clean line fixes latin lookalikes inside cyrillic words`() {
        // «ЛPHИВЕТ» — типичный вывод латинской модели ML Kit на русском тексте.
        OcrTextCleaner.cleanMlKitLine("ЛPHИВЕТ!") shouldBe "ЛРНИВЕТ!"
    }

    @Test
    fun `ml kit clean line drops latin garbage from a cyrillic line`() {
        // «Vorld» не вошёл в белый список — как и у кириллического движка,
        // мусорный токен уходит, чистая фраза остаётся.
        OcrTextCleaner.cleanMlKitLine("ПРИВЕТ Vorld") shouldBe "ПРИВЕТ"
    }

    @Test
    fun `ml kit clean line rejects non-lookalike mixed-script garbage`() {
        // «q» нет в таблице омоглифов: слово остаётся смешанным, а смешанный
        // токен не является чистой кириллицей — строка отбрасывается целиком.
        OcrTextCleaner.cleanMlKitLine("Прqaмер") shouldBe ""
    }

    @Test
    fun `ml kit clean line rejects diacritic mojibake`() {
        // «Êðèñòî» — мусорная раскодировка кириллицы в Latin-1: буквы не ASCII,
        // кириллицы нет — это не текст, а крякозябры.
        OcrTextCleaner.cleanMlKitLine("Êðèñòî") shouldBe ""
    }

    @Test
    fun `fictional captions are not dictionary corrected`() {
        OcrTextCleaner.normalizeLocalCyrillicCaption("Столичный город Арзия") shouldBe
            "Столичный город Арзия"
        OcrTextCleaner.normalizeLocalCyrillicCaption("Никак иначе") shouldBe
            "Никак иначе"
    }

    @Test
    fun `promotional watermark is removed without touching caption`() {
        val text = "Столичный город Арзия\nREMANGA.ORG ЧИТАЙ РАНЬШЕ ВСЕХ"
        OcrTextCleaner.stripPromotionalText(text) shouldBe "Столичный город Арзия"
        OcrTextCleaner.isPromotionalText("REMANGA.ORG ЧИТАЙ РАНЬШЕ ВСЕХ") shouldBe true
    }
}
