package eu.kanade.tachiyomi.ui.koma

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Голос авточтения KOMA-читалки.
 *
 * Возможности взяты из прежней читалки: список системных русских голосов
 * (`VoiceHelper`), выбор голоса, скорость речи, ожидание окончания реплики по
 * колбэку. Ничего не угадывается по длине строки — ждём UtteranceProgressListener.
 */
class KomaTts(private val context: Context) {

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var note: String? = null
        private set

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val localIndex = ConcurrentHashMap<String, Int>()
    /** Колбэк «реплика реально началась» — приходит из потока синтезатора. */
    @Volatile
    private var lineStarted: ((Int) -> Unit)? = null

    private val ready = CompletableDeferred<Boolean>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            runCatching {
                val result = tts.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    note = "Русский голос не установлен в системе"
                } else {
                    available = true
                    // Речь помечаем как медиа-поток: иначе часть прошивок
                    // считает её уведомлением и приглушает в конце фразы.
                    runCatching {
                        tts.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                    }
                    applySavedVoice()
                    tts.setSpeechRate(prefs.getFloat(KEY_RATE, 1f))
                }
            }.onFailure { note = it.message }
        } else {
            note = "Синтезатор речи недоступен на этом устройстве"
        }
        ready.complete(available)
    }.apply {
        setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                // Реплика ФАКТИЧЕСКИ началась. Раньше onStart ничего не делал, а
                // «текущая строка» объявлялась в момент постановки всех реплик в
                // очередь — подсветка перескакивала через весь кадр, автопрокрутка
                // дёргалась, и страница листалась раньше, чем голос дочитал.
                override fun onStart(utteranceId: String?) {
                    val index = utteranceId?.let { localIndex[it] } ?: return
                    lineStarted?.invoke(index)
                }
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }
            },
        )
    }

    suspend fun ensureReady(): Boolean = withTimeoutOrNull(8_000) { ready.await() } ?: available

    /** Русские голоса системы: сюда попадают Google, RHVoice и голоса прошивки. */
    fun voices(): List<Voice> = runCatching { VoiceHelper.russianVoices(tts) }.getOrDefault(emptyList())

    /** Как голос показать в списке: RHVoice отдаёт имена, Google — коды. */
    fun label(voice: Voice): String {
        val kind = runCatching { VoiceHelper.classify(voice) }.getOrNull()
        val gender = when (kind?.name) {
            "FEMALE" -> "жен."
            "MALE" -> "муж."
            "TEEN" -> "дет."
            else -> ""
        }
        val net = if (voice.isNetworkConnectionRequired) " · сеть" else ""
        val quality = if (voice.quality >= Voice.QUALITY_HIGH) " · hi-q" else ""
        return listOfNotNull(voice.name, gender.ifBlank { null }, (net + quality).trim().ifBlank { null })
            .joinToString(" ")
    }

    fun selectedVoiceName(): String? = prefs.getString(KEY_VOICE, null)

    fun selectVoice(name: String) {
        prefs.edit().putString(KEY_VOICE, name).apply()
        runCatching {
            voices().firstOrNull { it.name == name }?.let { tts.voice = it }
        }
    }

    fun rate(): Float = prefs.getFloat(KEY_RATE, 1f)

    fun setRate(rate: Float) {
        val value = rate.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat(KEY_RATE, value).apply()
        runCatching { tts.setSpeechRate(value) }
    }

    private fun applySavedVoice() {
        val name = prefs.getString(KEY_VOICE, null) ?: return
        runCatching { voices().firstOrNull { it.name == name }?.let { tts.voice = it } }
    }

    /**
     * Озвучивает список реплик одной очередью: каждая следующая встаёт в
     * QUEUE_ADD сразу за предыдущей, поэтому между репликами нет «пузыря»
     * тишины, который появлялся, когда мы ждали каждую строку отдельно.
     * [onLineStart] вызывается, когда синтезатор реально начал реплику —
     * по нему подсвечивается текущая строка.
     */
    suspend fun speakAll(texts: List<String>, onLineStart: (Int) -> Unit): Unit = withContext(Dispatchers.IO) {
        val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !available) return@withContext
        val batch = UUID.randomUUID().toString()
        val ids = lines.indices.map { "$batch-$it" }
        localIndex.clear()
        lineStarted = onLineStart
        lines.forEachIndexed { index, line ->
            localIndex[ids[index]] = index
            val queued = runCatching {
                tts.speak(line, TextToSpeech.QUEUE_ADD, null, ids[index])
            }.getOrDefault(TextToSpeech.ERROR)
            if (queued != TextToSpeech.SUCCESS) localIndex.remove(ids[index])
        }
        val last = ids.lastOrNull() ?: return@withContext
        val done = CompletableDeferred<Unit>()
        waiting[last] = done
        try {
            // Запас по времени щедрый: на медленной речи и длинных репликах
            // прежний расчёт обрывал страницу на середине, и «слова
            // пропускались» — очередь просто не успевала дочитаться.
            withTimeoutOrNull(max(20_000, lines.sumOf { it.length } * 260L)) { done.await() }
        } finally {
            waiting.remove(last)
            localIndex.clear()
            lineStarted = null
        }
        // Хвост последней реплики. onDone у RHVoice и части движков приходит
        // раньше, чем звук реально доигран, а следующий вызов stop() обрывал
        // остаток — отсюда «в конце предложения голос затихает, слышны только
        // окончания». Небольшая пауза даёт договорить.
        delay(TAIL_GRACE_MS)
        Unit
    }


    /** Произносит строку и ждёт окончания. */
    suspend fun speak(text: String, timeoutMs: Long = 30_000): Unit = withContext(Dispatchers.IO) {
        val line = text.trim()
        if (line.isEmpty()) return@withContext
        if (!available) return@withContext
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        val queued = runCatching {
            tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            waiting.remove(id)
            return@withContext
        }
        withTimeoutOrNull(max(5_000, timeoutMs)) { done.await() }
        waiting.remove(id)
        Unit
    }

    fun stop() {
        runCatching { tts.stop() }
        waiting.values.forEach { it.complete(Unit) }
        waiting.clear()
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    private companion object {
        /** Сколько дать движку доиграть хвост последней реплики. */
        const val TAIL_GRACE_MS = 260L
        const val PREFS = "koma_tts"
        const val KEY_VOICE = "voice"
        const val KEY_RATE = "rate"
    }
}

