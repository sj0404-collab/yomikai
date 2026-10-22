package eu.kanade.tachiyomi.ui.koma

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.manga.model.readingMode
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Настройки читалки — как в KOMA.
 *
 * В прежней читалке настроек было несколько экранов (жесты, полосы, отступы,
 * масштаб, «Text Recognition» с десятком переключателей движков). Здесь
 * оставлено только то, что реально меняют при чтении: порядок чтения, язык
 * распознавания, авточтение и автопрокрутка. Всё остальное из читалки никуда
 * не делось — оно живёт в своих кнопках, а полный список настроек открывается
 * одной строкой внизу.
 */
@Composable
fun KomaReaderSettingsSheet(
    manga: Manga?,
    onDismissRequest: () -> Unit,
    onChangeReadingMode: (Int) -> Unit,
    onAutoscrollToggle: (Boolean, Float) -> Unit,
    autoscrollActive: Boolean,
    onOpenVoiceSettings: () -> Unit,
    onOpenFullSettings: () -> Unit,
    onReadPage: () -> Unit,
) {
    val prefs: OcrPreferences = remember { Injekt.get() }

    var model by remember { mutableStateOf(prefs.ocrModel().get()) }
    var autoStart by remember { mutableStateOf(prefs.autoReadAutoStart().get()) }
    var autoScroll by remember { mutableStateOf(autoscrollActive) }
    var speed by remember { mutableStateOf(2f) }
    val readingMode = manga?.readingMode?.toInt() ?: 1

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Text("Как читать эту историю", fontSize = 23.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Распознавание — движок KOMA (PP-OCRv4 + CTC), модели в APK",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("ПОРЯДОК ЧТЕНИЯ")
        listOf(
            2 to "Справа налево · манга",
            1 to "Слева направо · комиксы",
            3 to "Вертикальная лента · манхва",
        ).forEach { (flag, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChangeReadingMode(flag) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = readingMode == flag, onClick = null)
                Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }

        SectionLabel("ЯЗЫК РАСПОЗНАВАНИЯ")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                OcrModel.CYRILLIC to "Кириллица",
                OcrModel.MLKIT to "Латиница",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = model == value,
                    onClick = {
                        model = value
                        prefs.ocrModel().set(value)
                    },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }

        SectionLabel("АВТОЧТЕНИЕ")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Читать сразу при открытии главы", fontSize = 13.sp)
                Text("Реплики в порядке чтения, затем следующая страница", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = autoStart, onCheckedChange = { autoStart = it; prefs.autoReadAutoStart().set(it) })
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Голос и скорость", fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenVoiceSettings) { Text("настроить", fontSize = 12.sp) }
        }
        TextButton(onClick = onReadPage) { Text("Прочитать эту страницу", fontSize = 12.sp) }

        SectionLabel("АВТОПРОКРУТКА")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Прокручивать к текущей реплике", fontSize = 13.sp)
                Text("Работает в вертикальной ленте во время чтения", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = autoScroll,
                onCheckedChange = {
                    autoScroll = it
                    onAutoscrollToggle(it, speed)
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Скорость", fontSize = 13.sp, modifier = Modifier.weight(1f))
            listOf(1f to "медленно", 2f to "обычно", 4f to "быстро").forEach { (value, label) ->
                FilterChip(
                    selected = speed == value,
                    onClick = {
                        speed = value
                        if (autoScroll) onAutoscrollToggle(true, value)
                    },
                    label = { Text(label, fontSize = 11.sp) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onOpenFullSettings) { Text("Все настройки читалки", fontSize = 12.sp) }
        TextButton(onClick = onDismissRequest) { Text("Закрыть", fontSize = 12.sp) }
    }
}
