package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import eu.kanade.tachiyomi.data.ai.AiConsole
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.util.system.logcat
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * OCR repository implementation that manages engine selection, page scanning, and OCR cache.
 */
class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {
    private val preferenceStore = AndroidPreferenceStore(context)
    private val ocrModelPref = preferenceStore.getEnum("pref_ocr_model", OcrModel.CYRILLIC)
    private val useFallbackModelsPref = preferenceStore.getBoolean("pref_use_fallback_models", true)

    /**
     * Страница без текста — это картинка, а не сбой движка.
     *
     * Раньше «пустой результат» считался поводом идти по всей резервной цепочке.
     * При пресете `auto` (дефолт) она включала GLENS, ZEN_FREE и GOOGLE, то есть
     * каждая иллюстрация, разворот и обложка уезжали тремя сетевыми запросами с
     * загрузкой картинки, а затем прогонялись все оффлайн-движки — и всё
     * ради вывода «текста нет». Авточтение на таком кадре стояло секунды и
     * «очень медленно листало», а мобильный трафик уходил в сеть без спроса.
     *
     * Теперь пустой результат завершает цепочку: движок отработал и сказал, что
     * текста нет. Фолбэк остаётся для настоящих отказов (исключение), где он и
     * нужен. Выключается настройкой «Пропускать страницы без текста».
     */
    private val skipUntitledPagesPref = preferenceStore.getBoolean("pref_skip_untitled_pages", true)

