package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.tachiyomi.data.books.BookParser
import eu.kanade.tachiyomi.data.books.BooksStore
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import eu.kanade.tachiyomi.data.tts.VoiceKind
import kotlinx.coroutines.runBlocking
import mihon.domain.ocr.service.OcrPreferences
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Мини HTTP-сервер AI-агента и внешних инструментов.
 *
 * Endpoints:
 *  GET  /            — страница чата (HTML+JS)
 *  POST /chat        — {"text": "..."} → ответ агента JSON
 *  GET  /files       — список файлов workspace (JSON)
 *  GET  /file?p=rel  — скачать файл workspace
 *  GET  /tts/voices  — список голосов TTS
 *  POST /tts/voice   — установить голос
 *  GET  /ocr/settings — настройки OCR
 *  POST /ocr/settings — обновить настройки OCR
 *  GET  /books/list   — список книг с метаданными
 *  POST /books/import — импорт книги по URL (потоковый, без OOM)
 *  GET  /books/metadata?uri=... — метаданные книги
 *  GET  /books/cover?uri=... — обложка книги (PNG)
 */
object AiHttpServer {

    const val PORT = 8765

    fun tokenFor(context: Context): String {
        val prefs = Injekt.get<OcrPreferences>()
        val existing = prefs.aiHttpToken().get()
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.aiHttpToken().set(generated)
        logcat(LogPriority.INFO) { "AiHttpServer: сгенерирован ключ доступа" }
        return generated
    }

