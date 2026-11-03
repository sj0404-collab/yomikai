package eu.kanade.tachiyomi.ui.koma.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import eu.kanade.tachiyomi.ui.koma.KomaOcrEngine
import eu.kanade.tachiyomi.ui.koma.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Читалка — это проверенная HTML-страница, целиком, внутри приложения.
 *
 * Разбор полётов после 2.9.42: страница при загрузке ищет в приложении семь
 * мостов, и я сделал не тот. Она зовёт `window.KomaOcr` (нативный движок
 * распознавания: есть/нет моделей, распознать кадр, самопроверка), а я отдал ей
 * `KomaLens` (это Google Lens, онлайн) — то есть страница не видела ни моделей,
 * ни движка и оставалась пустой. Здесь мосты сделаны ровно по контракту,
 * который страница проверяет своими же тестами:
 *
 *  • `KomaOcr`   — нативный KOMA-движок (ONNX Runtime Android, модели вшиты в
 *                  АПК): `available()`, `status()`, `recognizeAsync(b64, rules, id)`,
 *                  `selfTestAsync(id)`, `pickModels()`; прогресс и ответ —
 *                  событием `window.__komaOcrEvent(id, 'state'|'done', data)`;
 *  • `KomaSpeech`— системный Android TTS (в WebView нет Web Speech API):
 *                  `status()`, `voices()`, `engines()`, `useEngine()`,
 *                  `speak(text,rate,lang,voice,id)`, `cancel()`, событие
 *                  `window.__komaSpeechEvent(id, 'start'|'end'|'error')`;
 *  • `KomaLens`  — онлайн-Lens приложения: `recognizeAsync(b64, opts, id)`,
 *                  ответ в `window.__komaLensResolve[id]`;
 *  • `KomaApp`   — `version()`, `checkUpdate(id)`, `reloadNow(reason)`,
 *                  `lensSelfTest()`.
 *
 * Кадр в `KomaOcr` приходит полосой (то, что видно на экране), и строки
 * возвращаются в ДОЛЯХ КАДРА — ровно как ждёт страница. Поэтому читается
 * видимая область, страница за страницей, а не весь лист целиком.
 *
 * Книга из библиотеки приложения открывается сразу здесь: страницы отдаются по
 * `https://appassets.androidplatform.net/appbook/<id>/<страница>`, а страница
 * получает их своим обычным путём (Library/Reader). Никаких копий в IndexedDB.
 *
 * Чёрный экран: любая ошибка загрузки больше не молчит — причина видна на
 * экране (console, HTTP, ошибка WebView, сторож по времени).
 */
class KomaWebReaderActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var speech: KomaSpeech? = null
    private var callback: ValueCallback<Array<android.net.Uri>>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val page = lazy { seedPage() }
    private val seedLock = Any()

    /** Что случилось при загрузке — показывается на экране, а не в logcat. */
    private var problem by mutableStateOf<List<String>>(emptyList())
    private val notes = LinkedHashSet<String>()
    private var pageFinished = false
    private var bookKey = ""
    private val engines = HashMap<String, KomaOcrEngine>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookKey = intent.getStringExtra(EXTRA_BOOK) ?: ""
        speech = KomaSpeech(this) { event -> emit("__komaSpeechEvent", event) }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.allowContentAccess = true
                                    settings.allowFileAccess = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                    addJavascriptInterface(speech!!, "KomaSpeech")
                                    addJavascriptInterface(KomaOcr(), "KomaOcr")
                                    addJavascriptInterface(KomaLens(), "KomaLens")
                                    addJavascriptInterface(KomaApp(), "KomaApp")
                                    webViewClient = LocalAssets()
                                    webChromeClient = chooser()
                                    webView = this
                                    loadUrl(PAGE_URL)
                                    // Сторож: если через 10 с страница не отчиталась,
                                    // чёрный экран объясняется словами, а не молчанием.
                                    postDelayed({ checkAlive() }, 10_000)
                                }
                            },
                        )
                        if (problem.isNotEmpty()) Problem(problem) { reload() }
                    }
                }
            }
        }
    }

    private fun reload() {
        problem = emptyList()
        notes.clear()
        pageFinished = false
        webView?.loadUrl(PAGE_URL)
    }

    private fun checkAlive() {
        val view = webView ?: return
        if (pageFinished) return
        view.evaluateJavascript(
            "(function(){return JSON.stringify({st:document.readyState," +
                "title:document.title,body:(document.body?document.body.innerText:'пусто').slice(0,120)});})()",
        ) { raw ->
            if (pageFinished) return@evaluateJavascript
            val seen = raw?.trim()?.takeIf { it.length > 2 } ?: "нет ответа от страницы"
            show("страница не отчиталась за 10 с", seen)
        }
    }

    private fun show(title: String, detail: String) {
        val lines = ArrayList<String>()
        lines += title
        if (detail.isNotBlank()) lines += detail
        lines += notes.toList()
        problem = lines
    }

    private fun emit(name: String, event: SpeechEvent) {
        webView?.evaluateJavascript(
            "window.$name && window.$name(${js(event.id)},${js(event.type)},${js(event.data)})",
            null,
        )
    }

    /** Выбор файлов (модели, книги) — штатный диалог Android поверх WebView. */
    private fun chooser() = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val text = message.message()
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                if (notes.size < 6) notes += "console: " + text.take(200)
            }
            Log.w("KomaWeb", text)
            return true
        }

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
            callback?.onReceiveValue(if (resultCode == Activity.RESULT_OK) uris else null)
            callback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else if (problem.isNotEmpty()) {
            reload()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        speech?.shutdown()
        engines.values.forEach { runCatching { it.close() } }
        engines.clear()
        webView?.destroy()
        webView = null
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────── страница и её файлы ───────────────────────────

    /**
     * Страница копируется из assets в filesDir при первом запуске (и после
     * обновления версии). Зачем копия: у `AssetManager` есть предел на файлы
     * больше 1 МБ при открытии по дескриптору, а страница весит 11 МБ. Из файла
     * она отдаётся обычным потоком и не зависит от того, как aapt её упаковал.
     */
    private fun seedPage(): File = synchronized(seedLock) {
        val dir = File(filesDir, "web").apply { mkdirs() }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "0"
        val file = File(dir, "koma-web-$version.html")
        if (!file.exists() || file.length() < 1000L) {
            dir.listFiles()?.forEach { if (it != file) runCatching { it.delete() } }
            runCatching {
                assets.open("koma/web/koma-web.html").use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                }
            }.onFailure {
                Log.e("KomaWeb", "страница не скопировалась", it)
                show("страница не скопировалась из АПК", it.toString())
            }
        }
        file
    }

    private fun book(): eu.kanade.tachiyomi.ui.koma.Book? =
        if (bookKey.isBlank()) null
        else runCatching {
            LibraryRepository(applicationContext).load().firstOrNull { it.id == bookKey }
        }.getOrNull()

    /** Байты страницы книги: ассет приложения или файл на диске — как есть. */
    private fun pageStream(index: Int): InputStream? = runCatching {
        val book = book() ?: return null
        val path = book.pages.getOrNull(index) ?: return null
        if (path.startsWith("file:///android_asset/")) {
            assets.open(path.removePrefix("file:///android_asset/"))
        } else {
            File(path.removePrefix("file://")).inputStream()
        }
    }.getOrNull()

    private inner class LocalAssets : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (!url.startsWith(ORIGIN)) return null
            val path = url.removePrefix(ORIGIN).substringBefore('?')
            return when {
                path.startsWith("web/") -> {
                    val file = page.value
                    if (!file.exists()) {
                        show("файла страницы нет", file.absolutePath)
                        null
                    } else {
                        WebResourceResponse("text/html", "utf-8", file.inputStream())
                    }
                }
                path.startsWith("appbook/") -> {
                    val rest = path.removePrefix("appbook/").split('/')
                    val index = rest.getOrNull(1)?.toIntOrNull() ?: -1
                    val name = rest.getOrNull(1) ?: "page.jpg"
                    val stream = pageStream(index)
                    if (stream == null) {
                        WebResourceResponse("text/plain", "utf-8", "нет страницы $index".byteInputStream())
                    } else {
                        WebResourceResponse(mimeOf(name), null, stream)
                    }
                }
                else -> null
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            show(
                "WebView не смог загрузить " + (request?.url?.toString() ?: "страницу"),
                (error?.description?.toString() ?: "") + " (код " + error?.errorCode + ")",
            )
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            pageFinished = true
            problem = emptyList()
            // Книгу открываем сами: страница получает её своим обычным путём.
            val book = book() ?: return
            view?.evaluateJavascript(openBookScript(book.id, book.title, book.pages.size), null)
        }
    }

    /**
     * Открыть книгу приложения в странице.
     *
     * Страницы забираются её же `fetch` с постоянного адреса (файлы приложения,
     * без копий), дальше — обычный путь страницы: Library.pages → Reader.open.
     * Поэтому работают полосы по видимой области, склейка реплик, словарь,
     * порядок чтения и озвучка — всё то, что в странице уже проверено.
     */
    private fun openBookScript(id: String, title: String, count: Int): String = buildString {
        append("(async function(){ try {\n")
        append("  const appId = 'app:' + ").append(js(id)).append(";\n")
        append("  const n = ").append(count).append(";\n")
        append("  const rows = [];\n")
        append("  for (let i = 0; i < n; i++) {\n")
        append("    const url = 'appbook/' + encodeURIComponent(").append(js(id)).append(") + '/' + i;\n")
        append("    const r = await fetch(url);\n")
        append("    if (!r.ok) throw new Error('страница ' + (i + 1) + ': HTTP ' + r.status);\n")
        append("    const blob = await r.blob();\n")
        append("    rows.push({ key: appId + '/' + i, id: appId, index: i, name: (i + 1) + '.jpg', blob: blob });\n")
        append("  }\n")
        append("  const orig = Library.pages.bind(Library);\n")
        append("  Library.pages = async (bid) => (bid === appId ? rows : orig(bid));\n")
        append("  const book = { id: appId, title: ").append(js(title))
        append(", kind: 'app', count: rows.length, at: Date.now(), progress: 0, mode: 'vertical', cover: rows[0] && rows[0].blob };\n")
        append("  await Reader.open(book);\n")
        append("} catch (e) { try { toast('Книга не открылась: ' + ((e && e.message) || e), 7000); } catch (x) {} } })()")
    }

    // ─────────────────────────── мост OCR (нативный движок) ───────────────────────────

    /**
     * `window.KomaOcr` — распознавание НАТИВНО, тем же движком, что в читалке.
     *
     * Кадр приходит из страницы как JPEG base64 (полоса, которую видно на
     * экране). Возвращаем строки в долях кадра: так страница сама раскладывает
     * их по порядку чтения, склеивает реплики и озвучивает.
     */
    private inner class KomaOcr {

        private fun engineLocked(): KomaOcrEngine = synchronized(engines) {
            engines.getOrPut("koma") { KomaOcrEngine(applicationContext).also { it.warmUp() } }
        }

        private fun modelFiles(): Map<String, Long> = runCatching {
            assets.list("koma/models").orEmpty()
                .filter { it.endsWith(".onnx") || it.endsWith(".txt") }
                .associateWith { name ->
                    runCatching { assets.open("koma/models/$name").use { s -> s.available().toLong() } }.getOrDefault(0L)
                }
        }.getOrDefault(emptyMap())

        /** Есть ли движок и модели: страница по этому ответу выбирает нативный путь. */
        @JavascriptInterface
        fun available(): Boolean {
            val files = modelFiles()
            return files.keys.any { it.endsWith(".onnx") } && files.keys.any { it == "cyrillic.onnx" }
        }

        @JavascriptInterface
        fun status(): String {
            val files = modelFiles()
            val det = files["detection.onnx"] ?: 0L
            val rec = files["cyrillic.onnx"] ?: 0L
            val classes = runCatching {
                assets.open("koma/models/cyrillic.txt").bufferedReader().readText().trim().length
            }.getOrDefault(0)
            val ready = det > 0 && rec > 0
            return buildString {
                append("{")
                append("\"present\":true,\"ready\":").append(ready)
                append(",\"models\":").append(ready)
                append(",\"embedded\":true")
                append(",\"classes\":").append(if (classes > 0) classes + 1 else 0)
                append(",\"files\":{")
                append("\"det.onnx\":").append(det)
                if (isNotEmpty()) append(",")
                append("\"rec.onnx\":").append(rec)
                append("}")
                append(",\"engine\":\"KOMA · PaddleOCR (ONNX Runtime)\"")
                append(",\"error\":").append(js(if (ready) "" else "модели не найдены в приложении"))
                append("}")
            }
        }

        /** Страница просит выбрать файлы моделей — в АПК они уже вшиты. */
        @JavascriptInterface
        fun pickModels() {
            // Страница зовёт это только если `available()` ложно. Здесь нечего
            // выбирать: модели лежат в АПК. Отвечаем, чтобы кнопка не молчала.
            scope.launch {
                withMain {
                    android.widget.Toast.makeText(
                        this@KomaWebReaderActivity,
                        "Модели вшиты в приложение — выбирать файлы не нужно",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        /**
         * Кадр → строки. Ответ уpage уходит событием, как ждёт её мост:
         * `__komaOcrEvent(id, 'state', текст)` и `__komaOcrEvent(id, 'done', json)`.
         */
        @JavascriptInterface
        fun recognizeAsync(base64: String, rulesJson: String, cbId: String) {
            scope.launch {
                val started = System.currentTimeMillis()
                fun state(text: String) = emitOcr(cbId, "state", text)
                val answer = withContext(Dispatchers.IO) {
                    runCatching {
                        state("нативный движок: кадр ${base64.length / 1024} КБ")
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("кадр не декодировался")
                        val page = engineLocked().recognizeImage(bitmap)
                        if (bitmap.isRecycled.not()) bitmap.recycle()
                        if (page.regions.isEmpty()) {
                            val why = engineLocked().lastError ?: "движок не нашёл строк"
                            return@runCatching """{"ok":false,"error":${js(why)}}"""
                        }
                        val items = page.regions.joinToString(",") { r ->
                            "{\"text\":" + js(r.text) +
                                ",\"left\":" + r.left + ",\"top\":" + r.top +
                                ",\"right\":" + r.right + ",\"bottom\":" + r.bottom +
                                ",\"confidence\":" + (r.confidence ?: 0f) + "}"
                        }
                        "{\"ok\":true,\"items\":[" + items + "],\"ms\":" +
                            (System.currentTimeMillis() - started) + "}"
                    }.getOrElse { e ->
                        """{"ok":false,"error":${js(e.message ?: e.toString())}}"""
                    }
                }
                emitOcr(cbId, "done", answer)
            }
        }

        /**
         * Самопроверка движка: рисуем строку кодом и читаем её тем же путём,
         * что и страницу. Нужна, чтобы «не читает» не оставалось догадкой.
         */
        @JavascriptInterface
        fun selfTestAsync(cbId: String) {
            scope.launch {
                val started = System.currentTimeMillis()
                val answer = withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = Bitmap.createBitmap(900, 220, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 96f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        canvas.drawText("ГО КОМА", 40f, 140f, paint)
                        val page = engineLocked().recognizeImage(bitmap)
                        val text = page.regions.joinToString(" ") { it.text }.trim()
                        if (text.isBlank()) {
                            val why = engineLocked().lastError ?: "движок не увидел нарисованную строку"
                            """{"ok":false,"error":${js(why)}}"""
                        } else {
                            """{"ok":true,"text":${js(text)},"ms":${System.currentTimeMillis() - started}}"""
                        }
                    }.getOrElse { e -> """{"ok":false,"error":${js(e.message ?: e.toString())}}""" }
                }
                emitOcr(cbId, "done", answer)
            }
        }
    }

    private fun emitOcr(id: String, type: String, data: String) {
        webView?.evaluateJavascript(
            "window.__komaOcrEvent && window.__komaOcrEvent(${js(id)},${js(type)},${js(data)})",
            null,
        )
    }

    // ─────────────────────────── голос ───────────────────────────

    private class SpeechEvent(val id: String, val type: String, val data: String)

    private class KomaSpeech(
        private val activity: Activity,
        private val onEvent: (SpeechEvent) -> Unit,
    ) {
        @Volatile
        private var ready = false

        @Volatile
        private var note: String? = null

        /**
         * Синтезатор строится через [build]: движок можно задать только при
         * создании (сеттера `engine` у TextToSpeech нет), а ссылка на готовый
         * синтезатор нужна уже в колбэке инициализации.
         */
        @Volatile
        private var tts: TextToSpeech = build(null)

        private fun build(engineName: String?): TextToSpeech {
            val holder = arrayOfNulls<TextToSpeech>(1)
            val listener = TextToSpeech.OnInitListener { status -> onInit(status, holder[0]) }
            val made = if (engineName == null) {
                TextToSpeech(activity.applicationContext, listener)
            } else {
                TextToSpeech(activity.applicationContext, listener, engineName)
            }
            holder[0] = made
            return made
        }

        private fun onInit(status: Int, engine: TextToSpeech?) {
            if (engine == null) return
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    val result = engine.setLanguage(Locale("ru", "RU"))
                    ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ready) note = "Русский голос не установлен в системе"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
            val engine = tts
            val list = runCatching { engine.engines.map { it.label.replace('"', '\'') } }
                .getOrDefault(emptyList())
            return list.joinToString(prefix = "[", postfix = "]") { quote(it) }
        }

        @JavascriptInterface
        fun useEngine(name: String): Boolean = runCatching {
            val current = tts
            val target = current.engines.firstOrNull { it.label == name } ?: return false
            ready = false
            runCatching { current.stop() }
            runCatching { current.shutdown() }
            tts = build(target.name)
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun voices(): String {
            val engine = tts
            fun row(v: android.speech.tts.Voice) =
                "{\"name\":" + quote(v.name) + ",\"lang\":" + quote(v.locale.toLanguageTag()) + "}"
            val russian = runCatching {
                engine.voices.orEmpty().filter { it.locale.language == "ru" }.map { row(it) }
            }.getOrDefault(emptyList())
            val all = if (russian.isEmpty()) {
                runCatching { engine.voices.orEmpty().map { row(it) } }.getOrDefault(emptyList())
            } else {
                russian
            }
            return all.joinToString(prefix = "[", postfix = "]")
        }

        @JavascriptInterface
        fun speak(text: String, rate: String, lang: String, voice: String, id: String) {
            if (!ready) {
                onEvent(SpeechEvent(id, "error", note ?: "tts not ready"))
                return
            }
            val engine = tts
            runCatching {
                engine.setSpeechRate(rate.toFloatOrNull()?.coerceIn(0.4f, 2.5f) ?: 1f)
                engine.setPitch(1f)
                if (voice.isNotBlank()) {
                    runCatching { engine.voices?.firstOrNull { it.name == voice }?.let { engine.voice = it } }
                }
                if (lang.isNotBlank() && !lang.startsWith("ru")) {
                    runCatching { engine.setLanguage(Locale.forLanguageTag(lang)) }
                }
                val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (queued != TextToSpeech.SUCCESS) onEvent(SpeechEvent(id, "error", "queue"))
            }.onFailure { onEvent(SpeechEvent(id, "error", it.message ?: "tts")) }
        }

        @JavascriptInterface
        fun cancel() {
            runCatching { tts.stop() }
        }

        fun shutdown() {
            val engine = tts
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    // ─────────────────────────── онлайн-Lens и сведения о приложении ───────────────────────────

    /**
     * `window.KomaLens` — онлайн-движок из настроек приложения (Google Lens).
     *
     * Это НЕ основной путь: он требует сети. Страница зовёт его только когда
     * человек сам выбрал Lens. Ответ — обычный для неё `__komaLensResolve`.
     */
    private inner class KomaLens {
        @JavascriptInterface
        fun recognizeAsync(base64: String, optsJson: String, id: String) {
            scope.launch {
                val answer = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("кадр не декодировался")
                        val text = runCatching {
                            val repository = uy.kohesive.injekt.Injekt.get<
                                mihon.domain.ocr.repository.OcrRepository>()
                            val width = bitmap.width
                            val height = bitmap.height
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            repository.recognizeText(
                                mihon.domain.ocr.model.OcrImage(width, height, pixels),
                                mihon.domain.ocr.model.OcrModel.GLENS,
                            )
                        }.getOrElse { e -> error(e.message ?: "Lens недоступен") }
                        if (bitmap.isRecycled.not()) bitmap.recycle()
                        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isEmpty()) error("Lens не вернул текста")
                        "{\"lines\":[" + lines.joinToString(",") { js(it) } + "],\"ms\":0}"
                    }.getOrElse { e -> "{\"error\":${js(e.message ?: "мост недоступен")}}" }
                }
                webView?.evaluateJavascript(
                    "window.__komaLensResolve && window.__komaLensResolve[${js(id)}](${js(answer)})",
                    null,
                )
            }
        }
    }

    /**
     * Причина на экране вместо чёрного экрана.
     *
     * Требование простое: если читалка не открылась, человек должен видеть
     * ПОЧЕМУ (ошибка WebView, сообщение console, адрес, который не отдался), а
     * не пустоту. Кнопка перезапускает страницу и чистит список причин.
     */
    @androidx.compose.runtime.Composable
    private fun Problem(lines: List<String>, onRetry: () -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .background(ComposeColor(0xFF171A17))
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Читалка не открылась", color = ComposeColor(0xFFFF8A4C), fontSize = 18.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) { Text("Перезагрузить") }
                Button(onClick = { withMain { finish() } }) { Text("Закрыть") }
            }
            lines.forEach { line ->
                Text(line, color = ComposeColor(0xFFF2F4EF), fontSize = 12.sp)
            }
            Text(
                "Это текст причины, а не догадка: здесь то, что сказали WebView, " +
                    "console страницы и проверка загрузки.",
                color = ComposeColor(0xFFB2B8AD),
                fontSize = 11.sp,
            )
        }
    }

    private inner class KomaApp {
        @JavascriptInterface
        fun version(): String = buildString {
            append("{")
            append("\"name\":\"Yomikai\",")
            append("\"version\":")
            append(quote(runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""))
            append(",\"native\":true")
            append(",\"speech\":").append(speech?.isReady() == true)
            append(",\"ocr\":\"native\"")
            append("}")
        }

        @JavascriptInterface
        fun checkUpdate(id: String): String = "{\"updated\":false,\"note\":\"обновления ставит магазин/АПК\"}"

        @JavascriptInterface
        fun reloadNow(reason: String) {
            withMain { reload() }
        }

        @JavascriptInterface
        fun lensSelfTest(): String = "{\"ok\":false,\"note\":\"Lens-самопроверка здесь не реализована\"}"
    }

    private fun withMain(block: () -> Unit) {
        runCatching { runOnUiThread(block) }
    }

    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net/"
        private const val PAGE_URL = ORIGIN + "web/koma-web.html"
        private const val REQUEST_FILES = 4711
        private const val EXTRA_BOOK = "koma.book"

        fun newIntent(context: android.content.Context, bookId: String? = null): Intent =
            Intent(context, KomaWebReaderActivity::class.java).apply {
                if (!bookId.isNullOrBlank()) putExtra(EXTRA_BOOK, bookId)
            }

        private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }

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
                    else -> if (c.code in 32..126) append(c) else append("\\u%04x".format(c.code))
                }
            }
        }
    }
}