    private val environmentResult by lazy {
        runCatching { Environment.create() }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "LiteRT environment unavailable; local OCR engines will fall back"
                }
            }
    }

    private val textPostprocessor by lazy { TextPostprocessor() }
    private val cacheStore by lazy { OcrCacheStore(context) }
    private val ocrPreferences by lazy { mihon.domain.ocr.service.OcrPreferences(preferenceStore) }

    /**
     * Профиль распознавания: пресет типа контента + область + ручные
     * переопределения. Пересобирается на каждый вызов, поэтому смена пресета в
     * настройках применяется сразу и не требует пересоздания движка.
     */
    private fun regionProfile(): OcrRegionProfile = OcrRegionProfile(
        contentType = OcrContentType.fromId(ocrPreferences.contentType().get()),
        scanRegion = presetScanRegion(),
        localMode = OcrLocalMode.fromId(ocrPreferences.localMode().get()),
        overrides = tuningOverrides(),
    )

    private fun currentTuning(): OcrTuning = regionProfile().tuning()

    /** Область из пресета; `pref_scan_region` остаётся быстрым переопределением. */
    private fun presetScanRegion(): mihon.domain.ocr.service.ScanRegion =
        OcrRegionRules.effectiveRegion(
            presetKey = ocrPreferences.presetScanRegion().get(),
            legacy = ocrPreferences.scanRegion().get(),
        )

    /**
     * Ручные переопределения пресета. Незаполненное или нечисловое поле
     * означает «как в пресете»: настройка, сохранённая старой версией, не
     * должна ломать распознавание.
     */
    private fun tuningOverrides(): OcrTuningOverrides = OcrRegionRules.overridesOf(
        detectorThreshold = ocrPreferences.detectorThresholdOverride().get(),
        minComponentArea = ocrPreferences.minComponentAreaOverride().get(),
        maxTextBoxes = ocrPreferences.maxTextBoxesOverride().get(),
        wordGapFactor = ocrPreferences.wordGapFactorOverride().get(),
        minAcceptConfidence = ocrPreferences.minAcceptConfidenceOverride().get(),
        shortTextMinConfidence = ocrPreferences.shortTextConfidenceOverride().get(),
        minCoverage = ocrPreferences.minCoverageOverride().get(),
        rescueMaxLines = ocrPreferences.rescueMaxLinesOverride().get(),
    )

    private var cyrillicEngine: CyrillicOcrEngine? = null
    private var mlKitEngine: MlKitOcrEngine? = null
    private var legacyEngine: LegacyOcrEngine? = null
    private var fastEngine: FastOcrEngine? = null
    private var glensEngine: GlensOcrEngine? = null
    private var owOcrEngine: OwOcrEngine? = null
    private var openRouterEngine: OpenRouterOcrEngine? = null
    private var googleAiEngine: GoogleAiOcrEngine? = null
    private var zenFreeEngine: ZenFreeOcrEngine? = null
    private var detEngine: DetOcrEngine? = null

    /**
     * Сторона кропа, ниже которой облачный OCR не пробуем.
     *
     * У облачных движков таймаут чтения 60-90 секунд, а мелкий кроп они всё
     * равно не читают: читатель выделял одну-две строки и ждал минуту вместо
     * результата. 160 px — примерно две строки крупного манга-шрифта.
     */
    private val onlineMinSidePx = 160

    /** Суммарный бюджет резервной цепочки для интерактивного распознавания. */
    private val defaultFallbackBudgetMs = 20_000L

    /** Для мелкого кропа: только локальные движки и почти без ожидания. */
    private val tinyFallbackBudgetMs = 4_000L

    private val engineLocks = OcrEngineLocks()

    /**
     * Движок, который реально вернул последний распознанный текст.
     *
     * Читатель жаловался, что «у онлайн ИИ качество такое же, как у
     * локального». Причина была в тихом фолбэке: без сети онлайн-движок
     * пропускался, и страницу читал локальный. Теперь подпись в окне
     * результата показывает фактического исполнителя, а не выбранного.
     */
    @Volatile
    override var lastRecognizedEngine: OcrModel? = null
        private set

    private fun noteEngineUsed(type: EngineType) {
        lastRecognizedEngine = type.toOcrModel()
    }

    /**
     * Старые значения настройки (LEGACY/FAST) давно ведут в тот же русский
     * движок. Отчёт показывает, что ОТРАБОТАЛО, а не что стояло в настройке
     * три года назад.
     */
    private fun EngineType.toOcrModel(): OcrModel = when (this) {
        EngineType.CYRILLIC -> OcrModel.CYRILLIC
        EngineType.MLKIT -> OcrModel.MLKIT
        EngineType.GLENS -> OcrModel.GLENS
        EngineType.OWOCR -> OcrModel.OWOCR
        EngineType.OPENROUTER -> OcrModel.OPENROUTER
        EngineType.GOOGLE -> OcrModel.GOOGLE
        EngineType.ZEN_FREE -> OcrModel.ZEN_FREE
        EngineType.LEGACY, EngineType.FAST -> OcrModel.CYRILLIC
    }

    private val cleanupMutex = Mutex()
    private val sessionMutex = Mutex()
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskQueue = PrioritizedTaskQueue(scope) {
        scope.launch {
            performDeferredCleanupIfIdle()
        }
    }

    private var cleanupRequested = false

    private var activeScanSessions = 0
    private var activeOperations = 0

    /**
     * Движок GLENS с учётом выбранного языка источника. Движок кэшируется, но
     * язык/регион читаются из настроек на каждый вызов: если пользователь сменил
     * язык (en/ru/ja/…), кэш пересоздаётся, чтобы читалка применяла новый язык,
     * а не зашитый ранее (было жёстко "ja"/"Asia/Tokyo").
     */
    private fun glensEngine(): GlensOcrEngine {
        val language = ocrPreferences.glensLanguage().get()
        val region = ocrPreferences.glensRegion().get()
        val existing = glensEngine
        if (existing != null && existing.clientLanguage == language && existing.clientRegion == region) {
            return existing
        }
        return GlensOcrEngine(
            clientLanguage = language.ifBlank { "ja" },
            clientRegion = region,
        ).also { glensEngine = it }
    }

    internal enum class EngineType {
        CYRILLIC,
        MLKIT,
        LEGACY,
        FAST,
        GLENS,
        OWOCR,
        OPENROUTER,
        GOOGLE,
        ZEN_FREE,
    }

    private fun selectedEngineType(): EngineType = engineTypeOf(ocrModelPref.get())

    /**
     * Модель в тип движка.
     *
     * Вынесено отдельно от [selectedEngineType], потому что оверле поверх
     * чужого приложения распознаёт своим движком, а не тем, что выбран для
     * манги, — и он тоже не должен молча уезжать в ML Kit на кириллице.
     */
    private fun engineTypeOf(model: OcrModel): EngineType {
        val engine = when (model) {
            OcrModel.CYRILLIC -> EngineType.CYRILLIC
            OcrModel.MLKIT -> EngineType.MLKIT
            // Old offline selections migrate transparently to the Russian
            // engine; the Japanese FAST/LEGACY models are no longer defaults.
            OcrModel.LEGACY -> EngineType.CYRILLIC
            OcrModel.FAST -> EngineType.CYRILLIC
            OcrModel.GLENS -> EngineType.GLENS
            OcrModel.OWOCR -> EngineType.OWOCR
            OcrModel.OPENROUTER -> EngineType.OPENROUTER
            OcrModel.GOOGLE -> EngineType.GOOGLE
            OcrModel.ZEN_FREE -> EngineType.ZEN_FREE
            OcrModel.TESSERACT -> EngineType.CYRILLIC
        }
        // ML Kit несёт в APK только латинскую модель. На кириллице он не
        // «медленно», а просто нечитаем, поэтому и как основной движок, и в
        // цепочке фолбэка для кириллического чтения он неуместен.
        return if (engine == EngineType.MLKIT && readsCyrillic()) EngineType.CYRILLIC else engine
    }

    /** Язык чтения страницы: кириллица не переваривается латинским движком. */
    private fun readsCyrillic(): Boolean = isCyrillicOcrLanguage(ocrPreferences.autoReadLanguage().get())

    private fun isConnectivityFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current.message?.contains("Unable to resolve host", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private val onlineEngines = setOf(
        EngineType.GLENS, EngineType.ZEN_FREE, EngineType.OWOCR,
        EngineType.OPENROUTER, EngineType.GOOGLE,
    )
    private val offlineEngines = listOf(
        // One canonical offline engine for Russian/Cyrillic text. Legacy,
        // FAST and Tesseract remain migration-only enum values.
        EngineType.CYRILLIC,
        // ML Kit вкомпилирован в APK и не требует ни сети, ни пакета моделей,
        // поэтому годится как офлайн-фолбэк, когда PP-OCR не установлен.
        EngineType.MLKIT,
    )

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
    }

    /**
     * Онлайн-движок реально готов к запросу: ключ/адрес заданы. Без этой
     * проверки цепочка тратила до минуты на заведомо 401/403-ответ.
     */
    private fun onlineEngineReady(type: EngineType): Boolean = when (type) {
        EngineType.ZEN_FREE -> ocrPreferences.zenFreeEnabled().get()
        EngineType.GOOGLE -> ocrPreferences.googleApiKey().get().isNotBlank()
        EngineType.OPENROUTER -> ocrPreferences.openrouterApiKey().get().isNotBlank()
        EngineType.OWOCR -> ocrPreferences.owocrAddress().get().isNotBlank()
        EngineType.GLENS -> true
        EngineType.CYRILLIC, EngineType.MLKIT, EngineType.FAST, EngineType.LEGACY -> false
    }

    /**
     * ЦЕПОЧКА фолбэков (по пресету пользователя), а не один шаг:
     *  auto    — при сети: онлайн → локальные; без сети: ТОЛЬКО локальные
     *            (онлайн даже не пробуются — мгновенный переход, без таймаутов);
     *  online  — только онлайн-движки;
     *  offline — только локальный Cyrillic PP-OCR (скачиваемый pack);
     *  single  — фолбэков нет.
     */
    private fun fallbackChain(primary: EngineType): List<EngineType> {
        // Дефолт — `offline`, а не `auto`. При `auto` цепочка включала онлайн-
        // движки, и картинка страницы уходила в GLENS/ZEN_FREE/Google без
        // явного согласия читателя. Теперь сеть в резервной цепочке появляется
        // только если её выбрали руками.
        val preset = preferenceStore.getString("pref_fallback_preset", "offline").get()
        val online = listOf(EngineType.GLENS, EngineType.ZEN_FREE, EngineType.GOOGLE)
            .filter { onlineEngineReady(it) }
        val chain = when (preset) {
            "single" -> emptyList()
            "online" -> online
            "offline" -> offlineEngines
            else -> { // auto
                if (isNetworkAvailable()) online + offlineEngines else offlineEngines
            }
        }
        // Латинский ML Kit в кириллической цепочке — это не фолбэк, а шум:
        // он либо не ответит, либо прочитает русский текст латиницей.
        val cyrillic = readsCyrillic()
        return chain.filter { it != primary && !(cyrillic && it == EngineType.MLKIT) }
    }

    private fun requireEnvironment(): Environment {
        return environmentResult.getOrElse { cause ->
            throw OcrException.InitializationError(cause)
        }
    }

    private fun localOcrAvailable(): Boolean {
        return environmentResult.isSuccess
    }

    private fun engineFor(type: EngineType): OcrEngine {
        return when (type) {
            EngineType.CYRILLIC -> {
                cyrillicEngine ?: CyrillicOcrEngine(
                    context,
                    requireEnvironment(),
                    textPostprocessor,
                    ::currentTuning,
                ).also { cyrillicEngine = it }
            }
            EngineType.MLKIT -> {
                mlKitEngine ?: MlKitOcrEngine().also { mlKitEngine = it }
            }
            EngineType.FAST -> {
                fastEngine ?: FastOcrEngine(context, requireEnvironment(), textPostprocessor).also {
                    fastEngine = it
                }
            }
            EngineType.LEGACY -> {
                legacyEngine ?: LegacyOcrEngine(context, requireEnvironment(), textPostprocessor).also {
                    legacyEngine = it
                }
            }
            EngineType.GLENS -> {
                glensEngine()
            }
            EngineType.OWOCR -> {
                owOcrEngine ?: OwOcrEngine(context).also {
                    owOcrEngine = it
                }
            }
            EngineType.OPENROUTER -> {
                openRouterEngine ?: OpenRouterOcrEngine(context, ocrPreferences).also {
                    openRouterEngine = it
                }
            }
            EngineType.GOOGLE -> {
                googleAiEngine ?: GoogleAiOcrEngine(context, ocrPreferences).also {
                    googleAiEngine = it
                }
            }
            EngineType.ZEN_FREE -> {
                zenFreeEngine ?: ZenFreeOcrEngine().also {
                    zenFreeEngine = it
                }
            }
        }
    }

    private fun detectionEngine(): DetOcrEngine {
        return detEngine ?: (
            // Детектор живёт в паке cyrillic_ocr вместе с распознавателями.
            // Пока модели не скачаны (или LiteRT недоступен) — заглушка, и
            // scanLocally честно деградирует на распознавание всей страницы.
            if (localOcrAvailable() && cyrillicModelsInstalled()) {
                CyrillicDetOcrEngine { engineFor(EngineType.CYRILLIC) as CyrillicOcrEngine }
            } else {
                UnavailableDetOcrEngine()
            }
            ).also {
            detEngine = it
        }
    }

    /** Пак cyrillic_ocr установлен целиком (детектор + распознаватель + словарь). */
    private fun cyrillicModelsInstalled(): Boolean {
        return OcrModelFiles.allInstalled(
            context,
            listOf(
                CyrillicOcrEngine.DETECTOR_PATH,
                CyrillicOcrEngine.PRIMARY_PATH,
                CyrillicOcrEngine.PRIMARY_DICT_PATH,
            ),
        )
    }

    /**
     * Единственное место, где движок реально отработал: отмечаем его здесь,
     * иначе вызывающий код узнал бы только «первый, кто ответил», а это
     * ровно то, что он и так не знает.
     */
    private suspend fun recognizeWithEngine(type: EngineType, image: Bitmap): String {
        return engineLocks.withTextEngineLock(type) {
            // Онлайн-модели отдают текст построчно и не склеивают переносы —
            // соединяем «пере-\nносится» в «переносится» централизованно.
            OcrTextCleaner.joinLineHyphens(engineFor(type).recognizeText(image))
        }.also { noteEngineUsed(type) }
    }

    /**
     * @param fallbackBudgetMs сколько ждать резервных движков суммарно. Основной
     *   движок бюджетом не режется: его читатель выбрал сам, и обрывать его
     *   на полуслове нельзя. Режется только цепочка «просто попробуем ещё».
     * @param allowOnlineFallback сеть в резервной цепочке. Для мелкого кропа
     *   облако бесполезно и очень медленно, поэтому не пробуем.
     */
    private suspend fun recognizeWithFallback(
        primary: EngineType,
        image: Bitmap,
        fallbackBudgetMs: Long = defaultFallbackBudgetMs,
        allowOnlineFallback: Boolean = true,
    ): String {
        val skipPrimary = primary in onlineEngines && !isNetworkAvailable()
        val fallbackEnabled = useFallbackModelsPref.get()
        var primaryText: String? = null
        var lastError: Throwable? = null

        if (!skipPrimary) {
            try {
                val text = recognizeWithEngine(primary, image)
                primaryText = text
                if (text.isNotBlank() || !fallbackEnabled) return text
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
            }
        }

        if (!fallbackEnabled) {
            return primaryText ?: throw lastError ?: OcrException.ConnectionError(null)
        }

        val deadline = SystemClock.elapsedRealtime() + fallbackBudgetMs
        for (engine in fallbackChain(primary)) {
            if (engine in onlineEngines && (!allowOnlineFallback || !isNetworkAvailable())) continue
            if (SystemClock.elapsedRealtime() > deadline) {
                logcat(LogPriority.WARN) {
                    "OCR fallback budget ${fallbackBudgetMs}ms exhausted, " +
                        "stopping at ${engine.name.lowercase()}"
                }
                break
            }
            try {
                logcat(LogPriority.WARN) {
                    "OCR (${primary.name.lowercase()}) returned no usable text, trying ${engine.name.lowercase()}"
                }
                val text = recognizeWithEngine(engine, image)
                if (text.isNotBlank()) return text
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError?.addSuppressed(e) ?: run { lastError = e }
            }
        }
        return primaryText ?: throw lastError ?: OcrException.InitializationError()
    }

    override suspend fun recognizeText(image: OcrImage): String = recognizeText(image, null)

    /**
     * Локальное распознавание без сети — для библиотеки книг.
     *
     * Движок задан здесь, а не берётся из настройки: у манги может быть выбран
     * онлайн-движок, и книга не должна от этого зависеть. Фолбэк в сеть тоже
     * запрещён — иначе «локальный» вызов всё равно ушёл бы в облако, только
     * на втором шаге.
     */
    override suspend fun recognizeLocalText(image: OcrImage): String {
        lastRecognizedEngine = null
        val primary = localTextEngine()
        return withActiveOperation {
            submitTask(PrioritizedTaskQueue.Priority.HIGH) {
                image.useBitmap { bitmap ->
                    recognizeWithFallback(
                        primary = primary,
                        image = bitmap,
                        // Страница книги — не срочное чтение, но и не повод
                        // ждать облако: бюджет фолбэка тут только локальный.
                        fallbackBudgetMs = localOnlyFallbackBudgetMs,
                        allowOnlineFallback = false,
                    )
                }
            }
        }
    }

    /**
     * Локальный движок для страниц книги.
     *
     * Выбирается по фактической готовности, а не жёстко. Кириллический
     * PP-OCR — лучший для русского, но ему нужны скачанный пакет моделей и
     * LiteRT; без них он не отработает и страница просто останется пустой.
     * Тогда берётся Google ML Kit: он ВНУТРИ APK, ничего качать не надо, и
     * для латиницы работает сразу.
     *
     * Оба варианта локальные. Сети здесь нет ни в одном.
     */
    private fun localTextEngine(): EngineType {
        val litertReady = environmentResult.getOrNull() != null
        val packReady = litertReady && runCatching {
            OcrModelFiles.allInstalled(
                context,
                listOf(
                    CyrillicOcrEngine.DETECTOR_PATH,
                    CyrillicOcrEngine.PRIMARY_PATH,
                    CyrillicOcrEngine.PRIMARY_DICT_PATH,
                ),
            )
        }.getOrDefault(false)
        return if (packReady) EngineType.CYRILLIC else EngineType.MLKIT
    }

    /**
     * Сколько ждать локальных фолбэков.
     *
     * Отдельный бюджет от [defaultFallbackBudgetMs]: там цепочка заканчивается
     * облаком за десятки секунд, а здесь её не будет, и ждать незачем.
     */
    private val localOnlyFallbackBudgetMs = 20_000L

    override suspend fun recognizeText(image: OcrImage, model: OcrModel?): String {
        // Новый вызов — новый отчёт: без сброса неудача показала бы движок
        // ПРЕДЫДУЩЕГО распознавания.
        lastRecognizedEngine = null
        val engine = model?.let(::engineTypeOf) ?: selectedEngineType()
        return withActiveOperation {
            submitTask(PrioritizedTaskQueue.Priority.HIGH) {
                image.useBitmap { bitmap ->
                    // Читатель ждал «вечность» на одиночных выделенных
                    // областях. Причина в резервной цепочке: у облачных
                    // движков таймаут чтения 60-90 секунд, и цепочка
                    // проходила их подряд. Мелкий кроп (выделил область в
                    // пару строк) облаку не нужен — 30 пикселей высоты он не
                    // прочитает, а минуту потратит, поэтому для него сеть не
                    // пробуем вовсе. Крупный кроп оставляем как был.
                    val tiny = minOf(bitmap.width, bitmap.height) < onlineMinSidePx
                    recognizeWithFallback(
                        primary = engine,
                        image = bitmap,
                        fallbackBudgetMs = if (tiny) tinyFallbackBudgetMs else defaultFallbackBudgetMs,
                        allowOnlineFallback = !tiny,
                    )
                }
            }
        }
    }

    /**
     * Вопрос к vision-модели по выбранному движку.
     *
     * Умеют не все: локальный кириллический распознаватель и ML Kit работают
     * только с текстом. Для них возвращается null, чтобы вызывающий код
     * продолжил разговор без взгляда на страницу, а не упал.
     */
    override suspend fun askAboutImage(image: OcrImage, question: String): String? {
        val text = question.trim()
        if (text.isEmpty()) return null
        return withActiveOperation {
            submitTask(PrioritizedTaskQueue.Priority.HIGH) {
                image.useBitmap { bitmap ->
                    // Тот же движок, что и у OCR, но с вопросом вместо
                    // «распознай текст». Умеют только vision-движки; остальные
                    // (кириллический, ML Kit, Lens) вернут null.
                    when (val engine = engineFor(selectedEngineType())) {
                        is ZenFreeOcrEngine -> engine.askAboutPage(bitmap, text)
                        is GoogleAiOcrEngine -> engine.askAboutPage(bitmap, text)
                        is OpenRouterOcrEngine -> engine.askAboutPage(bitmap, text)
                        else -> null
                    }
                }
            }
        }
    }

    override suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        onPartial: ((OcrRegion) -> Unit)?,
        cacheResult: Boolean,
    ): OcrPageResult {
        // Живой журнал. Консоль открывают именно ради скана главы, а движки
        // OCR ходят в сеть напрямую и минуют AiAssistant — из-за чего журнал был
        // пуст ровно тогда, когда он нужнее всего.
        val engineName = OcrPlugins.byModel(ocrModelPref.get()).title
        val startedAt = android.os.SystemClock.elapsedRealtime()
        AiConsole.ocr(
            title = "Скан страницы ${pageIndex + 1} · $engineName",
            detail = buildString {
                append("chapterId=").append(chapterId)
                append(" · ").append(image.width).append('×').append(image.height)
                append(" · кэш: ").append(if (cacheResult) "да" else "нет")
            },
        )
        return try {
            val outcome = withActiveOperation {
                val regionChoice = ocrPreferences.scanRegion().get()
            val result = image.useBitmap { originalBitmap ->
                val sourceHeight = originalBitmap.height
                val cropTop = when (regionChoice) {
                    mihon.domain.ocr.service.ScanRegion.BOTTOM_HALF -> sourceHeight / 2
                    else -> 0
                }
                val cropHeight = when (regionChoice) {
                    mihon.domain.ocr.service.ScanRegion.FULL_PAGE -> sourceHeight
                    else -> (sourceHeight - cropTop).coerceAtLeast(1)
                }
                val crop = if (cropTop == 0 && cropHeight == sourceHeight) {
                    originalBitmap
                } else {
                    Bitmap.createBitmap(
                        originalBitmap,
                        0,
                        cropTop,
                        originalBitmap.width,
                        cropHeight,
                    )
                }
                val croppedResult = try {
                    scanPageWithFallback(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        image = crop,
                        primary = selectedEngineType(),
                        onPartial = onPartial,
                    )
                } finally {
                    if (crop !== originalBitmap && !crop.isRecycled) crop.recycle()
                }
                croppedResult.copy(
                    imageHeight = sourceHeight,
                    regions = croppedResult.regions.mapNotNull { region ->
                        OcrBoxGeometry.restoreVerticalCrop(
                            box = region.boundingBox,
                            sourceHeight = sourceHeight,
                            cropTop = cropTop,
                            cropHeight = cropHeight,
                        )?.let { restored ->
                            region.copy(boundingBox = restored)
                        }
                    },
                )
            }

            // Кадр авточтения — это окно вебтуна, а не страница: его рамки
            // нормализованы к обрезанному кадру. В кэш страницы такую запись
            // класть нельзя, иначе оверлей потом подсвечивал не там. Плюс
            // веб-авточтение шлёт chapterId = -1 на каждую позицию прокрутки,
            // и clearChapter такие записи не убирал.
                if (cacheResult) cacheStore.upsert(result)
                result
            }
            // Итог по странице: сколько реплик, сколько заняло, какой движок
            // отдал текст на самом деле (не выбранный, а фактический — при
            // фолбэке это разные движки).
            AiConsole.ocr(
                title = "Страница ${pageIndex + 1} · реплик: ${outcome.regions.size}",
                detail = "движок: ${OcrPlugins.byModel(outcome.ocrModel).title} · " +
                    "${android.os.SystemClock.elapsedRealtime() - startedAt} мс",
                level = if (outcome.regions.isEmpty()) AiConsole.Level.WARN else AiConsole.Level.OK,
            )
            outcome
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AiConsole.ocr(
                title = "Скан страницы ${pageIndex + 1} не удался · $engineName",
                detail = e.message ?: e.javaClass.simpleName,
                level = AiConsole.Level.ERROR,
            )
            throw e
        }
    }

    override suspend fun savePage(result: OcrPageResult) {
        cacheStore.upsert(result)
    }

    override suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult? {
        return cacheStore.getPage(
            chapterId = chapterId,
            pageIndex = pageIndex,
        )?.withoutPromotionalRegions()
    }

    override suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long> {
        return cacheStore.getCachedChapterIds(
            chapterIds = chapterIds,
        )
    }

    override suspend fun clearCachedChapter(chapterId: Long) {
        cacheStore.clearChapter(chapterId)
    }

    override suspend fun clearCache() {
        cacheStore.clear()
    }

    override suspend fun getCacheSizeBytes(): Long {
        return cacheStore.sizeBytes()
    }

    override suspend fun <T> withScanSession(block: suspend () -> T): T {
        sessionMutex.withLock {
            activeScanSessions++
        }

        return try {
            block()
        } finally {
            sessionMutex.withLock {
                activeScanSessions--
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun scanPageWithFallback(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        primary: EngineType,
        onPartial: ((OcrRegion) -> Unit)? = null,
    ): OcrPageResult {
        val fallbackEnabled = useFallbackModelsPref.get()
        val skipUntitled = skipUntitledPagesPref.get()
        val engines = if (fallbackEnabled) listOf(primary) + fallbackChain(primary) else listOf(primary)
        var attempted = false
        var lastResult: OcrPageResult? = null
        var lastError: Throwable? = null

        for (engine in engines) {
            if (engine in onlineEngines && !isNetworkAvailable()) continue
            attempted = true
            try {
                // Прогресс отдаётся только тому движку, который реально работает
                // по областям: упавший движок мог наполнить канал мусором, и
                // авточтение прочитало бы то, что потом отбросило.
                val result = scanPageByEngine(chapterId, pageIndex, image, engine, onPartial)
                val usable = result.withoutPromotionalRegions()
                if (usable.regions.isNotEmpty()) return usable
                lastResult = usable
                // Движок отработал и не нашёл текста. Правило хода цепочки —
                // в домене ([pageScanStepAfterEngine]), здесь только вывод в
                // консоль: читатель должен видеть, почему страница прошла мимо.
                if (
                    mihon.domain.ocr.interactor.pageScanStepAfterEngine(
                        failed = false,
                        regionCount = usable.regions.size,
                        skipUntitledPages = skipUntitled,
                    ) == mihon.domain.ocr.interactor.PageScanStep.STOP
                ) {
                    AiConsole.ocr(
                        title = "Страница ${pageIndex + 1} · без текста",
                        detail = "${engine.name.lowercase()} не нашёл текста — " +
                            "цепочка фолбэка прервана, страница считается картинкой",
                        level = AiConsole.Level.INFO,
                    )
                    return usable
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!fallbackEnabled) throw error
                logcat(LogPriority.WARN, error) {
                    "OCR page scan ${engine.name.lowercase()} failed or returned no text"
                }
                lastError?.addSuppressed(error) ?: run { lastError = error }
            }
        }

        if (!attempted) throw lastError ?: OcrException.ConnectionError(null)
        return lastResult ?: throw lastError ?: OcrException.InitializationError()
    }

    private suspend fun scanPageByEngine(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        type: EngineType,
        onPartial: ((OcrRegion) -> Unit)? = null,
    ): OcrPageResult {
        // Страничный скан тоже идёт по резервной цепочке, поэтому и тут
        // запоминаем фактического исполнителя: тап по готовой странице
        // показывает подпись того движка, который её прочитал.
        noteEngineUsed(type)
        return when (type) {
            EngineType.CYRILLIC -> scanLocally(chapterId, pageIndex, image, OcrModel.CYRILLIC, type, onPartial)
            EngineType.MLKIT -> scanWithMlKit(chapterId, pageIndex, image, OcrModel.MLKIT, onPartial)
            EngineType.GLENS -> scanWithGlens(chapterId, pageIndex, image, OcrModel.GLENS)
            EngineType.OWOCR -> scanWithOwOcr(chapterId, pageIndex, image, OcrModel.OWOCR)
            EngineType.ZEN_FREE -> scanWithZenFree(chapterId, pageIndex, image)
            EngineType.OPENROUTER,
            EngineType.GOOGLE,
            -> scanWithTextEngine(chapterId, pageIndex, image, type)
            EngineType.LEGACY,
            EngineType.FAST,
            -> scanLocally(chapterId, pageIndex, image, OcrModel.CYRILLIC, EngineType.CYRILLIC, onPartial)
        }
    }

    private suspend fun scanWithTextEngine(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        type: EngineType,
    ): OcrPageResult {
        val text = recognizeWithEngine(type, image).trim()
        val model = when (type) {
            EngineType.OPENROUTER -> OcrModel.OPENROUTER
            EngineType.GOOGLE -> OcrModel.GOOGLE
            else -> error("Unsupported text-only OCR engine: $type")
        }
        val regions = if (text.isBlank()) {
            emptyList()
        } else {
            listOf(
                OcrRegion(
                    order = 0,
                    text = text,
                    boundingBox = OcrBoundingBox(0f, 0f, 1f, 1f),
                    textOrientation = OcrTextOrientation.Horizontal,
                ),
            )
        }
        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = model,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = regions,
        )
    }

    private suspend fun scanWithZenFree(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
    ): OcrPageResult {
        val recognized = submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
            engineLocks.withTextEngineLock(EngineType.ZEN_FREE) {
                val engine = engineFor(EngineType.ZEN_FREE)
                if (engine is ZenFreeOcrEngine) engine.recognizePage(image) else emptyList()
            }
        }
        val regions = recognized.mapIndexedNotNull { index, region ->
            val text = OcrTextCleaner.joinLineHyphens(region.text).trim()
            text.takeIf(String::isNotEmpty)?.let {
                region.copy(order = index, text = it)
            }
        }
        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = OcrModel.ZEN_FREE,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = regions,
        )
    }

    /**
     * Распознавание через вкомпилированную модель Google ML Kit.
     *
     * ML Kit отдаёт готовые строки с координатами, поэтому отдельный детектор
     * не нужен: каждая строка становится регионом, и тап по реплике открывает
     * именно её. Если движок падает или ничего не нашёл — честно уходим в общий
     * фолбэк (онлайн Glens при сети, иначе локальный кириллический PP-OCR).
     */
    private suspend fun scanWithMlKit(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
        onPartial: ((OcrRegion) -> Unit)? = null,
    ): OcrPageResult {
        val regions = submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
            engineLocks.withTextEngineLock(EngineType.MLKIT) {
                val engine = engineFor(EngineType.MLKIT)
                if (engine is MlKitOcrEngine) engine.recognizeRegions(image) else emptyList()
            }
        }

        val mapped = regions.mapIndexedNotNull { index, region ->
            val text = OcrTextCleaner.joinLineHyphens(region.text).trim()
            text.takeIf { it.isNotBlank() }?.let {
                OcrRegion(
                    order = index,
                    text = it,
                    boundingBox = region.boundingBox,
                    textOrientation = OcrTextOrientation.Horizontal,
                )
            }
        }
        // Прогресс по мере готовности: движок уже вернул все области, но
        // авточтение может начать читать их, не дожидаясь сборки страницы.
        mapped.forEach { region -> onPartial?.invoke(region) }

        if (mapped.isEmpty()) {
            return OcrPageResult(
                chapterId = chapterId,
                pageIndex = pageIndex,
                ocrModel = modelKey,
                imageWidth = image.width,
                imageHeight = image.height,
                regions = emptyList(),
            )
        }

        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = modelKey,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = mapped,
        )
    }

    private suspend fun scanWithGlens(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
    ): OcrPageResult {
        val result = try {
            submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                engineLocks.withTextEngineLock(EngineType.GLENS) {
                    glensEngine().recognizePage(image)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isConnectivityFailure(error)) {
                throw OcrException.ConnectionError(error)
            }
            throw error
        }
        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = modelKey,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = result.regions.map { it.copy(text = OcrTextCleaner.joinLineHyphens(it.text)) },
        )
    }

    private suspend fun scanWithOwOcr(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
    ): OcrPageResult {
        val result = try {
            submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                engineLocks.withTextEngineLock(EngineType.OWOCR) {
                    val engine = owOcrEngine ?: OwOcrEngine(context).also {
                        owOcrEngine = it
                    }
                    engine.recognizePage(image)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isConnectivityFailure(error)) {
                throw OcrException.ConnectionError(error)
            }
            throw error
        }
        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = modelKey,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = result.map { it.copy(text = OcrTextCleaner.joinLineHyphens(it.text)) },
        )
    }

    private suspend fun scanLocally(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
        type: EngineType,
        onPartial: ((OcrRegion) -> Unit)? = null,
    ): OcrPageResult {
        // Детектор областей работает на модели PP-OCRv4 из пака cyrillic_ocr
        // и даёт по региону на строку — благодаря этому тап по конкретной
        // реплике открывает именно её.
        //
        // Если пак не установлен (или LiteRT недоступен), детектор бросает
        // DetectionUnavailable, и мы честно деградируем: распознаём страницу
        // целиком и отдаём один регион на весь лист (isWholePage = true).
        // Раньше заглушка бросала всегда, поэтому постраничный режим был
        // единственно возможным.
        val boxes: List<OcrBoundingBox>? = try {
            submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                engineLocks.withDetectionLock {
                    val engine = detectionEngine()
                    engine.detectTextRegions(image)
                }
            }
                .filter(OcrBoundingBox::isValid)
        } catch (e: OcrException.DetectionUnavailable) {
            logcat(LogPriority.INFO) {
                "Region detector unavailable; falling back to whole-page recognition"
            }
            null
        }

        // Детектор ничего не нашёл, либо нашёл единственный бокс во весь лист:
        // разметки по репликам не получится, поэтому идём общим путём, а не
        // сообщаем «текста нет».
        val usableBoxes = boxes?.takeIf { found ->
            found.isNotEmpty() && !(found.size == 1 && OcrBoxGeometry.coversWholePage(found[0]))
        }

        if (usableBoxes == null) {
            val text = submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                recognizeWithEngine(type, image)
            }.trim()
            val regions = if (text.isBlank()) {
                emptyList()
            } else {
                val region = OcrRegion(
                    order = 0,
                    text = text,
                    boundingBox = OcrBoundingBox(0f, 0f, 1f, 1f),
                    textOrientation = OcrTextOrientation.Horizontal,
                )
                onPartial?.invoke(region)
                listOf(region)
            }
            return OcrPageResult(
                chapterId = chapterId,
                pageIndex = pageIndex,
                ocrModel = modelKey,
                imageWidth = image.width,
                imageHeight = image.height,
                regions = regions,
            )
        }

        val regions = mutableListOf<OcrRegion>()
        // Области обрабатываются СТРОГО ПО ОЧЕРЕДИ, и каждая готовая реплика
        // сразу уходит в onPartial. Раньше цикл был ленивым mapIndexedNotNull,
        // который копил всё до конца: читатель ждал последнюю реплику страницы,
        // чтобы услышать первую. Теперь озвучка идёт вслед за распознаванием.
        for ((index, box) in usableBoxes.withIndex()) {
            val crop = cropBitmap(image, box) ?: continue
            val orientation = if (
                OcrBoxGeometry.classifyKind(0, 0, crop.width, crop.height, crop.width, crop.height) ==
                OcrBoxGeometry.Kind.VERTICAL
            ) {
                OcrTextOrientation.Vertical
            } else {
                OcrTextOrientation.Horizontal
            }
            val text = try {
                submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                    engineLocks.withTextEngineLock(type) {
                        val engine = engineFor(type)
                        if (engine is LineOcrEngine) {
                            // Кроп — уже готовая строка: повторный детектор
                            // запрещён, иначе линия дробится и текст рушится.
                            engine.recognizeLine(crop)
                        } else {
                            engine.recognizeText(crop)
                        }
                    }
                }.trim()
            } finally {
                if (!crop.isRecycled) {
                    crop.recycle()
                }
            }
            if (text.isBlank()) continue
            val region = OcrRegion(
                order = index,
                text = text,
                boundingBox = box,
                textOrientation = orientation,
            )
            regions += region
            onPartial?.invoke(region)
        }

        // Если детектор нашёл области, но каждая построчная попытка была
        // отклонена, не превращаем видимое большое облачко в «Нет результатов».
        // Выполняем один локальный цельностраничный rescue-проход; он проходит
        // через тот же CyrillicOcrEngine и те же UTF-8/кириллические фильтры.
        val finalRegions = if (regions.isNotEmpty()) {
            regions
        } else {
            val text = submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                recognizeWithEngine(type, image)
            }.trim()
            if (text.isBlank()) {
                emptyList()
            } else {
                val region = OcrRegion(
                    order = 0,
                    text = text,
                    boundingBox = OcrBoundingBox(0f, 0f, 1f, 1f),
                    textOrientation = OcrTextOrientation.Horizontal,
                )
                onPartial?.invoke(region)
                listOf(region)
            }
        }

        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = modelKey,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = finalRegions,
        )
    }

    private fun cropBitmap(
        image: Bitmap,
        box: OcrBoundingBox,
    ): Bitmap? {
        val left = (box.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = (box.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = (box.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = (box.bottom * image.height).toInt().coerceIn(top + 1, image.height)

        val rect = Rect(left, top, right, bottom)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return null
        }

        return Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
    }

    private fun OcrPageResult.withoutPromotionalRegions(): OcrPageResult {
        return copy(
            regions = regions.mapNotNull { region ->
                val text = OcrTextCleaner.stripPromotionalText(region.text)
                text.takeIf(String::isNotBlank)?.let { region.copy(text = it) }
            },
        )
    }

    override fun cleanup() {
        scope.launch {
            cleanupMutex.withLock {
                cleanupRequested = true
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun <T> submitTask(
        priority: PrioritizedTaskQueue.Priority,
        block: suspend () -> T,
    ): T {
        return taskQueue.submit(priority, block)
    }

    private suspend fun <T> withActiveOperation(block: suspend () -> T): T {
        operationMutex.withLock {
            activeOperations++
        }

        return try {
            block()
        } finally {
            operationMutex.withLock {
                activeOperations--
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun performDeferredCleanupIfIdle() {
        val shouldCleanup = cleanupMutex.withLock {
            if (!cleanupRequested || !taskQueue.isIdle() || hasActiveOperations() || hasActiveScanSessions()) {
                return@withLock false
            }

            cleanupRequested = false
            true
        }

        if (!shouldCleanup) {
            return
        }

        try {
            closeEngines()
            cacheStore.close()
            logcat(LogPriority.INFO) { "OcrRepositoryImpl cleaned up successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error cleaning up OcrRepositoryImpl" }
        }
    }

    private suspend fun <T> OcrImage.useBitmap(
        block: suspend (Bitmap) -> T,
    ): T {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            block(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun hasActiveScanSessions(): Boolean {
        return sessionMutex.withLock { activeScanSessions > 0 }
    }

    private suspend fun hasActiveOperations(): Boolean {
        return operationMutex.withLock { activeOperations > 0 }
    }

    private suspend fun closeEngines() {
        engineLocks.withAllLocks {
            // Сначала сбрасываем детектор: он делегирует в cyrillicEngine и
            // после его закрытия ссылался бы на закрытые модели.
            detEngine = null

            cyrillicEngine?.close()
            cyrillicEngine = null

            mlKitEngine?.close()
            mlKitEngine = null

            legacyEngine?.close()
            legacyEngine = null

            fastEngine?.close()
            fastEngine = null

            glensEngine?.close()
            glensEngine = null

            owOcrEngine?.close()
            owOcrEngine = null

            openRouterEngine?.close()
            openRouterEngine = null

            googleAiEngine?.close()
            googleAiEngine = null

            zenFreeEngine?.close()
            zenFreeEngine = null

            detEngine?.close()
            detEngine = null
        }
    }
}
