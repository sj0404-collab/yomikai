package eu.kanade.tachiyomi.data.tts

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.data.ocr.OcrHistoryStore
import mihon.data.ocr.OcrRegionRules
import mihon.data.ocr.RuMorph
import mihon.data.ocr.MangaTranslatorService
import mihon.data.ocr.CyrillicTranslitFixer
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.normalizeOcrTextForDisplay
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Движок авточтения: скан кадра → фильтр по языку → (перевод) → озвучка
 * реплика-за-репликой с подсветкой текущей (линейка как в AlReader) →
 * сигнал «страница дочитана» для автолистания.
 *
 * Ключевые правила:
 * • Читается ТОЛЬКО текст выбранного языка (ru/en/ja/…): остальной текст
 *   на кадре игнорируется. UI-оверлеи приложения в кадр не попадают —
 *   захватывается контент, а не плавающие кнопки.
 * • История сканов: каждая прочитанная реплика запоминается (нормализованный
 *   хэш) — при повторном попадании в кадр (скролл туда-сюда, миллисекундные
 *   пересечения при листании) она не читается второй раз.
 * • Автолистание БЛОКИРУЕТСЯ, пока все реплики текущего кадра не озвучены:
 *   колбэк onPageFinished зовётся строго после последней реплики.
 */
