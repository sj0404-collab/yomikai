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

    /** Порядок чтения: панель за панелью, внутри панели — строки сверху вниз. */
    fun order(
        regions: List<OcrRegion>,
        rtl: Boolean,
        onlyCyrillic: Boolean,
        /** Панели страницы в порядке чтения (см. [KomaPanels]); пусто — рамок нет. */
        panels: List<KomaPanels.Box> = emptyList(),
    ): List<Line> {
        val source = regions
            // Латинские двойники чиним ДО фильтра по языку: иначе фрагмент с
            // одной-двумя латинскими буквами («He6лuцa») отбрасывался целиком,
            // и слова пропадали из озвучки.
            .map {
                val text = normalize(it.text)
                val fixed = fixLookalikes(text)
                // Строка из одних двойников («OHO», «4YBC», «PA3A») по-русски не
                // выглядит, и фильтр языка её терял. Переводим принудительно —
                // но только когда читаем кириллицу: на английской странице
                // латинские строки должны остаться латиницей.
                if (onlyCyrillic && !isCyrillic(fixed) && KomaText.isLookalikeOnly(fixed)) {
                    it.copy(text = KomaText.fixLookalikesForced(fixed))
                } else {
                    it.copy(text = fixed)
                }
            }
            // Фрагмент из одной буквы — это часть слова, а не мусор: раньше
            // такие отбрасывались, и слова теряли буквы («Небылица» → «Небца»).
            .filter { it.text.any { ch -> ch.isLetterOrDigit() } }
            .filter { !onlyCyrillic || isCyrillic(it.text) }
        if (source.isEmpty()) return emptyList()

        // Панели разбираются ПО ОЧЕРЕДИ. Раньше всё сортировалось по высоте
        // («лесенкой»): две панели, стоящие рядом, читались вперемешку —
        // верхняя строка левой, верхняя строка правой, вторая строка левой…
        // Теперь страница режется на панели разрезами по пустым полосам, и
        // каждая панель вычитывается целиком, прежде чем начаться следующая.
        // Порядок чтения идёт ПО ПАНЕЛЯМ, найденным детектором кадров: панель
        // вычитывается целиком, прежде чем начаться следующая. Доверяем
        // детектору, только если он нашёл хотя бы две панели и в них лежит
        // почти весь текст (PANEL_TRUST); иначе — прежний разбор по облачкам,
        // который работает и на страницах без рамок.
        val ordered = if (panels.size >= 2 && KomaPanels.coverage(source, panels) >= PANEL_TRUST) {
            KomaPanels.assign(source, panels).flatMap { panel -> bubblesOf(panel, rtl) }
        } else {
            splitPanels(source, rtl, depth = 0).flatMap { panel -> bubblesOf(panel, rtl) }
        }
        return ordered
            // Языковая починка — на СОБРАННОЙ реплике, а не на обрывке: модель
            // путает буквы в словах, и словарь это чинит («ПОШЕВЕАИТЬСЯ» →
            // «ПОШЕВЕЛИТЬСЯ», «СЮОДА» → «СЮДА», «ТРУ» → «ТРИ»), возвращает
            // потерянные дефисы («ЧТОТО» → «ЧТО-ТО») и склеивает слово,
            // разорванное на две строки бокса («РАССЧИ ТВВАЮ»).
            .map { it.copy(text = KomaWords.finish(it.text)) }
    }

    /** Доля страницы, ниже которой разрез не считается разрезом. */
    private const val Y_CUT = 0.022f
    private const val X_CUT = 0.035f

    /** Доля текста внутри панелей, при которой детектору кадров можно верить. */
    private const val PANEL_TRUST = 0.75f

    /**
     * Рекурсивные разрезы страницы (XY-cut).
     *
     * Сначала ищем горизонтальную пустую полосу — страница делится на верхнюю и
     * нижнюю части, верхняя читается первой. Если такой полосы нет, ищем
     * вертикальную: это панели, стоящие рядом, и для манги они читаются справа
     * налево. Внутри найденной панели разрез повторяется, пока текст не станет
     * одной связной группой.
     */
    private fun splitPanels(group: List<OcrRegion>, rtl: Boolean, depth: Int): List<List<OcrRegion>> {
        if (group.size <= 1 || depth > 12) return listOf(group)

        // Порог разреза — от реальной высоты строки на ЭТОЙ странице, а не от
        // накопленной высоты группы: раньше порог рос вместе с группой, разрез
        // переставал срабатывать, и панели снова читались «лесенкой».
        val lineHeight = group.map { it.bottom - it.top }.sorted().let { it[it.size / 2] }
            .coerceAtLeast(0.008f)
        val yCut = max(Y_CUT, lineHeight * 0.9f)
        val xCut = max(X_CUT, lineHeight * 0.9f)

        // 1. Горизонтальный разрез: ищем полосу пустоты между интервалами строк.
        val rows = mergeByAxis(group, byY = true, yCut)
        if (rows.size > 1) return rows.flatMap { splitPanels(it, rtl, depth + 1) }

        // 2. Вертикальный разрез: панели, стоящие рядом (для манги — справа налево).
        val columns = mergeByAxis(group, byY = false, xCut)
        if (columns.size > 1) {
            val ordered = if (rtl) columns.reversed() else columns
            return ordered.flatMap { splitPanels(it, rtl, depth + 1) }
        }

        return listOf(group)
    }

    /**
     * Делит группу на части по пустым полосам вдоль оси.
     *
     * Интервалы регионов сливаются ([start, end]), и там, где между ними
     * остаётся зазор больше [cut], группа режется. Это устойчивее прежнего
     * «накопления строки»: регионы на одной высоте всегда попадают в одну часть.
     */
    private fun mergeByAxis(group: List<OcrRegion>, byY: Boolean, cut: Float): List<List<OcrRegion>> {
        data class Band(val start: Float, val end: Float, val items: MutableList<OcrRegion>)

        val sorted = group.sortedBy { if (byY) it.top else it.left }
        val bands = mutableListOf<Band>()
        for (region in sorted) {
            val start = if (byY) region.top else region.left
            val end = if (byY) region.bottom else region.right
            val last = bands.lastOrNull()
            if (last != null && start - last.end <= cut) {
                bands[bands.lastIndex] = Band(last.start, max(last.end, end), last.items.apply { add(region) })
            } else {
                bands += Band(start, end, mutableListOf(region))
            }
        }
        if (bands.size <= 1) return listOf(group)
        return bands.map { it.items.toList() }
    }

    /** Реплики одной панели: фрагменты → строки → облачка, сверху вниз. */
    private fun bubblesOf(group: List<OcrRegion>, rtl: Boolean): List<Line> {
        if (group.isEmpty()) return emptyList()

        // 1. Фрагменты одной строки — в один блок: совпадает вертикальная полоса
        //    и между ними нет большого горизонтального разрыва.
        val sorted = group.sortedWith(compareBy({ it.top }, { it.left }))
        val blocks = mutableListOf<MutableList<OcrRegion>>()
        for (region in sorted) {
            // Кандидат — блок, который лежит на той же высоте и стоит ВПЛОТНУЮ по
            // горизонтали. Выбираем ближайший по зазору, а не последний
            // подходящий: раньше фрагмент мог приклеиться к чужой строке, и в
            // одной «строке» оказывались две соседние реплики.
            var target: MutableList<OcrRegion>? = null
            var bestGap = Float.MAX_VALUE
            for (block in blocks) {
                val head = block.last()
                val lineHeight = max(head.bottom - head.top, 0.01f)
                // 0.35 вместо 0.5: у рукописных реплик и строк вразнобой
                // перекрытие по высоте меньше, и фрагмент уходил в отдельную
                // «строку» — рамка на странице не покрывала всю реплику.
                val overlap = min(head.bottom, region.bottom) - max(head.top, region.top)
                if (overlap <= lineHeight * 0.35f) continue
                val blockLeft = block.minOf { it.left }
                val blockRight = block.maxOf { it.right }
                // Расстояние берётся ПО МОДУЛЮ. Раньше для фрагмента слева от
                // блока разность шла с минусом, он считался «перекрытием» и
                // приклеивался к чужой строке даже через всё поле страницы —
                // отсюда склейка соседних панелей в одну реплику.
                val gap = when {
                    region.left > blockRight -> region.left - blockRight
                    region.right < blockLeft -> blockLeft - region.right
                    else -> 0f
                }
                // Разрыв внутри строки небольшой. Раньше порог был 1.6 высоты
                // строки, и две реплики, стоящие рядом по горизонтали,
                // склеивались в одну «строку»: слова шли вперемешку
                // («ПЕПЕЛЬНЫЕ ДАВАЙТЕ, ГРЕМЛИНЫ, СКОРЕЕ…»), а часть реплик
                // пропадала. Исключение — разреженные буквы крупного заголовка:
                // короткие фрагменты на одной высоте склеиваются и через поле.
                val shortHead = head.text.count { it.isLetterOrDigit() } <= 3
                val shortRegion = region.text.count { it.isLetterOrDigit() } <= 3
                val gapLimit = if (shortHead && shortRegion && overlap >= lineHeight * 0.7f) {
                    lineHeight * 2.5f
                } else {
                    lineHeight * 0.45f
                }
                if (gap > gapLimit) continue
                if (gap < -lineHeight * 0.9f) continue
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
                        when {
                            // Перенос слова («ВЫСО-» + «ЧЕСТВО») — без пробела.
                            endsWithHyphen() -> setLength(length - 1)
                            // Обрывки в одну-две буквы — остатки слова, а не слова.
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
        val bubbles = mergeBubbles(lines)

        // 4. Внутри панели — полосами сверху вниз, внутри полосы по RTL/LTR.
        return orderBubbles(bubbles, rtl)
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
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val result = ArrayList<Line>(sorted.size)
        for (line in sorted) {
            // Строка сравнивается со ВСЕМИ уже собранными облачками, а не только
            // с последним. Когда облачка стоят рядом по горизонтали, их строки
            // идут по высоте вперемешку (верх левого, верх правого, вторая
            // строка левого…): «последнее» облачко в этот момент чужое, строка
            // уходила в новое — и реплика рвалась на куски. Берём ближайшее по
            // зазору среди подходящих.
            var bestIndex = -1
            var bestGap = Float.MAX_VALUE
            for (index in result.indices) {
                val bubble = result[index]
                if (!sameBubble(bubble, line)) continue
                val gap = line.top - bubble.bottom
                if (gap < bestGap) {
                    bestGap = gap
                    bestIndex = index
                }
            }
            if (bestIndex >= 0) {
                val bubble = result[bestIndex]
                result[bestIndex] = Line(
                    text = joinBubbleText(bubble.text, line.text),
                    left = min(bubble.left, line.left),
                    top = min(bubble.top, line.top),
                    right = max(bubble.right, line.right),
                    bottom = max(bubble.bottom, line.bottom),
                )
            } else {
                result += line
            }
        }
        return absorbOrphans(result)
    }

    /**
     * Обрывок в одну-две буквы, лежащий ВНУТРИ рамки соседней реплики, —
     * это часть её слова, а не отдельная реплика. Детектор иногда отдаёт
     * «Небы» и «лица» отдельными блоками: без этой склейки слово терялось
     * («Небылица» → «небца») или читалось отдельно.
     */
    private fun absorbOrphans(bubbles: List<Line>): List<Line> {
        if (bubbles.size <= 1) return bubbles
        val alive = bubbles.toMutableList()
        var pass = 0
        while (pass++ < 4) {
            var merged = false
            for (orphan in alive.toList()) {
                if (orphan.text.count { it.isLetterOrDigit() } > 3) continue
                val cx = (orphan.left + orphan.right) / 2f
                val cy = (orphan.top + orphan.bottom) / 2f
                var targetIndex = -1
                var bestDistance = Float.MAX_VALUE
                for (index in alive.indices) {
                    val bubble = alive[index]
                    if (bubble === orphan) continue
                    val padX = (bubble.right - bubble.left) * 0.12f
                    val padY = (bubble.bottom - bubble.top) * 0.35f
                    if (cx < bubble.left - padX || cx > bubble.right + padX) continue
                    if (cy < bubble.top - padY || cy > bubble.bottom + padY) continue
                    val distance = abs(cy - (bubble.top + bubble.bottom) / 2f)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        targetIndex = index
                    }
                }
                if (targetIndex < 0) continue
                val bubble = alive[targetIndex]
                val above = cy < (bubble.top + bubble.bottom) / 2f
                val text = if (above) {
                    joinBubbleText(orphan.text, bubble.text)
                } else {
                    joinBubbleText(bubble.text, orphan.text)
                }
                alive[targetIndex] = Line(
                    text = text,
                    left = min(bubble.left, orphan.left),
                    top = min(bubble.top, orphan.top),
                    right = max(bubble.right, orphan.right),
                    bottom = max(bubble.bottom, orphan.bottom),
                )
                val dropIndex = alive.indexOfFirst { it === orphan }
                if (dropIndex >= 0) alive.removeAt(dropIndex)
                merged = true
                break
            }
            if (!merged) break
        }
        return alive
    }

    /**
     * Порядок облачков внутри панели: полосами по высоте.
     *
     * Полоса — облачка, верх которых отличается не больше чем на 0.8 высоты
     * строки. Внутри полосы — по направлению чтения (справа налево), поэтому
     * две реплики одного ряда читаются по очереди, а не «лесенкой» по
     * округлённой высоте, когда верхняя строка правой опережает среднюю левой.
     */
    private fun orderBubbles(bubbles: List<Line>, rtl: Boolean): List<Line> {
        if (bubbles.size <= 1) return bubbles
        val heights = bubbles.map { it.bottom - it.top }.sorted()
        val median = heights[heights.size / 2]
        val tolerance = max(0.02f, median * 0.8f)
        val bands = mutableListOf<MutableList<Line>>()
        var bandTop = 0f
        for (bubble in bubbles.sortedBy { it.top }) {
            if (bands.isNotEmpty() && bubble.top - bandTop <= tolerance) {
                bands.last() += bubble
            } else {
                bands += mutableListOf(bubble)
                bandTop = bubble.top
            }
        }
        return bands.flatMap { band -> band.sortedBy { if (rtl) -it.left else it.left } }
    }

    private fun sameBubble(a: Line, b: Line): Boolean {
        val aWidth = a.right - a.left
        val bWidth = b.right - b.left
        val aHeight = a.bottom - a.top
        val bHeight = b.bottom - b.top
        if (aWidth <= 0f || bWidth <= 0f || aHeight <= 0f || bHeight <= 0f) return false
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap <= 0f) return false
        if (overlap / min(aWidth, bWidth) < 0.30f) return false
        // Строки одного облачка стоят друг под другом, а не со сдвигом вбок:
        // если центры разъехались больше половины ширины — это соседние
        // облачка, и склеивать их нельзя (иначе одна рамка на две реплики).
        val centerShift = abs((a.left + a.right) / 2f - (b.left + b.right) / 2f)
        if (centerShift > max(aWidth, bWidth) * 0.8f) return false
        val gap = b.top - a.bottom
        if (gap < -aHeight * 0.6f || gap > max(aHeight, bHeight) * 1.2f) return false
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
    /** Латинские, греческие и цифровые двойники — см. [KomaText.fixLookalikes]. */
    private fun fixLookalikes(raw: String): String = KomaText.fixLookalikes(raw)

    private fun cleanSpeech(raw: String): String = KomaText.cleanSpeech(raw)

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
                // Направление внутри строки — из режима чтения, а не из языка:
                // русская манга читается справа налево так же, как японская.
                // Раньше условие требовало «Японский», и в переводной манге
                // реплики одного ряда читались слева направо — «вразброс».
                rtl = book.mode == "rtl",
                onlyCyrillic = language == "Кириллица" || language == "Авто",
                panels = result?.panels.orEmpty(),
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
