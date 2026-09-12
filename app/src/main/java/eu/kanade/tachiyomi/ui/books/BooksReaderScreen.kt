package eu.kanade.tachiyomi.ui.books

import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.books.BookParser
import eu.kanade.tachiyomi.data.books.BooksStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Простой читатель электронных книг с авточтением (TTS) и
 * восстановлением последнего места чтения.
 *
 * @param bookFileName Имя файла книги (относительно каталога books/).
 * @param title Название произведения для отображения в шапке.
 */
data class BooksReaderScreen(
    val bookFileName: String,
    val title: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        var loading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var chapterTexts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var currentChapterIndex by remember { mutableIntStateOf(0) }
        var currentSentenceIndex by remember { mutableIntStateOf(0) }
        var isPlaying by remember { mutableStateOf(false) }
        // Свои настройки озвучки книг (экран «Озвучка книг»): читаем префы
        // при входе, изменения слайдеров пишем обратно.
        val bookPrefs = remember { Injekt.get<OcrPreferences>() }
        var speechRate by remember { mutableFloatStateOf(bookPrefs.bookSpeechRate().get()) }
        var pitch by remember { mutableFloatStateOf(bookPrefs.bookSpeechPitch().get()) }
        var showSettings by remember { mutableStateOf(false) }
        var showChapterList by remember { mutableStateOf(false) }
        var availableVoiceNames by remember { mutableStateOf<List<String>>(emptyList()) }
        var selectedVoiceIndex by remember { mutableIntStateOf(0) }
        val tts = remember { mutableStateOf<TextToSpeech?>(null) }

        // Загрузка и парсинг
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = BooksStore.booksDirectory(context) ?: throw IllegalStateException("Нет каталога книг")
                    val bookFile = dir.findFile(bookFileName)
                        ?: throw IllegalStateException("Файл книги не найден")
                    val parsed = BookParser.parse(bookFile)
                    val saved = BooksStore.load(context, bookFile)
                    withContext(Dispatchers.Main) {
                        chapterTexts = parsed.chapters
                        currentChapterIndex = saved.chapter.coerceIn(0, parsed.chapters.lastIndex.coerceAtLeast(0))
                        val totalSentences = splitSentences(
                            parsed.chapters.getOrNull(currentChapterIndex)?.second.orEmpty(),
                        ).size
                        currentSentenceIndex = saved.sentence.coerceIn(0, totalSentences.coerceAtLeast(1) - 1)
                        loading = false
                    }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "BooksReader: ошибка чтения $bookFileName" }
                    withContext(Dispatchers.Main) {
                        errorMessage = e.message ?: "Не удалось прочитать книгу"
                        loading = false
                    }
                }
            }
        }

        // TTS init
        LaunchedEffect(Unit) {
            tts.value = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    logcat(LogPriority.INFO) { "BooksReader: TTS init OK" }
                } else {
                    logcat(LogPriority.WARN) { "BooksReader: TTS init failed: $status" }
                }
            }
        }
        LaunchedEffect(tts.value) {
            val engine = tts.value ?: return@LaunchedEffect
            val voices = engine.voices
                ?.filter { v -> v.locale.language in listOf("ru", "en") }
                ?.sortedBy { it.name }
                .orEmpty()
            availableVoiceNames = voices.map { "${it.name} (${it.locale.displayLanguage ?: it.locale.language})" }
            if (voices.isNotEmpty()) {
                val defaultIdx = voices.indexOfFirst { it.locale.language == "ru" }.coerceAtLeast(0)
                selectedVoiceIndex = defaultIdx
                engine.voice = voices[defaultIdx]
            }
        }

        // Авточтение
        LaunchedEffect(isPlaying, currentChapterIndex, currentSentenceIndex, speechRate, pitch, selectedVoiceIndex) {
            if (!isPlaying) return@LaunchedEffect
            val engine = tts.value ?: run { isPlaying = false; return@LaunchedEffect }
            val chapter = chapterTexts.getOrNull(currentChapterIndex)
                ?: run { isPlaying = false; return@LaunchedEffect }
            val sentences = splitSentences(chapter.second)
            if (sentences.isEmpty()) {
                if (currentChapterIndex < chapterTexts.lastIndex) {
                    currentChapterIndex++; currentSentenceIndex = 0
                } else {
                    isPlaying = false
                }
                return@LaunchedEffect
            }
            if (currentSentenceIndex >= sentences.size) {
                if (currentChapterIndex < chapterTexts.lastIndex) {
                    currentChapterIndex++; currentSentenceIndex = 0
                } else {
                    isPlaying = false
                }
                return@LaunchedEffect
            }
            val voices = engine.voices
            if (voices != null && selectedVoiceIndex < voices.size) {
                engine.voice = voices.elementAt(selectedVoiceIndex)
            }
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)
            engine.speak(
                sentences[currentSentenceIndex],
                TextToSpeech.QUEUE_FLUSH,
                null,
                "book_${currentChapterIndex}_$currentSentenceIndex",
            )
            while (engine.isSpeaking) delay(150)
            currentSentenceIndex++
        }

        // Сохранение прогресса
        val bookFile = remember {
            val dir = BooksStore.booksDirectory(context)
            dir?.findFile(bookFileName)
        }
        LaunchedEffect(bookFileName, currentChapterIndex, currentSentenceIndex, chapterTexts.size) {
            if (chapterTexts.isEmpty() || loading) return@LaunchedEffect
            val bk = bookFile ?: return@LaunchedEffect
            val totalSentencesPerChapter = chapterTexts.map { splitSentences(it.second).size }
            val total = totalSentencesPerChapter.sum().coerceAtLeast(1)
            val consumedBefore = totalSentencesPerChapter.subList(0, currentChapterIndex).sum() + currentSentenceIndex
            val percent = ((consumedBefore.toLong() * 100) / total).toInt().coerceIn(0, 100)
            BooksStore.save(context, bk, BooksStore.Snapshot(currentChapterIndex, currentSentenceIndex, percent))
        }

        // Очистка при закрытии. Прогресс уже сохраняется LaunchedEffect'ом
        // на каждый переход по главам/предложениям, здесь — только TTS.
        DisposableEffect(Unit) {
            onDispose {
                tts.value?.stop()
                tts.value?.shutdown()
            }
        }

        // ---------- UI ----------

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Загрузка…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return
        }

        errorMessage?.let { msg ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Ошибка",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                }
            }
            return
        }

        if (chapterTexts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет текста для чтения", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        val currentText = chapterTexts.getOrNull(currentChapterIndex)?.second.orEmpty()
        val sentences = splitSentences(currentText)
        val scrollState = rememberScrollState()

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = chapterTexts.getOrNull(currentChapterIndex)?.first ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        tts.value?.stop()
                        navigator.pop()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showChapterList = !showChapterList }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Главы")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                },
            )

            AnimatedVisibility(visible = showChapterList) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    itemsIndexed(chapterTexts) { index, (name, _) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentChapterIndex = index
                                    currentSentenceIndex = 0
                                    showChapterList = false
                                }
                                .background(
                                    if (index == currentChapterIndex)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp),
                ) {
                    Text(
                        "Скорость: x${String.format("%.1f", speechRate)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = speechRate,
                        onValueChange = {
                            speechRate = it
                            bookPrefs.bookSpeechRate().set(it)
                        },
                        valueRange = 0.5f..3.0f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Высота: ${String.format("%.1f", pitch)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pitch,
                        onValueChange = {
                            pitch = it
                            bookPrefs.bookSpeechPitch().set(it)
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (availableVoiceNames.isNotEmpty()) {
                        Text(
                            "Голос (${selectedVoiceIndex + 1}/${availableVoiceNames.size})",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            availableVoiceNames.take(6).forEachIndexed { idx, name ->
                                Text(
                                    text = name.take(18),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .background(
                                            if (idx == selectedVoiceIndex)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                        )
                                        .clickable {
                                            selectedVoiceIndex = idx
                                            val engine = tts.value
                                            val voices = engine?.voices
                                            if (voices != null && idx < voices.size) {
                                                engine.voice = voices.elementAt(idx)
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
            ) {
                if (sentences.isNotEmpty()) {
                    Text(
                        text = buildAnnotatedText(sentences, currentSentenceIndex),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 18.sp,
                            lineHeight = 28.sp,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "Глава пуста",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }

            if (sentences.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { currentSentenceIndex.toFloat() / sentences.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Глава ${currentChapterIndex + 1}/${chapterTexts.size} · " +
                        "Предложение ${currentSentenceIndex + 1}/${sentences.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (currentChapterIndex > 0) {
                        currentChapterIndex--; currentSentenceIndex = 0
                    }
                }) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Предыдущая глава")
                }
                IconButton(
                    onClick = {
                        if (isPlaying) { tts.value?.stop(); isPlaying = false }
                        else isPlaying = true
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Читать",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = {
                    if (currentChapterIndex < chapterTexts.lastIndex) {
                        currentChapterIndex++; currentSentenceIndex = 0
                    }
                }) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Следующая глава")
                }
            }
        }
    }

    private fun splitSentences(text: String): List<String> {
        return text
            .replace(Regex("\n{2,}"), "\n")
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun buildAnnotatedText(sentences: List<String>, currentIndex: Int): String {
        return sentences.mapIndexed { idx, s ->
            if (idx == currentIndex) "▸ $s" else s
        }.joinToString("  ")
    }
}