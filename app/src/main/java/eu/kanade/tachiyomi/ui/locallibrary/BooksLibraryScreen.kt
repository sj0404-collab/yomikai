package eu.kanade.tachiyomi.ui.locallibrary

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.books.BooksStore
import eu.kanade.tachiyomi.ui.books.BooksBookScreen
import eu.kanade.tachiyomi.ui.books.BooksReaderScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Экран библиотеки книг, во всём повторяющий возможности библиотеки манги:
 *  • сетка обложек (как BrowseSource у манги) или список — переключается;
 *  • сортировка по имени / недавним (открывали) / новым (добавлены) / размеру;
 *  • карточка «Продолжить чтение» с последней открытой книгой и временем чтения;
 *  • добавление СРАЗУ НЕСКОЛЬКИХ файлов из системного пикера;
 *  • длинное нажатие по книге — информация/открытие, отдельная кнопка удаления.
 */
object BooksLibraryScreen : Screen {

    private data class BookItem(
        val fileName: String,
        val displayTitle: String,
        val author: String?,
        val description: String?,
        val ext: String,
        val progressPercent: Int,
        val coverPath: String?,
        val lastOpenedAt: Long,
        val readSeconds: Long,
        val sizeBytes: Long,
        val addedAt: Long,
        val savedChapter: Int,
    )

    private enum class BookSort { NAME, RECENT, ADDED, SIZE }

    private const val UI_PREFS = "book_library_ui"