class AutoReadEngine(
    private val context: Context,
    private val scanPageOcr: ScanPageOcr = Injekt.get(),
    private val prefs: OcrPreferences = Injekt.get(),
) {

    /** Детектор панелей/баллонов (YOLO Seeneva, модель в APK). */
    private val detectPanels: mihon.domain.panel.interactor.DetectPanels by lazy { Injekt.get() }

    /** Selected OCR engine for individual balloon crops (Cyrillic by default). */
    private val bubbleOcr: mihon.domain.ocr.interactor.OcrProcessor by lazy { Injekt.get() }

    /** Строка кадра: текст + нормализованный box (для подсветки/порядка). */
    data class Line(val text: String, val boundingBox: OcrBoundingBox)

    data class SpokenRegion(
        val text: String,
        val translated: String?,
        /** Служебные пометки для показа ({1}{ж}) — TTS их не произносит. */
        val marks: String = "",
        val box: OcrBoundingBox,
        val index: Int,
        val total: Int,
    )

    /** Текущая читаемая реплика — для подсветки-линейки поверх страницы. */
    private val _currentRegion = MutableStateFlow<SpokenRegion?>(null)
    val currentRegion = _currentRegion.asStateFlow()

    /**
     * ВСЕ реплики кадра с их статусом: прочитана / читается / предстоит.
     * Оверлей рисует прочитанные полупрозрачно, текущую — ярко, будущие —
     * пунктирно, так видно и историю, и план чтения.
     */
    data class FrameRegion(
        val box: OcrBoundingBox,
        val index: Int,
        val state: State,
        val text: String = "",
    ) {
        enum class State { DONE, CURRENT, UPCOMING }
    }

    private val _frameRegions = MutableStateFlow<List<FrameRegion>>(emptyList())
    // v1.9.39: озвученные строки «заморожены»: после автолистания они могут
    // остаться вверху кадра, но повторно не читаются (помечаются DONE).
    private val spokenLines = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun lineKey(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}0-9]+"), " ").trim()
    val frameRegions = _frameRegions.asStateFlow()

    /**
     * Слоты голосов ВСЕЙ сессии чтения: пол → последний выданный слот.
     * Не сбрасываются между кадрами (только в [stop]), поэтому персонаж,
     * начавший реплику в одном кадре и продолживший в следующем (перекрытие
     * вебтуна), звучит тем же голосом, а каждый НОВЫЙ персонаж того же пола
     * получает следующий свободный голос.
     */
    private val speakerSlots = mutableMapOf<String, Int>()

    /** Полы реплик прошлого кадра (в порядке чтения) — для определения
     *  продолжения одного персонажа через границу кадров. */
    private var prevFrameGenders: List<String?> = emptyList()

    /**
     * Слот голоса для реплики в режиме «много голосов» / «свой голос
     * персонажу». frameOccurrence — сколько реплик БОЛЬШЕ ТОГО же пола уже
     * прочитано в этом кадре (0 = первый персонаж этого пола в сцене).
     *
     * Правило:
     *  • null-пол (нарратор) → слот 0 (свой голос нарратора);
     *  • продолжение реплики прошлого кадра (текст похож на прошлый кадр,
     *    это обычное 65%-перекрытие вебтуна) → ТОТ ЖЕ слот, что и раньше;
     *  • иначе (новый персонаж или следующая реплика того же пола) →
     *    следующий свободный слот сессии.
     */
    private fun voiceSlotFor(gender: String?, text: String, frameOccurrence: Int): Int {
        val key = gender ?: return 0
        val current = speakerSlots[key] ?: -1
        val continuesPrev = frameOccurrence == 0 &&
            prevFrameGenders.lastOrNull() == gender &&
            prevFrameLines.any { prev ->
                trigramSimilarity(
                    prev.lowercase().filter { it.isLetterOrDigit() },
                    text.lowercase().filter { it.isLetterOrDigit() },
                ) >= 0.45f
            }
        val next = if (continuesPrev) current else current + 1
        speakerSlots[key] = next
        return next
    }

    /**
     * Зона книги внутри вьюпорта (доли 0..1) — если кадр перед OCR был
     * обрезан до неё, оверлей обязан пересчитать box'ы обратно.
     */
    @Volatile
    var highlightZone: android.graphics.RectF? = null

    /** Box из координат обрезанного кадра -> координаты вьюпорта. */
    fun mapToViewport(box: OcrBoundingBox): OcrBoundingBox {
        val z = highlightZone ?: return box
        val zw = z.right - z.left
        val zh = z.bottom - z.top
        return OcrBoundingBox(
            left = z.left + box.left * zw,
            top = z.top + box.top * zh,
            right = z.left + box.right * zw,
            bottom = z.top + box.bottom * zh,
        )
    }

    private val _isReading = MutableStateFlow(false)
    val isReading = _isReading.asStateFlow()

    /** Был ли в последнем кадре новый текст (для темпа автоскролла). */
    @Volatile
    var lastFrameHadText: Boolean = false
        private set

    /** Весь распознанный текст последнего кадра — контекст для AI-чата. */
    @Volatile
    var lastFrameText: String = ""
        private set

    /** Реплики прошлого кадра — передаются ассистенту для дедупликации. */
    private var prevFrameLines: List<String> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Поколение запуска: stop() инвалидирует все колбэки прежних запусков. */
    @Volatile
    private var generation = 0

    /**
     * История прочитанного с НЕЧЁТКИМ сравнением: OCR той же реплики при
     * смещённом кадре даёт слегка другой текст (обрезанные края, дрожание),
     * поэтому точный хэш пропускал дубли. Храним нормализованные строки и
     * сравниваем по включению/похожести 3-граммами (порог 0.6).
     *
     * Вебтун листает кадр на ~35% высоты: хвост прошлой страницы остаётся
     * вверху следующего кадра и OCR может дать его текст с искажением. Порог
     * понижен, чтобы такой хвост не читался во второй раз (жалоба: «сначала
     * середину, потом верх прошлой реплики»), ценой редкого пропуска настоящей
     * реплики-повтора на той же странице.
     */
    private val spokenTexts = ArrayDeque<String>()

    @Synchronized
    private fun isDuplicate(rawText: String): Boolean {
        val norm = rawText.lowercase().filter { it.isLetterOrDigit() }
        // Короткие настоящие слова (я, и, но, не, да…) читаем, а не режем как
        // мусор: к этому месту чистка уже отбросила обрывки, а одиночные слова
        // — осмысленные реплики. Раньше guard `length < 4 -> true` глушил их.
        if (norm.length < 4) return false
        for (old in spokenTexts) {
            if (old.contains(norm) || norm.contains(old)) return true
            if (trigramSimilarity(old, norm) >= 0.6f) return true
        }
        spokenTexts.addLast(norm)
        while (spokenTexts.size > HISTORY_LIMIT) spokenTexts.removeFirst()
        return false
    }

    private fun trigramSimilarity(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return if (a == b) 1f else 0f
        val ta = HashSet<String>(a.length)
        for (i in 0..a.length - 3) ta.add(a.substring(i, i + 3))
        var common = 0
        var total = 0
        for (i in 0..b.length - 3) {
            total++
            if (b.substring(i, i + 3) in ta) common++
        }
        return if (total == 0) 0f else common.toFloat() / total
    }

    @Synchronized
    fun clearHistory() = spokenTexts.clear()

    /**
     * Прочитать кадр. [onPageFinished] вызывается ПОСЛЕ озвучки всех реплик —
     * там вызывающая сторона листает/скроллит дальше. Если нового текста нет
     * (всё уже в истории) — завершится сразу.
     */
    fun readFrame(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        onPageFinished: () -> Unit,
    ) {
        job?.cancel()
        TtsSpeaker.stop()
        val myGen = ++generation
        job = scope.launch {
            _isReading.value = true
            var aiRefine: Job? = null
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val image = OcrImage(bitmap.width, bitmap.height, pixels)
                // Кадр в JPEG для AI-определения пола говорящих (если включено)
                // В ручном режиме пол задан читателем — AI Vision не нужен.
                val genderJpeg: ByteArray? = if (prefs.aiGenderVoices().get() &&
                    !prefs.manualVoiceMode().get()
                ) {
                    runCatching {
                        val out = java.io.ByteArrayOutputStream()
                        val scaled = if (bitmap.width > 1024) {
                            val h = bitmap.height * 1024 / bitmap.width
                            Bitmap.createScaledBitmap(bitmap, 1024, h, true)
                        } else bitmap
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                        out.toByteArray()
                    }.getOrNull()
                } else null

                // Жёсткий таймаут OCR кадра: движок (локальный при первом
                // запуске/загрузке модели, онлайн при недоступном GLENS или
                // неотвечающем Google) может «зависнуть» без ответа. Без этого
                // авточтение крутится вечно («бесконечное сканирование»).
                // По таймауту кадр считается пустым: возвращаем пустой регион
                // и идём по обычному конвейеру (пустой кадр → дочитывается и
                // страница листается дальше, а не блокируется навсегда).
                val result: mihon.domain.ocr.model.OcrPageResult = try {
                    withTimeout(OCR_FRAME_TIMEOUT_MS) {
                        scanPageOcr.await(chapterId, pageIndex, image)
                    }
                } catch (e: TimeoutCancellationException) {
                    logcat(LogPriority.WARN) {
                        "OCR frame timeout (${OCR_FRAME_TIMEOUT_MS}ms) pageIndex=$pageIndex"
                    }
                    OcrHistoryStore.addAutoRead(
                        false,
                        "OCR-кадр таймаут",
                        "${OCR_FRAME_TIMEOUT_MS / 1000}с pageIndex=$pageIndex",
                    )
                    mihon.domain.ocr.model.OcrPageResult(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        ocrModel = prefs.ocrModel().get(),
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        regions = emptyList(),
                    )
                }

                val language = prefs.autoReadLanguage().get()
                val translate = prefs.autoReadTranslate().get()
                // Порядок чтения берём из пресета типа контента (манхва/вебтун →
                // «vertical» сверху вниз), а не из старого `scanReadingOrder`,
                // который по умолчанию «rtl» и ломал подсветку на вебтунах.
                val order = OcrRegionRules.readingOrderFor(prefs)

                // ===== БАЛЛОНЫ ВМЕСТО «ВСЕЙ СТРАНИЦЫ» (фикс по скриншотам) =====
                // Полностраничные движки возвращают один регион 0,0-1,1.
                // Для авточтения такой результат дополнительно разбирается:
                //  1) YOLO-детектор находит баллоны;
                //  2) каждый баллон распознаётся выбранным OCR (по умолчанию
                //     полностью офлайн Cyrillic PP-OCR);
                //  3) каждый баллон = своя реплика со своей рамкой, номером
                //     {N} и полом говорящего.
                // Если детектор ничего не нашёл — текст страницы хотя бы
                // делится на строки-реплики вместо одного блока.
                var lines: List<Line> = result.regions.map {
                    // v1.9.44: OCR-путаница латиницы/кириллицы (M E Ч, А Т М) и
                    // цифр (5→Б) лечится фиксером похожих символов.
                    Line(normalizeOcrTextForDisplay(CyrillicTranslitFixer.autoFixCyrillic(it.text)).trim(), it.boundingBox)
                }
                val wholePage = result.regions.size == 1 && result.regions.first().isWholePage
                if (wholePage && !bitmap.isRecycled) {
                    val bubbleLines = runCatching { readBubbles(bitmap, chapterId, pageIndex, order) }
                        .onFailure {
                            logcat(LogPriority.WARN, it) { "Bubble detection failed" }
                            OcrHistoryStore.addAutoRead(false, "детектор облачков", it.message ?: it.javaClass.simpleName)
                        }
                        .getOrDefault(emptyList())
                    lines = if (bubbleLines.isNotEmpty()) {
                        bubbleLines
                    } else {
                        // Фолбэк: строки полностраничного текста как реплики
                        lines.firstOrNull()?.let { splitWholePageToLines(it) } ?: emptyList()
                    }
                }
                if (!bitmap.isRecycled) bitmap.recycle()

                // Локальный OCR (Cyrillic PP-OCR) отдаёт регион на КАЖДУЮ
                // строку: реплика из двух строк распадалась на два разных
                // «текста», и перенос слова читался как два разных слова.
                // Строки одного облачка (сильное перекрытие по X, малый зазор)
                // склеиваются в одну реплику. Полностраничные фолбэки не трогаем.
                if (!wholePage) lines = mergeBubbleLines(lines)

                // 1) фильтр мусора OCR (обрывки «eS la 4», «| | > |», «о»)
                //    + фильтр по языку; 2) отсев уже прочитанного
                val fresh = lines
                    .asSequence()
                    .map { it.copy(text = cleanOcrGarbage(it.text, language)) }
                    .filter { isMeaningful(it.text, language) }
                    .filter { !isDuplicate(it.text) }
                    // Рамка обязана лежать внутри страницы и иметь разумный
                    // размер: иначе голубая подсветка вылезает за текст/экран.
                    .filter { b ->
                        val bb = b.boundingBox
                        bb.left >= -0.001f && bb.top >= -0.001f &&
                            bb.right <= 1.001f && bb.bottom <= 1.001f &&
                            (bb.right - bb.left) > 0.02f &&
                            (bb.bottom - bb.top) > 0.01f
                    }
                    .toList()

                // 3) порядок чтения (группировка в строки по близости центров Y,
                // а не по фиксированным 12% полосам — убирает «лесенку»)
                val ordered = orderRegions(fresh, order)

                // 3.5) Пол говорящих. Приоритет:
                //  а) ВСТРОЕННЫЙ локальный AI (LocalSpeakerAi) — морфология
                //     русского текста, работает без сети и без ключей;
                //  б) AI-конвейер (prepareFrame) — ПАРАЛЛЕЛЬНО, без блокировки.
                //
                // БЫСТРОЕ АВТОЧТЕНИЕ (фикс замедления): раньше конвейер
                // «чат → голос» БЛОКИРОВАЛ старт озвучки до 8 секунд на
                // каждом кадре (ждали ответа модели). Теперь чтение стартует
                // МГНОВЕННО с локальной морфологией, а ответ ассистента
                // подхватывается на лету и применяется к ещё НЕ прочитанным
                // репликам (пол, чистка, скип дублей). Дубли прошлых кадров
                // и так режутся локальной нечёткой историей — заглушек нет,
                // просто больше не ждём сеть.
                val preparedRef = java.util.concurrent.atomic.AtomicReference<
                    List<eu.kanade.tachiyomi.data.ai.AiAssistant.PreparedLine>?,
                    >(null)
                if (prefs.aiGenderVoices().get() && ordered.isNotEmpty()) {
                    val newLines = ordered.map { it.text }
                    val prevSnapshot = prevFrameLines
                    scope.launch {
                        preparedRef.set(
                            eu.kanade.tachiyomi.data.ai.AiAssistant.prepareFrame(
                                newLines = newLines,
                                prevLines = prevSnapshot,
                            ),
                        )
                    }
                }
                prevFrameLines = ordered.map { it.text }

                val localGenders = LocalSpeakerAi.guessGenders(ordered.map { it.text })
                val genders = java.util.concurrent.atomic.AtomicReferenceArray<String?>(ordered.size)
                for (i in ordered.indices) {
                    genders.set(i, localGenders[i] ?: detectGenderByDictionary(ordered[i].text))
                }
                aiRefine = null
                // Gemini Vision как ещё один фоновый уточнитель — только с ключом
                if (genderJpeg != null && ordered.isNotEmpty() &&
                    prefs.googleApiKey().get().isNotBlank() && localGenders.any { it == null }
                ) {
                    scope.launch {
                        val vision = SpeakerGenderService.detect(genderJpeg, ordered.map { it.text }, prefs)
                        for (i in ordered.indices) {
                            if (genders.get(i) == null) genders.set(i, vision.getOrNull(i))
                        }
                    }
                }

                lastFrameHadText = ordered.isNotEmpty()
                if (ordered.isNotEmpty()) {
                    lastFrameText = ordered.joinToString("\n") { it.text }
                }
                // Полы текущего кадра — для слотов голосов следующего кадра
                // (продолжение персонажа через перекрытие вебтуна).
                prevFrameGenders = (0 until ordered.size).map { genders.get(it) }

                // 3.7) перевод ВСЕЙ страницы одним запросом (раньше был
                // отдельный HTTP-запрос на каждую реплику — на 15 бабблах
                // это 15 последовательных обращений между озвучками).
                val target = prefs.translateTarget().get().ifBlank { "ru" }
                val translations: List<String> = if (translate && language != target) {
                    runCatching { MangaTranslatorService.translateAll(ordered.map { it.text }, target) }
                        .getOrElse { ordered.map { it.text } }
                } else {
                    ordered.map { it.text }
                }

                // Публикуем карту кадра: всё, что будет прочитано
                val frozenL = ordered.filter { lineKey(it.text) in spokenLines }
                val speakable = ordered.filterNot { lineKey(it.text) in spokenLines }
                _frameRegions.value = speakable.mapIndexed { i, r ->
                    FrameRegion(r.boundingBox, i + 1, FrameRegion.State.UPCOMING, r.text)
                } + frozenL.mapIndexed { j, r ->
                    FrameRegion(r.boundingBox, speakable.size + j + 1, FrameRegion.State.DONE, r.text)
                }

                // 4) реплика за репликой: подсветка -> озвучка -> ждём конца
                for ((i, region) in speakable.withIndex()) {
                    if (job?.isActive != true) break

                    // Ответ ассистента подхватывается на лету (если уже
                    // пришёл): скип дублей, чистый текст, пол. Если ещё не
                    // пришёл — читаем немедленно локальным конвейером.
                    val prep = preparedRef.get()?.getOrNull(i)
                    if (prep != null && !prep.speak) continue
                    if (prep?.gender != null && genders.get(i) == null) genders.set(i, prep.gender)

                    // Обновляем статусы: до i — прочитано, i — читается, после — предстоит
                    _frameRegions.value = speakable.mapIndexed { j, r ->
                        FrameRegion(
                            r.boundingBox,
                            j + 1,
                            when {
                                j < i -> FrameRegion.State.DONE
                                j == i -> FrameRegion.State.CURRENT
                                else -> FrameRegion.State.UPCOMING
                            },
                        )
                    }

                    // Текст: приоритет — очищенный ассистентом, затем перевод, затем сырой OCR
                    val speakTextRaw = prep?.text?.takeIf { it.isNotBlank() }
                        ?: translations.getOrNull(i) ?: region.text

                    // Ручной режим важнее автоопределения: читатель выбрал
                    // голос кнопкой в читалке и ждёт именно его.
                    //
                    // Роль-режим (1 / 2 / много голосов):
                    //  • SINGLE — весь текст одним голосом нарратора;
                    //  • DUAL/TRIPLE — пол реплики (муж/жен) определяет голос;
                    //  • MULTI — как DUAL, но с отдельным слотом каждому
                    //    персонажу одного пола (см. slot ниже).
                    val roleMode = VoiceModeResolver.currentMode()
                    val gender = when {
                        prefs.manualVoiceMode().get() ->
                            prefs.manualVoiceGender().get().takeIf { it.isNotBlank() } ?: "female"
                        roleMode == VoiceModeResolver.Mode.SINGLE -> VoiceModeResolver.narratorGender()
                        else -> genders.get(i) // мог дозаполниться AI пока читали предыдущие
                    }

                    // Служебные пометки: номер по порядку чтения и пол.
                    // Они показываются на экране, но НЕ произносятся —
                    // SpeechMarkup.strip() снимает их перед синтезом.
                    val marks = buildString {
                        if (prefs.showSpeechNumbers().get()) append("{").append(i + 1).append("}")
                        when (gender) {
                            "female" -> append("{ж}")
                            "male" -> append("{м}")
                        }
                    }

                    _currentRegion.value = SpokenRegion(
                        text = region.text,
                        translated = speakTextRaw.takeIf { it != region.text },
                        box = region.boundingBox,
                        index = i + 1,
                        total = ordered.size,
                        marks = marks,
                    )

                    // Слот говорящего: каждый персонаж одного пола в сцене
                    // получает свой голос. Слоты сессионные (переживают кадры):
                    // продолжение реплики прошлого кадра звучит тем же голосом,
                    // новый персонаж — следующим свободным. В режиме «много
                    // голосов» и при «свой голос персонажу» всегда включено.
                    val slot = if (
                        roleMode == VoiceModeResolver.Mode.MULTI ||
                        prefs.perSpeakerVoices().get()
                    ) {
                        val occurrence = (0 until i).count { genders.get(it) == gender }
                        voiceSlotFor(gender, region.text, occurrence)
                    } else {
                        0
                    }

                    speakAndAwait(SpeechMarkup.strip(speakTextRaw), gender, slot)
                spokenLines.add(lineKey(region.text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "AutoRead frame failed" }
                OcrHistoryStore.addAutoRead(false, "сбой страницы", e.message ?: e.javaClass.simpleName)
            } finally {
                aiRefine?.cancel()
                _currentRegion.value = null
                _frameRegions.value = emptyList()
                _isReading.value = false
                // Колбэк только для АКТУАЛЬНОГО запуска: после stop() старый
                // цикл не имеет права листать дальше или перезапускать чтение
                if (myGen == generation && job?.isCancelled != true) {
                    onPageFinished()
                }
            }
        }
    }

    fun stop() {
        spokenLines.clear()
        speakerSlots.clear()
        prevFrameGenders = emptyList()
        generation++ // инвалидируем все pending-колбэки
        job?.cancel()
        job = null
        TtsSpeaker.stop()
        _currentRegion.value = null
        _frameRegions.value = emptyList()
        _isReading.value = false
    }

    /**
     * Озвучить ОДНУ реплику (бабл), выбранную пользователем (значок 🔊,
     * перетаскивание значка или ручной бабл). В отличие от [readFrame] не
     * трогает историю кадра и не листает: просто произносит переданный текст
     * выбранным движком/полом. Запускается в [scope], чтобы [job] был активен
     * во время озвучки (иначе [speakAndAwait] мгновенно прерывается).
     */
    fun speakSingle(text: String, gender: String? = null, speakerSlot: Int = 0) {
        val clean = SpeechMarkup.strip(text).trim()
        if (clean.isBlank()) return
        if (_isReading.value) TtsSpeaker.stop()
        job?.cancel()
        val myGen = ++generation
        job = scope.launch {
            _isReading.value = true
            try {
                speakAndAwait(clean, gender, speakerSlot)
            } finally {
                if (generation == myGen) _isReading.value = false
            }
        }
    }

    /** Озвучка с ожиданием реального окончания фразы. */
    private suspend fun speakAndAwait(text: String, gender: String? = null, speakerSlot: Int = 0) {
        var started = false
        // Флаг фактического завершения фразы. MutableStateFlow, потому что onState
        // приходит из потока TTS, а читается из этой корутины (диспетчер IO).
        val done = MutableStateFlow(false)
        val t0 = System.currentTimeMillis()
        TtsSpeaker.speakAs(context, text, gender, speakerSlot) { speaking ->
            if (speaking && !started) {
                started = true
                logcat(LogPriority.DEBUG) { "TTS started (${System.currentTimeMillis() - t0}ms): ${text.take(60)}" }
            }
            if (!speaking && started) {
                done.value = true
                logcat(LogPriority.DEBUG) { "TTS done in ${System.currentTimeMillis() - t0}ms" }
                OcrHistoryStore.addAutoRead(true, "озвучено (${System.currentTimeMillis() - t0} мс)", text.take(60))
            }
        }
        // страховка: макс. время = длина текста * 220мс + запас 5с
        val timeoutMs = text.length * 220L + 5_000L
        val start = System.currentTimeMillis()
        while (!done.value && System.currentTimeMillis() - start < timeoutMs) {
            if (job?.isActive != true) {
                TtsSpeaker.stop()
                return
            }
            delay(40) // быстрый опрос: между репликами нет лишней паузы
        }
        // Диагностика недоговорённых реплик: TTS мог прерваться без onDone.
        if (started && !done.value) {
            logcat(LogPriority.WARN) {
                "TTS timeout without onDone: ${text.take(60)} (waited ${System.currentTimeMillis() - start}ms)"
            }
            OcrHistoryStore.addAutoRead(false, "TTS без завершения", text.take(60))
        } else if (!started) {
            logcat(LogPriority.WARN) { "TTS never started: ${text.take(60)}" }
            OcrHistoryStore.addAutoRead(false, "TTS не запустился", text.take(60))
        }
    }

    /**
     * Словарный фолбэк пола говорящего: работает, когда морфология
     * LocalSpeakerAi не дала ответа (в реплике нет «я …ла/…л»). Ориентируемся
     * на маркеры окружения персонажа: родственные связи и роли. Возвращаем
     * пол только при явном перевесе — иначе null (нейтральный голос).
     */
    private fun detectGenderByDictionary(text: String): String? {
        val maleMarkers = listOf(
            "брат", "отец", "папа", "дед", "сын", "мужчина", "парень",
            "господин", "старик", "мальчик", "юноша", "принц", "король",
        )
        val femaleMarkers = listOf(
            "сестра", "мать", "мама", "бабушка", "дочь", "женщина", "девушка",
            "госпожа", "старуха", "девочка", "принцесса", "королева",
        )
        val lower = text.lowercase()
        // Подстрочный поиск покрывает падежи: «моей сестры», «к отцу».
        val maleCount = maleMarkers.count { lower.contains(it) }
        val femaleCount = femaleMarkers.count { lower.contains(it) }
        val byMarkers = when {
            maleCount > femaleCount -> "male"
            femaleCount > maleCount -> "female"
            else -> null
        }
        if (byMarkers != null) return byMarkers
        // Морфологический фолбэк: род по окончаниям словоформ (RuMorph).
        val morph = RuMorph.guessGender(text)
        if (morph != null) {
            OcrHistoryStore.addAutoRead(true, "пол говорящего: морфология", "$morph: ${text.take(40)}")
        }
        return morph
    }

    // region Баллоны (YOLO) и чистка OCR-мусора

    /**
     * YOLO-детект баллонов на кадре + пофрагментный OCR. Каждый найденный
     * баллон становится отдельной репликой со своей рамкой. После разовой
     * загрузки моделей весь конвейер работает полностью офлайн.
     */
    private suspend fun readBubbles(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        order: String,
    ): List<Line> {
        val direction = when (order) {
            "ltr" -> tachiyomi.core.common.util.system.ReadingDirection.LTR
            "vertical" -> tachiyomi.core.common.util.system.ReadingDirection.VERTICAL
            else -> tachiyomi.core.common.util.system.ReadingDirection.RTL
        }
        val det = detectPanels.await(
            cacheKey = "autoread_${chapterId}_$pageIndex",
            image = bitmap,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            direction = direction,
        )
        val bubbles = det.debugBubbles
            .map { it.rect }
            .filter { it.width() > 16 && it.height() > 16 }
            .take(MAX_BUBBLES_PER_FRAME)
        if (bubbles.isEmpty()) return emptyList()

        val out = mutableListOf<Line>()
        for ((index, r) in bubbles.withIndex()) {
            if (job?.isActive != true) break
            // 1) Широкая рамка (ширина > 1.6 высоты) часто обнимает ДВА круглых
            //    облачка, совмещённых вплотную, с разным текстом. Прогоняем по
            //    ней панельный детектор ещё раз и распознаём ВЕСЬ текст,
            //    разбив его по найденным облачкам.
            if (r.width() > 1.6f * r.height()) {
                val subLines = readBubbleSplits(
                    bitmap = bitmap,
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    rect = r,
                    index = index,
                    direction = direction,
                )
                out += subLines
                continue
            }
            // 2) Обычное облачко: поля 6%, круглая форма маскируется эллипсом,
            //    чтобы углы квадратного кадрирования не тащили текст соседа.
            val padX = (r.width() * 0.06f).toInt()
            val padY = (r.height() * 0.06f).toInt()
            val left = (r.left - padX).coerceAtLeast(0)
            val top = (r.top - padY).coerceAtLeast(0)
            val right = (r.right + padX).coerceAtMost(bitmap.width)
            val bottom = (r.bottom + padY).coerceAtMost(bitmap.height)
            if (right - left < 16 || bottom - top < 16) continue
            var text = ocrCroppedRegion(
                bitmap,
                left,
                top,
                right,
                bottom,
                maskRound = (right - left).toFloat() / (bottom - top) in 0.7f..1.4f,
            )
            var clean = normalizeOcrTextForDisplay(text).trim()
            // 3) Пустой/куцый результат (углы коснулись соседнего облачка или
            //    рамка съехала) — повторяем по ужатому центру рамки.
            if (clean.count { it.isLetter() } < 3 && bottom - top > 32) {
                val dx = ((right - left) * 0.14f).toInt()
                val dy = ((bottom - top) * 0.14f).toInt()
                text = ocrCroppedRegion(
                    bitmap,
                    left + dx,
                    top + dy,
                    right - dx,
                    bottom - dy,
                    maskRound = false,
                )
                clean = normalizeOcrTextForDisplay(text).trim()
            }
            if (clean.isNotBlank()) {
                out += Line(
                    text = clean,
                    boundingBox = OcrBoundingBox(
                        left = left.toFloat() / bitmap.width,
                        top = top.toFloat() / bitmap.height,
                        right = right.toFloat() / bitmap.width,
                        bottom = bottom.toFloat() / bitmap.height,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Два круглых облачка вплотную внутри одной широкой рамки: повторный
     * панельный детектор на кадрированном фрагменте находит их по отдельности,
     * каждый распознаётся со своей репликой. Если детектор не нашёл ничего —
     * распознаём всю широкую рамку целиком.
     */
    private suspend fun readBubbleSplits(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        rect: android.graphics.Rect,
        index: Int,
        direction: tachiyomi.core.common.util.system.ReadingDirection,
    ): List<Line> {
        val out = mutableListOf<Line>()
        val padX = (rect.width() * 0.04f).toInt()
        val padY = (rect.height() * 0.04f).toInt()
        val left = (rect.left - padX).coerceAtLeast(0)
        val top = (rect.top - padY).coerceAtLeast(0)
        val right = (rect.right + padX).coerceAtMost(bitmap.width)
        val bottom = (rect.bottom + padY).coerceAtMost(bitmap.height)
        if (right - left < 16 || bottom - top < 16) return out
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val kids = try {
            detectPanels.await(
                cacheKey = "bubble_split_${chapterId}_${pageIndex}_$index",
                image = crop,
                originalWidth = crop.width,
                originalHeight = crop.height,
                direction = direction,
            ).debugBubbles.map { it.rect }
                .filter {
                    it.width() > 16 && it.height() > 16 &&
                        (it.width() * it.height()) >= 0.13f * crop.width * crop.height
                }
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "bubble split detect failed" }
            emptyList()
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
        if (kids.size < 2) {
            // Детектор не увидел отдельных облачков — читаем всю рамку как есть.
            val text = ocrCroppedRegion(bitmap, left, top, right, bottom, maskRound = false)
            val clean = normalizeOcrTextForDisplay(text).trim()
            if (clean.isNotBlank()) {
                out += Line(
                    text = clean,
                    boundingBox = normalizeBox(left, top, right, bottom, bitmap),
                )
            }
            return out
        }
        for (kid in kids.take(MAX_BUBBLES_PER_FRAME)) {
            if (job?.isActive != true) break
            val kPadX = (kid.width() * 0.05f).toInt()
            val kPadY = (kid.height() * 0.05f).toInt()
            val kl = (left + kid.left - kPadX).coerceAtLeast(0)
            val kt = (top + kid.top - kPadY).coerceAtLeast(0)
            val kr = (left + kid.right + kPadX).coerceAtMost(bitmap.width)
            val kb = (top + kid.bottom + kPadY).coerceAtMost(bitmap.height)
            if (kr - kl < 16 || kb - kt < 16) continue
            val text = ocrCroppedRegion(
                bitmap,
                kl,
                kt,
                kr,
                kb,
                maskRound = (kr - kl).toFloat() / (kb - kt) in 0.7f..1.4f,
            )
            val clean = normalizeOcrTextForDisplay(text).trim()
            if (clean.isNotBlank()) {
                out += Line(clean, normalizeBox(kl, kt, kr, kb, bitmap))
            }
        }
        return out
    }

    /** Обрезка + (по желанию) круглая маска + OCR одной области. */
    private suspend fun ocrCroppedRegion(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        maskRound: Boolean,
    ): String {
        val w = right - left
        val h = bottom - top
        if (w < 16 || h < 16) return ""
        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        return try {
            val ocrBitmap = if (maskRound) maskCircle(crop) else crop
            val pixels = IntArray(ocrBitmap.width * ocrBitmap.height)
            ocrBitmap.getPixels(pixels, 0, ocrBitmap.width, 0, 0, ocrBitmap.width, ocrBitmap.height)
            bubbleOcr.getText(OcrImage(ocrBitmap.width, ocrBitmap.height, pixels))
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    /** Нормализация области кадрирования в доли стороны кадра. */
    private fun normalizeBox(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bitmap: Bitmap,
    ) = OcrBoundingBox(
        left = left.toFloat() / bitmap.width,
        top = top.toFloat() / bitmap.height,
        right = right.toFloat() / bitmap.width,
        bottom = bottom.toFloat() / bitmap.height,
    )

    /** Круглая маска: углы квадратного облачка убираются (пустые), текст
     *  соседнего облачка в углах кадрирования не попадает в распознавание. */
    private fun maskCircle(src: Bitmap): Bitmap {
        val masked = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(masked)
        canvas.drawBitmap(src, 0f, 0f, null)
        val erase = android.graphics.Paint().apply {
            isAntiAlias = true
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        canvas.drawOval(
            android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            erase,
        )
        // Полностью прозрачные углы заливаем белым: для OCR чёрный/прозрачный
        // фон в углах вреднее белого.
        val px = IntArray(masked.width * masked.height)
        masked.getPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
        var changed = false
        for (i in px.indices) {
            if (px[i] and -0x1000000 == 0) {
                px[i] = android.graphics.Color.WHITE
                changed = true
            }
        }
        if (changed) masked.setPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
        return masked
    }

    /**
     * Полностраничный результат (один регион 0..1) делится на строки:
     * каждая непустая строка — отдельная реплика. Рамки приблизительные
     * (равномерно по высоте) — хоть какая-то подсветка вместо всей страницы.
     */
    private fun splitWholePageToLines(whole: Line): List<Line> {
        val rows = whole.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rows.size <= 1) return listOf(whole)
        val h = 1f / rows.size
        return rows.mapIndexed { i, t ->
            Line(t, OcrBoundingBox(0.05f, i * h, 0.95f, (i + 1) * h))
        }
    }

    // endregion

    // region Склейка строк одной реплики

    /**
     * Склеивает соседние строки OCR, принадлежащие ОДНОЙ реплике / облачку,
     * в одну реплику.
     *
     * Локальный движок (Cyrillic PP-OCR) отдаёт регион на КАЖДУЮ строку
     * текста: реплика из двух строк распадалась на два разных «текста»,
     * а перенос слова (понеде- / льник) читался как два разных слова.
     */
    private fun mergeBubbleLines(lines: List<Line>): List<Line> {
        if (lines.size <= 1) return lines
        val sorted = lines.sortedBy { it.boundingBox.top }
        val merged = mutableListOf<Line>()
        var current: Line? = null
        for (line in sorted) {
            val cur = current
            if (cur != null && sameBubble(cur, line)) {
                current = Line(
                    text = joinBubbleText(cur.text, line.text),
                    boundingBox = union(cur.boundingBox, line.boundingBox),
                )
            } else {
                current?.let { merged += it }
                current = line
            }
        }
        current?.let { merged += it }
        return merged
    }

    /** Обе строки выровнены (одна колонка/облачко) и стоят вплотную. */
    private fun sameBubble(a: Line, b: Line): Boolean {
        val ab = a.boundingBox
        val bb = b.boundingBox
        val aW = ab.right - ab.left
        val bW = bb.right - bb.left
        if (aW <= 0f || bW <= 0f) return false
        // Перекрытие по горизонтали ≥ 50% ширины меньшей строки — один «столбец».
        val overlap = kotlin.math.min(ab.right, bb.right) - kotlin.math.max(ab.left, bb.left)
        if (overlap <= 0f) return false
        if (overlap / kotlin.math.min(aW, bW) < 0.5f) return false
        // Зазор между строками одного облачка много меньше зазора между
        // облачками (у диалогов и рамок есть пустое поле между ними).
        val aH = ab.bottom - ab.top
        val bH = bb.bottom - bb.top
        if (aH <= 0f || bH <= 0f) return false
        val gap = bb.top - ab.bottom
        if (gap < 0f || gap > kotlin.math.max(aH, bH) * 0.55f) return false
        // Вертикальные японские колонки (узкие и высокие) не склеиваем.
        if (aH > aW * 2f || bH > bW * 2f) return false
        return true
    }

    /** Перенос слова в конце строки склеивается вплотную, иначе пробел. */
    private fun joinBubbleText(a: String, b: String): String {
        val aT = a.trim()
        val bT = b.trim()
        if (aT.isBlank()) return bT
        if (bT.isBlank()) return aT
        return if (aT.endsWith("-")) {
            aT.dropLast(1) + bT
        } else {
            "$aT $bT"
        }
    }

    private fun union(a: OcrBoundingBox, b: OcrBoundingBox) = OcrBoundingBox(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )

    // endregion Склейка строк одной реплики

    // region Чистка мусора OCR

    companion object {
        private const val HISTORY_LIMIT = 600
        private const val MAX_BUBBLES_PER_FRAME = 14

        /**
         * Максимальное время OCR одного кадра в авточтении. Локальный движок
         * при первом запуске/загрузке модели, а также онлайн-движок (GLENS /
         * Google) при недоступном сервисе БЕЗ этого жёсткого лимита могли
         * заморозить авточтение навсегда. По таймауту кадр считается пустым.
         */
        private const val OCR_FRAME_TIMEOUT_MS = 45_000L

        /** Настоящие одно- и двухбуквенные русские слова (союзы/предлоги/междометия). */
        private val RUSSIAN_SINGLE_WORD = setOf("а", "и", "в", "с", "у", "о", "я", "к")
        private val RUSSIAN_SHORT_WORD = setOf(
            "но", "не", "да", "он", "мы", "вы", "ты", "ни", "от", "до", "на",
            "за", "по", "из", "ко", "то", "ли", "же", "бы", "ну", "ах", "ох",
            "ой", "эх", "уж", "ага", "так", "тот", "это", "се", "об", "при", "про",
        )

        /** Одно- или двухбуквенное настоящее русское слово. */
        fun isShortRussianWord(text: String): Boolean {
            val t = text.trim().lowercase()
            return t in RUSSIAN_SINGLE_WORD || t in RUSSIAN_SHORT_WORD
        }

        /**
         * Упорядочивает реплики кадра в читаемый порядок.
         *
         * Раньше реплики группировались в «строки» по близости вертикальных
         * центров (допуск 0.7 медианной высоты), строки шли сверху вниз, внутри
         * строки — по направлению чтения. На манге и при наклонных/разновысоких
         * панелях этот допуск раскалывал реплики одной строки по разным
         * «строкам» (лесенка), а на вебтуне из-за перекрытия кадров текст
         * читался «середину → хвост прошлой страницы → низ».
         *
         * Теперь порядок — как читает человек: КОЛОНКАМИ по направлению чтения.
         *  • манга (RTL): сначала самая правая колонка сверху вниз, затем левее;
         *  • комикс (LTR): колонка за колонкой слева направо, сверху вниз;
         *  • вебтун/манхва (vertical): строго сверху вниз, одной колонкой.
         * Так реплики читаются в том порядке, где физически расположены на
         * кадре, и не путаются ни на манге, ни на перекрывающихся кадрах.
         */
        fun orderRegions(lines: List<Line>, order: String): List<Line> {
            if (lines.size <= 1) return lines
            return when (order) {
                "ltr" -> lines.sortedWith(
                    compareBy<Line> { it.boundingBox.left }.thenBy { it.boundingBox.top },
                )
                "vertical" -> lines.sortedBy { it.boundingBox.top }
                else -> lines.sortedWith(
                    compareByDescending<Line> { it.boundingBox.right }
                        .thenBy { it.boundingBox.top },
                )
            }
        }

        /**
         * Чистка OCR-мусора ВНУТРИ реплики (по скриншотам пользователя:
         * «АХЕ возьмешь — МЕНЯ НА РУЧКИ? eS la 4…», «Я | | > | КАК ПРИНЦЕСС…»,
         * «РУ у 4а (WX i ДЖЕЙН…»). Правила:
         *  • строки из символов-палок/скобок/стрелок выбрасываются целиком;
         *  • строки, где смесь латиницы+цифр не похожа на слова (eS la 4,
         *    WX i, 4a) — выбрасываются при целевом языке ru;
         *  • одиночные буквы-обрывки («о», «Я» без продолжения в 1 строку из
         *    многих) — выбрасываются;
         *  • остальные строки склеиваются пробелом.
         */
        fun cleanOcrGarbage(text: String, language: String): String {
            val rawRows = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            // Короткие целые слова («шум», «гам») OCR-чистка больше не кромсает:
            // одиночное слово из букв проходит без правок и фильтров.
            val singleWord = rawRows.singleOrNull()
            if (singleWord != null && singleWord.length in 3..14 && singleWord.all { it.isLetter() }) {
                return singleWord
            }
            // OCR часто путает буквы с цифрами («4» вместо «а», «1» вместо «л»).
            // Убираем только ЦИФРЫ, слипшиеся с буквой (4а, а4, eS la 4) — это
            // типичная ошибка OCR. Самостоятельные числа («1», «глава 1», «5»)
            // НЕ трогаем: пользователь просил, чтобы цифры озвучивались
            // («он цифры не говорит почему-то»). Чисто числовые строки-мусор
            // («2/89», «7») отсекаются ниже по isMeaningfulRow (нет букв).
            val rows = rawRows.map { row ->
                row.replace(Regex("(?<=[\\p{L}])[0-9]+|[0-9]+(?=[\\p{L}])"), " ")
                    .replace(Regex("\\s+"), " ").trim()
            }.filter { it.isNotBlank() }
            if (rows.isEmpty()) return ""
            val kept = rows.filter { row -> isMeaningfulRow(row, language) }
            // Если ВСЁ забраковано, но исходник был длинный — вернём самую
            // «словесную» строку, чтобы не терять настоящие реплики.
            if (kept.isEmpty()) {
                val best = rows.maxByOrNull { r -> r.count { it.isLetter() } }
                return if (best != null && best.count { it.isLetter() } >= 4) best else ""
            }
            // Склейка строк с учётом переноса слова в конце строки:
            // «понеде-» + «льник» → «понедельник», иначе обычный пробел.
            val joined = StringBuilder()
            for (row in kept) {
                if (joined.isNotEmpty()) {
                    if (joined.lastOrNull() == '-') {
                        joined.deleteCharAt(joined.length - 1)
                    } else {
                        joined.append(' ')
                    }
                }
                joined.append(row)
            }
            return joined.toString().replace(Regex("\\s+"), " ").trim()
        }

        /** Похожа ли строка на осмысленный текст (не обрывок/не мусор). */
        private fun isMeaningfulRow(row: String, language: String): Boolean {
            val letters = row.count { it.isLetter() }
            val total = row.length
            // Палки, скобки, стрелки, точки: буквы < 40% строки — мусор
            if (letters == 0) return false
            if (letters.toFloat() / total < 0.4f && total >= 3) return false
            // Одна-две буквы — настоящие русские слова (я, и, в, с, но, не…)
            if (letters <= 2) return language == "ru" && isShortRussianWord(row)
            when (language) {
                "ru" -> {
                    val cyr = row.count { it in '\u0400'..'\u04FF' }
                    // Латиница с цифрами (eS la 4, WX i) при русском языке — мусор
                    if (cyr == 0) return false
                    if (cyr.toFloat() / letters < 0.6f) return false
                    // Должно быть хотя бы одно «слово» из 3+ кириллических букв
                    return Regex("[\\u0400-\\u04FF]{3,}").containsMatchIn(row)
                }
                "en" -> {
                    val lat = row.count { it in 'a'..'z' || it in 'A'..'Z' }
                    if (lat.toFloat() / letters < 0.6f) return false
                    return Regex("[A-Za-z]{3,}").containsMatchIn(row)
                }
                else -> return true
            }
        }

        /** Финальная проверка собранной реплики перед чтением. */
        fun isMeaningful(text: String, language: String): Boolean {
            if (text.isBlank()) return false
            if (!matchesLanguage(text, language)) return false
            // Служебная навигация сайта: «— том 1 глава 1 →», «том 1 глава 1»,
            // «← том 3 глава 5 →». Это шапка/футер манга-ридера, а не реплика;
            // раньше авточтение постоянно зачитывало эти подписи и стрелки.
            if (isSiteChromeNoise(text)) return false
            val words = text.split(Regex("\\s+"))
            return words.any { w -> w.count { it.isLetter() } >= 3 } ||
                // Короткие настоящие русские слова (я, и, но, не…) — читаем.
                (language == "ru" && words.any { isShortRussianWord(it) }) ||
                // …или короткая осмысленная («Да!», «Ах!», «Нет?»)
                (text.length in 2..6 && text.count { it.isLetter() } >= 2)
        }

        /**
         * Служебная навигация манга-ридера, которую авточтение не должно
         * зачитывать: подписи «том N глава N», счётчик страниц «N/M» и
         * строки, состоящие только из стрелок/декора (нет букв).
         * Возвращает true, если текст — это навигация, а не реплика.
         */
        fun isSiteChromeNoise(text: String): Boolean {
            val t = text.trim()
            if (t.isBlank()) return false
            val letters = t.count { it.isLetter() }
            if (letters > 0) {
                // «— том 1 глава 1 →», «том 1, глава 2», «← том 3 глава 5 →».
                // Отсекаем только если после удаления подписи остаётся мало
                // настоящих букв (это навигация, а не реплика с упоминанием тома).
                val chapter = Regex("том(а|у|е)?[\\s\\d.,-]*глава[\\s\\d.,-]*", RegexOption.IGNORE_CASE)
                if (chapter.containsMatchIn(t)) {
                    val left = chapter.replace(t, "")
                        .replace(Regex("[→←⇐⇒↔—–-|/\\\\\\d\\s,.\\(\\)«»\\[\\]\\\"']"), "")
                    if (left.count { it.isLetter() } <= 3) return true
                }
                // Оглавление ридера «Том 1», «Т. 12», «Том 2, глава 3» без реплики.
                if (Regex("^том(а|у|е)?\\.?\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
                    t.count { it.isDigit() } >= 1 && letters <= 6) return true
                // Счётчик страниц «N / M», «2/89», «стр. 2 из 89».
                if (Regex("^\\s*[\\d.,\\s]+\\s*(/|из|оф)\\s*[\\d.,\\s]+\\s*$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(t)) {
                    return true
                }
            }
            // Строка без букв (стрелки, точки, палки, цифры) — не реплика.
            if (letters == 0) {
                return t.any { it in "→←⇐⇒↔<|/\\·•⭐❤⟩⟨" } || t.count { it.isDigit() } >= 2
            }
            return false
        }

        /**
         * Определение языка текста по алфавиту. Реплика проходит фильтр,
         * если ≥60% её букв принадлежат целевому алфавиту.
         */
        fun matchesLanguage(text: String, language: String): Boolean {
            if (language == "any") return true
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return false
            val matching = letters.count { ch ->
                when (language) {
                    "ru" -> ch in '\u0400'..'\u04FF'
                    "en" -> ch in 'a'..'z' || ch in 'A'..'Z'
                    "ja" -> ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' || ch in '\u31F0'..'\u31FF'
                    "ko" -> ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF'
                    "zh" -> ch in '\u4E00'..'\u9FFF'
                    else -> true
                }
            }
            return matching.toFloat() / letters.length >= 0.6f
        }
    }
}
