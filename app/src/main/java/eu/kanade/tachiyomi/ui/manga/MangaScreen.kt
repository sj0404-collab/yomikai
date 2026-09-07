package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.screens.LoadingScreen

class MangaScreen(
    private val mangaId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val screenModel = rememberScreenModel {
            MangaScreenModel(context, lifecycleOwner.lifecycle, mangaId, fromSource)
        }

        val state by screenModel.state.collectAsStateWithLifecycle()

        if (state is MangaScreenModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaScreenModel.State.Success
        val isHttpSource = remember { successState.source is HttpSource }

        LaunchedEffect(successState.manga, screenModel.source) {
            if (isHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getMangaUrl(screenModel.manga, screenModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
                }
            }
        }

        val autoReadState by screenModel.chapterAutoRead.collectAsStateWithLifecycle()

        // Следующая непрочитанная глава — цель скан-чтения (кнопка и диалог).
        val nextUnread = remember(successState.manga, successState.chapters) {
            screenModel.getNextUnreadChapter()
        }

        // Настройки скан-чтения: движок и формат документа (выбор перед стартом).
        var showScanSettings by remember { mutableStateOf(false) }
        var scanEngine by remember { mutableStateOf(AutoReadEngineChoice.OFFLINE) }
        var scanFormat by remember { mutableStateOf("md") }

        // Открытие читалки в режиме авточтения после фонового скана главы.
        LaunchedEffect(autoReadState.openChapterId) {
            val chapterId = autoReadState.openChapterId
            if (chapterId != null) {
                autoReadState.exportFile?.let { path ->
                    context.toast("Транскрипт сохранён: $path")
                }
                val chapter = successState.chapters.firstOrNull { it.chapter.id == chapterId }?.chapter
                if (chapter != null) {
                    openChapterAutoRead(context, chapter)
                }
                screenModel.clearAutoReadOpen()
            }
        }

        Box(Modifier.fillMaxSize()) {
        MangaScreen(
            state = successState,
            snackbarHostState = screenModel.snackbarHostState,
            nextUpdate = successState.manga.expectedNextUpdate,
            isTabletUi = isTabletUi(),
            chapterSwipeStartAction = screenModel.chapterSwipeStartAction,
            chapterSwipeEndAction = screenModel.chapterSwipeEndAction,
            navigateUp = navigator::pop,
            onChapterClicked = { openChapter(context, it) },
            onDownloadChapter = screenModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
            onAddToLibraryClicked = {
                screenModel.toggleFavorite()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onWebViewClicked = {
                // Открыть мангу во вкладке «Браузер» (web) вместо полноэкранного
                // встроенного WebView-экрана.
                getMangaUrl(screenModel.manga, screenModel.source)?.let { url ->
                    navigator.popUntilRoot()
                    scope.launch {
                        HomeScreen.openTab(
                            HomeScreen.Tab.Browser(url = url, title = screenModel.manga?.title),
                        )
                    }
                }
            }.takeIf { isHttpSource },
            onWebViewLongClicked = {
                copyMangaUrl(
                    context,
                    screenModel.manga,
                    screenModel.source,
                )
            }.takeIf { isHttpSource },
            onTrackingClicked = {
                if (!successState.hasLoggedInTrackers) {
                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                } else {
                    screenModel.showTrackDialog()
                }
            },
            onTagSearch = { scope.launch { performGenreSearch(navigator, it, screenModel.source!!) } },
            onFilterButtonClicked = screenModel::showSettingsDialog,
            onRefresh = screenModel::fetchAllFromSource,
            onContinueReading = { continueReading(context, screenModel.getNextUnreadChapter()) },
            onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
            onCoverClicked = screenModel::showCoverDialog,
            onShareClicked = { shareManga(context, screenModel.manga, screenModel.source) }.takeIf { isHttpSource },
            onDownloadActionClicked = screenModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
            onEditCategoryClicked = screenModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
            onEditFetchIntervalClicked = screenModel::showSetFetchIntervalDialog.takeIf {
                successState.manga.favorite
            },
            onMigrateClicked = {
                navigator.push(MigrationConfigScreen(successState.manga.id))
            }.takeIf { successState.manga.favorite },
            onEditNotesClicked = { navigator.push(MangaNotesScreen(manga = successState.manga)) },
            onMultiBookmarkClicked = screenModel::bookmarkChapters,
            onMultiMarkAsReadClicked = screenModel::markChaptersRead,
            onMarkPreviousAsReadClicked = screenModel::markPreviousChapterRead,
            onOcrClicked = screenModel::scanChapters,
            onMultiDeleteClicked = screenModel::showDeleteChapterDialog,
            onChapterSwipe = screenModel::chapterSwipe,
            onChapterSelected = screenModel::toggleSelection,
            onAllChapterSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
        )

            // Кнопка «Скан и чтение»: фоновое авто-сканирование следующей
            // непрочитанной главы с прогрессом, затем открытие читалки в
            // авточтении. Размещается чуть выше основного FAB «Читать».
            if (nextUnread != null && !autoReadState.running && autoReadState.openChapterId == null) {
                SmallFloatingActionButton(
                    onClick = { showScanSettings = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 96.dp, end = 16.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Autorenew,
                        contentDescription = "Сканировать текущую главу и читать",
                    )
                }
            }
        }

        // Выбор движка распознавания и формата документа перед стартом скана.
        if (showScanSettings && nextUnread != null) {
            AlertDialog(
                onDismissRequest = { showScanSettings = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.setAutoReadEngine(scanEngine)
                            screenModel.setAutoReadFormat(scanFormat)
                            showScanSettings = false
                            screenModel.scanAndAutoReadChapter(nextUnread)
                        },
                    ) {
                        Text("Сканировать и читать")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showScanSettings = false }) {
                        Text("Отмена")
                    }
                },
                title = { Text("Сканирование главы") },
                text = {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Движок распознавания", fontWeight = FontWeight.Bold)
                        ScanEngineRow("Оффлайн", AutoReadEngineChoice.OFFLINE, scanEngine) { scanEngine = it }
                        ScanEngineRow("Glens", AutoReadEngineChoice.GLENS, scanEngine) { scanEngine = it }
                        ScanEngineRow("GitHub-раннер", AutoReadEngineChoice.GITHUB_RUNNER, scanEngine) { scanEngine = it }
                        Spacer(Modifier.height(8.dp))
                        Text("Формат документа", fontWeight = FontWeight.Bold)
                        ScanFormatRow("Markdown (md)", "md", scanFormat) { scanFormat = it }
                        ScanFormatRow("TXT", "txt", scanFormat) { scanFormat = it }
                        ScanFormatRow("PDF", "pdf", scanFormat) { scanFormat = it }
                        ScanFormatRow("DOCX", "docx", scanFormat) { scanFormat = it }
                    }
                },
            )
        }

        // Прогресс фонового скана и результат.
        when {
            autoReadState.running -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    title = { Text("Сканирование главы") },
                    text = {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(autoReadState.title.ifBlank { "Сканирование…" })
                            if (autoReadState.total > 0) {
                                Text("Страница ${autoReadState.processed} из ${autoReadState.total}")
                            } else {
                                Text("Подготовка…")
                            }
                            LinearProgressIndicator(
                                progress = {
                                    if (autoReadState.total > 0) {
                                        (autoReadState.processed.toFloat() / autoReadState.total.toFloat())
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                },
                            )
                        }
                    },
                )
            }
            autoReadState.error != null -> {
                AlertDialog(
                    onDismissRequest = { screenModel.dismissAutoReadError() },
                    confirmButton = {
                        TextButton(onClick = { screenModel.dismissAutoReadError() }) {
                            Text("OK")
                        }
                    },
                    title = { Text("Не удалось просканировать") },
                    text = { Text(autoReadState.error.orEmpty()) },
                )
            }
        }

        var showScanlatorsDialog by remember { mutableStateOf(false) }

        val onDismissRequest = { screenModel.dismissDialog() }
        when (val dialog = successState.dialog) {
            null -> {}
            is MangaScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is MangaScreenModel.Dialog.DeleteChapters -> {
                DeleteChaptersDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.toggleAllSelection(false)
                        screenModel.deleteChapters(dialog.chapters)
                    },
                )
            }

            is MangaScreenModel.Dialog.DuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.showMigrateDialog(it) },
                )
            }

            is MangaScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaScreenModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                onDownloadFilterChanged = screenModel::setDownloadedFilter,
                onUnreadFilterChanged = screenModel::setUnreadFilter,
                onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
                onSortModeChanged = screenModel::setSorting,
                onDisplayModeChanged = screenModel::setDisplayMode,
                onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
                onResetToDefault = screenModel::resetToDefaultSettings,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorsDialog = true },
            )
            MangaScreenModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = TrackInfoDialogHomeScreen(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is TrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            MangaScreenModel.Dialog.FullCover -> {
                val sm = rememberScreenModel { MangaCoverScreenModel(successState.manga.id) }
                val manga by sm.state.collectAsState()
                if (manga != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editCover(context, it)
                    }
                    MangaCoverDialog(
                        manga = manga!!,
                        snackbarHostState = sm.snackbarHostState,
                        isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                        onShareClick = { sm.shareCover(context) },
                        onSaveClick = { sm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is MangaScreenModel.Dialog.SetFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.manga.fetchInterval,
                    nextUpdate = dialog.manga.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    onValueChanged = { interval: Int -> screenModel.setFetchInterval(dialog.manga, interval) }
                        .takeIf { screenModel.isUpdateIntervalEnabled },
                )
            }
        }

        if (showScanlatorsDialog) {
            ScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorsDialog = false },
                onConfirm = screenModel::setExcludedScanlators,
            )
        }
    }

    private fun continueReading(context: Context, unreadChapter: Chapter?) {
        if (unreadChapter != null) openChapter(context, unreadChapter)
    }

    private fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }

    /** Открыть главу сразу в режиме авточтения (после фонового скана). */
    private fun openChapterAutoRead(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newAutoReadIntent(context, chapter.mangaId, chapter.id))
    }

    private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
        val manga = manga_ ?: return null
        val source = source_ as? HttpSource ?: return null

        return try {
            source.getMangaUrl(manga.toSManga())
        } catch (e: Exception) {
            null
        }
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        try {
            getMangaUrl(manga_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                previousController.search(query)
            }
            is BrowseSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(navigator: Navigator, genreName: String, source: Source) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseSourceScreen && source is HttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        val url = source.getMangaUrl(manga.toSManga())
        context.copyToClipboard(url, url)
    }
}

/** Строка выбора OCR-движка в диалоге скан-чтения. */
@Composable
private fun ScanEngineRow(
    label: String,
    engine: AutoReadEngineChoice,
    current: AutoReadEngineChoice,
    onSelect: (AutoReadEngineChoice) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(engine) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = engine == current, onClick = { onSelect(engine) })
        Text(label)
    }
}

/** Строка выбора формата документа в диалоге скан-чтения. */
@Composable
private fun ScanFormatRow(
    label: String,
    format: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(format) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = format == current, onClick = { onSelect(format) })
        Text(label)
    }
}
