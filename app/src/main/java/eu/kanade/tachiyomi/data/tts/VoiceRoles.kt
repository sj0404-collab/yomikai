package eu.kanade.tachiyomi.data.tts

import mihon.domain.ocr.service.OcrPreferences

/**
 * Роль говорящего (персонаж) для озвучки.
 *
 * Полноценный словарь поверх прежних трёх слотов ♀/♂/🎙: роль связывает имя
 * или метки [markers] с голосом, полом, возрастом и модификаторами
 * питча/темпа. Имя приходит из разметки `{имя:Аки}` или из подписи в самом
 * тексте реплики («АКИ: …»), пол — из `{ж}/{м}` или пресета пользователя.
 *
 * Правила подбора, по убыванию приоритета:
 *  1. имя говорящего совпало с меткой роли (без учёта регистра, подстрока);
 *  2. имя совпало с [name];
 *  3. имя или метка роли упомянуты САМИМ ТЕКСТОМ реплики — единственный путь
 *     для OCR-чтения, где разметки `{имя:Аки}` нет (иначе все настроенные
 *     роли молча игнорировались и оставался «только пол»);
 *  4. явный пол (male/female) совпал с [gender] роли (клавиши ♀/♂/🎙 и
 *     ручной режим);
 *  5. пол «auto» — первая роль запасного совпадения только по имени [name]
 *     даже без меток не выбирается (риск ложного срабатывания).
 *
 * Все модификаторы перемножаются с пресетами [VoicePreset], поэтому роль
 * «Старик» звучит ниже/медленнее без ручной звукорежиссуры.
 */
data class VoiceRole(
    val id: String,
    val name: String,
    val gender: String = "auto", // auto | male | female | neutral
    val age: String = "adult", // VoicePreset.Age.id
    val voice: String = "", // «пакет::имя», пусто = подбор по полу/возрасту
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f,
    val markers: List<String> = emptyList(),
) {

    /** Совпадает ли роль с именем говорящего (`{имя:Аки}`). */
    fun matchesName(speakerName: String?): Boolean {
        if (speakerName.isNullOrBlank()) return false
        val name = speakerName.trim().lowercase()
        if (this.name.lowercase() == name) return true
        return markers.any { marker ->
            val m = marker.lowercase()
            m == name || name.contains(m) || m.contains(name)
        }
    }

    /** Совпадает ли роль с явным полом реплики. */
    fun matchesGender(roleGender: String?): Boolean {
        if (roleGender.isNullOrBlank()) return false
        if (gender == "auto") return false
        return roleGender.equals(gender, ignoreCase = true) || roleGender == "narrator"
    }

    /**
     * Упомянут ли персонаж в тексте реплики: «АКИ: …», «Аки, помоги!».
     *
     * Сравнение идёт ПО СЛОВАМ, а не по подстроке: иначе короткая метка
     * совпала бы с половиной реплики («Анна» внутри «анна‑виктория»), и роль
     * перехватывала бы чужие реплики. Метка из нескольких слов должна
     * присутствовать целиком («Ким Чен», а не одно «ким»), слова короче трёх
     * букв не сверяем — такие встречаются в любом тексте.
     */
    fun mentionedIn(text: String): Boolean {
        val words = text.lowercase()
            .split(WORD_BREAK)
            .filter { it.isNotEmpty() }
            .toSet()
        if (words.isEmpty()) return false
        return mentions().any { marker ->
            val parts = marker.lowercase().trim()
                .split(WORD_BREAK)
                .filter { it.isNotEmpty() }
            parts.isNotEmpty() &&
                parts.all { it.length >= 3 && it in words }
        }
    }

    /** Имя роли и её метки — всё, чем роль может назвать персонажа. */
    private fun mentions(): List<String> = buildList {
        add(name)
        addAll(markers)
    }

    companion object {
        const val GENDER_AUTO = "auto"
        const val GENDER_MALE = "male"
        const val GENDER_FEMALE = "female"
        const val GENDER_NEUTRAL = "neutral"

        /** Разделители слов: всё, кроме букв и цифр. */
        private val WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")
    }
}

/**
 * Доступ к словарю голосовых ролей: JSON хранится в настройках
 * ([OcrPreferences.voiceRoles]), читается при каждой озвучке — менять роли
 * можно без перезапуска приложения.
 */
object VoiceRoleDictionary {

