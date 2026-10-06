package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LocalContentColor
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import eu.kanade.presentation.more.settings.screen.rememberNetworkState
import mihon.data.ocr.OcrPlugins
import mihon.domain.ocr.model.OcrModel
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
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

        // Следующая непрочитанная глава — цель скан-чтения с кнопки у «Начать».
        val nextUnread = remember(successState.manga, successState.chapters) {
            screenModel.getNextUnreadChapter()
        }

        // Настройки скан-чтения: движок и формат документа (выбор перед стартом).
        var showScanSettings by remember { mutableStateOf(false) }
        var scanEngine by remember { mutableStateOf(screenModel.autoReadEngine) }
        var scanFormat by remember { mutableStateOf("md") }
        // Глава, выбранная для скана: null — ещё не выбрана, диалог закрыт.
        // Диалог помнит именно её, поэтому «Сканировать и читать» из строки
        // сканирует ту главу, у которой нажали кнопку, а не «следующую
        // непрочитанную»: в списке из 155 глав одна кнопка на весь экран была
        // единственным способом выбрать.
        var scanTargetChapter by remember { mutableStateOf<Chapter?>(null) }
        // Список глав для пакетного скана; пустой — сканируется одна глава.
        var scanBatchTarget by remember { mutableStateOf<List<Chapter>>(emptyList()) }
        // Консоль ИИ — на этом экране, рядом с кнопкой скана главы: скан идёт
        // здесь же, и смотреть, чем занят ИИ, нужно тут же. В читалке её нет:
        // обычному чтению полэкранный терминал не нужен, а место занимал.
        var showAiConsole by remember { mutableStateOf(false) }
        // Скан занят — кнопки у глав гасим, чтобы не запустить второй поверх.
        val scanRunning = autoReadState.running || autoReadState.openChapterId != null

        // Открытие читалки в режиме авточтения после фонового скана главы.
        LaunchedEffect(autoReadState.openChapterId) {
            val chapterId = autoReadState.openChapterId
            if (chapterId != null) {
                // У пачки транскриптов по числу глав, и путь у каждого свой:
                // показывать путь последнего молча значило бы выдать его за
                // единственный файл.
                when {
                    autoReadState.transcriptsSaved > 1 ->
                        context.toast("Транскриптов сохранено: ${autoReadState.transcriptsSaved}")
                    autoReadState.exportFile != null ->
                        context.toast("Транскрипт сохранён: ${autoReadState.exportFile}")
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
                val url = getMangaUrl(screenModel.manga, screenModel.source)
                if (url != null) {
                    navigator.popUntilRoot()
                    scope.launch {
                        HomeScreen.openTab(
                            HomeScreen.Tab.Browser(url = url, title = screenModel.manga?.title ?: url),
                        )
                    }
                    Unit
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
            // Вторая кнопка рядом с «Читать»: обложка с голосом = сканировать
            // главу онлайн-моделью, озвучить и открыть в авточтении. Показывает-
            // ся только когда скан ещё не идёт и глава не открыта.
            onScanChapterClicked = {
                scanTargetChapter = nextUnread
                showScanSettings = true
            }.takeIf {
                nextUnread != null && !scanRunning
            },
            // Та же кнопка у каждой главы: жмёшь у нужной — сканируется она.
            onOpenAiConsole = { showAiConsole = true },
            onScanChapterItemClicked = { chapter: Chapter ->
                scanTargetChapter = chapter
                showScanSettings = true
            }.takeIf {
                !scanRunning && !successState.source.isLocalOrStub()
            },
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
            // Пачкой по выделенным скачанным главам: тот же диалог движка и
            // формата, но сканируется весь список и открывается первая
            // удачная глава.
            onScanAndReadChapters = { chapters: List<Chapter> ->
                scanBatchTarget = chapters
                scanTargetChapter = chapters.firstOrNull()
                showScanSettings = true
            }.takeIf { !scanRunning },
            onMultiDeleteClicked = screenModel::showDeleteChapterDialog,
            onChapterSwipe = screenModel::chapterSwipe,
            onChapterSelected = screenModel::toggleSelection,
            onAllChapterSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
        )

        }

        // Выбор движка распознавания и формата документа перед стартом скана.
        // Диалог работает с ТЕМ главой, у которой нажали кнопку: иначе выбор
        // главы был невозможен вовсе — кнопка била по «следующей непрочитанной».
        val scanTarget = scanTargetChapter
        if (showScanSettings && scanTarget != null) {
            AlertDialog(
                onDismissRequest = { showScanSettings = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            screenModel.selectAutoReadEngine(scanEngine)
                            screenModel.setAutoReadFormat(scanFormat)
                            showScanSettings = false
                            val batch = scanBatchTarget
                            if (batch.isEmpty()) {
                                screenModel.scanAndAutoReadChapter(scanTarget)
                            } else {
                                screenModel.scanAndAutoReadChapters(batch)
                            }
                            scanBatchTarget = emptyList()
                        },
                    ) {
                        Text("Сканировать и читать")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showScanSettings = false
                            scanBatchTarget = emptyList()
                        },
                    ) {
                        Text("Отмена")
                    }
                },
                title = { Text("Сканирование главы") },
                text = {
                    // Ссылка на консоль прямо в диалоге скана: скан идёт здесь же,
                    // и кнопка «консоль» стоит рядом с «Сканировать и читать».
                    TextButton(onClick = { showAiConsole = true }) { Text("Консоль ИИ") }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // Список ПРОКРУЧИВАЕТСЯ. Без этого 7 движков (у
                            // недоступных — ещё и строка с причиной) и 4 формата
                            // не влезали в диалог, и всё после первых двух строк
                            // просто обрезалось: выглядело так, будто движок один.
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Что именно пойдёт в скан: одна глава — её название,
                        // пачка — сколько глав и какая первой.
                        val batch = scanBatchTarget
                        Text(
                            text = if (batch.size <= 1) {
                                scanTarget.name
                            } else {
                                "${batch.size} глав, сначала «${scanTarget.name}»"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Движок распознавания", fontWeight = FontWeight.Bold)
                        // Тот же список, что и в «Плагины OCR»: сканировать можно
                        // любой движок приложения, а не два зашитых. Недоступные
                        // показаны с причиной и не выбираются.
                        // Сеть переспрашивается при каждом открытии диалога:
                        // включил интернет — и онлайн-движки стали доступны,
                        // а не остались серыми до перезапуска процесса.
                        val engineOptions = screenModel.autoReadEngineOptions(
                            networkAvailable = rememberNetworkState(context, showScanSettings),
                        )
                        val ocrPrefs = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                        engineOptions.forEach { option ->
                            // v1.9.134: у каждого движка — замер последнего кадра
                            // авточтения (мс), чтобы скорость модели сравнивалась
                            // прямо в диалоге, а не на глаз.
                            ScanEngineRow(
                                option,
                                scanEngine,
                                ocrPrefs.autoReadLastMs(option.model).get().ifBlank { null },
                            ) { model -> scanEngine = model }
                        }
                        // Режим авточтения выбирается ОТДЕЛЬНО под каждый движок:
                        // GLens с «page» не перечитывает реплики, Space Bunny со
                        // «stream» не молчит, пока переводится кадр.
                        Spacer(Modifier.height(8.dp))
                        val scanModePref = ocrPrefs.autoReadModeFor(scanEngine)
                        val scanMode by scanModePref.changes().collectAsState(initial = scanModePref.get())
                        Text("Режим авточтения", fontWeight = FontWeight.Bold)
                        ScanFormatRow("Строки сразу", "stream", scanMode) { scanModePref.set(it) }
                        ScanFormatRow("Баблоны с буфером слов", "bubble", scanMode) { scanModePref.set(it) }
                        ScanFormatRow("Вся страница одной озвучкой", "page", scanMode) { scanModePref.set(it) }
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

        // Консоль ИИ на экране манги.
        if (showAiConsole) {
            eu.kanade.presentation.reader.components.AiConsoleDialog(
                onClose = { showAiConsole = false },
                onStopEverything = {
                    // Здесь читалки нет: гасим то, что ещё могло идти —
                    // запросы к модели и внешний HTTP-агент.
                    runCatching {
                        eu.kanade.tachiyomi.data.ai.AiHttpServer
                            .abortAllRequests("кнопка «Остановить» в консоли")
                    }
                    eu.kanade.tachiyomi.data.ai.AiAssistant
                        .abortActiveRequests("кнопка «Остановить» в консоли")
                    eu.kanade.tachiyomi.data.tts.TtsSpeaker.stop()
                    eu.kanade.tachiyomi.data.ai.AiConsole.user("Остановлено из консоли")
                },
            )
        }

        // Прогресс фонового скана и результат.
        when {
            autoReadState.running -> {
                AlertDialog(
                    // Намеренно НЕ отменяем по нажатию снаружи или «назад»:
                    // поверх диалога открывается консоль, и её закрытие не должно
                    // срывать скан. Отмена — только явной кнопкой ниже.
                    onDismissRequest = {},
                    confirmButton = {
                        TextButton(onClick = { screenModel.cancelChapterScan() }) {
                            Text("Остановить скан")
                        }
                    },
                    dismissButton = {
                        // Терминал прямо тут: скан уже идёт, и «что делает ИИ»
                        // нужно видно именно сейчас, не закрывая его.
                        TextButton(onClick = { showAiConsole = true }) { Text("Консоль ИИ") }
                    },
                    title = { Text("Сканирование главы") },
                    text = {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(autoReadState.title.ifBlank { "Сканирование…" })
                            // «Терминал»: что делает скан прямо сейчас. Без этой
                            // строки запрос к модели на каждую страницу выглядел
                            // как зависание — а ИИ-проверка идёт между OCR-страницами,
                            // и прогресс-бар этого не показывает.
                            if (autoReadState.stage.isNotBlank()) {
                                Text(
                                    "> ${autoReadState.stage}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (autoReadState.total > 0) {
                                Text("Страница ${autoReadState.processed} из ${autoReadState.total}")
                            } else {
                                // Голое «Подготовка…» висит до первой страницы, а
                                // первая страница онлайн-движка идёт секундами: выглядит
                                // как зависание. Поэтому сразу называем, что именно
                                // запущено.
                                val engine = OcrPlugins.byModel(scanEngine).title
                                Text(
                                    "Движок: $engine · ждём первую страницу",
                                    style = MaterialTheme.typography.bodySmall,
                                )
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

/**
 * Строка выбора OCR-движка в диалоге скан-чтения.
 *
 * Недоступный движок остаётся виден: иначе список молча выглядел бы короче
 * настроек, и читатель гадал бы, куда делся настроенный им движок. Причина
 * недоступности подписана, а сама строка не выбирается.
 */
@Composable
private fun ScanEngineRow(
    option: AutoReadEngineOption,
    current: OcrModel,
    lastMs: String? = null,
    onSelect: (OcrModel) -> Unit,
) {
    val enabled = option.available
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(option.model) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = option.model == current,
            onClick = { onSelect(option.model) },
            enabled = enabled,
        )
        Column {
            Text(option.title, color = LocalContentColor.current.copy(alpha = alpha))
            // v1.9.134: последний замер кадра авточтения этого движка.
            lastMs?.let {
                Text(
                    text = "⏱ последний кадр: $it мс",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                )
            }
            option.unavailableReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                )
            }
        }
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