    @Volatile
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = server?.isClosed == false

    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val appContext = context.applicationContext
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", PORT))
        tokenFor(appContext)
        server = ss
        thread = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    Thread { handle(appContext, client) }.start()
                } catch (e: Exception) {
                    if (!ss.isClosed) logcat(LogPriority.WARN, e) { "AiHttpServer accept failed" }
                }
            }
        }.apply {
            isDaemon = true
            name = "AiHttpServer"
            start()
        }
        logcat(LogPriority.INFO) { "AiHttpServer started on :$PORT" }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun handle(context: Context, socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 180_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val fullPath = parts[1]
                var contentLength = 0
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isBlank()) break
                    if (h.startsWith("Content-Length:", true)) {
                        contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (method == "POST" && contentLength > 0) {
                    val buf = CharArray(contentLength.coerceAtMost(1 shl 20))
                    var read = 0
                    while (read < buf.size) {
                        val n = reader.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else {
                    ""
                }

                val out = s.getOutputStream()

                if (queryParam(fullPath.substringAfter('?', ""), "key") != tokenFor(context)) {
                    respond(
                        out,
                        401,
                        "text/plain; charset=utf-8",
                        "401 Unauthorized\n\nОткройте ссылку с ключом из приложения:\nвкладка AI — блок «Доступ из внешнего браузера».".toByteArray(),
                    )
                    return
                }

                val path = fullPath.substringBefore('?')
                val query = fullPath.substringAfter('?', "")
                when {
                    method == "GET" && path == "/" -> respond(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
                    method == "POST" && path == "/chat" -> {
                        val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
                        val reply = runBlocking { AiAgent.run(context, text) }
                        val json = JSONObject()
                            .put("text", reply.text)
                            .put("tools", JSONArray(reply.toolResults.map { "${it.name}: ${it.output}" }))
                            .put("images", JSONArray(reply.images.map { AiWorkspace.relPath(context, it) }))
                        respond(out, 200, "application/json; charset=utf-8", json.toString().toByteArray())
                    }
                    method == "GET" && path == "/tts/voices" -> {
                        val language = queryParam(query, "lang")?.ifBlank { "ru" } ?: "ru"
                        val prefs = Injekt.get<OcrPreferences>()
                        val voices = JSONArray()
                        VoiceHelper.localCatalog(language).forEach { voice ->
                            val gender = when (VoiceHelper.classify(voice)) {
                                VoiceKind.FEMALE -> "female"
                                VoiceKind.MALE -> "male"
                                VoiceKind.TEEN -> "teen"
                                VoiceKind.OTHER -> "other"
                            }
                            voices.put(
                                JSONObject()
                                    .put("name", voice.name)
                                    .put("language", voice.locale.toLanguageTag())
                                    .put("gender", gender)
                                    .put("local", true),
                            )
                        }
                        val json = JSONObject()
                            .put("engine", prefs.systemTtsEngine().get())
                            .put("selected", prefs.voiceName().get())
                            .put("female", prefs.voiceFemale().get())
                            .put("male", prefs.voiceMale().get())
                            .put("voices", voices)
                        respond(out, 200, "application/json; charset=utf-8", json.toString().toByteArray())
                    }
                    method == "POST" && path == "/tts/voice" -> {
                        val request = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                        val name = request.optString("name").trim()
                        val language = request.optString("lang").ifBlank { "ru" }
                        val slot = request.optString("slot").ifBlank { "main" }
                        val valid = VoiceHelper.localCatalog(language).any { it.name == name }
                        if (!valid) {
                            respond(out, 400, "application/json", "{\"error\":\"unknown voice\"}".toByteArray())
                        } else {
                            val prefs = Injekt.get<OcrPreferences>()
                            when (slot) {
                                "female" -> prefs.voiceFemale().set(name)
                                "male" -> prefs.voiceMale().set(name)
                                else -> prefs.voiceName().set(name)
                            }
                            respond(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                        }
                    }
                    method == "GET" && path == "/files" -> {
                        val arr = JSONArray()
                        AiWorkspace.listAll(context).filter { it.isFile }.forEach {
                            arr.put(
                                JSONObject()
                                    .put("path", AiWorkspace.relPath(context, it))
                                    .put("size", it.length()),
                            )
                        }
                        respond(out, 200, "application/json; charset=utf-8", arr.toString().toByteArray())
                    }
                    method == "GET" && path == "/ocr/settings" -> {
                        val prefs = Injekt.get<OcrPreferences>()
                        val json = JSONObject()
                            .put("contentType", prefs.contentType().get())
                            .put("autoPreset", prefs.autoPreset().get())
                            .put("scanRegion", prefs.presetScanRegion().get())
                            .put("readingOrder", prefs.scanReadingOrder().get())
                            .put("autoReadLanguage", prefs.autoReadLanguage().get())
                            .put("autoReadTranslate", prefs.autoReadTranslate().get())
                            .put("voiceIcons", prefs.voiceIcons().get())
                            .put("showSpeechNumbers", prefs.showSpeechNumbers().get())
                            .put("aiGenderVoices", prefs.aiGenderVoices().get())
                            .put("voiceEngine", prefs.voiceEngine().get())
                            .put("voiceName", prefs.voiceName().get())
                            .put("systemTtsEngine", prefs.systemTtsEngine().get())
                        respond(out, 200, "application/json; charset=utf-8", json.toString().toByteArray())
                    }
                    method == "POST" && path == "/ocr/settings" -> {
                        val prefs = Injekt.get<OcrPreferences>()
                        val request = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                        if (request.has("contentType")) prefs.contentType().set(request.optString("contentType"))
                        if (request.has("autoPreset")) prefs.autoPreset().set(request.optString("autoPreset"))
                        if (request.has("scanRegion")) prefs.presetScanRegion().set(request.optString("scanRegion"))
                        if (request.has("readingOrder")) prefs.scanReadingOrder().set(request.optString("readingOrder"))
                        if (request.has("autoReadLanguage")) prefs.autoReadLanguage().set(request.optString("autoReadLanguage"))
                        if (request.has("autoReadTranslate")) prefs.autoReadTranslate().set(request.optBoolean("autoReadTranslate"))
                        if (request.has("voiceIcons")) prefs.voiceIcons().set(request.optBoolean("voiceIcons"))
                        respond(out, 200, "application/json", "{\"ok\":true}".toByteArray())
                    }

                    // ---- Books API ----
                    method == "GET" && path == "/books/list" -> {
                        val arr = JSONArray()
                        BooksStore.listBooks(context).forEach { file ->
                            val snap = BooksStore.load(context, file)
                            val metadata = BooksStore.loadMetadata(context, file)
                            arr.put(
                                JSONObject()
                                    .put("name", file.name.orEmpty())
                                    .put("uri", file.uri.toString())
                                    .put("title", metadata.title)
                                    .put("author", metadata.author ?: "")
                                    .put("ext", file.name.orEmpty().substringAfterLast('.', "").uppercase())
                                    .put("progressPercent", snap.percent)
                                    .put("chapter", snap.chapter)
                                    .put("sentence", snap.sentence),
                            )
                        }
                        respond(out, 200, "application/json; charset=utf-8", arr.toString().toByteArray())
                    }
                    method == "POST" && path == "/books/import" -> {
                        val request = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                        val url = request.optString("url").trim()
                        val name = request.optString("name").trim()
                        if (url.isBlank()) {
                            respond(out, 400, "application/json", "{\"error\":\"empty url\"}".toByteArray())
                        } else {
                            val result = importBookFromUrl(context, url, name.ifBlank { null })
                            respond(
                                out,
                                if (result is BooksStore.ImportResult.Success) 200 else 400,
                                "application/json; charset=utf-8",
                                when (result) {
                                    is BooksStore.ImportResult.Success -> {
                                        val metadata = BooksStore.loadMetadata(context, result.book)
                                        JSONObject()
                                            .put("ok", true)
                                            .put("name", result.book.name.orEmpty())
                                            .put("title", metadata.title)
                                            .put("author", metadata.author ?: "")
                                            .toString()
                                    }
                                    is BooksStore.ImportResult.Failure ->
                                        JSONObject().put("ok", false).put("error", result.reason).toString()
                                }.toByteArray(),
                            )
                        }
                    }
                    method == "GET" && path == "/books/metadata" -> {
                        val uri = queryParam(query, "uri")?.trim()
                        if (uri.isNullOrEmpty()) {
                            respond(out, 400, "application/json", "{\"error\":\"missing uri\"}".toByteArray())
                        } else {
                            val file = BooksStore.listBooks(context).find { it.uri.toString() == uri }
                            if (file == null) {
                                respond(out, 404, "application/json", "{\"error\":\"book not found\"}".toByteArray())
                            } else {
                                val metadata = BooksStore.loadMetadata(context, file)
                                val json = JSONObject()
                                    .put("title", metadata.title)
                                    .put("author", metadata.author ?: "")
                                    .put("description", metadata.description ?: "")
                                    .put("language", metadata.language ?: "")
                                    .put("publisher", metadata.publisher ?: "")
                                    .put("year", metadata.year ?: JSONObject.NULL)
                                    .put("genre", JSONArray(metadata.genre.orEmpty()))
                                    .put("hasCover", metadata.coverImage != null || BooksStore.coverPath(context, file) != null)
                                respond(out, 200, "application/json; charset=utf-8", json.toString().toByteArray())
                            }
                        }
                    }
                    method == "GET" && path == "/books/cover" -> {
                        val uri = queryParam(query, "uri")?.trim()
                        if (uri.isNullOrEmpty()) {
                            respond(out, 400, "text/plain", "missing uri".toByteArray())
                        } else {
                            val file = BooksStore.listBooks(context).find { it.uri.toString() == uri }
                            if (file == null) {
                                respond(out, 404, "text/plain", "book not found".toByteArray())
                            } else {
                                val coverPath = BooksStore.coverPath(context, file)
                                if (coverPath != null) {
                                    val coverFile = java.io.File(coverPath)
                                    if (coverFile.exists()) {
                                        respond(out, 200, "image/png", coverFile.readBytes())
                                    } else {
                                        respond(out, 404, "text/plain", "cover not found".toByteArray())
                                    }
                                } else {
                                    respond(out, 404, "text/plain", "no cover".toByteArray())
                                }
                            }
                        }
                    }
                    method == "GET" && path == "/file" -> {
                        val rel = query.split('&')
                            .firstOrNull { it.startsWith("p=") }
                            ?.substringAfter('=')
                            ?.let { URLDecoder.decode(it, "UTF-8") }
                        val f = rel?.let { AiWorkspace.resolve(context, it) }
                        if (f?.isFile == true) {
                            val mime = when (f.extension.lowercase()) {
                                "jpg", "jpeg" -> "image/jpeg"
                                "png" -> "image/png"
                                "zip" -> "application/zip"
                                "txt", "md" -> "text/plain; charset=utf-8"
                                else -> "application/octet-stream"
                            }
                            respond(out, 200, mime, f.readBytes(), "attachment; filename=\"${f.name}\"")
                        } else {
                            respond(out, 404, "text/plain", "not found".toByteArray())
                        }
                    }
                    else -> respond(out, 404, "text/plain", "not found".toByteArray())
                }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "AiHttpServer request failed" }
            }
        }
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    /**
     * Скачивает книгу по URL с потоковым чтением (без OOM).
     * Лимит: 40 МБ. Проверяет Content-Length и читает по чанкам.
     */
    private fun importBookFromUrl(
        context: Context,
        url: String,
        name: String?,
    ): BooksStore.ImportResult {
        val MAX_SIZE = 40 * 1024 * 1024L
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.connect()

            if (conn.responseCode !in 200..299) {
                return BooksStore.ImportResult.Failure("HTTP ${conn.responseCode}")
            }

            // Проверяем Content-Length до чтения
            val contentLength = conn.contentLengthLong.takeIf { it > 0 } ?: -1
            if (contentLength > MAX_SIZE) {
                conn.disconnect()
                return BooksStore.ImportResult.Failure("Файл слишком большой (${contentLength / 1024 / 1024} МБ, лимит 40 МБ)")
            }

            // Потоковое чтение с проверкой размера
            val baos = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalRead = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalRead += read
                    if (totalRead > MAX_SIZE) {
                        conn.disconnect()
                        return BooksStore.ImportResult.Failure("Файл больше 40 МБ")
                    }
                    baos.write(buffer, 0, read)
                }
            }
            conn.disconnect()

            val fileName = when {
                !name.isNullOrBlank() -> name
                else -> {
                    // Пытаемся извлечь имя из URL или Content-Disposition
                    val disposition = conn.getHeaderField("Content-Disposition")
                    val dispositionName = disposition?.let {
                        Regex("""filename[*]?="?([^";\s]+)"""").find(it)?.groupValues?.get(1)
                    }
                    dispositionName
                        ?: java.net.URL(url).path.substringAfterLast('/').ifBlank { null }
                        ?: "book_${System.currentTimeMillis()}"
                }
            }

            BooksStore.importBytes(context, fileName, baos.toByteArray())
        } catch (e: Exception) {
            BooksStore.ImportResult.Failure("Скачивание не удалось: ${e.message}")
        }
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        disposition: String? = null,
    ) {
        val status = if (code == 200) "200 OK" else "$code Error"
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            if (disposition != null) append("Content-Disposition: $disposition\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        out.write(body)
        out.flush()
    }

    // Страница чата: чистый HTML+JS без внешних зависимостей
    private val PAGE = """
<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Yomikai AI</title>
<style>
 body{font-family:system-ui,sans-serif;margin:0;background:#101418;color:#e6e9ec;display:flex;flex-direction:column;height:100vh}
 header{padding:10px 16px;background:#1a2027;font-weight:600}
 #log{flex:1;overflow-y:auto;padding:12px}
 .m{margin:6px 0;padding:10px 12px;border-radius:12px;max-width:85%;white-space:pre-wrap;word-break:break-word}
 .u{background:#2b4a6f;margin-left:auto}
 .a{background:#232b33}
 .tools{font-size:12px;color:#8fa3b5}
 img{max-width:100%;border-radius:8px;margin-top:6px}
 footer{display:flex;padding:8px;background:#1a2027;gap:8px}
 input{flex:1;padding:10px;border-radius:8px;border:none;background:#0d1115;color:#e6e9ec}
 button{padding:10px 16px;border:none;border-radius:8px;background:#3b82f6;color:#fff;font-weight:600}
 a{color:#7cb3ff}
</style></head><body>
<header>🦊 Yomikai AI-агент <a href="#" onclick="files()" style="float:right;font-size:13px">workspace</a></header>
<div id="log"></div>
<footer><input id="t" placeholder="Сообщение агенту…" onkeydown="if(event.key==='Enter')send()"><button onclick="send()">➤</button></footer>
<script>
const KEY=new URLSearchParams(location.search).get('key')||'';
const log=document.getElementById('log');
function add(cls,text,html){const d=document.createElement('div');d.className='m '+cls;if(html)d.innerHTML=html;else d.textContent=text;log.appendChild(d);log.scrollTop=log.scrollHeight;return d;}
async function send(){
 const t=document.getElementById('t');const text=t.value.trim();if(!text)return;t.value='';
 add('u',text);const w=add('a','…думаю');
 try{
  const r=await fetch('/chat?key='+KEY,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text})});
  const j=await r.json();
  let h=j.text.replace(/&/g,'&amp;').replace(/</g,'&lt;');
  if(j.tools&&j.tools.length)h+='<div class="tools">'+j.tools.map(x=>'🔧 '+x.replace(/</g,'&lt;')).join('<br>')+'</div>';
  if(j.images)for(const p of j.images)h+='<br><img src="/file?p='+encodeURIComponent(p)+'&key='+KEY+'"><br><a href="/file?p='+encodeURIComponent(p)+'&key='+KEY+'" download>⬇ скачать</a>';
  w.innerHTML=h;
 }catch(e){w.textContent='Ошибка: '+e;}
}
async function files(){
 const r=await fetch('/files?key='+KEY);const j=await r.json();
 add('a','',j.map(f=>'<a href="/file?p='+encodeURIComponent(f.path)+'&key='+KEY+'" download>'+f.path+'</a> ('+Math.round(f.size/1024)+' КБ)').join('<br>')||'workspace пуст');
}
</script></body></html>
    """.trimIndent()
}
