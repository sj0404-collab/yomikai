package eu.kanade.tachiyomi.data.tts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

class VoiceRolesTest {

    private val roles = listOf(
        VoiceRole(
            id = "aki",
            name = "Аки",
            gender = "female",
            age = "teen",
            voice = "ru-ru-x-dfa-network",
            pitch = 1.2f,
            rate = 1.0f,
            markers = listOf("Аки", "AKI"),
        ),
        VoiceRole(
            id = "oldman",
            name = "Старик",
            gender = "male",
            age = "elderly",
            voice = "",
            pitch = 0.7f,
            rate = 0.85f,
        ),
    )

    @Test
    fun `parse and toJson round trip keeps all fields`() {
        val json = VoiceRoleDictionary.toJson(roles)
        val parsed = VoiceRoleDictionary.parse(json)
        parsed shouldBe roles
    }

    @Test
    fun `parse ignores blank and malformed entries`() {
        val parsed = VoiceRoleDictionary.parse("""[{"id":"","name":""},{"name":"Аки","gender":"female"}]""")
        parsed.size shouldBe 1
        parsed.first().name shouldBe "Аки"
    }

    @Test
    fun `resolve picks role by speaker name first`() {
        val resolved = VoiceRoleDictionary.resolve(roles, speakerName = "АКИ", gender = null)
        resolved shouldNotBe null
        resolved!!.name shouldBe "Аки"
    }

    @Test
    fun `resolve matches marker alias case-insensitively`() {
        val resolved = VoiceRoleDictionary.resolve(roles, speakerName = "aki", gender = null)
        resolved?.name shouldBe "Аки"
    }

    @Test
    fun `resolve by explicit gender without a name`() {
        val resolved = VoiceRoleDictionary.resolve(roles, speakerName = null, gender = "male")
        resolved?.name shouldBe "Старик"
    }

    @Test
    fun `resolve returns null when nothing matches`() {
        VoiceRoleDictionary.resolve(roles, speakerName = "Кводе", gender = null).shouldBeNull()
        VoiceRoleDictionary.resolve(roles, speakerName = null, gender = null).shouldBeNull()
    }

    @Test
    fun `role with auto gender never matches explicit gender`() {
        val auto = roles + VoiceRole(id = "n", name = "Нейтрал", gender = "auto")
        VoiceRoleDictionary.resolve(auto, speakerName = null, gender = "male")?.name shouldBe "Старик"
    }

    // --- Роли, привязанные к книге ---------------------------------------
    //
    // Глобальный словарь один на всё приложение, и это было проблемой: голос,
    // назначенный Аки в «Миэруко-тян», тут же перехватывал такую же подпись в
    // любой другой книге. Книжный словарь лежит поверх и имеет приоритет.

    @Test
    fun `book roles are read from a map keyed by manga id`() {
        val json = """{"12":[{"name":"Аки","voice":"book-a","markers":["Аки"]}]}"""
        val parsed = VoiceRoleDictionary.parseBookRoles(json, bookId = 12L)
        parsed.map { it.name } shouldBe listOf("Аки")
        parsed.first().voice shouldBe "book-a"
    }

    @Test
    fun `book roles of another book are invisible`() {
        val json = """{"12":[{"name":"Аки","voice":"book-a"}]}"""
        // Ключа книги 34 нет — роли чужих книг не применяются.
        VoiceRoleDictionary.parseBookRoles(json, bookId = 34L) shouldBe emptyList()
    }

    @Test
    fun `broken book json does not throw`() {
        // Мусор в настройках не должен ронять озвучку: возвращается пусто,
        // дальше работают общие роли.
        VoiceRoleDictionary.parseBookRoles("", bookId = 1L) shouldBe emptyList()
        VoiceRoleDictionary.parseBookRoles("не json", bookId = 1L) shouldBe emptyList()
        VoiceRoleDictionary.parseBookRoles("""{"1": }""", bookId = 1L) shouldBe emptyList()
    }

    @Test
    fun `book role wins over a global one with the same name`() {
        val json = """{"12":[{"name":"Аки","voice":"book-a","markers":["Аки"]}]}"""
        val book = VoiceRoleDictionary.parseBookRoles(json, bookId = 12L)
        // Книжные идут первыми — resolve находит именно их.
        val merged = book + roles
        VoiceRoleDictionary.resolve(merged, speakerName = "Аки", gender = null)?.voice shouldBe "book-a"
    }

    @Test
    fun `rewriting one book keeps the others`() {
        // Ключевая гарантия: перезапись ролей книги не трогает остальные книги
        // и не ломает формат словаря.
        val start = """{"12":[{"name":"Аки","voice":"book-a"}]}"""
        val updated = VoiceJson.withArray(start, key = "34", value = """[{"name":"Бо","voice":"book-b"}]""")

        VoiceRoleDictionary.parseBookRoles(updated, bookId = 12L).first().voice shouldBe "book-a"
        VoiceRoleDictionary.parseBookRoles(updated, bookId = 34L).first().voice shouldBe "book-b"
        // Чужая книга добавилась — прежние не пропали, порядок не важен.
        VoiceRoleDictionary.parseBookRoles(updated, bookId = 12L).map { it.name } shouldBe listOf("Аки")
    }

    @Test
    fun `rewriting a book replaces only its own entry`() {
        val start = """{"12":[{"name":"Аки","voice":"old-a"}],"34":[{"name":"Бо","voice":"old-b"}]}"""
        val updated = VoiceJson.withArray(start, key = "12", value = """[{"name":"Аки","voice":"new-a"}]""")

        VoiceRoleDictionary.parseBookRoles(updated, bookId = 12L).first().voice shouldBe "new-a"
        VoiceRoleDictionary.parseBookRoles(updated, bookId = 34L).first().voice shouldBe "old-b"
    }

    @Test
    fun `empty store becomes a valid single entry`() {
        VoiceJson.withArray("", key = "7", value = """[{"name":"X"}]""")
            .let { VoiceRoleDictionary.parseBookRoles(it, bookId = 7L).map { r -> r.name } } shouldBe listOf("X")
    }

    @Test
    fun `extractArray reads both array and string values`() {
        // Массив — всегда работало.
        VoiceJson.extractArray("""{"12":[{"name":"Аки"}]}""", "12") shouldBe """[{"name":"Аки"}]"""
        // Строка: раньше ветка начинала разбор с открывающей кавычки и сразу
        // выходила -> возвращала null, строковые значения не читались вовсе.
        // Строка-контейнер со вложенным JSON раскрывается обратно в текст.
        val escaped = """{"12":"[{\"name\":\"Аки\"}]"}"""
        VoiceJson.extractArray(escaped, "12")
            .let { VoiceJson.parseObjects(it!!).first()["name"] } shouldBe "Аки"
        // Нет ключа — null, как и раньше.
        VoiceJson.extractArray("""{"12":[{"name":"Аки"}]}""", "99") shouldBe null
    }
}