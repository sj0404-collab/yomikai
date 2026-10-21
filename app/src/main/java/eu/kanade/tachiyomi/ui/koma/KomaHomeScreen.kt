package eu.kanade.tachiyomi.ui.koma

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val Orange = Color(0xFFE7784C)
val Ink = Color(0xFF252721)

/**
 * Раздел KOMA внутри Yomikai.
 *
 * Читалки здесь больше нет: KOMA импортирует книги в локальную библиотеку, а
 * читает их главная читалка (единственная в приложении) — со всеми режимами,
 * OCR-баблами и авточтением. Остались каталог, импорт и настройки распознавания.
 */
@Composable
fun KomaSection(onOpenLibrary: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { LibraryRepository(context) }
    var tab by remember { mutableIntStateOf(0) }
    var archives by remember { mutableStateOf(repo.archives()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askOpenLibrary by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf(repo.prefs.getString("theme", "system") ?: "system") }
    val scope = rememberCoroutineScope()
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())

    fun refresh() {
        archives = repo.archives()
    }

    // Книги прежней полки KOMA переносим один раз: иначе после обновления
    // пользователь не нашёл бы уже импортированное.
    LaunchedEffect(Unit) {
        val moved = runCatching { repo.migrateOldShelf() }.getOrDefault(0)
        if (moved > 0) {
            message = "Книги прежней полки KOMA перенесены в локальную библиотеку: $moved"
            refresh()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val book = repo.importUri(uri)
                    refresh()
                    askOpenLibrary = true
                    message = "«${book.title}» добавлена в локальную библиотеку"
                } catch (e: Exception) {
                    message = e.message ?: "Ошибка импорта"
                } finally {
                    busy = false
                }
            }
        }
    }

    KomaTheme(darkTheme = dark) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "Импорт" to Icons.Outlined.LibraryBooks,
                    "Каталог" to Icons.Outlined.Explore,
                    "AI-студия" to Icons.Outlined.AutoAwesome,
                    "Настройки" to Icons.Outlined.Tune,
                ).forEachIndexed { index, (title, icon) ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(title, fontSize = 12.sp) },
                        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(35.dp).clip(RoundedCornerShape(10.dp)).background(Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AutoStories,
                        null,
                        tint = Color(0xFFF8F6F0),
                        modifier = Modifier.size(22.dp),
                    )
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp).background(Orange, CircleShape))
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("KOMA", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text(
                        "Книги читает главная читалка",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = { picker.launch(SUPPORTED_MIME) },
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Orange),
                ) {
                    Icon(Icons.Default.Add, "Добавить архив")
                }
            }
            when (tab) {
                0 -> ImportScreen(
                    archives = archives,
                    onOpenLibrary = onOpenLibrary,
                    onImport = { picker.launch(SUPPORTED_MIME) },
                    onDelete = { archive ->
                        if (repo.deleteArchive(archive)) {
                            refresh()
                            message = "«${archive.title}» удалена из локальной библиотеки"
                        } else {
                            message = "Не удалось удалить файл"
                        }
                    },
                )
                1 -> CatalogScreen(
                    repo = repo,
                    onAdded = {
                        refresh()
                        askOpenLibrary = true
                        message = "«${it.title}» сохранена в локальную библиотеку"
                    },
                    onMessage = { message = it },
                    knownTitles = archiveTitles(archives),
                )
                2 -> AiStudioScreen(repo) { message = it }
                else -> SettingsScreenKoma(
                    repo = repo,
                    archives = archives,
                    theme = theme,
                    onTheme = { value ->
                        theme = value
                        repo.prefs.edit().putString("theme", value).apply()
                    },
                    onMessage = { message = it },
                )
            }
        }
        if (busy) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Добавляем книгу") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Копируем архив в локальную библиотеку…")
                    }
                },
                confirmButton = {},
            )
        }
        if (askOpenLibrary) {
            AlertDialog(
                onDismissRequest = { askOpenLibrary = false },
                title = { Text("Книга на полке") },
                text = {
                    Text(
                        "Она уже в локальной библиотеке. Открывать и читать её будет главная " +
                            "читалка — там же OCR-баблы, авточтение и все режимы.",
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askOpenLibrary = false
                        onOpenLibrary()
                    }) { Text("Открыть библиотеку") }
                },
                dismissButton = {
                    TextButton(onClick = { askOpenLibrary = false }) { Text("Позже") }
                },
            )
        }
        if (message != null) {
            AlertDialog(
                onDismissRequest = { message = null },
                title = { Text("KOMA") },
                text = { Text(message!!) },
                confirmButton = { TextButton(onClick = { message = null }) { Text("Понятно") } },
            )
        }
    }
}

private fun archiveTitles(archives: List<LocalArchive>): Set<String> =
    archives.map { it.title.lowercase() }.toSet()

/** «Импорт»: архивы локальной библиотеки + понятный путь в главную читалку. */
@Composable
private fun ImportScreen(
    archives: List<LocalArchive>,
    onOpenLibrary: () -> Unit,
    onImport: () -> Unit,
    onDelete: (LocalArchive) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item {
            Text("Свои архивы", fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text(
                "CBZ, CBR (RAR4), PDF. Книга попадает в локальную библиотеку и открывается " +
                    "главной читалкой — отдельной читалки в KOMA больше нет.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 7.dp, bottom = 16.dp),
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                    .clickable(onClick = onImport)
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(38.dp).background(Orange.copy(.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, null, tint = Orange, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Добавить архив", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Файл скопируется в локальную библиотеку",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp))
                    .clickable(onClick = onOpenLibrary)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoStories, null, tint = Orange, modifier = Modifier.size(20.dp))
                Text(
                    "Открыть локальную библиотеку",
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(Icons.Default.ArrowForward, null, tint = Orange, modifier = Modifier.size(16.dp))
            }
        }
        item {
            Text(
                "На полке: ${archives.size}",
                modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (archives.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LibraryBooks, null, Modifier.size(34.dp), tint = Orange)
                    Text("Пусто", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Добавьте CBZ, CBR или PDF — или скачайте книгу в «Каталоге»",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        items(archives, key = { it.file.absolutePath }) { archive ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        archive.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${archive.extension.uppercase()} · ${archive.size / (1024 * 1024)} МБ",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                IconButton(onClick = { onDelete(archive) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        "Удалить",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "Файлы лежат в папке local хранилища приложения. Их видит и главная " +
                    "библиотека, и KOMA — это одна и та же полка.",
                fontSize = 10.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SUPPORTED_MIME = arrayOf(
    "application/vnd.comicbook+zip",
    "application/zip",
    "application/x-cbr",
    "application/pdf",
    "*/*",
)
