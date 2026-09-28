package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.ai.AiBackendState
import eu.kanade.tachiyomi.data.ai.AiBackendStatus
import eu.kanade.tachiyomi.data.ai.AiBackends
import eu.kanade.tachiyomi.data.ai.AiPlugins
import eu.kanade.tachiyomi.data.ai.AiProviders
import eu.kanade.tachiyomi.data.ai.AiRequirement
import eu.kanade.tachiyomi.data.ai.AiWorkspace
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

/**
 * Настройки AI-ассистента: реестр бэкендов чата с их готовностью на этом
 * устройстве, состояние рабочей области и плагинов разработчика, плюс
 * доступ к самой вкладке «AI».
 *
 * Переключатели бэкенда, моделей и ключей намеренно НЕ дублируются: они живут
 * в настройках вкладки AI (⚙), и второй источник истины мгновенно разошёлся бы
 * с первым. Задача этого экрана — показать, что выбрано, готово ли оно и где
 * это менять.
 */
object SettingsAiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_ai

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<OcrPreferences>() }

        val online = rememberNetworkState(context)
        val backend by prefs.aiBackend().collectPreferenceAsState()
        val provider by prefs.aiProvider().collectPreferenceAsState()

        // Снимок состояния бэкендов читает сеть и файлы (сессии ранера,
        // установленные .task-модели), а список плагинов и workspace — это
        // обход дерева каталогов. Раньше всё это выполнялось прямо в
        // recomposition, то есть на главном потоке, и на большом workspace
        // экран подвисал. Теперь считаем в фоне, а до готовности показываем
        // заглушку.
        var snapshot by remember { mutableStateOf<AiBackendState?>(null) }
        var disk by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var userProviders by remember { mutableStateOf<List<AiProviders.Spec>>(emptyList()) }
        LaunchedEffect(context) {
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    AiBackends.state(context, prefs),
                    runCatching { AiPlugins.list(context).size to AiWorkspace.listAll(context).size }
                        .getOrDefault(0 to 0),
                    AiProviders.list(context),
                )
            }
            snapshot = loaded.first
            disk = loaded.second
            userProviders = loaded.third
        }

        val statuses = remember(snapshot, provider, online) {
            // Сеть меняется отдельно от файлового снимка, поэтому статус
            // пересчитываем с её актуальным значением.
            snapshot?.let { state ->
                val effective = state.copy(networkAvailable = online)
                AiBackends.ALL.associate { it.id to AiBackends.statusOf(it, effective, provider) }
            }.orEmpty()
        }

        return listOf(
            getBackendsGroup(
                prefs = prefs,
                statuses = statuses,
                selected = backend,
                loading = snapshot == null,
                online = online,
            ),
            getModelKeyGroup(prefs = prefs, userProviders = userProviders),
            getWorkspaceGroup(plugins = disk?.first, files = disk?.second),
            getAccessGroup(prefs = prefs, context = context, navigator = navigator),
        )
    }

    /**
     * «Модели и ключи»: провайдер, модель Zen, ключ и модель OpenRouter.
     * Пишет в те же преференсы (`aiProvider`, `zenModel`, `openrouterApiKey`,
     * `openrouterFreeModel`), что и настройки AI-чата и модель-пикер в читалке,
     * поэтому расхождений между экранами нет.
     */
    @Composable
    private fun getModelKeyGroup(
        prefs: OcrPreferences,
        userProviders: List<eu.kanade.tachiyomi.data.ai.AiProviders.Spec>,
    ): Preference.PreferenceGroup {
        val providerEntries = buildMap {
            put(eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN, "Zen (без ключа)")
            put(eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER, "OpenRouter")
            // Свои провайдеры (id -> название) добавляются как обычный элемент
            // списка, потому что aiProvider хранит именно их id.
            userProviders.forEach { spec ->
                put(spec.id, spec.title.ifBlank { spec.id })
            }
        }
        return Preference.PreferenceGroup(
            title = "Модели и ключи",
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.aiProvider(),
                    entries = providerEntries,
                    title = "Провайдер",
                    subtitle = "Провайдер: %s",
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.zenModel(),
                    entries = eu.kanade.tachiyomi.data.ai.AiAssistant.ZEN_MODELS.associateWith { it },
                    title = "Модель Zen",
                    subtitle = "Модель: %s",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.openrouterApiKey(),
                    title = "OpenRouter API-ключ",
                    subtitle = "Ключ: %s",
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.openrouterFreeModel(),
                    title = "OpenRouter модель (:free)",
                    subtitle = "Модель: %s",
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = "Без ключа работают бесплатные модели Zen. Для OpenRouter нужен ключ; модель можно выбрать из списка :free.",
                ),
            ),
        )
    }

    @Composable
    private fun getBackendsGroup(
        prefs: OcrPreferences,
        statuses: Map<String, AiBackendStatus>,
        selected: String,
        loading: Boolean,
        online: Boolean,
    ): Preference.PreferenceGroup {
        if (loading) {
            return Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_ai_backends_group),
                preferenceItems = listOf(
                    Preference.PreferenceItem.InfoPreference(title = "Проверяю бэкенды…"),
                ),
            )
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_ai_backends_group),
            preferenceItems = listOf(
                // Сам выбор бэкенда. Раньше строки ниже были чисто
                // информационными, а подсказка отправляла читателя в «⚙
                // вкладки AI», которой не существует — переключить бэкенд
                // было негде.
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.aiBackend(),
                    entries = AiBackends.SELECTABLE.associate { it.id to it.title },
                    title = "Бэкенд чата",
                    subtitle = "Бэкенд: %s",
                ),
            ) + AiBackends.ALL.map { plugin ->
                Preference.PreferenceItem.TextPreference(
                    title = backendTitle(
                        plugin = plugin,
                        isSelected = plugin.id == selected,
                        status = statuses[plugin.id],
                    ),
                    subtitle = backendSubtitle(
                        plugin = plugin,
                        status = statuses[plugin.id],
                        online = online,
                    ),
                )
            } + listOf(
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.pref_ai_backend_current)
                        .format(AiBackends.byId(selected).title),
                ),
            ),
        )
    }

    @Composable
    private fun getWorkspaceGroup(plugins: Int?, files: Int?): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_ai_workspace_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.InfoPreference(
                    title = if (files == null) {
                        "Считаю файлы рабочей области…"
                    } else {
                        stringResource(MR.strings.pref_ai_workspace_files).format(files)
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = if (plugins == null) {
                        "Считаю плагины…"
                    } else {
                        stringResource(MR.strings.pref_ai_workspace_plugins).format(plugins)
                    },
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(MR.strings.pref_ai_workspace_hint),
                ),
            ),
        )

    @Composable
    private fun getAccessGroup(
        prefs: OcrPreferences,
        context: Context,
        navigator: Navigator,
    ): Preference.PreferenceGroup =
        Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_ai_access_group),
            preferenceItems = listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_ai_open_chat),
                    subtitle = stringResource(MR.strings.pref_ai_open_chat_summary),
                    onClick = {
                        // Тот же приём, что у кнопки «настройки OCR» в читалке:
                        // вкладка AI живёт в нижней навигации, поэтому открываем
                        // её интентом с extra, а не навигатором настроек.
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_AI_CHAT, true),
                        )
                        navigator.pop()
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.aiTabVisible(),
                    title = stringResource(MR.strings.pref_ai_tab_visible),
                    subtitle = stringResource(MR.strings.pref_ai_tab_visible_summary),
                ),
                // Разрешения, которые проверяет агент. Раньше переключателей не
                // было нигде, поэтому `runner_*` и `github_api` были мёртвыми
                // инструментами: агент видел их в промпте и получал отказ на
                // каждом вызове, а требование «ранер разрешён» нечем было
                // выполнить.
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.aiAllowRunner(),
                    title = "Разрешить ранер",
                    subtitle = "Агент сможет запускать OpenCode-сессии (широкий доступ к репозиториям)",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.aiAllowGithub(),
                    title = "Разрешить GitHub",
                    subtitle = "Агент сможет читать воркфлоу и запускать их через привязанный токен",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.aiHttpServer(),
                    title = stringResource(MR.strings.pref_ai_http_server),
                    subtitle = stringResource(MR.strings.pref_ai_http_server_summary),
                ),
            ),
        )

    @Composable
    private fun backendTitle(
        plugin: AiBackends.Plugin,
        isSelected: Boolean,
        status: AiBackendStatus?,
    ): String = buildString {
        append(plugin.title)
        append(" • ")
        append(
            when {
                isSelected -> stringResource(MR.strings.pref_ai_backend_selected)
                status?.available == true -> stringResource(MR.strings.pref_ai_backend_ready)
                else -> stringResource(MR.strings.pref_ai_backend_not_ready)
            },
        )
        if (plugin.offline) {
            append(" • ")
            append(stringResource(MR.strings.pref_ai_backend_offline))
        }
    }

    @Composable
    private fun backendSubtitle(
        plugin: AiBackends.Plugin,
        status: AiBackendStatus?,
        online: Boolean,
    ): String = buildString {
        append(plugin.summary)
        if (status == null) return@buildString
        append('\n')
        append(status.detail)
        if (status.missing.isNotEmpty()) {
            // Подписи требований готовим до joinToString: внутри его лямбды
            // @Composable вызывать нельзя.
            val labels = status.missing.associateWith { requirementLabel(it) }
            append('\n')
            append(stringResource(MR.strings.pref_ai_requires))
            append(": ")
            append(status.missing.joinToString(", ") { labels.getValue(it) })
        } else if (!online && plugin.requirements.contains(AiRequirement.NETWORK)) {
            append('\n')
            append("Сети нет — бэкенд сейчас не ответит")
        }
    }

    @Composable
    private fun requirementLabel(requirement: AiRequirement): String =
        stringResource(
            when (requirement) {
                AiRequirement.NETWORK -> MR.strings.pref_ai_requires_network
                AiRequirement.OPENROUTER_KEY -> MR.strings.pref_ai_requires_openrouter_key
                AiRequirement.GITHUB_PAT -> MR.strings.pref_ai_requires_github_pat
                AiRequirement.RUNNER_ALLOWED -> MR.strings.pref_ai_requires_runner_allowed
                AiRequirement.MODEL_DOWNLOAD -> MR.strings.pref_ai_requires_model
                AiRequirement.RUNNER_SESSION -> MR.strings.pref_ai_requires_runner_session
            },
        )
}
