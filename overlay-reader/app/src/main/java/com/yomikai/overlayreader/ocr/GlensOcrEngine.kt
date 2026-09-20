package com.yomikai.overlayreader.ocr

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Онлайн-движок Google Lens.
 *
 * Шлёт протобуф-запрос на lensfrontend (как в Google Lens web) и разбирает
 * ответ: текст по строкам с координатами. Открытый API-ключ общеизвестный
 * (используется клиентами Google Lens), без логина и оплаты.
 *
 * Распознаёт текст, а также — как следствие vision-модели — даёт координаты
 * каждого фрагмента. По координатам читалка может сопоставлять реплики с
 * позициями на экране (лица/пузыри распределяются по верхней/нижней части
 * кадра, поэтому тон озвучки различается по расположению и типу реплики).
 */
internal class GlensOcrEngine(
    private val clientLanguage: String = "ru",
    private val clientRegion: String = "Europe/Kiev",
) : OcrEngine {

    override suspend fun recognizeText(image: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        try {
            val prepared = prepareImage(image)
            val response = executeRequest(buildRequestPayload(prepared))
            val result = parseResponse(response)
            Log.i(TAG, "glens OCR got ${result.regions.size} regions")
            result
        } catch (e: Exception) {
            Log.e(TAG, "glens OCR failed", e)
            throw e
        }
    }

    override fun close() = Unit

    private fun prepareImage(image: Bitmap): PreparedImage {
        val maxDim = maxOf(image.width, image.height)
        val scaled = if (maxDim > MAX_IMAGE_DIMENSION) {
            val factor = MAX_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
            Bitmap.createScaledBitmap(
                image,
                (image.width * factor).toInt().coerceAtLeast(1),
                (image.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            null
        }
        val working = scaled ?: image
        return try {
            val out = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (out.size() == 0) throw IOException("Failed to encode image for GLens request")
            PreparedImage(out.toByteArray(), working.width, working.height)
        } finally {
            scaled?.recycle()
        }
    }

    private fun buildRequestPayload(image: PreparedImage): ByteArray {
        val requestId = Random.nextLong()
        return ProtoWriter().apply {
            writeMessage(SERVER_REQUEST_OBJECTS_REQUEST) { root ->
                root.writeMessage(OBJECTS_REQUEST_CONTEXT) { ctx ->
                    ctx.writeMessage(REQUEST_CONTEXT_REQUEST_ID) { id ->
                        id.writeUInt64(REQUEST_ID_UUID, requestId)
                        id.writeInt32(REQUEST_ID_SEQUENCE_ID, 0)
                        id.writeInt32(REQUEST_ID_IMAGE_SEQUENCE_ID, 0)
                        id.writeBytes(REQUEST_ID_ANALYTICS_ID, Random.nextBytes(16))
                    }
                    ctx.writeMessage(REQUEST_CONTEXT_CLIENT_CONTEXT) { cc ->
                        cc.writeInt32(CLIENT_CONTEXT_PLATFORM, PLATFORM_WEB)
                        cc.writeInt32(CLIENT_CONTEXT_SURFACE, SURFACE_CHROMIUM)
                        cc.writeMessage(CLIENT_CONTEXT_LOCALE_CONTEXT) { lc ->
                            lc.writeString(LOCALE_LANGUAGE, clientLanguage)
                            lc.writeString(LOCALE_REGION, clientRegion)
                        }
                        cc.writeMessage(CLIENT_CONTEXT_CLIENT_FILTERS) { filters ->
                            filters.writeMessage(CLIENT_FILTERS_FILTER) { f ->
                                f.writeInt32(FILTER_FILTER_TYPE, AUTO_FILTER)
                            }
                        }
                    }
                }
                root.writeMessage(OBJECTS_REQUEST_IMAGE_DATA) { img ->
                    img.writeMessage(IMAGE_DATA_PAYLOAD) { payload ->
                        payload.writeBytes(IMAGE_PAYLOAD_BYTES, image.bytes)
                    }
                    img.writeMessage(IMAGE_DATA_METADATA) { meta ->
                        meta.writeInt32(IMAGE_METADATA_WIDTH, image.width)
                        meta.writeInt32(IMAGE_METADATA_HEIGHT, image.height)
                    }
                }
            }
        }.toByteArray()
    }

    private fun executeRequest(payload: ByteArray): ByteArray {
        val connection = (URL(LENS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", CONTENT_TYPE_PROTOBUF)
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("X-Goog-Api-Key", API_KEY)
            setRequestProperty("Connection", "keep-alive")
            setRequestProperty("Sec-Fetch-Mode", "no-cors")
            setRequestProperty("Sec-Fetch-Dest", "empty")
        }
        return try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                val preview = body.toString(Charsets.UTF_8).take(256)
                throw IOException("GLens request failed with HTTP $status: $preview")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(bytes: ByteArray): OcrResult {
        val reader = ProtoReader(bytes)
        val lines = mutableListOf<ParsedLine>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == SERVER_RESPONSE_OBJECTS_RESPONSE && wire == WIRE_LEN) {
                lines += parseObjectsResponse(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }

        val sorted = lines
            .filter { it.text.isNotBlank() && it.width > 0f && it.height > 0f }
            .sortedWith(compareBy({ it.centerY }, { it.centerX }))

        val regions = sorted.mapIndexedNotNull { index, line ->
            val left = ((line.centerX - line.width / 2f)).coerceIn(0f, 1f)
            val top = ((line.centerY - line.height / 2f)).coerceIn(0f, 1f)
            val right = ((line.centerX + line.width / 2f)).coerceIn(0f, 1f)
            val bottom = ((line.centerY + line.height / 2f)).coerceIn(0f, 1f)
            if (left >= right || top >= bottom) return@mapIndexedNotNull null
            OcrRegion(
                text = OcrTextCleaner.postprocess(line.text),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }

        val text = regions.joinToString(" ") { it.text.trim() }.trim()
        return OcrResult(text, regions)
    }

    private fun parseObjectsResponse(bytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(bytes)
        val out = mutableListOf<ParsedLine>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == OBJECTS_RESPONSE_TEXT && wire == WIRE_LEN) {
                out += parseTextLayouts(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }
        return out
    }

    private fun parseTextLayouts(bytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(bytes)
        val out = mutableListOf<ParsedLine>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == TEXT_LAYOUT && wire == WIRE_LEN) {
                out += parseParagraphs(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }
        return out
    }

    private fun parseParagraphs(bytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(bytes)
        val out = mutableListOf<ParsedLine>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == TEXT_LAYOUT_PARAGRAPH && wire == WIRE_LEN) {
                out += parseLines(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }
        return out
    }

    private fun parseLines(bytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(bytes)
        val out = mutableListOf<ParsedLine>()
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == PARAGRAPH_LINE && wire == WIRE_LEN) {
                parseLine(reader.readBytes())?.let { out += it }
            } else {
                reader.skipField(wire)
            }
        }
        return out
    }

    private fun parseLine(bytes: ByteArray): ParsedLine? {
        val reader = ProtoReader(bytes)
        val words = mutableListOf<Word>()
        var text = ""
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == LINE_WORD && wire == WIRE_LEN) {
                words += parseWord(reader.readBytes())
            } else {
                reader.skipField(wire)
            }
        }
        if (words.isEmpty()) return null

        val sb = StringBuilder()
        for (w in words) sb.append(w.text).append(w.separator)
        text = sb.toString().trim()

        val positioned = words.filter { it.cx > 0f || it.cy > 0f }
        if (positioned.isEmpty()) return null

        var minX = positioned.minOf { it.cx - it.width / 2f }
        var maxX = positioned.maxOf { it.cx + it.width / 2f }
        var minY = positioned.minOf { it.cy - it.height / 2f }
        var maxY = positioned.maxOf { it.cy + it.height / 2f }

        return ParsedLine(
            text = text,
            centerX = minX + (maxX - minX) / 2f,
            centerY = minY + (maxY - minY) / 2f,
            width = maxX - minX,
            height = maxY - minY,
        )
    }

    private fun parseWord(bytes: ByteArray): Word {
        val reader = ProtoReader(bytes)
        var text = ""
        var separator = ""
        var cx = 0f
        var cy = 0f
        var width = 0f
        var height = 0f
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == WORD_PLAIN_TEXT && wire == WIRE_LEN) {
                text = reader.readString()
            } else if (field == WORD_SEPARATOR && wire == WIRE_LEN) {
                separator = reader.readString()
            } else if (field == WORD_GEOMETRY && wire == WIRE_LEN) {
                val geo = parseGeometry(reader.readBytes())
                cx = geo[0]; cy = geo[1]; width = geo[2]; height = geo[3]
            } else {
                reader.skipField(wire)
            }
        }
        return Word(text, separator, cx, cy, width, height)
    }

    private fun parseGeometry(bytes: ByteArray): FloatArray {
        val reader = ProtoReader(bytes)
        val out = FloatArray(4)
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (field == GEOMETRY_BOUNDING_BOX && wire == WIRE_LEN) {
                val bb = parseBoundingBox(reader.readBytes())
                out[0] = bb[0]; out[1] = bb[1]; out[2] = bb[2]; out[3] = bb[3]
            } else {
                reader.skipField(wire)
            }
        }
        return out
    }

    private fun parseBoundingBox(bytes: ByteArray): FloatArray {
        val reader = ProtoReader(bytes)
        val out = FloatArray(4)
        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break
            val field = tag ushr 3
            val wire = tag and WIRE_MASK
            if (wire != WIRE_32BIT) {
                reader.skipField(wire)
                continue
            }
            when (field) {
                BOUNDING_BOX_CENTER_X -> out[0] = reader.readFloat()
                BOUNDING_BOX_CENTER_Y -> out[1] = reader.readFloat()
                BOUNDING_BOX_WIDTH -> out[2] = reader.readFloat()
                BOUNDING_BOX_HEIGHT -> out[3] = reader.readFloat()
                else -> reader.skipField(wire)
            }
        }
        return out
    }

    private data class ParsedLine(
        val text: String,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    private data class Word(
        val text: String,
        val separator: String,
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float,
    )

    private class PreparedImage(val bytes: ByteArray, val width: Int, val height: Int)

    companion object {
        private const val TAG = "OverlayGlens"

        private const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
        private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
        private const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private const val MAX_IMAGE_DIMENSION = 1500
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000

        private const val WIRE_MASK = 0x7
        private const val WIRE_LEN = 2
        private const val WIRE_32BIT = 5

        private const val SERVER_REQUEST_OBJECTS_REQUEST = 1
        private const val OBJECTS_REQUEST_CONTEXT = 1
        private const val OBJECTS_REQUEST_IMAGE_DATA = 3
        private const val REQUEST_CONTEXT_REQUEST_ID = 3
        private const val REQUEST_CONTEXT_CLIENT_CONTEXT = 4
        private const val REQUEST_ID_UUID = 1
        private const val REQUEST_ID_SEQUENCE_ID = 2
        private const val REQUEST_ID_IMAGE_SEQUENCE_ID = 3
        private const val REQUEST_ID_ANALYTICS_ID = 4
        private const val CLIENT_CONTEXT_PLATFORM = 1
        private const val CLIENT_CONTEXT_SURFACE = 2
        private const val CLIENT_CONTEXT_LOCALE_CONTEXT = 4
        private const val CLIENT_CONTEXT_CLIENT_FILTERS = 7
        private const val CLIENT_FILTERS_FILTER = 1
        private const val FILTER_FILTER_TYPE = 1
        private const val AUTO_FILTER = 1
        private const val LOCALE_LANGUAGE = 1
        private const val LOCALE_REGION = 2
        private const val IMAGE_DATA_PAYLOAD = 1
        private const val IMAGE_DATA_METADATA = 3
        private const val IMAGE_PAYLOAD_BYTES = 1
        private const val IMAGE_METADATA_WIDTH = 1
        private const val IMAGE_METADATA_HEIGHT = 2
        private const val PLATFORM_WEB = 3
        private const val SURFACE_CHROMIUM = 4

        private const val SERVER_RESPONSE_OBJECTS_RESPONSE = 2
        private const val OBJECTS_RESPONSE_TEXT = 3
        private const val TEXT_LAYOUT = 1
        private const val TEXT_LAYOUT_PARAGRAPH = 1
        private const val PARAGRAPH_LINE = 2
        private const val LINE_WORD = 1
        private const val WORD_PLAIN_TEXT = 2
        private const val WORD_SEPARATOR = 3
        private const val WORD_GEOMETRY = 4
        private const val GEOMETRY_BOUNDING_BOX = 1
        private const val BOUNDING_BOX_CENTER_X = 1
        private const val BOUNDING_BOX_CENTER_Y = 2
        private const val BOUNDING_BOX_WIDTH = 3
        private const val BOUNDING_BOX_HEIGHT = 4
    }
}

