package mihon.data.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal class ZenFreeOcrEngine : OcrEngine {
    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }
        recognizePage(image).joinToString("\n") { it.text }
    }

    suspend fun recognizePage(image: Bitmap): List<OcrRegion> = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("stream", false)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject().apply {
                                            put("type", "text")
                                            put("text", OCR_PROMPT)
                                        },
                                    )
                                    .put(
                                        JSONObject().apply {
                                            put("type", "image_url")
                                            put(
                                                "image_url",
                                                JSONObject().apply {
                                                    put("url", "data:image/jpeg;base64,${encodeBitmap(image)}")
                                                },
                                            )
                                        },
                                    ),
                            )
                        },
                    ),
                )
            }
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("OpenCode Zen OCR request failed HTTP $status")
            }

            val message = JSONObject(response)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: throw IllegalStateException("OpenCode Zen OCR response has no choices")
            parseZenOcrRegions(extractMessageContent(message))
        } finally {
            connection.disconnect()
        }
    }

    override fun close() = Unit

    private fun extractMessageContent(message: JSONObject): String {
        return when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val text = content.optJSONObject(index)?.optString("text").orEmpty()
                    if (text.isNotBlank()) append(text)
                }
            }
            else -> ""
        }
    }

    private fun encodeBitmap(bitmap: Bitmap): String {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scale = if (maxSide > MAX_IMAGE_SIDE) MAX_IMAGE_SIDE.toFloat() / maxSide else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            null
        }
        val output = ByteArrayOutputStream()
        try {
            (scaled ?: bitmap).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        } finally {
            if (scaled != null && !scaled.isRecycled) scaled.recycle()
        }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    companion object {
        const val MODEL = "space-bunny-free"
        private const val ENDPOINT = "https://opencode.ai/zen/v1/chat/completions"
        private const val MAX_IMAGE_SIDE = 2048
        private const val JPEG_QUALITY = 88
        private const val MAX_TOKENS = 4096
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000
        private val OCR_PROMPT =
            "Transcribe every visible text block in this image. Preserve the original reading order, " +
                "language, spelling, punctuation, and line breaks. Do not translate, explain, or invent text. " +
                "Return only JSON: {\"regions\":[{\"text\":\"...\",\"box\":[left,top,right,bottom]," +
                "\"orientation\":\"horizontal\"}]}. Coordinates are integers from 0 to 1000 relative to the " +
                "full image. Use one region per visible speech bubble or text block. Use [] when no text exists."
    }
}

private val zenFreeJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ZenOcrResponse(
    val regions: List<ZenOcrRegion> = emptyList(),
    val text: String = "",
)

@Serializable
private data class ZenOcrRegion(
    val text: String = "",
    val box: List<Float> = emptyList(),
    val orientation: String = "horizontal",
)

internal fun parseZenOcrRegions(content: String): List<OcrRegion> {
    if (content.isBlank()) return emptyList()
    val jsonText = content.substringAfter("```json", content).substringBefore("```").trim()
    val objectStart = jsonText.indexOf('{')
    val objectEnd = jsonText.lastIndexOf('}')
    val parsed = if (objectStart >= 0 && objectEnd > objectStart) {
        runCatching {
            zenFreeJson.decodeFromString<ZenOcrResponse>(jsonText.substring(objectStart, objectEnd + 1))
        }.getOrNull()
    } else {
        null
    }
    if (parsed == null) {
        return listOf(
            OcrRegion(
                order = 0,
                text = content.trim(),
                boundingBox = OcrBoundingBox(0f, 0f, 1f, 1f),
                textOrientation = OcrTextOrientation.Horizontal,
            ),
        )
    }

    val regions = parsed.regions.mapIndexedNotNull { index, region ->
        val text = region.text.trim()
        if (text.isBlank() || region.box.size != 4) return@mapIndexedNotNull null
        val divisor = if (region.box.all { it > 1f }) 1000f else 1f
        val left = region.box[0] / divisor
        val top = region.box[1] / divisor
        val right = region.box[2] / divisor
        val bottom = region.box[3] / divisor
        val box = OcrBoundingBox(left, top, right, bottom)
        if (!box.isValid() || left !in 0f..1f || top !in 0f..1f || right !in 0f..1f || bottom !in 0f..1f) {
            return@mapIndexedNotNull null
        }
        OcrRegion(
            order = index,
            text = text,
            boundingBox = box,
            textOrientation = if (region.orientation.equals("vertical", ignoreCase = true)) {
                OcrTextOrientation.Vertical
            } else {
                OcrTextOrientation.Horizontal
            },
        )
    }
    if (regions.isNotEmpty()) return regions
    val fallbackText = parsed.text.trim()
    return if (fallbackText.isBlank()) {
        emptyList()
    } else {
        listOf(
            OcrRegion(
                order = 0,
                text = fallbackText,
                boundingBox = OcrBoundingBox(0f, 0f, 1f, 1f),
                textOrientation = OcrTextOrientation.Horizontal,
            ),
        )
    }
}
