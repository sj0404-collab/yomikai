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

    // ── Пресеты голоса: четыре голоса с тембром ─────────────────────────────
    // Пользователь: сначала «три голоса с ползунками от низкого до высокого
    // тембра», затем «на каждый тембр 3 разных голоса, например f1, f2, m1 от
    // Tera TTS, можно и 4 — m2». Итог: четыре слота ж1/ж2/м1/м2. У каждого
    // свой НАСТОЯЩИЙ голос движка (или автоподбор по полу) и свой тембр —
    // ползунок остаётся, но теперь он правит голос, а не подменяет его.
    const val SLOT_COUNT = 4

    /** Подписи слотов в интерфейсе. */
    val SLOT_TITLES = listOf("ж1", "ж2", "м1", "м2")

    private fun slotKey(bookId: String, text: String) =
        "slot:$bookId:" + KomaReadingOrder.key(text).take(120)

    /** Какой слот (0..3) назначен реплике, null — голос по умолчанию. */
    fun savedSlot(context: Context, bookId: String, text: String): Int? =
        prefs(context).getInt(slotKey(bookId, text), -1).takeIf { it in 0 until SLOT_COUNT }

    fun saveSlot(context: Context, bookId: String, text: String, slot: Int?) {
        val edit = prefs(context).edit()
        if (slot == null) edit.remove(slotKey(bookId, text)) else edit.putInt(slotKey(bookId, text), slot)
        edit.apply()
    }

    /** Тембр слота (множитель питча): 0.6 — низкий, 1.0 — обычный, 1.6 — высокий. */
    fun savedPitch(context: Context, slot: Int): Float =
        prefs(context).getFloat("pitch:$slot", DEFAULT_PITCH[slot.coerceIn(0, SLOT_COUNT - 1)])

    fun savePitch(context: Context, slot: Int, pitch: Float) {
        prefs(context).edit().putFloat("pitch:$slot", pitch.coerceIn(0.6f, 1.6f)).apply()
    }

    /**
     * Имя системного голоса, выбранного для слота вручную.
     * null — автоподбор по полу слота (ж1/ж2 → женский, м1/м2 → мужской).
     */
    fun savedVoiceName(context: Context, slot: Int): String? =
        prefs(context).getString("voice:$slot", null)?.takeIf { it.isNotBlank() }

    fun saveVoiceName(context: Context, slot: Int, name: String?) {
        val edit = prefs(context).edit()
        if (name.isNullOrBlank()) edit.remove("voice:$slot") else edit.putString("voice:$slot", name)
        edit.apply()
    }

    /**
     * Голос слота.
     *
     * [voiceName] — конкретный голос движка (Tera/RHVoice/Google). Если null,
     * голос остаётся системным, а слот меняет только тембр — так работает
     * «авто»-режим и случай, когда в движке всего один голос.
     */
    fun slotVoice(slot: Int, pitch: Float, voiceName: String? = null): KomaVoice = KomaVoice(
        voice = voiceName?.takeIf { it.isNotBlank() },
        pitch = pitch.coerceIn(0.6f, 1.6f),
        rate = 1f,
        title = "Голос " + SLOT_TITLES.getOrElse(slot) { (slot + 1).toString() },
        kind = "slot${slot + 1}",
    )

    /**
     * Тембры по умолчанию: если в движке один голос, слоты всё равно должны
     * звучать по-разному — ж1/ж2 чуть выше и ниже, м1/м2 заметно ниже.
     */
    val DEFAULT_PITCH = floatArrayOf(1.0f, 1.08f, 0.92f, 0.84f)

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
