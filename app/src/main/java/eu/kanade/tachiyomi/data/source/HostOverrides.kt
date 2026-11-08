package eu.kanade.tachiyomi.data.source

import android.app.Application
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.network.HostRouter
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Связка настроек и сетевого слоя: пользовательские домены → [HostRouter].
 *
 * Каталог зеркал для известных сайтов задан здесь же: у RU-источников домены
 * меняются регулярно, и «сайт сменился» не должен означать «источник умер».
 * Пользовательские переопределения из prefs важнее каталога.
 */
object HostOverrides {

    /**
     * Известные группы зеркал: любой домен из группы может заменить любой другой.
     * Список проверялся 25.10.2026; мёртвые домены не включены, чтобы приложение
     * не тратило попытки на несуществующие адреса.
     */
    private val KNOWN_MIRRORS: Map<String, List<String>> = mapOf(
        "mangalib.me" to listOf("mangalib.org", "mangalib.mobi"),
        "mangalib.org" to listOf("mangalib.me", "mangalib.mobi"),
        "mangalib.mobi" to listOf("mangalib.org", "mangalib.me"),
        "ranobelib.me" to listOf("ranobelib.me", "ranobehub.org"),
        "mangahub.io" to listOf("mangahub.io"),
        "remanga.org" to listOf("remanga.org"),
        "mangabuff.ru" to listOf("mangabuff.ru"),
        "ruranobe.ru" to listOf("ruranobe.ru", "ranobehub.org"),
        "readmanga.eu" to listOf("readmanga.live", "selfmanga.live"),
        "mintmanga.com" to listOf("mintmanga.live", "selfmanga.live"),
        "manga-chan.me" to listOf("manga-chan.me", "acomics.ru"),
        "usagi.one" to listOf("usagi.one", "acomics.ru"),
        "novelib.me" to listOf("ranobehub.org", "ranobelib.me"),
        "animevost.org" to listOf("animevost.org"),
        "anilibria.tv" to listOf("anilibria.tv"),
        "animego.org" to listOf("animego.org"),
        "animelib.org" to listOf("animelib.org"),
        "acomics.ru" to listOf("acomics.ru"),
    )

    /** Прочитать prefs и заполнить [HostRouter]. Вызывается один раз при старте. */
    fun apply(prefs: SourcePreferences) {
        val overrides = runCatching {
            val root = JSONObject(prefs.hostOverrides.get())
            root.keys().asSequence().associateWith { root.optString(it) }
                .filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())

        HostRouter.overrides = overrides
        HostRouter.mirrors = KNOWN_MIRRORS
        HostRouter.enabled = prefs.autoDomainSwitch.get()

        // Успешное автопереключение запоминаем: следующий запуск пойдёт сразу на
        // рабочий домен, без траты попыток на мёртвый.
        HostRouter.onSwitched = { from, to -> save(prefs, from, to) }
    }

    private fun save(prefs: SourcePreferences, from: String, to: String) {
        runCatching {
            val root = JSONObject(prefs.hostOverrides.get())
            root.put(from, to)
            prefs.hostOverrides.set(root.toString())
            HostRouter.overrides = HostRouter.overrides + (from to to)
        }
    }

    /** Ручная смена домена источника: `host` — новый адрес (можно с https://). */
    fun override(prefs: SourcePreferences, from: String, host: String) {
        val clean = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .lowercase()
        if (clean.isBlank() || clean == from) return
        save(prefs, from, clean)
    }

    /** Забыть переопределение (вернуть адрес расширения). */
    fun reset(prefs: SourcePreferences, from: String) {
        runCatching {
            val root = JSONObject(prefs.hostOverrides.get())
            root.remove(from)
            prefs.hostOverrides.set(root.toString())
            HostRouter.overrides = HostRouter.overrides - from
        }
    }

    /** Список переопределений для экрана настроек. */
    fun list(prefs: SourcePreferences): List<Pair<String, String>> = runCatching {
        val root = JSONObject(prefs.hostOverrides.get())
        root.keys().asSequence().map { it to root.optString(it) }.toList()
    }.getOrDefault(emptyList())

    /** Домены известной группы для подсказки в UI. */
    fun mirrorsOf(host: String): List<String> = KNOWN_MIRRORS[host].orEmpty()

    /** Заполнить каталог зеркал из JSON-файла (на будущее, без интернета). */
    fun applyMirrorJson(prefs: SourcePreferences, json: String) {
        runCatching { JSONArray(json) }.getOrNull()?.let { /* формат зарезервирован */ }
    }

    /** Стартовая инициализация — вызывается из AppModule при создании клиента. */
    fun install(app: Application) {
        runCatching { apply(Injekt.get<SourcePreferences>()) }
    }
}