    /** «2 ч 5 мин», «47 мин», «<мин» — надпись времени чтения. */
    private fun formatReadTime(seconds: Long): String {
        if (seconds < 60) return "<1 мин"
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            val restMin = minutes % 60
            if (restMin > 0) "$hours ч $restMin мин" else "$hours ч"
        } else {
            "$minutes мин"
        }
    }

    /** «только что», «5 мин назад», «2 ч назад», «3 дн. назад». */
    private fun formatAgo(ts: Long): String {
        if (ts <= 0) return "никогда"
        val diff = System.currentTimeMillis() - ts
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 60 * 24 -> "${minutes / 60} ч назад"
            else -> "${minutes / (60 * 24)} дн. назад"
        }
    }

    @Composable
    private fun coverBitmap(path: String, sample: Int = 4) = remember(path) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var importing by remember { mutableStateOf(false) }
        var importingCount by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var books by remember { mutableStateOf(emptyList<BookItem>()) }
        var showDeleteDialog by remember { mutableStateOf<BookItem?>(null) }
        var showInfoDialog by remember { mutableStateOf<BookItem?>(null) }

        val uiPrefs = remember { context.getSharedPreferences(UI_PREFS, 0) }
        // Сортировка и раскладка — сохраняем, как в библиотеке манги.
        var sort by remember {
            mutableStateOf(
                runCatching { BookSort.valueOf(uiPrefs.getString("sort", "RECENT") ?: "RECENT") }
                    .getOrDefault(BookSort.RECENT),
            )
        }
        var gridMode by remember { mutableStateOf(uiPrefs.getBoolean("grid_mode", true)) }

        fun refresh() {
            scope.launch {
                val items = withIOContext {
                    BooksStore.listBooks(context).map { file ->
                        val snapshot = BooksStore.load(context, file)
                        val ext = file.name.orEmpty().substringAfterLast('.', "").uppercase()
                        val metadata = BooksStore.loadMetadata(context, file)
                        val cPath = BooksStore.coverPath(context, file)
                        BookItem(
                            fileName = file.name.orEmpty(),
                            displayTitle = metadata.title.ifBlank {
                                file.name.orEmpty().substringBeforeLast('.').ifBlank { "Книга" }
                            },
                            author = metadata.author,
                            description = metadata.description,
                            ext = ext,
                            progressPercent = snapshot.percent,
                            coverPath = cPath,
                            lastOpenedAt = BooksStore.lastOpened(context, file),
                            readSeconds = BooksStore.readSeconds(context, file),
                            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                            addedAt = BooksStore.addedAt(context, file),
                            savedChapter = snapshot.chapter,
                        )
                    }.sortedByDescending { it.addedAt } // стабильная база
                }
                loading = false
                books = items
            }
        }

        LaunchedEffect(navigator.lastItem) { refresh() }

        val sortedBooks = remember(books, sort) {
            when (sort) {
                BookSort.NAME -> books.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle },
                )
                BookSort.RECENT -> books.sortedWith(
                    compareByDescending<BookItem> { it.lastOpenedAt }
                        .thenByDescending { it.addedAt },
                )
                BookSort.ADDED -> books.sortedByDescending { it.addedAt }
                BookSort.SIZE -> books.sortedByDescending { it.sizeBytes }
            }
        }

        // «Продолжить чтение»: последняя открытая книга с прогрессом.
        val continueBook = remember(books) {
            books.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt }
        }

        // --- Импорт: добавить СРАЗУ НЕСКОЛЬКО книг ---
        val addLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                importing = true
                importingCount = 0
                var ok = 0
                var failed = 0
                try {
                    uris.forEach { uri ->
                        importingCount++
                        val result = withIOContext {
                            runCatching { BooksStore.importBook(context, uri) }
                                .getOrNull()
                                ?: BooksStore.ImportResult.Failure("Нет доступа к файлу")
                        }
                        if (result is BooksStore.ImportResult.Success) ok++ else failed++
                    }
                    val msg = buildString {
                        append("Добавлено книг: $ok")
                        if (failed > 0) append(", не удалось: $failed")
                    }
                    snackbarHostState.showSnackbar(msg)
                } finally {
                    importing = false
                    refresh()
                }
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
                                "Добавление книги $importingCount…",
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
                                "Добавьте книги: PDF, EPUB, FB2, DOCX, HTML, TXT, MD…\n" +
                                    "(можно выбрать сразу несколько файлов)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { addLauncher.launch(arrayOf("*/*")) }) {
                                Text("Добавить книги")
                            }
                        }
                    }
                }

                else -> {
                    if (gridMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        ) {
                            item(key = "__continue__", span = { GridItemSpan(maxLineSpan) }) {
                                ContinueCard(
                                    book = continueBook,
                                    onContinue = { item ->
                                        navigator.push(
                                            BooksReaderScreen(item.fileName, item.displayTitle),
                                        )
                                    },
                                )
                            }
                            item(key = "__controls__", span = { GridItemSpan(maxLineSpan) }) {
                                ControlsRow(
                                    count = books.size,
                                    sort = sort,
                                    onSort = {
                                        sort = it
                                        uiPrefs.edit().putString("sort", it.name).apply()
                                    },
                                    gridMode = gridMode,
                                    onToggleMode = {
                                        gridMode = !gridMode
                                        uiPrefs.edit().putBoolean("grid_mode", gridMode).apply()
                                    },
                                )
                            }
                            items(sortedBooks.size, key = { sortedBooks[it].fileName }) { index ->
                                BookGridCard(
                                    item = sortedBooks[index],
                                    coverBitmap = sortedBooks[index].coverPath?.let { coverBitmap(it) },
                                    onOpen = {
                                        // Тап по книге — детальный экран, как у манги.
                                        navigator.push(BooksBookScreen(sortedBooks[index].fileName))
                                    },
                                    onShowInfo = { showInfoDialog = sortedBooks[index] },
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item(key = "__continue__") {
                                ContinueCard(
                                    book = continueBook,
                                    onContinue = { item ->
                                        navigator.push(
                                            BooksReaderScreen(item.fileName, item.displayTitle),
                                        )
                                    },
                                )
                            }
                            item(key = "__controls__") {
                                ControlsRow(
                                    count = books.size,
                                    sort = sort,
                                    onSort = {
                                        sort = it
                                        uiPrefs.edit().putString("sort", it.name).apply()
                                    },
                                    gridMode = gridMode,
                                    onToggleMode = {
                                        gridMode = !gridMode
                                        uiPrefs.edit().putBoolean("grid_mode", gridMode).apply()
                                    },
                                )
                            }
                            itemsIndexed(sortedBooks, key = { _, it -> it.fileName }) { index, item ->
                                if (index > 0) HorizontalDivider()
                                BookListRow(
                                    item = item,
                                    coverBitmap = item.coverPath?.let { coverBitmap(it) },
                                    onOpen = {
                                        navigator.push(BooksBookScreen(item.fileName))
                                    },
                                    onShowInfo = { showInfoDialog = item },
                                    onShowDelete = { showDeleteDialog = item },
                                )
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

            showInfoDialog?.let { item ->
                AlertDialog(
                    onDismissRequest = { showInfoDialog = null },
                    title = { Text(item.displayTitle, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.author?.takeIf { it.isNotBlank() }?.let {
                                Text("Автор: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Формат: ${item.ext.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Прогресс: ${item.progressPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (item.readSeconds > 0) {
                                Text(
                                    "Время чтения: ${formatReadTime(item.readSeconds)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (item.lastOpenedAt > 0) {
                                Text(
                                    "Открывали: ${formatAgo(item.lastOpenedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (item.sizeBytes > 0) {
                                val sizeText = when {
                                    item.sizeBytes >= 1048576 -> "%.1f МБ".format(item.sizeBytes / 1048576f)
                                    item.sizeBytes >= 1024 -> "%.1f КБ".format(item.sizeBytes / 1024f)
                                    else -> "${item.sizeBytes} Б"
                                }
                                Text("Размер: $sizeText", style = MaterialTheme.typography.bodySmall)
                            }
                            item.description?.takeIf { it.isNotBlank() }?.let {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showInfoDialog = null
                            navigator.push(
                                BooksReaderScreen(item.fileName, item.displayTitle),
                            )
                        }) { Text("Открыть") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showInfoDialog = null }) { Text("Закрыть") }
                    },
                )
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

    // ---------- Виджеты ----------

    /** Карточка «Продолжить чтение» поверх сетки — с обложкой и временем. */
    @Composable
    private fun ContinueCard(book: BookItem?, onContinue: (BookItem) -> Unit) {
        book ?: return
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clickable { onContinue(book) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(10.dp),
            ) {
                val bmp = book.coverPath?.let { coverBitmap(it) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp, 60.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Продолжить чтение",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        book.displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val bits = buildString {
                        append("Глава ${book.savedChapter + 1}")
                        append(" · ${formatAgo(book.lastOpenedAt)}")
                        if (book.readSeconds > 0) {
                            append(" · читали ${formatReadTime(book.readSeconds)}")
                        }
                    }
                    Text(
                        bits,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Продолжить",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }

    /** Строка управления: число книг, сортировка, переключение сетка/список. */
    @Composable
    private fun ControlsRow(
        count: Int,
        sort: BookSort,
        onSort: (BookSort) -> Unit,
        gridMode: Boolean,
        onToggleMode: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "Книг: $count  ·  ",
                style = MaterialTheme.typography.labelMedium,
            )
            FilterChip(
                selected = sort == BookSort.RECENT,
                onClick = { onSort(BookSort.RECENT) },
                label = { Text("Недавние") },
                modifier = Modifier.padding(end = 6.dp),
            )
            FilterChip(
                selected = sort == BookSort.NAME,
                onClick = { onSort(BookSort.NAME) },
                label = { Text("По имени") },
                modifier = Modifier.padding(end = 6.dp),
            )
            FilterChip(
                selected = sort == BookSort.ADDED,
                onClick = { onSort(BookSort.ADDED) },
                label = { Text("Новые") },
                modifier = Modifier.padding(end = 6.dp),
            )
            FilterChip(
                selected = sort == BookSort.SIZE,
                onClick = { onSort(BookSort.SIZE) },
                label = { Text("По размеру") },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleMode) {
                Icon(
                    imageVector = if (gridMode) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                    contentDescription = if (gridMode) "Списком" else "Сеткой",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** Карточка книги в режиме сетки (как обложки манги). */
    @ExperimentalFoundationApi
    @Composable
    private fun BookGridCard(
        item: BookItem,
        coverBitmap: android.graphics.Bitmap?,
        onOpen: () -> Unit,
        onShowInfo: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .padding(6.dp)
                .combinedClickable(onClick = onOpen, onLongClick = onShowInfo),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = item.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = item.ext,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.progressPercent > 0) {
                    LinearProgressIndicator(
                        progress = { item.progressPercent / 100f },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.displayTitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (item.readSeconds > 0) {
                        append(formatReadTime(item.readSeconds))
                    } else {
                        append(item.ext)
                    }
                    if (item.lastOpenedAt > 0) {
                        append(" · ")
                        append(formatAgo(item.lastOpenedAt))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** Строка книги в режиме списка (компактный, как раньше). */
    @ExperimentalFoundationApi
    @Composable
    private fun BookListRow(
        item: BookItem,
        coverBitmap: android.graphics.Bitmap?,
        onOpen: () -> Unit,
        onShowInfo: () -> Unit,
        onShowDelete: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onShowInfo)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = "Обложка",
                    modifier = Modifier
                        .size(56.dp, 80.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp, 80.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.ext,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))

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
                Text(
                    text = buildString {
                        append(item.ext)
                        if (item.progressPercent > 0) append(" · ${item.progressPercent}%")
                        if (item.readSeconds > 0) {
                            append(" · ${formatReadTime(item.readSeconds)}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.progressPercent > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (item.progressPercent > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { item.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
            IconButton(onClick = onShowInfo) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "О книге",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShowDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

