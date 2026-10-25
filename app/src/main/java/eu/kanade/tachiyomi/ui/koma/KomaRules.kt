package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Настройки чтения, которые можно менять БЕЗ пересборки APK.
 *
 * Почти всё, что чинилось в последних выпусках, — это числа: порог «чернил»,
 * растяжка уровней, запас на срезе полосы, зазор склейки реплик. Держать их
 * константами в коде значит требовать 90-мегабайтную сборку на каждую
 * подстройку под конкретный шрифт.
 *
 * Теперь они читаются из файла в папке приложения:
 *
 * ```
 * /data/data/<пакет>/files/koma-plugins/koma-rules.json
 * ```
 *
 * Заменили файл → «Распознать заново» (или «Пересчитать порядок из буфера») —
 * новый порог работает. Приложение не пересобирается.
 *
 * Кроме JSON поддерживается настоящий плагин — dex-файл:
 * ```
 * /data/data/<пакет>/files/koma-plugins/koma-rules.dex
 * ```
 * с классом `koma.rules.Plugin`, реализующим [KomaRulesPlugin]. Плагин может не
 * просто поменять числа, а посчитать их сам (например, по гистограмме кадра).
 * Загрузка — через `DexClassLoader`, как и расширения Mihon, только файл лежит
 * в приватной папке приложения.
 *
 * Отдельно: значения НЕ применяются там, где неизвестное поле могло бы уронить
 * чтение. Парсер игнорирует незнакомые поля и зажимает диапазоны, поэтому
 * испорченный файл даёт «правила по умолчанию», а не вылет.
 */
