package eu.kanade.tachiyomi.ui.videoplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoPlayerScreen(
    val videoUrl: String,
    val title: String = "",
) : Screen {

    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val activity = context as ComponentActivity

        var isPlaying by remember { mutableStateOf(false) }
        var playbackPosition by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var isBuffering by remember { mutableStateOf(true) }
        var hasError by remember { mutableStateOf<String?>(null) }
        var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
        var showControls by remember { mutableStateOf(true) }

        val exoPlayer = remember {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1.0f
            }
        }

        // Скрытие системных UI
        LaunchedEffect(Unit) {
            @Suppress("DEPRECATION")
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = activity.window
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        // Загрузка видео
        LaunchedEffect(videoUrl) {
            hasError = null
            isBuffering = true
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        // Обновление позиции
        LaunchedEffect(exoPlayer) {
            while (true) {
                kotlinx.coroutines.delay(500)
                try {
                    if (exoPlayer.isPlaying) {
                        playbackPosition = exoPlayer.currentPosition
                        duration = exoPlayer.duration.coerceAtLeast(0)
                    }
                } catch (_: Exception) { }
            }
        }

        // Слушатель плеера
        LaunchedEffect(exoPlayer) {
            exoPlayer.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) {
                        isBuffering = false
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        isPlaying = false
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    hasError = error.message ?: "Ошибка воспроизведения"
                    isBuffering = false
                    logcat(LogPriority.ERROR) { "Video player error: ${error.message}" }
                }
            })
        }

        // Освобождение + восстановление ориентации
        DisposableEffect(Unit) {
            onDispose {
                exoPlayer.release()
                @Suppress("DEPRECATION")
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // PiP
        fun enterPip() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                activity.enterPictureInPictureMode(params)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // Видео
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Буферизация
            if (isBuffering && hasError == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    color = Color.White,
                )
            }

            // Ошибка
            if (hasError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Ошибка: $hasError",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // Верхняя панель
            if (showControls && hasError == null) {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            @Suppress("DEPRECATION")
                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            navigator.pop()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { enterPip() }) {
                            Icon(
                                Icons.Outlined.PictureInPicture,
                                contentDescription = "PiP",
                                tint = Color.White,
                            )
                        }
                        // Скорость
                        Text(
                            text = "x${playbackSpeed}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .background(
                                    Color.White.copy(alpha = 0.2f),
                                    shape = MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f),
                )
            }

            // Нижняя панель
            if (showControls && hasError == null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column {
                        Slider(
                            value = if (duration > 0) playbackPosition.toFloat() / duration else 0f,
                            onValueChange = { ratio ->
                                val newPos = (ratio * duration).toLong()
                                exoPlayer.seekTo(newPos)
                                playbackPosition = newPos
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Text(
                            text = "${formatTime(playbackPosition)} / ${formatTime(duration)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Tap to toggle controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val sdf = SimpleDateFormat("mm:ss", Locale.getDefault())
    return sdf.format(Date(ms))
}
