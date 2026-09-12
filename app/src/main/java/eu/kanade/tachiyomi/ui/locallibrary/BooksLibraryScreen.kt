package eu.kanade.tachiyomi.ui.locallibrary

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.books.BookParser
import eu.kanade.tachiyomi.data.books.BooksStore
import eu.kanade.tachiyomi.ui.books.BooksReaderScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Экран библиотеки книг с обложками и метаданными.
 *
 * Показывает список импортированных книг (PDF, EPUB, FB2, DOCX, HTML, TXT и др.),
 * позволяет добавить новый файл (SAF picker), удалить и открыть для чтения.
 * Каждая книга отображается с обложкой (если доступна), названием из метаданных
 * и прогрессом чтения.
 */
object BooksLibraryScreen : Screen {

    private data class BookItem(
        val fileName: String,
        val displayTitle: String,
        val author: String?,
        val ext: String,
        val progressPercent: Int,
        val coverBitmap: Bitmap?,
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var importing by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var books by remember { mutableStateOf(emptyList<BookItem>()) }
        var showDeleteDialog by remember { mutableStateOf<BookItem?>(null) }

        fun refresh() {
            scope.launch {
                val items = withIOContext {
                    BooksStore.listBooks(context).map { file ->
                        val pct = BooksStore.load(context, file).percent
                        val ext = file.name.orEmpty().substringAfterLast('.', "").uppercase()
                        val metadata = BooksStore.loadMetadata(context, file)
                        val cover = BooksStore.loadCover(context, file)
                        BookItem(
                            fileName = file.name.orEmpty(),
                            displayTitle = metadata.title.ifBlank {
                                file.name.orEmpty().substringBeforeLast('.').ifBlank { "Книга" }
                            },
                            author = metadata.author,
                            ext = ext,
                            progressPercent = pct,
                            coverBitmap = cover,
                        )
                    }
                }
                loading = false
                books = items
            }
        }

        LaunchedEffect(navigator.lastItem) { refresh() }

        val addLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                importing = true
                val result = withIOContext { BooksStore.importBook(context, uri) }
                importing = false
                if (result is BooksStore.ImportResult.Success) {
                    snackbarHostState.showSnackbar(
                        "Добавлена книга «${result.book.name.orEmpty()}»",
                    )
                } else {
                    val reason = (result as? BooksStore.ImportResult.Failure)?.reason
                        ?: "Неизвестная ошибка"
                    snackbarHostState.showSnackbar("Не удалось добавить: $reason")
                }
                refresh()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && books.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }

                importing -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "Добавление книги…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                books.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📚", style = MaterialTheme.typography.displaySmall)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Нет книг",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Добавьте книги: PDF, EPUB, FB2, DOCX, HTML, TXT, MD…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { addLauncher.launch(arrayOf("*/*")) }) {
                                Text("Добавить книгу")
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "__count__") {
                            Text(
                                "  Книг: ${books.size}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        itemsIndexed(books, key = { _, it -> it.fileName }) { index, item ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navigator.push(
                                            BooksReaderScreen(item.fileName, item.displayTitle),
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Обложка
                                if (item.coverBitmap != null) {
                                    Image(
                                        bitmap = item.coverBitmap.asImageBitmap(),
                                        contentDescription = "Обложка",
                                        modifier = Modifier
                                            .size(56.dp, 80.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                } else {
                                    // Заглушка для обложки
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp, 80.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .then(
                                                Modifier.padding(0.dp)
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Text(
                                                text = item.ext,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.displayTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.author != null) {
                                        Text(
                                            text = item.author,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.ext,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (item.progressPercent > 0) {
                                            Text(
                                                text = " · ${item.progressPercent}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (item.progressPercent > 0) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { item.progressPercent / 100f },
                                            modifier = Modifier.fillMaxWidth().height(6.dp),
                                        )
                                    }
                                }
                                IconButton(onClick = { showDeleteDialog = item }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            ExtendedFloatingActionButton(
                onClick = { addLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("  Добавить", style = MaterialTheme.typography.labelMedium)
            }
        }

        showDeleteDialog?.let { item ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text("Удалить книгу?") },
                text = { Text("Удалить «${item.displayTitle}» из библиотеки?") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            withIOContext {
                                val file = BooksStore.booksDirectory(context)?.findFile(item.fileName)
                                if (file != null) {
                                    BooksStore.clear(context, file)
                                    BooksStore.deleteBook(file)
                                }
                            }
                            books = books.filter { it.fileName != item.fileName }
                        }
                        showDeleteDialog = null
                    }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("Отмена") } },
            )
        }
    }
}
