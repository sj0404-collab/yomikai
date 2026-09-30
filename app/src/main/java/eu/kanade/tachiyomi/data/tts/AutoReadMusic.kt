package eu.kanade.tachiyomi.data.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.data.books.BookAmbience
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.math.abs

/**
 * Фоновая музыка манга-авточтения — порт той же фичи, что уже работает в
 * читалке книг. Треков в проекте нет: петли процедурные, их синтезирует
 * [BookAmbience] в кэш (`book_ambience/amb_<mood>.wav`), поэтому здесь только
 * владелец MediaPlayer и логика громкости.
 *
 * Отличия от читалки книг:
 *
 *  * **Речь и музыка звучат одновременно.** Реплики манги короткие и идут
 *    плотно, пауз между ними почти нет, поэтому на время произнесения музыка
 *    приглушается ([setSpeaking]) и плавно возвращается в паузах — иначе голос
 *    тонет в аккордах.
 *  * **Громкость меняется лесенкой.** Мгновенный setVolume на границе фразы
 *    слышен как щелчок, поэтому шаг [RAMP_STEP] раз в [RAMP_STEP_MS] мс до
 *    целевого значения.
 *  * **Движок зовёт с Dispatchers.IO.** MediaPlayer создаётся и используется
 *    только в потоке с Looper, поэтому всё, что его трогает, выполняется на
 *    главном (см. [onMain]); тяжёлый синтез WAV уходит на IO.
 *
 * Намеренно НЕ ходит в AutoReadEngine: движок сейчас правится параллельно.
 * Точки вызова — [start] в начале чтения кадра, [setSpeaking] в колбэке
 * `TtsSpeaker.speakAs`, [pause] в `stop()` движка и `stopAutoReadLoop()`
 * читалки, [release] в `finally` кадра и в `onDestroy` сервиса/activity.
 *
 * Владелец — синглтон намеренно: движок создаётся в трёх местах
 * (ReaderActivity, OcrOverlayService, BrowserTab), и общий владелец не даёт
 * каждому экземпляру завести свою 14-секундную петлю.
 */
object AutoReadMusic {

    /** Насколько тише музыка во время произнесения реплики. */
    private const val DUCK_FACTOR = 0.32f

    /** Шаг изменения громкости при плавной подстройке. */
    private const val RAMP_STEP = 0.05f

    /** Пауза между шагами подстройки, мс. */
    private const val RAMP_STEP_MS = 120L

    /** Порог, ниже которого разницу громкости считаем нулём. */
    private const val VOLUME_EPS = 0.005f

    /** Сколько символов текста страницы смотрим при выборе настроения. */
    private const val PAGE_TEXT_SAMPLE = 400

    /** MediaPlayer живёт на главном потоке — иначе он не примет ни одного вызова. */
    private val main = Handler(Looper.getMainLooper())

    /** Синтез WAV — тяжёлая работа, в кадре UI её делать нельзя. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Всё поле ниже — только главный поток, единственное исключение [running]:
    // его читают из произвольного потока (см. [isRunning]).

    private var player: MediaPlayer? = null
    private var loadJob: Job? = null

    /** Настроение уже загруженного трека: смена — это перезагрузка петли. */
    private var mood: BookAmbience.Mood? = null

    /** Номер последнего запроса: отсекает запоздавший ответ на смену трека. */
    private var requestId = 0

    /** Музыку просил держать движок (в отличие от настройки и паузы чтения). */
    private var wanted = false

    /** Сейчас произносится реплика — музыка приглушена. */
    private var speaking = false

    /** Громкость из настроек, к которой стремимся с учётом [speaking]. */
    private var baseVolume = 0.15f

    /** Текущая громкость плеера; к [targetVolume] идёт по лесенке. */
    private var currentVolume = 0f

    @Volatile
    private var running = false

    private val ramp = object : Runnable {
        override fun run() {
            val target = targetVolume()
            val diff = target - currentVolume
            // Дошли до цели (или осталась пыль): фиксируем и не крутим дальше,
            // иначе Handler просыпался бы каждые 120 мс до конца главы.
            if (abs(diff) <= RAMP_STEP || abs(diff) <= VOLUME_EPS) {
                applyVolume(target)
                return
            }
            applyVolume(currentVolume + if (diff > 0) RAMP_STEP else -RAMP_STEP)
            main.postDelayed(this, RAMP_STEP_MS)
        }
    }

    /**
     * Включить/продолжить музыку под настроение сцены.
     *
     * Зовётся в начале чтения кадра. [chapterTitle] и [pageText] — слова, по
     * которым [BookAmbience.moodFor] выбирает петлю; если их нет, берётся
     * нейтральная. Повторный вызов с тем же настроением просто возобновляет
     * уже загруженный трек.
     */
    @JvmOverloads
    fun start(context: Context, chapterTitle: String? = null, pageText: String? = null) {
        val prefs = prefs() ?: return
        // Выключено в настройках — держим паузу, даже если движок зовёт на каждом кадре.
        val enabled = runCatching { prefs.autoReadMusicEnabled().get() }.getOrDefault(false)
        if (!enabled) {
            pause()
            return
        }
        val volume = runCatching { prefs.autoReadMusicVolume().get() }.getOrDefault(0.15f)
        val target = BookAmbience.moodFor(keywords(chapterTitle, pageText))
        val app = context.applicationContext
        onMain { request(target, volume, app) }
    }

