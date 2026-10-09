package eu.kanade.tachiyomi.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.ui.locallibrary.ENGINE_TITLES
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Отдельный экран настроек оверлея поверх экрана.
 *
 * Раньше оверлей был третьей вкладкой внутри экрана «Распознавание» вперемешку
 * с движками и очередью OCR, и ещё раз отдельным пунктом в настройках
 * локальной библиотеки. Это две разные сущности: распознавание страниц в
 * читалке и плавающая кнопка поверх чужих приложений. Здесь только оверлей —
 * питание, область, буфер обмена, своя технология распознавания и список
 * приложений, на которые его можно наложить.
 */
object OcrOverlaySettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<OcrPreferences>() }

        val regionMode by prefs.overlayRegionMode().changes()
            .collectAsState(initial = prefs.overlayRegionMode().get())
        val fixedRegion by prefs.overlayFixedRegion().changes()
            .collectAsState(initial = prefs.overlayFixedRegion().get())
        val showFrame by prefs.overlayShowFrame().changes()
            .collectAsState(initial = prefs.overlayShowFrame().get())
        val watchClip by prefs.overlayWatchClipboard().changes()
            .collectAsState(initial = prefs.overlayWatchClipboard().get())
        val appEngine by prefs.appOcrEngine().changes()
            .collectAsState(initial = prefs.appOcrEngine().get())
        val ownEngine by prefs.overlayOwnEngine().changes()
            .collectAsState(initial = prefs.overlayOwnEngine().get())

        val canDraw = OcrOverlayService.canDrawOverlays(context)

        Column(modifier = Modifier.fillMaxSize()) {
            androidx.compose.material3.TopAppBar(
                title = { Text("Оверлей поверх экрана") },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                if (!canDraw) {
                    WarningNote(
                        text = "Нет разрешения «отображать поверх других приложений» — оверлей не появится.",
                        actionLabel = "Выдать разрешение",
                        onAction = { OcrOverlayService.requestPermission(context) },
                    )
                }

                OverlaySectionTitle("Питание")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (!OcrOverlayService.canDrawOverlays(context)) {
                                OcrOverlayService.requestPermission(context)
                            } else {
                                OcrOverlayService.start(context)
                            }
                        },
                    ) { Text("Запустить") }
                    TextButton(onClick = { OcrOverlayService.stop(context) }) { Text("Остановить") }
                }

                OverlaySectionTitle("Область распознавания")
                OptionsBlock(
                    options = mapOf(
                        "auto" to "Авто — весь экран",
                        "manual" to "Вручную — выделить пальцем",
                        "fixed" to "Фикс — реплики игр, область не двигается",
                    ),
                    selected = regionMode,
                    onSelect = {
                        prefs.overlayRegionMode().set(it)
                        OcrOverlayService.refresh(context)
                    },
                )
                Text(
                    text = if (fixedRegion.isBlank()) {
                        "Область не выбрана. Нажми «Задать область» и обведи пальцем " +
                            "нужный прямоугольник — рамка появится поверх приложения."
                    } else {
                        "Область: $fixedRegion. Чтобы передвинуть — нажми «Задать область» заново."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextButton(
                    onClick = {
                        if (!OcrOverlayService.canDrawOverlays(context)) {
                            OcrOverlayService.requestPermission(context)
                        } else {
                            // Рамка рисуется только в режиме «Фикс» и только
                            // при заданной области. Раньше оба условия надо
                            // было выполнить вручную и в нужном порядке,
                            // иначе включённый переключатель не давал
                            // никакого видимого результата.
                            prefs.overlayRegionMode().set("fixed")
                            OcrOverlayService.start(context)
                            OcrOverlayService.selectRegion(context)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Задать область") }
                SwitchRow(
                    title = "Показывать рамку области",
                    subtitle = "Тонкая рамка зафиксированной области поверх приложений",
                    checked = showFrame,
                    onChange = {
                        prefs.overlayShowFrame().set(it)
                        OcrOverlayService.refresh(context)
                    },
                )
                SwitchRow(
                    title = "Следить за буфером обмена",
                    subtitle = "Новое скопированное озвучивается само",
                    checked = watchClip,
                    onChange = {
                        prefs.overlayWatchClipboard().set(it)
                        OcrOverlayService.refresh(context)
                    },
                )

                OverlaySectionTitle("Чтение рамки и своя прокрутка")
                Text(
                    text = "Кнопка «▶ Читать рамку» на панели снимает область экрана, " +
                        "распознаёт, озвучивает и листает приложение СВОИМ свайпом — " +
                        "не полагаясь на его собственную прокрутку. Дальше листать " +
                        "чужое приложение можно только жестом, а жест умеет " +
                        "отправлять Служба доступности. Она ничего не читает и " +
                        "ничего не выполняет — только свайп по вашей команде.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SwitchRow(
                    title = "Служба доступности включена",
                    subtitle = "Нужна для прокрутки. Если выключена, чтение озвучивает " +
                        "кадр, но листать не сможет.",
                    checked = OverlayGestureService.isEnabled(),
                    onChange = { context.requestGestureService() },
                )
                Text(
                    text = "Переключатель серый и не включается? Android 13+ блокирует " +
                        "Службу доступности у приложений не из магазина. Нажмите здесь, " +
                        "чтобы открыть «О приложении», затем ⋮ → «Разрешить ограниченные " +
                        "настройки», после чего включите переключатель снова.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { context.openAppDetails() },
                )
                Text(
                    text = "Пауза после реплики: ${prefs.overlayReadPause().get()} мс • " +
                        "ожидание перерисовки: ${prefs.overlayScrollSettle().get()} мс • " +
                        "длина свайпа: ${prefs.overlayScrollStep().get()}% высоты области",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                OverlaySectionTitle("Технология распознавания")
                SwitchRow(
                    title = "Свой движок для оверлея",
                    subtitle = "Выключено — читает тем же движком, что и читалка. " +
                        "Включено — собственным, заданным ниже.",
                    checked = ownEngine,
                    onChange = {
                        prefs.overlayOwnEngine().set(it)
                        OcrOverlayService.refresh(context)
                    },
                )
                Text(
                    text = "Движок, которым оверлей читает текст поверх приложений.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OcrModel.entries.forEach { model ->
                    OptionsRow(
                        label = ENGINE_TITLES[model] ?: model.name,
                        selected = appEngine == model,
                        onSelect = {
                            prefs.appOcrEngine().set(model)
                            // Живой оверлей держит движок в своём поле, и без
                            // refresh следующий запрос ушёл бы в старую модель
                            // вплоть до перезапуска оверлея.
                            OcrOverlayService.refresh(context)
                        },
                    )
                }

                OverlaySectionTitle("Приложения")
                AppsList()
            }
        }
    }
}

@Composable
private fun WarningNote(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun OverlaySectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionsBlock(
    options: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            OptionsRow(label = label, selected = selected == value, onSelect = { onSelect(value) })
        }
    }
}

@Composable
private fun OptionsRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Список установленных приложений, на которые оверлей можно наложить.
 *
 * Оверлей кладётся поверх того, что видно, поэтому приложение сначала
 * открывается и только потом запускается оверлей — иначе панель повисла бы
 * над нашими же настройками.
 */
@Composable
private fun AppsList() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<InstalledApps.AppsState>(InstalledApps.AppsState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        // PackageManager и разбор иконок бьют в диск и binder: на главном
        // потоке список приложений открывал экран с белым фреймом.
        apps = withContext(Dispatchers.IO) {
            InstalledApps.loadState(context)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Выберите приложение — оверлей появится поверх него",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
        )
        IconButton(
            onClick = {
                reloadKey++
                context.toast("Обновляю список приложений")
            },
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Обновить список")
        }
    }

    TextButton(
        onClick = { OcrOverlayService.start(context) },
        modifier = Modifier.padding(horizontal = 12.dp),
    ) { Text("Запустить оверлей без приложения") }

    when (val state = apps) {
        is InstalledApps.AppsState.Loading ->
            Text(
                text = "Читаю список приложений…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )

        is InstalledApps.AppsState.Failed ->
            Text(
                text = "Не удалось прочитать список: ${state.reason}",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )

        is InstalledApps.AppsState.Ready -> {
            if (state.entries.isEmpty()) {
                Text(
                    text = "Запускаемых приложений не найдено",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            // Список в LazyColumn внутри скроллируемой Column нельзя — он
            // сам не измерит высоту и схлопнется в ноль. Поэтому обычный
            // Column: список запускаемых приложений короткий.
            Column {
                state.entries.forEach { entry ->
                    AppRow(
                        label = entry.label,
                        icon = entry.icon,
                        onClick = {
                            InstalledApps.launch(context, entry.packageName)
                            OcrOverlayService.start(context)
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    label: String,
    icon: android.graphics.drawable.Drawable?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Иконка приложения.
 *
 * `Icon()` умеет только [androidx.compose.ui.graphics.vector.ImageVector] и
 * Painter, а PackageManager отдаёт Drawable. Конвертируем в картинку сами:
 * тянуть accompanist ради одного значка незачем.
 */
@Composable
private fun AppIcon(drawable: android.graphics.drawable.Drawable) {
    val bitmap = remember(drawable) {
        runCatching {
            if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return@runCatching null
            android.graphics.Bitmap
                .createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                .also { bmp ->
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
        }.getOrNull()
    }
    if (bitmap == null) {
        Spacer(Modifier.size(36.dp))
        return
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(36.dp),
    )
}