internal class ProtoWriter {
    private val bytes = ByteArrayOutputStream()

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, 0)
        writeVarint(value)
    }

    fun writeUInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, 0)
        writeVarint64(value)
    }

    fun writeString(fieldNumber: Int, value: String) = writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, 2)
        writeVarint(value.size)
        bytes.write(value)
    }

    fun writeMessage(fieldNumber: Int, block: (ProtoWriter) -> Unit) {
        val nested = ProtoWriter().apply(block).toByteArray()
        writeBytes(fieldNumber, nested)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()

    private fun writeTag(fieldNumber: Int, wireType: Int) = writeVarint((fieldNumber shl 3) or wireType)

    private fun writeVarint(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            bytes.write(v and 0x7F or 0x80)
            v = v ushr 7
        }
        bytes.write(v and 0x7F)
    }

    private fun writeVarint64(value: Long) {
        var v = value
        while (v and -0x80L != 0L) {
            bytes.write((v and 0x7F or 0x80).toInt())
            v = v ushr 7
        }
        bytes.write((v and 0x7F).toInt())
    }
}

internal class ProtoReader(private val bytes: ByteArray) {
    private var pos = 0

    fun hasRemaining(): Boolean = pos < bytes.size

    fun readTag(): Int {
        if (!hasRemaining()) return 0
        return readVarint32()
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readBytes(): ByteArray {
        val length = readVarint32()
        if (length < 0 || pos + length > bytes.size) {
            throw IOException("Invalid length-delimited field: $length")
        }
        val value = bytes.copyOfRange(pos, pos + length)
        pos += length
        return value
    }

    fun readFloat(): Float {
        if (pos + 4 > bytes.size) throw IOException("Unexpected end of protobuf while reading float")
        val bits = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return Float.fromBits(bits)
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint64()
            1 -> skipBytes(8)
            2 -> {
                val length = readVarint32()
                skipBytes(length)
            }
            5 -> skipBytes(4)
            else -> throw IOException("Unsupported protobuf wire type: $wireType")
        }
    }

    private fun skipBytes(count: Int) {
        if (count < 0 || pos + count > bytes.size) throw IOException("Invalid skip length: $count")
        pos += count
    }

    private fun readVarint32(): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint32")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        repeat(5) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint32 overflow")
            if (bytes[pos++].toInt() and 0x80 == 0) return result
        }
        throw IOException("Malformed varint32")
    }

    private fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint64")
            val b = bytes[pos++].toLong() and 0xFFL
            result = result or ((b and 0x7FL) shl shift)
            if (b and 0x80L == 0L) return result
            shift += 7
        }
        throw IOException("Malformed varint64")
    }
}