    /**
     * Разбор строки JSON-массива ролей. Не зависит от org.json (в юнит-тестах
     * app-модуля org.json не замокан и бросает), поэтому полностью ручной.
     */
    fun parse(json: String): List<VoiceRole> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        return VoiceJson.parseObjects(trimmed).mapNotNull { obj ->
            val name = obj["name"].orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val markers = VoiceJson.parseArrayTokens(obj["markers"].orEmpty())
            VoiceRole(
                id = obj["id"].orEmpty().ifBlank { name },
                name = name,
                gender = obj["gender"].orEmpty().ifBlank { VoiceRole.GENDER_AUTO },
                age = obj["age"].orEmpty().ifBlank { "adult" },
                voice = obj["voice"].orEmpty(),
                pitch = obj["pitch"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.0f,
                rate = obj["rate"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.0f,
                markers = markers,
            )
        }
    }

    /** Ручная JSON-сериализация массива ролей. */
    fun toJson(roles: List<VoiceRole>): String {
        val sb = StringBuilder("[")
        roles.forEachIndexed { idx, role ->
            if (idx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":\"").append(VoiceJson.jsonEscape(role.id)).append('"')
            sb.append(",\"name\":\"").append(VoiceJson.jsonEscape(role.name)).append('"')
            sb.append(",\"gender\":\"").append(VoiceJson.jsonEscape(role.gender)).append('"')
            sb.append(",\"age\":\"").append(VoiceJson.jsonEscape(role.age)).append('"')
            sb.append(",\"voice\":\"").append(VoiceJson.jsonEscape(role.voice)).append('"')
            sb.append(",\"pitch\":").append(role.pitch)
            sb.append(",\"rate\":").append(role.rate)
            sb.append(",\"markers\":[")
            role.markers.forEachIndexed { mi, marker ->
                if (mi > 0) sb.append(',')
                sb.append('"').append(VoiceJson.jsonEscape(marker)).append('"')
            }
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }

    fun load(prefs: OcrPreferences): List<VoiceRole> = parse(prefs.voiceRoles().get())

    fun save(prefs: OcrPreferences, roles: List<VoiceRole>) {
        prefs.voiceRoles().set(toJson(roles))
    }

    /**
     * Роли текущей книги поверх глобальных.
     *
     * Книжные идут ПЕРВЫМИ: под «АКИ» в «Миэруко-тян» назначен один голос, и
     * такой же персонаж в другой книге не должен его перехватывать. Общие
     * роли остаются в хвосте и работают там, где книжной нет.
     */
    fun loadForBook(prefs: OcrPreferences, bookId: Long?): List<VoiceRole> {
        val global = load(prefs)
        if (bookId == null || bookId <= 0L) return global
        return parseBookRoles(prefs.voiceRolesByBook().get(), bookId) + global
    }

    /**
     * Разбор объекта `{ "<id>": [ роли ] }` и выбор ролей одной книги.
     *
     * Свой парсер, как и в [parse]: org.json в юнит-тестах app-модуля не
     * замокан. Любой мусор в JSON не должен ронять озвучку — при негодном
     * разборе возвращается пустой список и работают общие роли.
     */
    fun parseBookRoles(json: String, bookId: Long): List<VoiceRole> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = VoiceJson.extractArray(trimmed, bookId.toString()) ?: return emptyList()
        return parse(array)
    }

    /**
     * Сохранить роли книги, не трогая роли остальных книг.
     *
     * Запись идёт целиком по ключу книги: чужие книги остаются как были, и
     * глобальный словарь не переписывается.
     */
    fun saveForBook(prefs: OcrPreferences, bookId: Long, roles: List<VoiceRole>) {
        if (bookId <= 0L) return
        val current = prefs.voiceRolesByBook().get()
        val updated = VoiceJson.withArray(current, bookId.toString(), toJson(roles))
        prefs.voiceRolesByBook().set(updated)
    }

    /**
     * Находит роль для реплики. [speakerName] — из `{имя:…}` (приоритет),
     * [gender] — явный пол (`{ж}/{м}`, кнопки карточки, ручной режим),
     * [text] — сама реплика: на OCR-чтении разметки с именем нет, поэтому роль
     * опознаётся по упоминанию имени/метки прямо в тексте.
     */
    fun resolve(
        roles: List<VoiceRole>,
        speakerName: String?,
        gender: String?,
        text: String? = null,
    ): VoiceRole? {
        val named = roles.filter { it.name.isNotBlank() }
        if (speakerName != null) {
            named.firstOrNull { it.matchesName(speakerName) }?.let { return it }
        }
        if (!text.isNullOrBlank()) {
            named.firstOrNull { it.mentionedIn(text) }?.let { return it }
        }
        if (gender != null && (gender.equals("male", true) || gender.equals("female", true))) {
            named.firstOrNull { it.matchesGender(gender) }?.let { return it }
        }
        return null
    }

    fun resolve(
        prefs: OcrPreferences,
        speakerName: String?,
        gender: String?,
        text: String? = null,
    ): VoiceRole? = resolve(load(prefs), speakerName, gender, text)
}