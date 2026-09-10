package eu.kanade.tachiyomi.data.tts

import mihon.domain.ocr.service.OcrPreferences

/**
 * Роль говорящего (персонаж) для озвучки.
 *
 * Полноценный словарь поверх прежних трёх слотов ♀/♂/🎙: роль связывает имя
 * или метки [markers] с голосом, полом, возрастом и модификаторами
 * питча/темпа. Имя приходит из разметки `{имя:Аки}`, пол — из `{ж}/{м}` или
 * пресета пользователя.
 *
 * Правила подбора, по убыванию приоритета:
 *  1. имя говорящего совпало с меткой роли (без учёта регистра, подстрока);
 *  2. имя совпало с [name];
 *  3. явный пол (male/female) совпал с [gender] роли (клавиши ♀/♂/🎙 и
 *     ручной режим);
 *  4. пол «auto» — первая роль запасного совпадения только по имени [name]
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

    companion object {
        const val GENDER_AUTO = "auto"
        const val GENDER_MALE = "male"
        const val GENDER_FEMALE = "female"
        const val GENDER_NEUTRAL = "neutral"
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
     * Находит роль для реплики. [speakerName] — из `{имя:…}` (приоритет),
     * [gender] — явный пол (`{ж}/{м}`, кнопки карточки, ручной режим).
     */
    fun resolve(
        roles: List<VoiceRole>,
        speakerName: String?,
        gender: String?,
    ): VoiceRole? {
        val named = roles.filter { it.name.isNotBlank() }
        if (speakerName != null) {
            named.firstOrNull { it.matchesName(speakerName) }?.let { return it }
        }
        if (gender != null && (gender.equals("male", true) || gender.equals("female", true))) {
            named.firstOrNull { it.matchesGender(gender) }?.let { return it }
        }
        return null
    }

    fun resolve(prefs: OcrPreferences, speakerName: String?, gender: String?): VoiceRole? =
        resolve(load(prefs), speakerName, gender)
}