/**
 * Раскладка страницы на реплики для авточтения.
 *
 * Прежняя читалка читала не «весь текст страницы подряд», а реплики в порядке
 * чтения: сверху вниз, внутри строки — по направлению языка. Здесь тот же
 * принцип:
 *  * регионы без кириллицы выбрасываются (звуковые эффекты катаканой и
 *    случайные латинские обрывки — «πD», «L», «T» из скриншотов);
 *  * фрагменты детектора склеиваются в строку, а строки — в блок реплики;
 *  * блоки сортируются по верхней границе, внутри — по левому (или правому)
 *    краю, поэтому порядок совпадает с тем, как текст лежит на странице;
 *  * уже прочитанное запоминается по нормализованному хэшу и не повторяется.
 */
object KomaReadingOrder {

    data class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val cyrillic = Regex("[а-яёА-ЯЁ]")
    private val letters = Regex("[\\p{L}]")

    /** Есть ли в строке кириллица: иероглифы и латиница при русском языке не читаются. */
    fun isCyrillic(text: String): Boolean {
        val all = letters.findAll(text).count()
        if (all == 0) return false
        val ru = cyrillic.findAll(text).count()
        return ru > 0 && ru * 2 >= all
    }

    /** Порядок чтения: блоки сверху вниз, внутри блока — по направлению чтения. */
    fun order(regions: List<OcrRegion>, rtl: Boolean, onlyCyrillic: Boolean): List<Line> {
        val source = regions
            .map { it.copy(text = normalize(it.text)) }
            .filter { it.text.length > 1 }
            .filter { !onlyCyrillic || isCyrillic(it.text) }
        if (source.isEmpty()) return emptyList()

        // 1. Фрагменты одной строки — в один блок: совпадает вертикальная полоса
        //    и между ними нет большого горизонтального разрыва.
        val sorted = source.sortedWith(compareBy({ it.top }, { it.left }))
        val blocks = mutableListOf<MutableList<OcrRegion>>()
        for (region in sorted) {
            // Кандидат — блок, который лежит на той же высоте и стоит ВПЛОТНУЮ по
            // горизонтали. Выбираем ближайший по зазору, а не последний
            // подходящий: раньше фрагмент мог приклеиться к чужой строке, и в
            // одной «строке» оказывались две соседние реплики — на странице это
            // выглядело как длинная тонкая полоса через весь кадр.
            var target: MutableList<OcrRegion>? = null
            var bestGap = Float.MAX_VALUE
            for (block in blocks) {
                val head = block.last()
                val lineHeight = max(head.bottom - head.top, 0.01f)
                val overlap = min(head.bottom, region.bottom) - max(head.top, region.top)
                if (overlap <= lineHeight * 0.5f) continue
                val blockLeft = block.minOf { it.left }
                val blockRight = block.maxOf { it.right }
                // Зазор считаем до ГРАНИЦЫ блока, а не до его последнего элемента.
                val gap = when {
                    region.left > blockRight -> region.left - blockRight
                    region.right < blockLeft -> region.right - blockLeft
                    else -> 0f
                }
                if (gap > lineHeight * 1.2f) continue
                if (gap < -lineHeight * 0.6f) continue
                if (gap < bestGap) {
                    bestGap = gap
                    target = block
                }
            }
            if (target != null) target += region else blocks += mutableListOf(region)
        }

        // 2. Внутри блока — по направлению чтения, текст склеиваем с переносами.
        val lines = blocks.map { block ->
            val ordered = block.sortedBy { if (rtl) -it.left else it.left }
            val text = buildString {
                ordered.forEachIndexed { index, part ->
                    if (index > 0) {
                        val previous = ordered[index - 1].text
                        // Перенос слова («ВЫСО-» + «ЧЕСТВО») — без пробела.
                        when {
                            endsWithHyphen() -> setLength(length - 1)
                            // Обрывки детектора в одну-две буквы («в», «д», «а»)
                            // — это остатки слова, а не отдельные слова.
                            part.text.length <= 2 && previous.lastOrNull()?.isLetter() == true &&
                                part.text.firstOrNull()?.isLetter() == true -> Unit
                            else -> append(' ')
                        }
                    }
                    append(part.text)
                }
            }
            Line(
                text = cleanSpeech(text),
                left = ordered.minOf { it.left },
                top = ordered.minOf { it.top },
                right = ordered.maxOf { it.right },
                bottom = ordered.maxOf { it.bottom },
            )
        }

        // 3. Строки одного облачка — в ОДНУ реплику.
        //
        // Движок отдаёт рамку на каждую строку, и без этой склейки каждая
        // строка читалась отдельной репликой: голос произносил «Я ТОЖЕ ТАК»,
        // замолкал, потом «СЧИТАЮ. ЖЕНЩИН…». На экране это выглядело как
        // отдельная рамка на каждой строке — ровно то, что видно на скриншотах.
        val bubbles = mergeBubbles(lines)

        // 4. Блоки — сверху вниз.
        return bubbles.sortedWith(compareBy({ (it.top * 200).toInt() }, { if (rtl) -it.left else it.left }))
    }

