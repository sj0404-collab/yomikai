package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe

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
