package tachiyomi.data.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Регрессии по жалобе с устройства: поиск «I am» в источнике, который игнорирует
 * запрос, показывал «How To Survive in Bed With The Emperor» как результат.
 */
class SearchRelevanceTest {

    @Test
    fun `source that ignores the query is detected`() {
        val stat = SearchRelevance.Stat(query = "I am", returned = 24, matched = 0)
        stat.ignoredQuery shouldBe true
        stat.partial shouldBe false
    }

    @Test
    fun `source that finds something is not flagged`() {
        val stat = SearchRelevance.Stat(query = "berserk", returned = 10, matched = 3)
        stat.ignoredQuery shouldBe false
        stat.partial shouldBe true
    }

    @Test
    fun `empty result is not a source problem`() {
        SearchRelevance.Stat(query = "X", returned = 0, matched = 0).ignoredQuery shouldBe false
    }

    @Test
    fun `no query means nothing to flag`() {
        val stat = SearchRelevance.Stat(returned = 30, matched = 0)
        stat.ignoredQuery shouldBe false
        stat.partial shouldBe false
    }

    @Test
    fun `report and reset are per source`() {
        SearchRelevance.reset(7L)
        SearchRelevance.report(7L, SearchRelevance.Stat(query = "berserk", returned = 5, matched = 5))
        SearchRelevance.of(7L)?.matched shouldBe 5
        SearchRelevance.reset(7L)
        SearchRelevance.of(7L) shouldBe null
    }
}
