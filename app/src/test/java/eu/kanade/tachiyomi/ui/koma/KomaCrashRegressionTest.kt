package eu.kanade.tachiyomi.ui.koma

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Регрессии по журналу падений 2.9.38 (файл `mihon_crash_logs-2.txt`).
 *
 * Здесь проверяется то, что можно проверить на JVM, — и ровно тем способом,
 * каким эти падения случаются:
 *
 *  1. **PatternSyntaxException** на шаблоне `\{\s*(?:имя|name)\s*:\s*[^}]{1,40}}`
 *     (KomaAutoRead.kt:81, главный поток). Одиночная `}` после квантификатора —
 *     синтаксическая ошибка для ICU на Android. Проверяем: битый шаблон через
 *     [KomaRegex] возвращает null, а читалка продолжает работать.
 *  2. **SIGSEGV в BitmapFactory.decodeStream** через KomaPageStore.decode.
 *     Нативный декодер больше не получает поток: страница читается в байты, а
 *     перед декодом проверяется сигнатура. Проверяем именно эту проверку —
 *     мусор до Skia не доходит (на таком входе Skia не возвращает null, а
 *     падает сигналом, поймать который в Kotlin нельзя).
 *  3. **Размер уменьшения** страницы: 16 Мпикс и 2600 px — пороги, из-за
 *     которых раньше декодировалась целая страница вебтуна.
 */
class KomaCrashRegressionTest {

    // ── 1. Битый шаблон не роняет читалку ────────────────────────────────────

    @Test
    fun `шаблон с одиночной скобкой после квантификатора не роняет читалку`() {
        // Дословно тот шаблон из падения: последняя `}` не экранирована.
        val broken = "\\{\\s*(?:имя|name)\\s*:\\s*[^}]{1,40}}"
        // Главное, что проверяется: сборка шаблона НЕ бросает наружу ни на JVM,
        // ни на Android/ICU. На JVM одиночная `}` допустима, на ICU — нет,
        // поэтому поведение компилятора здесь разное и в тесте не зашивается.
        val compiled = KomaRegex.compileOrNull(broken)
        if (compiled != null) {
            // На JVM шаблон собирается, но разметку не находит: лишняя `}` в
            // шаблоне означает «две закрывающие скобки подряд». Именно это на
            // Android превращалось в PatternSyntaxException и вылет.
            assertNull(compiled.find("{имя:Аки} Привет"))
        }
        // А рабочий шаблон читалки собирается всегда и имя находит.
        val good = KomaRegex.compileOrNull("\\{\\s*(?:имя|name)\\s*:\\s*([^}]{1,40})\\}")
        assertNotNull(good)
        assertEquals("Аки", good!!.find("{имя:Аки} Привет")?.groupValues?.get(1))
    }

    @Test
    fun `правильные шаблоны читалки собираются и работают`() {
        assertEquals("Аки", KomaCasting.speakerName("{имя:Аки} Привет!"))
        assertEquals("Привет!", KomaCasting.stripSpeaker("{имя:Аки} Привет!"))
        assertNull(KomaCasting.speakerName("просто реплика"))
    }

    @Test
    fun `заведомо некомпилируемый шаблон возвращает null`() {
        assertNull(KomaRegex.compileOrNull("([a-z"))
        assertFalse(KomaRegex.isCompilable("*"))
    }

    // ── 2. До нативного декодера доходит только изображение ──────────────────

    @Test
    fun `сигнатуры известных форматов распознаются`() {
        assertTrue(KomaImageBytes.looksLikeImage(jpeg()))
        assertTrue(KomaImageBytes.looksLikeImage(png()))
        assertTrue(KomaImageBytes.looksLikeImage(webp()))
        assertTrue(KomaImageBytes.looksLikeImage("GIF89a".toByteArray() + ByteArray(8)))
        assertTrue(KomaImageBytes.looksLikeImage("BM".toByteArray() + ByteArray(10)))
    }

    @Test
    fun `мусор не считается изображением`() {
        assertFalse(KomaImageBytes.looksLikeImage(ByteArray(0)))
        assertFalse(KomaImageBytes.looksLikeImage(null))
        assertFalse(KomaImageBytes.looksLikeImage(ByteArray(40)))
        // Оглавление архива и HTML-страница ошибки источника.
        assertFalse(KomaImageBytes.looksLikeImage("PK\u0003\u0004".toByteArray() + ByteArray(20)))
        assertFalse(KomaImageBytes.looksLikeImage("<html><body>404".toByteArray() + ByteArray(20)))
        // Усечённая сигнатура: один FF D8 без маркера — не JPEG.
        assertFalse(KomaImageBytes.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(20)))
    }

    // ── 3. Уменьшение страницы при декоде ────────────────────────────────────

    @Test
    fun `обычная страница манги не уменьшается`() {
        assertEquals(1, KomaImageBytes.sampleFor(1200, 1800))
        assertEquals(1, KomaImageBytes.sampleFor(1600, 2400))
    }

    @Test
    fun `длинная страница вебтуна уменьшается по пикселям`() {
        assertEquals(2, KomaImageBytes.sampleFor(1500, 12000))   // 18 Мпикс → 4,5
        assertEquals(2, KomaImageBytes.sampleFor(2000, 9000))    // 18 Мпикс → 4,5
    }

    @Test
    fun `слишком широкая страница уменьшается по ширине`() {
        val sample = KomaImageBytes.sampleFor(6000, 1000)
        assertEquals(4, sample)
        assertTrue(6000 / sample <= KomaImageBytes.MAX_WIDTH)
    }

    @Test
    fun `пиксели после уменьшения укладываются в предел`() {
        val sample = KomaImageBytes.sampleFor(5000, 7000)
        assertTrue(5000L * 7000 / (sample.toLong() * sample) <= KomaImageBytes.MAX_PIXELS)
    }

    @Test
    fun `абсурдный размер не зацикливает уменьшение`() {
        assertTrue(KomaImageBytes.sampleFor(Int.MAX_VALUE, Int.MAX_VALUE) > 0)
        assertEquals(1, KomaImageBytes.sampleFor(0, 0))
    }

    @Test
    fun `широкая страница отдаётся в 16 битах`() {
        assertFalse(KomaImageBytes.prefer565(1200))
        assertTrue(KomaImageBytes.prefer565(1600))
    }

    private fun jpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)

    private fun png(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

    private fun webp(): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray() + ByteArray(8)
}
