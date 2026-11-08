package mihon.data.ocr.vlm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Работа с Hugging Face: поиск моделей, список файлов с размерами, скачивание.
 *
 * Имена файлов модели НЕ зашиты в код: они приходят из `/api/models/{id}?blobs=true`.
 * Это принципиально — у каждой VLM свой набор (genai_config.json, model.onnx,
 * model.onnx.data, tokenizer.json, processor_config.json …), и выдуманный список
 * ломается на первой же модели, которую мы не видели.
 *
 * Сеть здесь — только huggingface.co, тот же адрес, что уже использует LocalLlm.
 */
internal object HfModelHub {

    private const val HOST = "https://huggingface.co"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Hit(
        val id: String,
        val downloads: Long,
        val likes: Long,
        val tags: List<String>,
        val pipeline: String?,
    ) {
        val onnx: Boolean get() = tags.any { it.equals("onnx", true) }
    }

    data class Blob(val name: String, val size: Long)

    /**
     * Поиск моделей. [query] — то, что человек ввёл в поле поиска.
     * Сортировка по скачиваниям: сверху то, что реально работает у людей.
     */
    suspend fun search(query: String, limit: Int = 30): List<Hit> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = get("$HOST/api/models?search=$q&sort=downloads&direction=-1&limit=$limit&full=true")
            ?: return@withContext emptyList()
        val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return@withContext emptyList()
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Hit(
                id = id,
                downloads = o["downloads"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                likes = o["likes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                tags = (o["tags"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                pipeline = o["pipeline_tag"]?.jsonPrimitive?.content,
            )
        }
    }

    /** Все файлы репозитория с размерами (байты; 0 = размер не отдан). */
    suspend fun files(repo: String): List<Blob> = withContext(Dispatchers.IO) {
        val body = get("$HOST/api/models/$repo?blobs=true") ?: return@withContext emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext emptyList()
        val sib = root["siblings"]?.jsonArray ?: return@withContext emptyList()
        sib.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Blob(name, o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
        }
    }

    fun url(repo: String, file: String): String =
        "$HOST/$repo/resolve/main/" + file.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    /** Скачать один файл в [dir], сохранив вложенность путей. true — файл на месте. */
    suspend fun download(repo: String, file: String, dir: File, onBytes: (Long) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val target = File(dir, file)
            target.parentFile?.mkdirs()
            if (target.exists() && target.length() > 0) return@withContext true
            val tmp = File(target.absolutePath + ".part")
            tmp.delete()
            var c: HttpURLConnection? = null
            try {
                c = (URL(url(repo, file)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "yomikai-vlm")
                }
                if (c.responseCode !in 200..299) return@withContext false
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                            onBytes(total)
                        }
                    }
                }
                tmp.renameTo(target)
                true
            } catch (e: Throwable) {
                tmp.delete()
                false
            } finally {
                c?.disconnect()
            }
        }

    private fun get(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "yomikai-vlm")
            }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            null
        } finally {
            c?.disconnect()
        }
    }
}
