package eu.kanade.tachiyomi.ui.audiobookplayer

import android.net.Uri
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран аудиокниги (MP3/OGG/ etc).
 *
 * @param tracks Список пар (название_дорожки, url)
 * @param initialTrackIndex Индекс начальной дорожки
 * @param title Название аудиокниги
 */
data class AudiobookPlayerScreen(
    val tracks: List<Pair<String, String>>,
    val initialTrackIndex: Int = 0,
    val title: String = "",
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        var currentTrackIndex by remember {
            mutableIntStateOf(initialTrackIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0)))
        }
        var isPlaying by remember { mutableStateOf(false) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
        var repeatMode by remember { mutableIntStateOf(0) } // 0=off, 1=all, 2=one
        var showTrackList by remember { mutableStateOf(false) }
        var hasError by remember { mutableStateOf<String?>(null) }

        val exoPlayer = remember {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
        }

        // Загрузка дорожки
        LaunchedEffect(currentTrackIndex) {
            val track = tracks.getOrNull(currentTrackIndex) ?: return@LaunchedEffect
            hasError = null
            val mediaItem = MediaItem.fromUri(Uri.parse(track.second))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = isPlaying
        }

        // Обновление позиции
        LaunchedEffect(exoPlayer) {
            while (true) {
                kotlinx.coroutines.delay(500)
                try {
                    if (exoPlayer.isPlaying) {
                        position = exoPlayer.currentPosition
                        duration = exoPlayer.duration.coerceAtLeast(0)
                    }
                } catch (_: Exception) { }
            }
        }

        // Слушатель
        LaunchedEffect(exoPlayer) {
            exoPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        when (repeatMode) {
                            2 -> { // Repeat one
                                exoPlayer.seekTo(0)
                                exoPlayer.playWhenReady = true
                            }
                            1 -> { // Repeat all
                                if (currentTrackIndex < tracks.lastIndex) {
                                    currentTrackIndex++
                                } else {
                                    currentTrackIndex = 0
                                }
                            }
                            0 -> { // No repeat
                                if (currentTrackIndex < tracks.lastIndex) {
                                    currentTrackIndex++
                                } else {
                                    isPlaying = false
                                }
                            }
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    hasError = error.message ?: "Ошибка воспроизведения"
                    logcat(LogPriority.ERROR) { "Audiobook player error: ${error.message}" }
                }
            })
        }

        DisposableEffect(Unit) {
            onDispose {
                exoPlayer.release()
            }
        }

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет аудиодорожек", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        val listState = rememberLazyListState()

        Column(modifier = Modifier.fillMaxSize()) {
            // TopBar
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = tracks.getOrNull(currentTrackIndex)?.first ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        exoPlayer.stop()
                        navigator.pop()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showTrackList = !showTrackList }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Дорожки")
                    }
                },
            )

            // Список дорожек
            if (showTrackList) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    itemsIndexed(tracks) { index, (name, _) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentTrackIndex = index
                                    isPlaying = true
                                    showTrackList = false
                                }
                                .background(
                                    if (index == currentTrackIndex)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (index == currentTrackIndex && isPlaying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
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

            Spacer(modifier = Modifier.weight(1f))

            // Ошибка
            if (hasError != null) {
                Text(
                    text = "Ошибка: $hasError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Прогресс
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { ratio ->
                        val newPos = (ratio * duration).toLong()
                        exoPlayer.seekTo(newPos)
                        position = newPos
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Управление
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Repeat
                IconButton(onClick = {
                    repeatMode = (repeatMode + 1) % 3
                    exoPlayer.repeatMode = when (repeatMode) {
                        1 -> Player.REPEAT_MODE_ALL
                        2 -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        imageVector = when (repeatMode) {
                            2 -> Icons.Outlined.RepeatOne
                            else -> Icons.Outlined.Repeat
                        },
                        contentDescription = when (repeatMode) {
                            0 -> "Без повтора"
                            1 -> "Повтор all"
                            else -> "Повтор одна"
                        },
                        tint = if (repeatMode > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Prev
                IconButton(
                    onClick = {
                        if (currentTrackIndex > 0) {
                            currentTrackIndex--
                            isPlaying = true
                        }
                    },
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Предыдущая", modifier = Modifier.size(28.dp))
                }

                // Play/Pause
                IconButton(
                    onClick = {
                        if (isPlaying) {
                            exoPlayer.playWhenReady = false
                            isPlaying = false
                        } else {
                            exoPlayer.playWhenReady = true
                            isPlaying = true
                        }
                    },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                        modifier = Modifier.size(40.dp),
                    )
                }

                // Next
                IconButton(
                    onClick = {
                        if (currentTrackIndex < tracks.lastIndex) {
                            currentTrackIndex++
                            isPlaying = true
                        }
                    },
                ) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Следующая", modifier = Modifier.size(28.dp))
                }

                // Speed
                IconButton(onClick = {
                    playbackSpeed = when {
                        playbackSpeed < 1.0f -> 1.0f
                        playbackSpeed < 1.5f -> 1.5f
                        playbackSpeed < 2.0f -> 2.0f
                        else -> 0.75f
                    }
                    exoPlayer.setPlaybackSpeed(playbackSpeed)
                }) {
                    Text(
                        text = "x${playbackSpeed}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (playbackSpeed != 1.0f) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Трек из списка
            Text(
                text = "Дорожка ${currentTrackIndex + 1} из ${tracks.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", m, s)
    }
}
