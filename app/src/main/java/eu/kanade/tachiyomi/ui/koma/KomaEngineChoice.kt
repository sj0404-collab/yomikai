package eu.kanade.tachiyomi.ui.koma

import mihon.data.ocr.OcrPlugins
import mihon.domain.ocr.model.OcrModel

/**
 * Каким движком читает KOMA.
 *
 * Раньше у читалки был ОДИН движок — встроенный PP-OCR, — а список из
 * «Настроек → Распознавание» (PP-OCR, Tesseract, ML Kit, Google Lens, Space
 * Bunny, Gemini, OpenRouter, OwOCR) на неё не влиял вовсе. Отсюда жалоба
 * «в основной читалке мгновенно, а в KOMA нет, и движок не сменился».
 *
 * Теперь выбор уважается: читалка спрашивает у того же реестра плагинов, что и
 * настройки, и передаёт кадр выбранному движку. У каждого пункта честно
 * написано, чем он живёт, — офлайн это или сервер.
 *
 * Логика выбора — чистые данные и функции без Android: её проверяют тесты, а не
 * глаза на устройстве.
 */
data class KomaEngineOption(
    /** `koma` — встроенный офлайн-движок; иначе id плагина из [OcrPlugins]. */
    val id: String,
    val title: String,
    /** Что нужно движку: «офлайн», «нужен ключ», «нужен сервер». */
    val requirement: String,
    /** Модель для репозитория распознавания; null — встроенный KOMA. */
    val model: OcrModel?,
    val online: Boolean,
    /** Готов ли движок прямо сейчас (ключ введён, сеть есть, пакет скачан). */
    val available: Boolean,
) {
    /** Пометка в списке: рабочее не выдаётся за недоработанное и наоборот. */
    val readiness: String
        get() = if (online) "только онлайн · ещё не доделан" else "офлайн · работает"
}

object KomaEngineChoice {

    /** Значение «читает встроенный KOMA»: модели лежат в APK. */
    const val LOCAL_ID = "koma"

    /** Ключ в настройках читалки (SharedPreferences `koma`). */
    const val PREF_KEY = "komaEngine"

    /**
     * Список движков для читалки: сначала офлайн (встроенный KOMA, потом
     * локальные плагины), затем онлайн — так же, как в списке голосов:
     * рабочее сверху, недоработанное внизу и с пометкой.
     */
    fun options(availableIds: Set<String>? = null): List<KomaEngineOption> {
        val local = KomaEngineOption(
            id = LOCAL_ID,
            title = "KOMA · PP-OCR (в APK)",
            requirement = "офлайн",
            model = null,
            online = false,
            available = true,
        )
        val plugins = OcrPlugins.ALL.map { plugin ->
            KomaEngineOption(
                id = plugin.id,
                title = plugin.title,
                requirement = requirementText(plugin.requirements.map { it.name }),
                model = plugin.model,
                online = plugin.online,
                // null — «доступность неизвестна»: список не должен врать,
                // что движка нет, если проверку ещё не делали.
                available = availableIds == null || plugin.id in availableIds,
            )
        }
        return listOf(local) + plugins.sortedWith(compareBy({ it.online }, { it.title }))
    }

    /**
     * Что движку нужно одной фразой. Ключ и сервер важнее сети: без ключа
     * движок не заработает и при полном покрытии.
     */
    fun requirementText(requirements: Collection<String>): String = when {
        requirements.isEmpty() -> "офлайн"
        "API_KEY" in requirements -> "нужен ключ"
        "SERVER_ADDRESS" in requirements -> "нужен свой сервер"
        "MODEL_PACK" in requirements -> "нужен пакет моделей"
        "LITERT" in requirements -> "нужен LiteRT"
        "NETWORK" in requirements -> "нужна сеть"
        else -> "офлайн"
    }

    /**
     * Сохранённое значение → id существующего движка. Неизвестное значение
     * (старая сборка, удалённый плагин) читается как встроенный KOMA, а не
     * оставляет читалку без распознавания.
     */
    fun resolveId(stored: String?, knownIds: Collection<String> = OcrPlugins.ALL.map { it.id }): String =
        stored?.takeIf { it == LOCAL_ID || it in knownIds } ?: LOCAL_ID

    /** Модель для репозитория распознавания: null — читать встроенным KOMA. */
    fun modelOf(id: String?): OcrModel? =
        if (resolveId(id) == LOCAL_ID) null else OcrPlugins.byId(resolveId(id))?.model
}
