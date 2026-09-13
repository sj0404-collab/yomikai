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
import androidx.compose.foundation.layout.size
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
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Вкладка «AI»: один слой — токен и запуск. После запуска в этой же вкладке
 * открывается npm-hub (терминал / файлы / дашборд) с GitHub-ранера, где живёт
 * OpenCode-агент. Никаких лишних под-вкладок и настроек.
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
    private val hubStatusFlow = kotlinx.coroutines.flow.MutableStateFlow("")
    private val hubStartingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = remember { Injekt.get<OcrPreferences>() }
        val pat by prefs.githubPat().changes().collectAsState(initial = prefs.githubPat().get())
        val starting by hubStartingFlow.collectAsState()
        val status by hubStatusFlow.collectAsState()
        var sessions by remember { mutableStateOf(RunnerLlm.listSessions(context)) }
        var openSession by remember {
            mutableStateOf<RunnerLlm.Session?>(null)
        }

        if (openSession != null) {
            HubWebView(
                session = openSession!!,
                onClose = { openSession = null },
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
                    "Запускает opencode + npm-hub (терминал, файлы, дашборд, git) на GitHub-ранере. " +
                        "Агент работает вашим PAT аккаунта, поэтому видит и меняет ВСЕ репозитории, " +
                        "коммиты и логи аккаунта. Нужен PAT с правом actions:write.",
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
                        hubStatusFlow.value = "⏳ Запуск OpenCode-агента…"
                        val appCtx = context.applicationContext
                        chatScope.launch {
                            val s = RunnerLlm.startOpenCode(
                                appCtx,
                                { st -> hubStatusFlow.value = st },
                                os = "linux",
                                ui = "mobile",
                            )
                            withContext(Dispatchers.Main) {
                                hubStartingFlow.value = false
                                if (s != null) {
                                    sessions = RunnerLlm.listSessions(context)
                                    openSession = s
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
                                "${s.model} • ${s.messages.size} сообщ.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (s.terminalUrl != null || s.url != null) {
                                FilterChip(
                                    selected = false,
                                    onClick = { openSession = s },
                                    label = { Text("Открыть") },
                                )
                            }
                            FilterChip(
                                selected = false,
                                onClick = {
                                    RunnerLlm.deleteSession(context, s)
                                    sessions = RunnerLlm.listSessions(context)
                                },
                                label = { Text("✕") },
                            )
                        }
                    }
                }
            }
        }
    }

    /** npm-hub / мобильный веб интерфейс в той же вкладке. */
    @Composable
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun HubWebView(
        session: RunnerLlm.Session,
        onClose: () -> Unit,
    ) {
        val context = LocalContext.current
        val url = session.terminalUrl ?: session.url.orEmpty()
        var loading by remember(session.id) { mutableStateOf(true) }
        val webView = remember(session.id, url) {
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView?,
                        handler: HttpAuthHandler,
                        host: String?,
                        realm: String?,
                    ) {
                        handler.proceed("yomikai", session.apiKey.orEmpty())
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        loading = false
                        view?.requestFocus()
                    }
                }
                if (url.isNotBlank()) loadUrl(url)
            }
        }
        DisposableEffect(webView) {
            onDispose {
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
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
                        (if (loading) " • подключение…" else " • терминал"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { loading = true; webView.reload() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Перезагрузить")
                }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}