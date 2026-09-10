package eu.kanade.tachiyomi.ui.videoplayer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cafe.adriel.voyager.core.screen.Screen
import java.io.Serializable

data class VideoPlayerScreen(
    val videoUrl: String,
    val title: String = "",
) : Screen, Serializable {

    @Composable
    override fun Content() {
        VideoPlayerContent(
            videoUrl = videoUrl,
            title = title,
            onDismiss = {},
        )
    }
}

@Composable
fun VideoPlayerContent(
    videoUrl: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    exoPlayer.release()
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.Center),
                update = { view ->
                    view.player = exoPlayer
                },
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
