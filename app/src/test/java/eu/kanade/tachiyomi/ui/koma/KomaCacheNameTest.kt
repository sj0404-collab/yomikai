package eu.kanade.tachiyomi.ui.koma

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Имя файла кэша распознавания.
 *
 * Падение с устройства (2.9.41): `FileNotFoundException: /data/user/0/app.yomikai/
 * files/ocr/chapter/66_5_Кириллица_b0_11.json: open failed: ENOENT`. Идентификатор
 * главы — `chapter/66`, и слэш из него делал из имени файла ПУТЬ в
 * несуществующую папку. Распознавание при этом уже отработало (в диагностике —
 * «итого областей: 30»), но запись в кэш падала, и результат выбрасывался:
 * на экране было «Текст не найден».
 *
 * Тест держит главное свойство: имя кэша — это ОДНО имя файла, без разделителей
 * пути, для любого идентификатора книги.
 */
class KomaCacheNameTest {

    /** Правило имени продублировано здесь осознанно: так тест видит сам формат. */
    private fun name(id: String, page: Int, language: String, band: Int, version: String) =
        id.replace(Regex("[^\\p{L}\\p{N}._-]"), "_") +
            "_${page}_${language.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")}_b${band}_$version.json"

    @Test
    fun `путь главы не превращается в подпапку`() {
        val n = name("chapter/66", 5, "Кириллица", 0, "11")
        assertFalse(n.contains('/'), "в имени файла остался разделитель пути: $n")
        assertEquals("chapter_66_5_Кириллица_b0_11.json", n)
    }

    @Test
    fun `разные страницы и полосы дают разные файлы`() {
        val a = name("book/x", 1, "Кириллица", 0, "11")
        val b = name("book/x", 1, "Кириллица", 2, "11")
        val c = name("book/x", 2, "Кириллица", 0, "11")
        assertTrue(a != b && b != c && a != c)
    }

    @Test
    fun `обычный идентификатор не портится`() {
        assertEquals(
            "book_1_3_Кириллица_b1_11.json",
            name("book_1", 3, "Кириллица", 1, "11"),
        )
    }

    @Test
    fun `кириллица и точки остаются в имени`() {
        val n = name("lib/book.v2", 7, "Латиница", 0, "11")
        assertTrue(n.contains("Кириллица_") || n.contains("Латиница"))
        assertTrue(n.contains("book.v2"))
    }
}
