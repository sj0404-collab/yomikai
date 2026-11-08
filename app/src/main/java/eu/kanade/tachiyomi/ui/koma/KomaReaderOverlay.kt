package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Оверлей для KOMA читалки с функциями из ReaderActivity.
 * 
 * Добавляет:
 * - Режимы чтения (LTR/RTL/вертикальный/вебтун)
 * - Цветовые фильтры (яркость, контраст)
 * - Ориентация экрана
 * - Действия со страницей (скриншот, поделиться, браузер)
 * - Переход к манге
 */
@Composable
fun KomaReaderOverlay(
    mangaId: Long?,
    chapterId: Long?,
    onDismiss: () -> Unit,
    onScreenshot: () -> Unit,
    onShare: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenManga: () -> Unit,
    onExportAnki: () -> Unit,
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showReadingMode by remember { mutableStateOf(false) }
    var showOrientation by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

    // Главное меню оверлея
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Функции читалки") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Режимы чтения
                OutlinedButton(
                    onClick = { showReadingMode = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.MenuBook, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Режим чтения")
                }

                // Ориентация
                OutlinedButton(
                    onClick = { showOrientation = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.ScreenRotation, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ориентация экрана")
                }

                // Настройки (цветовые фильтры, яркость)
                OutlinedButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Settings, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Настройки чтения")
                }

                Divider()

                // Действия со страницей
                OutlinedButton(
                    onClick = { showActions = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.MoreHoriz, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Действия со страницей")
                }

                // Переход к манге
                if (mangaId != null) {
                    OutlinedButton(
                        onClick = {
                            onOpenManga()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Info, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Перейти к манге")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )

    // Диалог выбора режима чтения
    if (showReadingMode && mangaId != null) {
        val getManga = remember { Injekt.get<GetManga>() }
        var manga by remember { mutableStateOf<tachiyomi.domain.manga.model.Manga?>(null) }
        
        LaunchedEffect(mangaId) {
            manga = getManga.await(mangaId)
        }

        manga?.let { m ->
            ReadingModeSelectDialog(
                onDismissRequest = { showReadingMode = false },
                screenModel = ReaderSettingsScreenModel(
                    readerState = kotlinx.coroutines.flow.MutableStateFlow(
                        eu.kanade.tachiyomi.ui.reader.ReaderViewModel.State()
                    ),
                    onChangeReadingMode = { },
                    onChangeOrientation = { }
                ),
                onChange = { showReadingMode = false }
            )
        }
    }

    // Диалог выбора ориентации
    if (showOrientation && mangaId != null) {
        val getManga = remember { Injekt.get<GetManga>() }
        var manga by remember { mutableStateOf<tachiyomi.domain.manga.model.Manga?>(null) }
        
        LaunchedEffect(mangaId) {
            manga = getManga.await(mangaId)
        }

        manga?.let { m ->
            OrientationSelectDialog(
                onDismissRequest = { showOrientation = false },
                screenModel = ReaderSettingsScreenModel(
                    readerState = kotlinx.coroutines.flow.MutableStateFlow(
                        eu.kanade.tachiyomi.ui.reader.ReaderViewModel.State()
                    ),
                    onChangeReadingMode = { },
                    onChangeOrientation = { }
                ),
                onChange = { showOrientation = false }
            )
        }
    }

    // Полные настройки чтения
    if (showSettings && mangaId != null) {
        val screenModel = remember {
            ReaderSettingsScreenModel(
                readerState = kotlinx.coroutines.flow.MutableStateFlow(
                    eu.kanade.tachiyomi.ui.reader.ReaderViewModel.State()
                ),
                onChangeReadingMode = { },
                onChangeOrientation = { }
            )
        }

        ReaderSettingsDialog(
            onDismissRequest = { showSettings = false },
            onShowMenus = { },
            onHideMenus = { },
            screenModel = screenModel
        )
    }

    // Меню действий со страницей
    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text("Действия") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onScreenshot()
                            showActions = false
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Скриншот страницы")
                    }

                    OutlinedButton(
                        onClick = {
                            onShare()
                            showActions = false
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Поделиться главой")
                    }

                    OutlinedButton(
                        onClick = {
                            onOpenInBrowser()
                            showActions = false
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.OpenInBrowser, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть в браузере")
                    }

                    OutlinedButton(
                        onClick = {
                            onExportAnki()
                            showActions = false
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Экспорт в Anki")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActions = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}