@Serializable
data class KomaRules(
    /** Подпись версии: видно в шторке «Правила», чтобы не путать файлы. */
    val version: String = "builtin-1",

    // ── Порог «чернил» (серый текст) ─────────────────────────────────────────
    /** Нижний процентиль яркости: цвет букв. 0.02 — самые тёмные 2 %. */
    @SerialName("inkLowPercentile") val inkLowPercentile: Float = 0.02f,
    /** Верхний процентиль: фон. */
    @SerialName("inkHighPercentile") val inkHighPercentile: Float = 0.98f,
    /** Разброс яркости меньше этого — участок однородный, текста нет. */
    @SerialName("inkFlatSpread") val inkFlatSpread: Int = 10,
    /** Где между буквами и фоном ставить порог: 0 — у букв, 1 — у фона. */
    @SerialName("inkCoefficient") val inkCoefficient: Float = 0.45f,
    @SerialName("inkMin") val inkMin: Int = 40,
    @SerialName("inkMax") val inkMax: Int = 235,
    /** Порог на случай, если участок однородный (старое поведение binarize). */
    @SerialName("binarizeFallback") val binarizeFallback: Int = 110,

    // ── Растяжка уровней (страховочный проход по бледному тексту) ────────────
    @SerialName("contrastGain") val contrastGain: Float = 2.2f,
    @SerialName("contrastOffset") val contrastOffset: Float = -140f,
    /** Третий проход включается, только если основной нашёл меньше этого. */
    @SerialName("rescueMinRegions") val rescueMinRegions: Int = 600,
    @SerialName("pageSide") val pageSide: Float = 1600f,
    @SerialName("rescueSide") val rescueSide: Float = 960f,
    @SerialName("minConfidence") val minConfidence: Float = 0.25f,

    // ── Полосы и реплики ─────────────────────────────────────────────────────
    /** Доля высоты полосы, в пределах которой низ реплики считается срезом. */
    @SerialName("cutMargin") val cutMargin: Float = 0.06f,
    /** Перекрытие соседних полос экрана. */
    @SerialName("bandOverlap") val bandOverlap: Float = 0.15f,
    @SerialName("bandsMax") val bandsMax: Int = 8,
    /** Доля видимой части, начиная с которой страница считается «одной полосой». */
    @SerialName("wholeThreshold") val wholeThreshold: Float = 0.72f,
    /** Зазор (в долях высоты строки), в пределах которого фрагменты — одна реплика. */
    @SerialName("bubbleGap") val bubbleGap: Float = 0.45f,
) {
    /**
     * Приведение к безопасным диапазонам.
     *
     * Файл правок — это файл, который правит человек. Опечатка вида
     * `"inkCoefficient": 45` не должна давать порог 45·разброс и превращать
     * страницу в кашу: значения зажимаются, а не падают.
     */
    fun sanitized(): KomaRules = copy(
        inkLowPercentile = inkLowPercentile.coerceIn(0f, 0.5f),
        inkHighPercentile = inkHighPercentile.coerceIn(0.5f, 1f),
        inkFlatSpread = inkFlatSpread.coerceIn(0, 128),
        inkCoefficient = inkCoefficient.coerceIn(0f, 0.95f),
        inkMin = inkMin.coerceIn(0, 254),
        inkMax = inkMax.coerceIn(1, 255),
        binarizeFallback = binarizeFallback.coerceIn(0, 255),
        contrastGain = contrastGain.coerceIn(1f, 8f),
        contrastOffset = contrastOffset.coerceIn(-255f, 100f),
        rescueMinRegions = rescueMinRegions.coerceIn(0, 100_000),
        pageSide = pageSide.coerceIn(320f, 4000f),
        rescueSide = rescueSide.coerceIn(320f, 4000f),
        minConfidence = minConfidence.coerceIn(0f, 1f),
        cutMargin = cutMargin.coerceIn(0f, 0.4f),
        bandOverlap = bandOverlap.coerceIn(0f, 0.5f),
        bandsMax = bandsMax.coerceIn(1, 32),
        wholeThreshold = wholeThreshold.coerceIn(0.1f, 1f),
        bubbleGap = bubbleGap.coerceIn(0.05f, 3f),
    ).let { if (it.inkMax <= it.inkMin) it.copy(inkMax = (it.inkMin + 1).coerceAtMost(255)) else it }

    fun toJson(): String = json.encodeToString(this)

    companion object {
        val DEFAULT = KomaRules()

        /** Имя версии для шторки «Правила» — чтобы видеть, что применено. */
        val DEFAULT_VERSION: String get() = DEFAULT.version

        /** Незнакомые поля игнорируются: новые версии правил не ломают старые. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun fromJson(text: String): KomaRules? = runCatching {
            json.decodeFromString<KomaRules>(text).sanitized()
        }.onFailure {
            // Логирование в runCatching: в модульных тестах android.util.Log
            // недоступен, и падение логгера не должно превращаться в падение
            // разбора правил.
            runCatching { logcat(LogPriority.WARN, it) { "koma-rules.json: не разобрался, беру встроенные правила" } }
        }.getOrNull()
    }
}

/**
 * Что должен уметь плагин правил.
 *
 * Класс с фиксированным именем `koma.rules.Plugin` в файле `koma-rules.dex`.
 * Интерфейс нарочно крошечный: плагин возвращает JSON правил, а всё остальное
 * (разбор, зажим диапазонов, значения по умолчанию) остаётся в приложении.
 * Так падение или мусор из плагина не могут ни обнулить чтение, ни уронить
 * приложение — в худшем случае берутся встроенные правила.
 */
interface KomaRulesPlugin {
    fun rulesJson(): String
}

/**
 * Хранилище правил: встроенные → JSON-файл → dex-плагин.
 *
 * Файл перечитывается по изменению времени модификации, поэтому «Заменить
 * файл правил» в приложении работает без перезапуска, и то же самое работает,
 * если файл положили через файловый менеджер или `adb push`.
 */
object KomaRulesStore {

    private const val DIR = "koma-plugins"
    private const val JSON_NAME = "koma-rules.json"
    private const val DEX_NAME = "koma-rules.dex"
    private const val PLUGIN_CLASS = "koma.rules.Plugin"

    @Volatile private var cached: KomaRules? = null
    @Volatile private var stamp: String? = null
    /** Откуда взялись действующие правила — для шторки, чтобы не гадать. */
    @Volatile var source: String = "встроенные"
        private set
    @Volatile private var lastError: String? = null

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun jsonFile(context: Context): File = File(dir(context), JSON_NAME)

    fun dexFile(context: Context): File = File(dir(context), DEX_NAME)

    /** Человеческая ошибка последней загрузки (или null). */
    fun error(): String? = lastError

    fun current(context: Context): KomaRules {
        val json = jsonFile(context)
        val dex = dexFile(context)
        val key = "${json.lastModified()}:${json.length()}:${dex.lastModified()}:${dex.length()}"
        cached?.let { if (key == stamp) return it }

        var rules: KomaRules? = null
        var from = "встроенные"
        lastError = null

        if (json.isFile) {
            val parsed = runCatching { KomaRules.fromJson(json.readText()) }.getOrNull()
            if (parsed != null) {
                rules = parsed
                from = "файл правил"
            } else {
                lastError = "Файл ${json.name} не разобрался — работают встроенные правила"
            }
        }

        if (dex.isFile) {
            val pluginJson = runCatching { loadPlugin(context, dex).rulesJson() }.onFailure {
                logcat(LogPriority.WARN, it) { "koma-rules.dex: плагин не загрузился" }
                lastError = "Плагин ${dex.name} не загрузился: ${it.message ?: it::class.simpleName}"
            }.getOrNull()
            val parsed = pluginJson?.let { KomaRules.fromJson(it) }
            if (parsed != null) {
                rules = parsed
                from = "плагин (dex)"
            }
        }

        val result = rules ?: KomaRules.DEFAULT
        cached = result
        stamp = key
        source = if (result.version == KomaRules.DEFAULT.version) "встроенные" else from
        return result
    }

    /** Принудительно перечитать файлы (кнопка «Перезагрузить правила»). */
    fun reload(context: Context): KomaRules {
        cached = null
        stamp = null
        return current(context)
    }

    /**
     * Положить файл правил: `.json` → файл правил, `.dex`/`.apk` → плагин.
     *
     * Возвращает версию, с которой правила применились, или null при ошибке.
     */
    fun install(context: Context, source: File): KomaRules? = runCatching {
        val name = source.name.lowercase()
        when {
            name.endsWith(".json") -> {
                dexFile(context).delete()
                source.copyTo(jsonFile(context), overwrite = true)
            }
            name.endsWith(".dex") || name.endsWith(".apk") || name.endsWith(".jar") -> {
                source.copyTo(dexFile(context), overwrite = true)
            }
            else -> return null
        }
        reload(context)
    }.getOrNull()

    fun installFromText(context: Context, text: String): KomaRules? = runCatching {
        jsonFile(context).writeText(text)
        dexFile(context).delete()
        reload(context)
    }.getOrNull()

    /** Убрать файлы и вернуться к встроенным правилам. */
    fun reset(context: Context): KomaRules {
        jsonFile(context).delete()
        dexFile(context).delete()
        cached = null
        stamp = null
        lastError = null
        return current(context)
    }

    private fun loadPlugin(context: Context, dex: File): KomaRulesPlugin {
        // Как у расширений Mihon: отдельный загрузчик классов на файл.
        val loader = DexClassLoader(
            dex.absolutePath,
            dir(context).absolutePath,
            null,
            context.classLoader,
        )
        val type = loader.loadClass(PLUGIN_CLASS)
        return type.getDeclaredConstructor().newInstance() as KomaRulesPlugin
    }
}
