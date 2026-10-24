package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import eu.kanade.tachiyomi.data.tts.VoicePreset
import org.json.JSONObject

/**
 * Голос конкретной реплики.
 *
 * @param voice имя системного голоса («ru-ru-x-ruf-network» и т.п.); null —
 *   оставить выбранный пользователем.
 * @param pitch/rate множители сверх пользовательских: возраст и пол.
 */
data class KomaVoice(
    val voice: String? = null,
    val pitch: Float = 1f,
    val rate: Float = 1f,
    val title: String = "Обычный",
    val kind: String = "auto",
) {
    fun withBasePitch(base: Float) = pitch * base
}

/**
 * Кто говорит и каким голосом.
 *
 * Требование пользователя: «разные голоса для разных персонажей» и «полуручной
 * режим» — автопроход доходит до реплики, человек нажимает «мужской» и выбирает
 * тип (парень, ребёнок, старик, взрослый; девушка, девочка, старуха, взрослая).
 *
 * Источники «кто говорит», по убыванию надёжности:
 *  1. ручной выбор пользователя (запоминается по тексту реплики и по имени);
 *  2. подпись в самой реплике («АКИ: …») или имя из разметки;
 *  3. автоопределение пола по картинке (Gemini), если введён ключ;
 *  4. предыдущий голос — диалог не должен скакать на каждой реплике.
 */
object KomaCasting {

    /** Типы персонажей, которые предлагаются в полуручном режиме. */
    enum class Kind(
        val id: String,
        val title: String,
        val group: String,
        val gender: VoicePreset.Gender3,
        val age: VoicePreset.Age,
    ) {
        MAN("man", "Мужчина", "male", VoicePreset.Gender3.MALE, VoicePreset.Age.ADULT),
        GUY("guy", "Парень", "male", VoicePreset.Gender3.MALE, VoicePreset.Age.TEEN),
        BOY("boy", "Мальчик", "male", VoicePreset.Gender3.MALE, VoicePreset.Age.CHILD),
        OLD_MAN("old_man", "Старик", "male", VoicePreset.Gender3.MALE, VoicePreset.Age.ELDERLY),
        WOMAN("woman", "Женщина", "female", VoicePreset.Gender3.FEMALE, VoicePreset.Age.ADULT),
        GIRL("girl", "Девушка", "female", VoicePreset.Gender3.FEMALE, VoicePreset.Age.TEEN),
        LITTLE_GIRL("little_girl", "Девочка", "female", VoicePreset.Gender3.FEMALE, VoicePreset.Age.CHILD),
        OLD_WOMAN("old_woman", "Старуха", "female", VoicePreset.Gender3.FEMALE, VoicePreset.Age.ELDERLY),
        KID("kid", "Ребёнок", "neutral", VoicePreset.Gender3.NEUTRAL, VoicePreset.Age.CHILD),
        NARRATOR("narrator", "Рассказчик", "neutral", VoicePreset.Gender3.NEUTRAL, VoicePreset.Age.ADULT),
        ;

        companion object {
            fun fromId(id: String?): Kind? = entries.firstOrNull { it.id == id }
            /** Набор для кнопки «мужской». */
            val male: List<Kind> get() = listOf(MAN, GUY, BOY, OLD_MAN)
            val female: List<Kind> get() = listOf(WOMAN, GIRL, LITTLE_GIRL, OLD_WOMAN)
            /** «Прочие»: ребёнок и рассказчик. */
            val other: List<Kind> get() = listOf(KID, NARRATOR)
        }
    }

    private const val PREFS = "koma_casting"

    // ── Память назначений ────────────────────────────────────────────────────
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyOf(bookId: String, text: String): String =
        "role:$bookId:" + KomaReadingOrder.key(text).take(120)

    /** Сохранённый ручной тип для этой реплики (тем же текстом — тем же голосом). */
    fun savedKind(context: Context, bookId: String, text: String): Kind? =
        Kind.fromId(prefs(context).getString(keyOf(bookId, text), null))

    fun saveKind(context: Context, bookId: String, text: String, kind: Kind) {
        prefs(context).edit().putString(keyOf(bookId, text), kind.id).apply()
    }

    /** Запомнить голос за ИМЕНЕМ персонажа — тогда он действует по всей книге. */
    fun saveName(context: Context, bookId: String, name: String, kind: Kind) {
        val clean = name.trim().lowercase()
        if (clean.length < 2) return
        prefs(context).edit().putString("name:$bookId:$clean", kind.id).apply()
    }

