package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.domain.source.model.ContentType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Тип контента (источник) — раньше это были чипы над нижней навигацией.
 * Теперь переключение живёт в настройках: одна кнопка открывает список типов,
 * где видно, какие вкладки/источники затронет переключение. Само переключение
 * только пишет преф [SourcePreferences.contentType] — состояния вкладок и кеш
 * не сбрасываются, приложение не перезапускается.
 */
object SettingsContentTypeScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.content_type

    @Composable
    override fun getPreferences(): List<Preference> {
        val sourcePrefs = remember { Injekt.get<SourcePreferences>() }
        val current by sourcePrefs.contentType.collectAsState()

        val items = ContentType.entries.map { type ->
            Preference.PreferenceItem.TextPreference(
                title = "${type.icon} ${type.displayName}",
                subtitle = when (type) {
                    ContentType.MANGA -> "Источники RU/EN/JA. Типовой вкладки нет — работает текущая библиотека; «Браузер» и «AI» не меняются."
                    ContentType.ANIME -> "Источники RU/EN/JA; появляется вкладка «Кинозал» (видео). «Браузер» и «AI» не меняются."
                    ContentType.RANOBE -> "Источники RU/EN; появляется вкладка «Ранобэ». «Браузер» и «AI» не меняются."
                    ContentType.BOOKS -> "Источники RU/EN; появляется вкладка «Аудиокниги». «Браузер» и «AI» не меняются."
                    ContentType.DRAMAS -> "Источники RU/JA/EN; появляется вкладка «Кинозал» (дорамы). «Браузер» и «AI» не меняются."
                },
                widget = {
                    if (current == type) {
                        Text(
                            text = "✓",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { sourcePrefs.contentType.set(type) },
            )
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.content_type),
                preferenceItems = items + Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.content_type_summary),
                ),
            ),
        )
    }
}