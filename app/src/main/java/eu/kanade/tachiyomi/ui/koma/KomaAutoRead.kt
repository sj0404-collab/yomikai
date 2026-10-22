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
                override fun onStart(utteranceId: String?) = Unit
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
            val target = blocks.lastOrNull { block ->
                val head = block.last()
                val lineHeight = max(head.bottom - head.top, 0.01f)
                val overlap = min(head.bottom, region.bottom) - max(head.top, region.top)
                val sameBand = overlap > lineHeight * 0.5f
                val gap = region.left - head.right
                sameBand && gap <= lineHeight * 1.2f && gap > -1f
            }
            if (target != null) target += region else blocks += mutableListOf(region)
        }

        // 2. Внутри блока — по направлению чтения, текст склеиваем с переносами.
        val lines = blocks.map { block ->
            val ordered = block.sortedBy { if (rtl) -it.left else it.left }
            val text = buildString {
                ordered.forEachIndexed { index, part ->
                    if (index > 0) {
                        // Перенос слова («ВЫСО-» + «ЧЕСТВО») — без пробела.
                        if (endsWithHyphen()) setLength(length - 1) else append(' ')
                    }
                    append(part.text)
                }
            }
            Line(
                text = text.trim(),
                left = ordered.minOf { it.left },
                top = ordered.minOf { it.top },
                right = ordered.maxOf { it.right },
                bottom = ordered.maxOf { it.bottom },
            )
        }

        // 3. Блоки — сверху вниз.
        return lines.sortedWith(compareBy({ (it.top * 200).toInt() }, { if (rtl) -it.left else it.left }))
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
    }

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
    ) {
        stopped = false
        paused = false
        skipRequested = false
        spokenKeys.clear()
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

            _state.value = _state.value.copy(
                running = true,
                page = index,
                pageCount = pages.size,
                status = if (lines.isEmpty()) "На этой странице текста нет" else "",
                spoken = emptyList(),
                currentLine = "",
            )

            for (line in lines) {
                if (stopped) break
                while (paused && !stopped) delay(120)
                if (stopped) break
                if (skipRequested) {
                    skipRequested = false
                    tts.stop()
                    break
                }
                val spoken = _state.value.spoken + line.text
                _state.value = _state.value.copy(
                    currentLine = line.text,
                    spoken = spoken.takeLast(24),
                    status = "",
                )
                KomaAutoReadNotifier.update(_state.value)
                tts.speak(line.text)
            }
            tts.stop()
            if (stopped) break
            index++
            if (index <= pages.lastIndex) delay(150)
        }

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