    fun savedName(context: Context, bookId: String, name: String): Kind? {
        val clean = name.trim().lowercase()
        if (clean.length < 2) return null
        return Kind.fromId(prefs(context).getString("name:$bookId:$clean", null))
    }

    fun clearBook(context: Context, bookId: String) {
        val keys: Set<String> = prefs(context).all.keys.toSet()
        val edit = prefs(context).edit()
        keys.filter { key -> key.contains(":$bookId:") }.forEach { key -> edit.remove(key) }
        edit.apply()
    }

    // ── Разбор «кто говорит» из текста ──────────────────────────────────────
    /**
     * Разметка `{имя:Аки}` и подпись «АКИ: …».
     *
     * Экранирование закрывающей скобки обязательно: Android компилирует regexp
     * через ICU, и одиночная `}` после квантификатора `{1,40}` — синтаксическая
     * ошибка, а не «литеральная скобка». Из-за этого v2.9.19 падала на старте
     * авточтения (PatternSyntaxException в KomaAutoRead.run). Плюс сами шаблоны
     * собираются безопасно: сбой компиляции превращается в null, а не в краш.
     */
    private val markedRegex: Regex? by lazy {
        runCatching { Regex("\\{\\s*(?:имя|name)\\s*:\\s*([^}]{1,40})\\}", RegexOption.IGNORE_CASE) }.getOrNull()
    }
    private val speakerPrefix: Regex? by lazy {
        runCatching { Regex("^\\s*([\\p{Lu}][\\p{L}]{1,20})\\s*:\\s+") }.getOrNull()
    }
    private val speakerDash: Regex? by lazy {
        runCatching { Regex("^\\s*([\\p{Lu}][\\p{L}]{1,20})\\s*[—-]\\s+") }.getOrNull()
    }
    private val markedStrip: Regex? by lazy {
        runCatching { Regex("\\{\\s*(?:имя|name)\\s*:\\s*[^}]{1,40}\\}", RegexOption.IGNORE_CASE) }.getOrNull()
    }

    fun speakerName(text: String): String? {
        val marked = markedRegex?.find(text)?.groupValues?.getOrNull(1)?.trim()
        if (!marked.isNullOrBlank()) return marked
        val prefix = speakerPrefix?.find(text) ?: speakerDash?.find(text)
        val name = prefix?.groupValues?.getOrNull(1)?.trim()
        // «НЕ:», «ДА:» и прочие короткие служебные слова именем не считаем.
        if (name == null || name.length < 3) return null
        return name
    }

    /** Текст без подписи — её озвучивать не нужно. */
    fun stripSpeaker(text: String): String {
        var out = markedStrip?.replace(text, "") ?: text
        val name = speakerName(out) ?: return out.trim()
        out = runCatching {
            out.replaceFirst(Regex("^\\s*${Regex.escape(name)}\\s*[:—-]\\s*"), "")
        }.getOrDefault(out)
        return out.trim()
    }

    // ── Голос ───────────────────────────────────────────────────────────────
    /**
     * Голос для типа персонажа.
     *
     * Настоящие «мужские» и «женские» голоса есть не в каждом движке, поэтому
     * пол и возраст передаются ещё и высотой/темпом ([VoicePreset]): это
     * работает на любом локальном синтезаторе.
     */
    fun voice(kind: Kind, basePitch: Float = 1f, baseRate: Float = 1f): KomaVoice = KomaVoice(
        voice = null,
        pitch = (basePitch * kind.gender.pitch * kind.age.pitch).coerceIn(0.5f, 2f),
        rate = (baseRate * kind.age.rate).coerceIn(0.5f, 2.5f),
        title = kind.title,
        kind = kind.id,
    )

    fun voiceOfId(id: String?, basePitch: Float = 1f, baseRate: Float = 1f): KomaVoice? =
        Kind.fromId(id)?.let { voice(it, basePitch, baseRate) }

    /** Пол реплики для озвучки: male/female/null. */
    fun genderOf(kind: Kind): String? = when (kind.group) {
        "male" -> "male"
        "female" -> "female"
        else -> null
    }

    /** Состояние «ждём выбора» в полуручном режиме. */
    data class Request(
        val bookId: String,
        val page: Int,
        val line: String,
        val speaker: String?,
    ) {
        fun json(): String = JSONObject()
            .put("book", bookId).put("page", page).put("line", line).put("speaker", speaker ?: "")
            .toString()
    }
}
