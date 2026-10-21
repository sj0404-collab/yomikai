package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OcrModelStep : OnboardingStep {

    override val isComplete: Boolean = true

    /**
     * Шаг онбординга про OCR больше НЕ качает модели.
     *
     * Раньше здесь предлагалось «скачать Cyrillic OCR (~21 МБ)»: на первом
     * запуске это была сетевая загрузка пяти файлов, и без интернета (или при
     * сбое уведомлений) шаг ронял приложение. Модели KOMA (PP-OCRv4 детектор +
     * CTC PP-OCRv5 распознаватель + словарь) лежат в APK, поэтому выбора
     * «скачать/не скачать» больше нет — есть только выбор движка.
     */
    @Composable
    override fun Content() {
        val preferences = remember { Injekt.get<OcrPreferences>() }
        var selectedChoice by remember { mutableStateOf(0) }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Русский офлайн OCR",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Модели распознавания уже внутри APK: ничего скачивать не нужно, " +
                    "интернет для OCR не требуется.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            EngineCard(
                title = "KOMA / ONNX — рекомендуется",
                subtitle = "PP-OCRv4 детектор + CTC PP-OCRv5 распознаватель и словарь вкомпилированы. " +
                    "Русский и кириллица полностью офлайн, рамки по строкам.",
                selected = selectedChoice == 0,
                onClick = {
                    selectedChoice = 0
                    preferences.ocrModel().set(OcrModel.CYRILLIC)
                },
            )

            EngineCard(
                title = "Google ML Kit (латиница/японский)",
                subtitle = "Тоже офлайн и тоже в APK. Для манги с латинскими и японскими " +
                    "надписями; кириллицу читает хуже, поэтому по умолчанию выбран KOMA.",
                selected = selectedChoice == 1,
                onClick = {
                    selectedChoice = 1
                    preferences.ocrModel().set(OcrModel.MLKIT)
                },
            )
        }
    }

    @Composable
    private fun EngineCard(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected, onClick = null)
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
