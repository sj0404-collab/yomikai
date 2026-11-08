package tachiyomi.domain.library.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Алфавитный указатель локальной библиотеки.
 *
 * Отдельно проверяется обратная совместимость: запрос без маркера обязан
 * работать как прежний поиск по подстроке, иначе указатель сломал бы поиск в
 * локальной библиотеке.
 */
@Execution(ExecutionMode.CONCURRENT)
class LibraryIndexTest {

    @Test
    fun `an index query keeps only names starting with the letter`() {
        LibraryIndex.matches("Берсерк", "#б") shouldBe true
        LibraryIndex.matches("берсерк", "#Б") shouldBe true
        LibraryIndex.matches("  Берт", "#б") shouldBe true
        LibraryIndex.matches("Акира", "#б") shouldBe false
        LibraryIndex.matches("Berserk", "#b") shouldBe true
        LibraryIndex.matches("Berserk", "#а") shouldBe false
    }

    @Test
    fun `yo is a separate key from ye`() {
        LibraryIndex.matches("Ёлка", "#ё") shouldBe true
        LibraryIndex.matches("Ёлка", "#е") shouldBe false
        LibraryIndex.matches("Елка", "#ё") shouldBe false
    }

    @Test
    fun `a query without the marker still searches by substring`() {
        LibraryIndex.matches("Берсерк", "ерс") shouldBe true
        LibraryIndex.matches("Берсерк", "ЕРС") shouldBe true
        LibraryIndex.matches("Берсерк", "z") shouldBe false
        LibraryIndex.matches("Берсерк", "") shouldBe true
    }

    @Test
    fun `digits and other keys`() {
        LibraryIndex.matches("12 Monkeys", "#0-9") shouldBe true
        LibraryIndex.matches("Акира", "#0-9") shouldBe false
        LibraryIndex.matches("[HorribleSubs] Akira", "#*") shouldBe true
        LibraryIndex.matches("-something", "#*") shouldBe true
        LibraryIndex.matches("Акира", "#*") shouldBe false
        LibraryIndex.matches("12 Monkeys", "#*") shouldBe false
    }

    @Test
    fun `an unknown key after the marker falls back to substring search`() {
        // «#dota» — не ключ указателя, значит пользователь ищет текст «#dota».
        LibraryIndex.indexKey("#dota").shouldBeNull()
        LibraryIndex.matches("#dota inside", "#dota") shouldBe true
        LibraryIndex.matches("Акира", "#dota") shouldBe false
    }

    @Test
    fun `a bare marker is not an index query and searches as text`() {
        // «#» без ключа — это запрос текста «#»: папка «#1 Special» находится,
        // а указатель не включается. Иначе имя папки, начинающееся с «#»,
        // стало бы ненаходимым.
        LibraryIndex.indexKey("#").shouldBeNull()
        LibraryIndex.indexKey("#   ").shouldBeNull()
        LibraryIndex.matches("#1 Special", "#") shouldBe true
        LibraryIndex.matches("#1 Special", "#   ") shouldBe true
        LibraryIndex.matches("Акира", "#") shouldBe false
    }

    @Test
    fun `letters cover cyrillic with yo, latin and both service keys`() {
        LibraryIndex.CYRILLIC.size shouldBe 33
        LibraryIndex.LATIN.size shouldBe 26
        LibraryIndex.LETTERS.size shouldBe 33 + 26 + 2
        LibraryIndex.LETTERS.first() shouldBe "а"
        LibraryIndex.LETTERS.last() shouldBe LibraryIndex.OTHER
        ("ё" in LibraryIndex.LETTERS) shouldBe true
        ("z" in LibraryIndex.LETTERS) shouldBe true
        ("ы" in LibraryIndex.LETTERS) shouldBe true
    }

    @Test
    fun `queryFor builds a marker and rejects garbage`() {
        LibraryIndex.queryFor("б") shouldBe "#б"
        LibraryIndex.queryFor(" Б ") shouldBe "#б"
        LibraryIndex.queryFor("0-9") shouldBe "#0-9"
        LibraryIndex.queryFor("*") shouldBe "#*"
        LibraryIndex.queryFor(null).shouldBeNull()
        LibraryIndex.queryFor("").shouldBeNull()
        LibraryIndex.queryFor("   ").shouldBeNull()
        LibraryIndex.queryFor("dota").shouldBeNull()
    }

    @Test
    fun `isIndexQuery recognizes only a valid marker`() {
        LibraryIndex.isIndexQuery("#а") shouldBe true
        LibraryIndex.isIndexQuery(" #а ") shouldBe true
        LibraryIndex.isIndexQuery("а") shouldBe false
        LibraryIndex.isIndexQuery("#") shouldBe false
        LibraryIndex.isIndexQuery("") shouldBe false
    }

    @Test
    fun `every letter key round-trips through queryFor and indexKey`() {
        LibraryIndex.LETTERS.forEach { letter ->
            val query = LibraryIndex.queryFor(letter)
            requireNotNull(query)
            LibraryIndex.indexKey(query) shouldBe letter
        }
    }

    // ── Мягкий поиск (общий для библиотеки, локальных папок и источников) ──

    @Test
    fun `matchesText ignores word order`() {
        LibraryIndex.matchesText("Берсерк Максимус", "макс берс") shouldBe true
        LibraryIndex.matchesText("Берсерк Максимус", "максим берс") shouldBe true
        LibraryIndex.matchesText("Берсерк Максимус", "макс акира") shouldBe false
    }

    @Test
    fun `matchesText treats yo as ye`() {
        LibraryIndex.matchesText("Ёлка", "елка") shouldBe true
        LibraryIndex.matchesText("Елка", "ёлка") shouldBe true
        LibraryIndex.matchesText("Семён", "семен") shouldBe true
    }

    @Test
    fun `matchesText ignores punctuation and case`() {
        LibraryIndex.matchesText("Тетрадь смерти / Death Note", "death тетрадь") shouldBe true
        LibraryIndex.matchesText("Re:Zero", "re zero") shouldBe true
    }

    @Test
    fun `matchesText with blank query matches everything`() {
        LibraryIndex.matchesText("Что угодно", "") shouldBe true
    }

    @Test
    fun `firstLetterKey maps names to index keys`() {
        LibraryIndex.firstLetterKey("Берсерк") shouldBe "б"
        LibraryIndex.firstLetterKey("  Ёлка") shouldBe "ё"
        LibraryIndex.firstLetterKey("Berserk") shouldBe "b"
        LibraryIndex.firstLetterKey("12 monkeys") shouldBe LibraryIndex.DIGITS
        LibraryIndex.firstLetterKey("[HorribleSubs] X") shouldBe LibraryIndex.OTHER
    }

    @Test
    fun `matchesLetter keeps only titles starting with the letter`() {
        LibraryIndex.matchesLetter("Берсерк", "б") shouldBe true
        LibraryIndex.matchesLetter("Акира", "б") shouldBe false
        LibraryIndex.matchesLetter("Акира", "а") shouldBe true
    }

    @Test
    fun `plain search no longer requires an exact substring`() {
        // Раньше это возвращало false: искалась точная подстрока «берс макс».
        LibraryIndex.matches("Берсерк Максимус", "берс макс") shouldBe true
    }
}
