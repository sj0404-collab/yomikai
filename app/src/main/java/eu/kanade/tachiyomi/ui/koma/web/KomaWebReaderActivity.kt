package eu.kanade.tachiyomi.ui.koma.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Читалка HTML — та самая проверенная веб-версия, целиком, внутри приложения.
 *
 * Почему так: страница `koma-web.html` — единственная сборка читалки, которая
 * прошла сквозные тесты (`test-*.cjs` в репозитории `yomikai-web`: порядок
 * реплик, склейка облачков, словарь, декодер, скорость, кнопки, голос в WebView).
 * Compose-вариант KOMA повторял её логику вручную и на каждом шаге отставал:
 * то «0 областей» после успешного распознавания, то движок не тот. Поэтому
 * теперь есть ровно один источник правды — эта страница, — а приложение вокруг
 * неё даёт то, чего в WebView нет:
 *
 *  • **Голос.** В Android WebView Web Speech API отсутствует ВООБЩЕ (проверено
 *    тестом `test-voice-webview.cjs`: без моста страница падала на первом же
 *    вызове голоса). Здесь мост `KomaSpeech` на системном TTS.
 *  • **Нативный OCR.** Мост `KomaLens`: страница отдаёт кадр base64 и получает
 *    строки. Движок — Google Lens приложения, тот же, что в настройках.
 *
 * Контракт мостов взят из самого репозитория страницы (`test-apk-bridge.cjs`,
 * `test-voice-webview.cjs`), а не придуман: имена методов и событий совпадают
 * с тем, что страница уже зовёт.
 *
 * Адрес страницы постоянный (`https://appassets.androidplatform.net/web/...`),
 * а не `file://`: IndexedDB — а в нём библиотека, модели и настройки читалки —
 * привязан к источнику. На `file://` IndexedDB в WebView не работает вовсе.
 */
class KomaWebReaderActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var speech: KomaSpeech? = null
    private var callback: ValueCallback<Array<android.net.Uri>>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = KomaSpeech(this) { event ->
            // События голоса уходят в страницу ровно теми именами, которые она
            // ждёт: start/end/error по id реплики.
            webView?.evaluateJavascript(
                "window.__komaSpeechEvent && window.__komaSpeechEvent(${js(event.id)},${js(event.type)},${js(event.data)})",
                null,
            )
        }
        setContent {
            var ready by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.allowFileAccess = true
                                    settings.allowContentAccess = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    addJavascriptInterface(speech!!, "KomaSpeech")
                                    addJavascriptInterface(KomaLens(scope), "KomaLens")
                                    addJavascriptInterface(KomaApp(::versionBlock), "KomaApp")
                                    webViewClient = LocalAssets()
                                    webChromeClient = chooser()
                                    webView = this
                                    loadUrl(PAGE_URL)
                                }
                            },
                            update = { view -> if (view.url == null) view.loadUrl(PAGE_URL) },
                        )
                        if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
            // Готовность страницы нужна только для индикатора: саму логику
            // ведёт страница, приложение в неё не вмешивается.
            androidx.compose.runtime.LaunchedEffect(Unit) { ready = true }
        }
    }

    /** Выбор файлов (модели, книги) — штатный диалог Android поверх WebView. */
    private fun chooser() = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<android.net.Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            callback?.onReceiveValue(null)
            callback = filePathCallback
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return runCatching {
                startActivityForResult(Intent.createChooser(intent, "Выбрать файлы"), REQUEST_FILES)
                true
            }.getOrElse {
                callback = null
                false
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILES) {
            val uris = when {
                data == null -> null
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                else -> data.data?.let { arrayOf(it) }
            }
            callback?.onReceiveResult(if (resultCode == Activity.RESULT_OK) uris else null)
            callback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        val view = webView
        // Сначала «назад» внутри читалки, потом выход: у страницы своя история.
        if (view != null && view.canGoBack()) view.goBack() else finish()
    }

    override fun onDestroy() {
        speech?.shutdown()
        webView?.destroy()
        webView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun versionBlock(): String = buildString {
        append("{")
        append("\"name\":\"Yomikai\",")
        append("\"version\":\"").append(runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "").append("\",")
        append("\"native\":true,")
        append("\"speech\":").append(speech?.isReady() == true)
        append("}")
    }

    /**
     * Страница отдаётся из assets по постоянному https-адресу.
     *
     * `shouldInterceptRequest` вместо androidx.webkit — чтобы не тянуть ещё одну
     * зависимость: запрос всего один (вся читалка в одном файле).
     */
    private inner class LocalAssets : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (!url.startsWith(BASE_URL)) return null
            val path = "koma/web/" + url.removePrefix(BASE_URL).substringBefore('?')
            return runCatching {
                val stream = assets.open(path)
                val mime = when (path.substringAfterLast('.', "")) {
                    "html" -> "text/html"
                    "js" -> "application/javascript"
                    "json" -> "application/json"
                    "wasm" -> "application/wasm"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(mime, "utf-8", stream)
            }.getOrNull()
        }
    }

    private class SpeechEvent(val id: String, val type: String, val data: String)

    /**
     * Голос: системный Android TTS за мостом `KomaSpeech`.
     *
     * Контракт страницы (из `part4.js`): `status()`, `voices()`, `engines()`,
     * `useEngine(name)`, `speak(text, rate, lang, voice, id)`, `cancel()`.
     * Ответ о ходе реплики — событием `window.__komaSpeechEvent(id, type)`.
     */
    private class KomaSpeech(
        private val activity: Activity,
        private val onEvent: (SpeechEvent) -> Unit,
    ) {
        @Volatile
        private var ready = false
        @Volatile
        private var note: String? = null

        private val tts: TextToSpeech = TextToSpeech(activity.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    val result = tts.setLanguage(Locale("ru", "RU"))
                    ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ready) note = "Русский голос не установлен в системе"
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            utteranceId?.let { onEvent(SpeechEvent(it, "start", "")) }
                        }

                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { onEvent(SpeechEvent(it, "end", "")) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { onEvent(SpeechEvent(it, "error", "tts")) }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let { onEvent(SpeechEvent(it, "error", "tts:$errorCode")) }
                        }
                    })
                }.onFailure { note = it.message }
            } else {
                note = "Синтезатор речи недоступен на этом устройстве"
            }
        }

        fun isReady(): Boolean = ready

        @JavascriptInterface
        fun status(): String =
            "{\"ready\":$ready,\"note\":${quote(note ?: "")},\"engine\":\"android-tts\"}"

        @JavascriptInterface
        fun engines(): String {
            val engines = runCatching {
                tts.engines.map { it.label.replace("\"", "'") }
            }.getOrDefault(emptyList())
            return engines.joinToString(prefix = "[", postfix = "]") { quote(it) }
        }

        /** Сменить движок TTS по имени. false — такого движка нет. */
        @JavascriptInterface
        fun useEngine(name: String): Boolean = runCatching {
            val target = tts.engines.firstOrNull { it.label == name } ?: return false
            tts.engine = target.name
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun voices(): String {
            val list = runCatching {
                tts.voices.orEmpty()
                    .filter { it.locale.language == "ru" }
                    .map { v -> "{\"name\":${quote(v.name)},\"lang\":${quote(v.locale.toLanguageTag())}}" }
            }.getOrDefault(emptyList())
            val all = if (list.isEmpty()) {
                runCatching {
                    tts.voices.orEmpty().map { v ->
                        "{\"name\":${quote(v.name)},\"lang\":${quote(v.locale.toLanguageTag())}}"
                    }
                }.getOrDefault(emptyList())
            } else {
                list
            }
            return all.joinToString(prefix = "[", postfix = "]")
        }

        @JavascriptInterface
        fun speak(text: String, rate: String, lang: String, voice: String, id: String) {
            if (!ready) {
                onEvent(SpeechEvent(id, "error", note ?: "tts not ready"))
                return
            }
            runCatching {
                runCatching { tts.setSpeechRate(rate.toFloatOrNull()?.coerceIn(0.4f, 2.5f) ?: 1f) }
                runCatching { tts.setPitch(1f) }
                if (voice.isNotBlank()) {
                    runCatching { tts.voices?.firstOrNull { it.name == voice }?.let { tts.voice = it } }
                }
                if (lang.isNotBlank() && !lang.startsWith("ru")) {
                    runCatching { tts.setLanguage(Locale.forLanguageTag(lang)) }
                }
                val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (queued != TextToSpeech.SUCCESS) onEvent(SpeechEvent(id, "error", "queue"))
            }.onFailure { onEvent(SpeechEvent(id, "error", it.message ?: "tts")) }
        }

        @JavascriptInterface
        fun cancel() {
            runCatching { tts.stop() }
        }

        fun shutdown() {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    /**
     * Нативный OCR за мостом `KomaLens` — тот же путь, что у движка в настройках
     * приложения: кадр → OcrRepository (Google Lens) → строки.
     *
     * Вызов асинхронный (`__komaLensResolve[id]`), как и в проверенной обёртке:
     * синхронный держал бы поток JavaScript и подвешивал интерфейс.
     */
    private inner class KomaLens(private val scope: CoroutineScope) {

        @JavascriptInterface
        fun recognizeAsync(base64: String, optsJson: String, id: String) {
            scope.launch(Dispatchers.IO) {
                val answer = runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("кадр не декодировался")
                    val width = bitmap.width
                    val height = bitmap.height
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    bitmap.recycle()
                    val repository = Injekt.get<OcrRepository>()
                    val text = repository.recognizeText(OcrImage(width, height, pixels), OcrModel.GLENS)
                    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isEmpty()) error("движок не вернул текста")
                    buildString {
                        append("{\"lines\":[")
                        append(lines.joinToString(",") { quote(it) })
                        append("],\"ms\":0}")
                    }
                }.getOrElse { error -> "{\"error\":${quote(error.message ?: "мост недоступен")}}" }
                withMain {
                    webView?.evaluateJavascript(
                        "window.__komaLensResolve && window.__komaLensResolve[${js(id)}](${js(answer)})",
                        null,
                    )
                }
            }
        }
    }

    /** `window.KomaApp`: версия и готовность движков — для экрана «О читалке». */
    private inner class KomaApp(private val version: () -> String) {
        @JavascriptInterface
        fun info(): String = version()

        @JavascriptInterface
        fun native(): Boolean = true
    }

    private fun withMain(block: () -> Unit) {
        runCatching { runOnUiThread(block) }
    }

    companion object {
        private const val BASE_URL = "https://appassets.androidplatform.net/web/"
        private const val PAGE_URL = BASE_URL + "koma-web.html"
        private const val REQUEST_FILES = 4711

        fun newIntent(context: android.content.Context): Intent =
            Intent(context, KomaWebReaderActivity::class.java)

        /** Строковый литерал для вставки в JS — с экранированием. */
        private fun quote(value: String): String = "\"" + escape(value) + "\""

        private fun js(value: String): String = "\"" + escape(value) + "\""

        private fun escape(value: String): String = buildString(value.length + 8) {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    // Экранируем ВСЁ не-ASCII: так строка одинаково переживает
                    // любой способ вставки в JS и не ломается на суррогатах.
                    else -> if (c.code in 32..126) append(c) else append("\\u%04x".format(c.code))
                }
            }
        }
    }
}
