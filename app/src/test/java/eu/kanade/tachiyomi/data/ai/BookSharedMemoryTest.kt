package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Память между книгами.
 *
 * Проверяется чистая часть — ключ серии и правила разбора. Обращения к файлу
 * (запись/чтение) требуют Android, поэтому в юнит-тесты не входят.
 */
class BookSharedMemoryTest {

    @Test
    fun `same series title gives same key`() {
        val key = BookSharedMemory.seriesKey("Падший лисёнок")
        BookSharedMemory.seriesKey("Падший лисёнок, том 1") shouldBe key
        BookSharedMemory.seriesKey("Падший лисёнок Том 2") shouldBe key
        BookSharedMemory.seriesKey("Падший лисёнок — часть 3") shouldBe key
    }

    @Test
    fun `number after a dot does not stay in the key`() {
        // «Vol. 4» и «Volume 2» — один ключ: точка между словом и номером
        // раньше не пропускалась, и в ключе оставался одинокая «4».
        val key = BookSharedMemory.seriesKey("Solo Leveling")
        BookSharedMemory.seriesKey("Solo Leveling, Vol. 4") shouldBe key
        BookSharedMemory.seriesKey("Solo Leveling vol 3") shouldBe key
        BookSharedMemory.seriesKey("Solo Leveling Volume 2") shouldBe key
    }

    @Test
    fun `different series give different keys`() {
        BookSharedMemory.seriesKey("Падший лисёнок") shouldBe
            BookSharedMemory.seriesKey("Синий замок")
    }

    @Test
    fun `empty title still yields a usable key`() {
        // Книга без названия не должна ломать память серии.
        BookSharedMemory.seriesKey("").isNotBlank() shouldBe true
        BookSharedMemory.seriesKey("   ").isNotBlank() shouldBe true
    }

    @Test
    fun `key ignores punctuation and case`() {
        BookSharedMemory.seriesKey("Один-Кувалда!") shouldBe
            BookSharedMemory.seriesKey("один кувалда")
    }
}
