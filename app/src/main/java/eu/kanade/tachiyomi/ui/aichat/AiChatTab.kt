package eu.kanade.tachiyomi.ui.aichat

import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.ai.RunnerLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Вкладка «AI»: один слой — токен и запуск. После запуска в этой же вкладке
 * открывается сайт-хаб npm-hub (дашборд / терминал / файлы / git / инструменты)
 * с GitHub-ранера, где живут CLI-агенты. Агент НЕ форсится при запуске —
 * пользователь сам выбирает и устанавливает его внутри хаба (как в оригинале).
 */
data object AiChatTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 9u,
            title = "AI",
            icon = rememberVectorPainter(Icons.Outlined.SmartToy),
        )

    // Запуск переживает уход со вкладки и сворачивание приложения
    private val chatScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    private val hubStatusFlow = MutableStateFlow("")
    private val hubStartingFlow = MutableStateFlow(false)
    // Fix 5: активная сессия живёт на уровне ОБЪЕКТА, а не в remember —
    // при переключении вкладки (и обратно) WebView не «теряется».
    private val hubSessionFlow = MutableStateFlow<RunnerLlm.Session?>(null)

    private fun rememberSession(s: RunnerLlm.Session) {
        hubSessionFlow.value = s
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = remember { Injekt.get<OcrPreferences>() }
        val pat by prefs.githubPat().changes().collectAsState(initial = prefs.githubPat().get())
        val starting by hubStartingFlow.collectAsState()
        val status by hubStatusFlow.collectAsState()
        val session by hubSessionFlow.collectAsState()
        var sessions by remember {
            mutableStateOf(runCatching { RunnerLlm.listSessions(context) }.getOrDefault(emptyList()))
        }

        val openSession = session
        if (openSession != null) {
            HubWebView(
                session = openSession,
                onClose = { hubSessionFlow.value = null },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("OpenCode-агент", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Запускает сайт-хаб (npm-hub) на GitHub-ранере: дашборд, терминал, " +
                        "файлы, git, инструменты. Нужного CLI-агента (opencode, DeepSeek, UTIM, " +
                        "AI Shell и др.) вы сами выбираете и устанавливаете прямо внутри хаба. " +
                        "Ранер работает вашим PAT аккаунта, поэтому видит и меняет ВСЕ репозитории " +
                        "аккаунта. Нужен PAT с правом actions:write.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pat,
                    onValueChange = prefs.githubPat()::set,
                    label = { Text("GitHub PAT") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                var elapsed by remember { mutableStateOf(0L) }
                LaunchedEffect(starting) {
                    elapsed = 0
                    while (starting) {
                        delay(1000)
                        elapsed++
                    }
                }
                FilledTonalButton(
                    enabled = !starting && pat.isNotBlank(),
                    onClick = {
                        hubStartingFlow.value = true
                        hubStatusFlow.value = "⏳ Запуск хаба npm-hub…"
                        val appCtx = context.applicationContext
                        chatScope.launch {
                            val s = runCatching {
                                RunnerLlm.startOpenCode(
                                    appCtx,
                                    { st -> hubStatusFlow.value = st },
                                    os = "linux",
                                    ui = "mobile",
                                )
                            }.getOrElse {
                                hubStatusFlow.value = "❌ Ошибка запуска: ${it.message ?: it}"
                                null
                            }
                            withContext(Dispatchers.Main) {
                                hubStartingFlow.value = false
                                if (s != null) {
                                    sessions = runCatching { RunnerLlm.listSessions(context) }
                                        .getOrDefault(emptyList())
                                    rememberSession(s)
                                }
                            }
                        }
                    },
                ) { Text(if (starting) "Запускаем…" else "▶ Запустить") }
                if (status.isNotBlank()) {
                    Text(
                        if (starting) "$status • ${elapsed / 60}:${"%02d".format(elapsed % 60)}"
                        else status,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (sessions.isNotEmpty()) {
                    Text("Сессии:", style = MaterialTheme.typography.bodySmall)
                    sessions.forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${s.model} • ${s.messages.size} сообщ. • ${s.os}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (s.terminalUrl != null || s.url != null) {
                                FilterChip(
                                    selected = false,
                                    onClick = { rememberSession(s) },
                                    label = { Text("Открыть") },
                                )
                            }
                            FilterChip(
                                selected = false,
                                onClick = {
                                    runCatching {
                                        RunnerLlm.deleteSession(context, s)
                                        sessions = RunnerLlm.listSessions(context)
                                    }
                                },
                                label = { Text("✕") },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Сайт-хаб npm-hub в той же вкладке: loading-экран вместо чёрного + ошибки. */
    @Composable
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun HubWebView(
        session: RunnerLlm.Session,
        onClose: () -> Unit,
    ) {
        val context = LocalContext.current
        val url = session.terminalUrl ?: session.url.orEmpty()
        var loading by remember(session.id) { mutableStateOf(true) }
        var error by remember(session.id) { mutableStateOf<String?>(null) }
        val webView = remember(session.id, url) {
            runCatching {
                WebView(context).apply {
                    // На чёрном фоне «протухающий» туннель выглядел как мёртвый экран.
                    setBackgroundColor(android.graphics.Color.WHITE)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = false
                    settings.useWideViewPort = false
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler,
                            host: String?,
                            realm: String?,
                        ) {
                            runCatching { handler.proceed("yomikai", session.apiKey.orEmpty()) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            error = null
                            view?.requestFocus()
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            loading = false
                            error = "Ошибка загрузки ($errorCode): $description"
                        }
                    }
                    if (url.isNotBlank()) loadUrl(url)
                }
            }.getOrElse {
                error = "WebView не создался: ${it.message ?: it}"
                null
            }
        }
        DisposableEffect(webView) {
            onDispose {
                runCatching {
                    (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView?.stopLoading()
                    webView?.destroy()
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "npm-hub • ${session.os}" +
                        (if (loading) " • подключение…" else " • сайт"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    loading = true
                    error = null
                    runCatching { webView?.reload() }
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Перезагрузить")
                }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
            if (webView != null && error == null) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        error ?: "Сайт хаба недоступен. Проверьте, что ранер ещё жив.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(
                        onClick = { error = null; loading = true; runCatching { webView?.reload() } },
                    ) { Text("Попробовать снова") }
                    TextButton(onClick = onClose) { Text("Закрыть и к запуску") }
                }
            }
        }
    }
}