    /** Пауза с сохранением трека в памяти: возобновление мгновенное. */
    fun pause() {
        onMain {
            // Движок зовёт паузу на каждом кадре выключенной музыки — пустой
            // заход в очередь главного тут только добавляет работы.
            if (!wanted && player == null && loadJob == null) return@onMain
            wanted = false
            running = false
            loadJob?.cancel()
            loadJob = null
            main.removeCallbacks(ramp)
            val p = player ?: return@onMain
            runCatching { if (p.isPlaying) p.pause() }
                .onFailure { logcat(LogPriority.WARN, it) { "AutoReadMusic: pause failed" } }
        }
    }

    /**
     * Сообщить, что идёт речь: музыка плавно уходит на [DUCK_FACTOR] от
     * базовой громкости. Зовётся из колбэка `TtsSpeaker.speakAs`.
     */
    fun setSpeaking(value: Boolean) {
        onMain {
            if (speaking == value) return@onMain
            speaking = value
            scheduleRamp()
        }
    }

    /** Новая базовая громкость (слайдер в настройках озвучки). */
    fun setVolume(volume: Float) {
        onMain {
            baseVolume = volume.coerceIn(0f, 1f)
            scheduleRamp()
        }
    }

    /**
     * Полное освобождение: без него MediaPlayer держит нативный аудиопоток
     * до самой смерти процесса. Звать при смерти сервиса/activity и в `finally`
     * движка.
     */
    fun release() {
        onMain {
            wanted = false
            speaking = false
            running = false
            loadJob?.cancel()
            loadJob = null
            main.removeCallbacks(ramp)
            val p = player
            player = null
            mood = null
            requestId++
            currentVolume = 0f
            runCatching { p?.release() }
                .onFailure { logcat(LogPriority.WARN, it) { "AutoReadMusic: release failed" } }
        }
    }

    /** Играет ли музыка прямо сейчас (индикатор/проверки, поток не важен). */
    fun isRunning(): Boolean = running

    // ---- внутреннее ----

    /**
     * MediaPlayer можно трогать только из потока, в котором он создан, то есть
     * из главного. Движок зовёт с IO, поэтому либо выполняем сразу, либо
     * перекладываем в очередь главного.
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post { block() }
    }

    private fun request(target: BookAmbience.Mood, volume: Float, app: Context) {
        wanted = true
        baseVolume = volume.coerceIn(0f, 1f)
        val id = ++requestId
        if (player != null && mood == target) {
            resume()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            // fileFor кэширует: первый кадр платит за синтез, дальше — только чтение.
            val file = BookAmbience.fileFor(app, target) ?: return@launch
            main.post { open(file, target, id) }
        }
    }

    private fun open(file: File, target: BookAmbience.Mood, id: Int) {
        // Пока файл готовился, чтение могло остановиться или сменить настроение.
        if (!wanted || id != requestId) return
        val opened = runCatching {
            val p = player ?: MediaPlayer().also { player = it }
            // reset обязателен и до isLooping: смена настроения = другой файл,
            // а setDataSource на уже подготовленном плеере без сброса не пройдёт.
            p.reset()
            p.setDataSource(file.absolutePath)
            p.isLooping = true
            p.setVolume(currentVolume, currentVolume)
            p.prepare()
            p.start()
            mood = target
            running = true
        }
        if (opened.isFailure) {
            // Битый кэш или отказ аудиостека: лучше тишина, чем новый MediaPlayer
            // и prepare() на каждом кадре до конца главы.
            logcat(LogPriority.WARN, opened.exceptionOrNull()) { "AutoReadMusic: open failed" }
            release()
            return
        }
        scheduleRamp()
    }

    private fun resume() {
        val p = player ?: return
        val started = runCatching {
            if (!p.isPlaying) p.start()
        }
        if (started.isFailure) {
            logcat(LogPriority.WARN, started.exceptionOrNull()) { "AutoReadMusic: resume failed" }
            return
        }
        running = true
        scheduleRamp()
    }

    private fun targetVolume(): Float = baseVolume * if (speaking) DUCK_FACTOR else 1f

    private fun applyVolume(value: Float) {
        currentVolume = value.coerceIn(0f, 1f)
        val p = player ?: return
        runCatching { p.setVolume(currentVolume, currentVolume) }
    }

    /** Перезапустить лесенку с текущей позиции, не плодя копий Runnable. */
    private fun scheduleRamp() {
        main.removeCallbacks(ramp)
        if (!wanted || player == null) return
        main.post(ramp)
    }

    private fun prefs(): OcrPreferences? = runCatching { Injekt.get<OcrPreferences>() }.getOrNull()

    /**
     * Ключевые слова сцены. Отдельного списка «слов сцены» в авточтении нет,
     * поэтому настроение выводится из заголовка главы и текста кадра — того
     * же, по чему движок уже решает, что и как произносить.
     */
    private fun keywords(chapterTitle: String?, pageText: String?): String? = buildString {
        chapterTitle?.takeIf { it.isNotBlank() }?.let { append(it) }
        pageText?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append(' ')
            append(it.take(PAGE_TEXT_SAMPLE))
        }
    }.takeIf { it.isNotBlank() }
}
