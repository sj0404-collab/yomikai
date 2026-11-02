package eu.kanade.tachiyomi.ui.koma

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Обложка книги: обычная картинка или ПЕРВАЯ СТРАНИЦА источника.
 *
 * Страницы локальной библиотеки больше не распаковываются на диск, поэтому
 * обложка — это `koma://book/<id>/0`, а не путь к файлу. Coil такие адреса не
 * понимает, и полка показывала серые прямоугольники; здесь такая обложка
 * берётся из [KomaPageStore] уменьшенной копией и живёт только на экране.
 */
@Composable
fun KomaCover(
    book: Book,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val path = book.cover
    if (!KomaPageStore.isVirtual(path)) {
        AsyncImage(
            model = path,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }
    val context = LocalContext.current
    val loaded by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                KomaPageStore.ensure(context, KomaPageStore.keyOf(path))
                KomaPageStore.cover(path)
            }.getOrNull()
        }
    }
    val bitmap = loaded
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