    /**
     * Склеивает строки, лежащие друг под другом внутри одного облачка.
     *
     * Условия — как у облачка на странице: строки перекрываются по горизонтали
     * (выключка по центру даёт перекрытие больше половины короткой строки) и
     * разделены зазором меньше высоты строки. Соседние облачка разделены
     * заметно большим полем, поэтому не слипаются. Вертикальные колонки
     * (японский текст) не склеиваются: они высокие и узкие.
     */
    private fun mergeBubbles(lines: List<Line>): List<Line> {
        if (lines.size <= 1) return lines
        val sorted = lines.sortedBy { it.top }
        val result = ArrayList<Line>(sorted.size)
        for (line in sorted) {
            val last = result.lastOrNull()
            if (last != null && sameBubble(last, line)) {
                result[result.lastIndex] = Line(
                    text = joinBubbleText(last.text, line.text),
                    left = min(last.left, line.left),
                    top = min(last.top, line.top),
                    right = max(last.right, line.right),
                    bottom = max(last.bottom, line.bottom),
                )
            } else {
                result += line
            }
        }
        return result
    }

    private fun sameBubble(a: Line, b: Line): Boolean {
        val aWidth = a.right - a.left
        val bWidth = b.right - b.left
        val aHeight = a.bottom - a.top
        val bHeight = b.bottom - b.top
        if (aWidth <= 0f || bWidth <= 0f || aHeight <= 0f || bHeight <= 0f) return false
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap <= 0f) return false
        if (overlap / min(aWidth, bWidth) < 0.45f) return false
        // Строки одного облачка стоят друг под другом, а не со сдвигом вбок:
        // если центры разъехались больше половины ширины — это соседние
        // облачка, и склеивать их нельзя (иначе одна рамка на две реплики).
        val centerShift = abs((a.left + a.right) / 2f - (b.left + b.right) / 2f)
        if (centerShift > max(aWidth, bWidth) * 0.5f) return false
        val gap = b.top - a.bottom
        if (gap < -aHeight * 0.4f || gap > max(aHeight, bHeight) * 0.8f) return false
        // Высокие узкие блоки — вертикальные колонки, а не строки облачка.
        if (aHeight > aWidth * 3f || bHeight > bWidth * 3f) return false
        return true
    }

    /** Перенос слова в конце строки склеивается вплотную, иначе — пробел. */
    private fun joinBubbleText(a: String, b: String): String {
        val left = a.trim()
        val right = b.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val last = left.last()
        return if (last == '-' || last == '\u2010' || last == '\u2011' ||
            last == '\u2013' || last == '\u2014' || last == '\u2015'
        ) {
            left.dropLast(1) + right
        } else {
            "$left $right"
        }
    }

    /**
     * Текст для синтезатора: дефисы-переносы внутри слова убираются полностью
     * («вы - сочест - ва» → «высочества»), а одиночные буквы, разорванные
     * пробелами («в д а - ль»), склеиваются обратно.
     */
    /**
     * Латинские буквы-двойники внутри русских слов.
     *
     * OCR часто отдаёт «бeз», «c тобой», «oн» — кириллица с латинской буквой
     * внутри. Синтезатор на таком слове сбивается и произносит его частями
     * («без» → «бе три»). Заменяем двойники, только если в слове уже есть
     * кириллица: настоящие латинские слова не трогаем.
     */
    private val lookalikes = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'c' to 'с', 'p' to 'р', 'x' to 'х',
        'y' to 'у', 'k' to 'к', 'm' to 'м', 't' to 'т', 'b' to 'в', 'h' to 'н',
        'A' to 'А', 'E' to 'Е', 'O' to 'О', 'C' to 'С', 'P' to 'Р', 'X' to 'Х',
        'Y' to 'У', 'K' to 'К', 'M' to 'М', 'T' to 'Т', 'B' to 'В', 'H' to 'Н',
    )

    private fun fixLookalikes(raw: String): String = raw.split(' ').joinToString(" ") { word ->
        val hasCyrillic = word.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        if (!hasCyrillic) return@joinToString word
        word.map { lookalikes[it] ?: it }.joinToString("")
    }

    private fun cleanSpeech(raw: String): String {
        var text = raw
            .replace(Regex("(?<=\\p{L})\\s*-\\s*(?=\\p{L})"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        text = fixLookalikes(text)
        // Склеиваем цепочки одиночных букв: «в д а ль» → «вдаль».
        repeat(3) {
            text = text.replace(Regex("(?<!\\p{L})\\p{L}(\\s+\\p{L}){1,4}(?!\\p{L})")) { match ->
                match.value.replace(" ", "")
            }
        }
        return text.trim()
    }

    private fun StringBuilder.endsWithHyphen(): Boolean =
        length > 0 && (this[length - 1] == '-' || this[length - 1] == '\u00AD')

    /** Нормализация: пробелы, переносы в конце строки, кавычки-ёлочки. */
    fun normalize(raw: String): String = raw
        .replace('\n', ' ')
        .replace('\u00AD', '-')
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^[«»\"'\\-–—•·.\\s]+"), "")
        .replace(Regex("[\\s]+$"), "")
        .trim()

    /** Ключ для «уже прочитано»: регистр, пунктуация и пробелы не важны. */
    fun key(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}

/**
 * Состояние и логика авточтения. Один объект на процесс: экран подписывается на
 * [state] (плавающая кнопка и шторка), а кнопки в уведомлении дергают
 * [pause]/[resume]/[stop] из BroadcastReceiver.
 */
object KomaAutoRead {

    /** Синтезатор текущего чтения: пауза и «пропустить» обрывают его очередь. */
    @Volatile
    private var activeTts: KomaTts? = null

    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val page: Int = 0,
        val pageCount: Int = 0,
        val currentLine: String = "",
        val spoken: List<String> = emptyList(),
        val status: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var paused = false
    private var stopped = false
    private var skipRequested = false

    /** Уже прочитанные реплики: при возврате назад не читаем второй раз. */
    private val spokenKeys = LinkedHashSet<String>()

    fun pause() {
        paused = true
        // Очередь реплик уже стоит в синтезаторе: без stop() голос дочитывал
        // страницу до конца, и «пауза» выглядела как «ничего не произошло».
        runCatching { activeTts?.stop() }
        _state.value = _state.value.copy(paused = true, status = "Пауза")
        KomaAutoReadNotifier.update(_state.value)
    }

    fun resume() {
        paused = false
        _state.value = _state.value.copy(paused = false, status = "")
        KomaAutoReadNotifier.update(_state.value)
    }

    fun togglePause() = if (paused) resume() else pause()

    fun stop() {
        stopped = true
        paused = false
        _state.value = _state.value.copy(running = false, paused = false, status = "Остановлено")
        KomaAutoReadNotifier.cancel()
    }

    fun skip() {
        skipRequested = true
        // Обрываем текущую реплику сразу, а не после конца страницы.
        runCatching { activeTts?.stop() }
    }

    /** Идёт ли реальное чтение прямо сейчас (есть живой синтезатор). */
    fun isActive(): Boolean = activeTts != null

    fun reset() {
        paused = false
        stopped = false
        skipRequested = false
        spokenKeys.clear()
        _state.value = State()
        KomaAutoReadNotifier.cancel()
    }

    /**
     * Цикл авточтения по книге, начиная со страницы [from].
     *
     * @param onPage переход на страницу (листание вьюпорта)
     * @param onResult распознанный текст страницы — экран обновляет шторку
     */
    suspend fun run(
        context: Context,
        book: Book,
        pages: List<String>,
        language: String,
        from: Int,
        tts: KomaTts,
        recognize: suspend (index: Int) -> PageOcr?,
        onPage: suspend (index: Int) -> Unit,
        onResult: (PageOcr?) -> Unit,
        onLine: (KomaReadingOrder.Line) -> Unit = {},
        /** Страница без реплик (арт, звуковые эффекты): не «перескочить», а проехать. */
        onSilentPage: suspend (index: Int) -> Unit = {},
    ) {
        stopped = false
        paused = false
        skipRequested = false
        spokenKeys.clear()
        activeTts = tts
        if (!tts.ensureReady() || !tts.available) {
            _state.value = State(status = tts.note ?: "Синтезатор речи недоступен")
            KomaAutoReadNotifier.cancel()
            return
        }
        _state.value = State(running = true, page = from, pageCount = pages.size)

        var index = from.coerceIn(0, pages.lastIndex)
        while (!stopped && index <= pages.lastIndex) {
            onPage(index)
            val result = recognize(index)
            onResult(result)
            val lines = KomaReadingOrder.order(
                regions = result?.regions.orEmpty(),
                rtl = book.mode == "rtl" && language == "Японский",
                onlyCyrillic = language == "Кириллица" || language == "Авто",
            ).filter { spokenKeys.add(KomaReadingOrder.key(it.text) + "@" + index) }

            // Страница без реплик: это не «пусто», а кадр с рисунком или
            // звуковым эффектом («ЦАП!», «ПРРР»). Раньше такая страница просто
            // пропускалась рывком — читатель терял место в главе. Теперь
            // проезжаем её плавно, как обычный кадр без озвучки.
            if (lines.isEmpty()) {
                _state.value = _state.value.copy(
                    running = true,
                    page = index,
                    pageCount = pages.size,
                    status = "Страница ${index + 1}: без реплик",
                    currentLine = "",
                )
                onSilentPage(index)
                if (stopped) break
                index++
                if (index <= pages.lastIndex) delay(120)
                continue
            }

            _state.value = _state.value.copy(
                running = true,
                page = index,
                pageCount = pages.size,
                status = "",
                spoken = emptyList(),
                currentLine = "",
            )

            _state.value = _state.value.copy(spoken = lines.map { it.text }.takeLast(24))

            // Озвучиваем страницу очередью, но с возможностью паузы и пропуска:
            // очередь прерывается, а после «продолжить» начинаем с реплики,
            // перед которой остановились.
            var startAt = 0
            while (startAt < lines.size && !stopped) {
                while (paused && !stopped) delay(120)
                if (stopped) break
                skipRequested = false
                var reached = startAt
                tts.speakAll(lines.drop(startAt).map { it.text }) { offset ->
                    val line = lines.getOrNull(startAt + offset) ?: return@speakAll
                    reached = startAt + offset
                    _state.value = _state.value.copy(currentLine = line.text)
                    onLine(line)
                }
                if (paused || skipRequested) {
                    startAt = (reached + 1).coerceAtMost(lines.size)
                    continue
                }
                break
            }
            tts.stop()
            if (stopped) break
            index++
            if (index <= pages.lastIndex) delay(150)
        }

        activeTts = null
        val finished = !stopped && index > pages.lastIndex
        _state.value = _state.value.copy(
            running = false,
            paused = false,
            currentLine = "",
            status = if (finished) "Книга прочитана до конца" else "",
        )
        KomaAutoReadNotifier.cancel()
    }
}

/**
 * Уведомление авточтения: в шторке видно, что читается сейчас, и можно
 * поставить паузу, пропустить реплику или остановить — как в прежней читалке.
 */
object KomaAutoReadNotifier {

    private const val CHANNEL = "koma_autoread"
    private const val ID = 4211

    fun update(state: KomaAutoRead.State) {
        val context = AppContextHolder.context ?: return
        runCatching {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, KomaReaderActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val body = state.spoken.joinToString("\n").ifBlank {
                if (state.status.isNotBlank()) state.status else "Готовим текст…"
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Читается глава · Страница ${state.page + 1} из ${state.pageCount}")
                .setContentText(state.currentLine.ifBlank { body.lineSequence().firstOrNull().orEmpty() })
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(
                    0,
                    if (state.paused) "Продолжить" else "Пауза",
                    action(context, if (state.paused) KomaAutoReadReceiver.RESUME else KomaAutoReadReceiver.PAUSE),
                )
                .addAction(0, "Пропустить", action(context, KomaAutoReadReceiver.SKIP))
                .addAction(0, "Остановить", action(context, KomaAutoReadReceiver.STOP))
            NotificationManagerCompat.from(context).notify(ID, builder.build())
        }
    }

    fun cancel() {
        val context = AppContextHolder.context ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    private fun action(context: Context, action: String): PendingIntent {
        val intent = Intent(context, KomaAutoReadReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Авточтение", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }
}

/** Кнопки в уведомлении: пауза/продолжить, пропустить реплику, остановить. */
class KomaAutoReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PAUSE -> KomaAutoRead.pause()
            RESUME -> KomaAutoRead.resume()
            SKIP -> KomaAutoRead.skip()
            STOP -> KomaAutoRead.stop()
        }
    }

    companion object {
        const val PAUSE = "eu.kanade.tachiyomi.KOMA_AUTOREAD_PAUSE"
        const val RESUME = "eu.kanade.tachiyomi.KOMA_AUTOREAD_RESUME"
        const val SKIP = "eu.kanade.tachiyomi.KOMA_AUTOREAD_SKIP"
        const val STOP = "eu.kanade.tachiyomi.KOMA_AUTOREAD_STOP"
    }
}

/** Контекст приложения для уведомлений из контроллера. */
object AppContextHolder {
    @Volatile
    var context: Context? = null
}
