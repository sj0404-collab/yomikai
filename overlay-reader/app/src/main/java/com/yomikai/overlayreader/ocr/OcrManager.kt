package com.yomikai.overlayreader.ocr

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Выбор движка по настройке Prefs.ocrMode():
 *  - "local"  — только самодельный оффлайн-движок;
 *  - "online" — только Google Lens;
 *  - "auto"   — Google Lens, при недоступности сети/пустом результате — локальный.
 */
class OcrManager {

    private val local = LocalOcrEngine()
    private val online = GlensOcrEngine()

    suspend fun recognize(image: Bitmap, mode: String): OcrResult {
        return when (mode) {
            "local" -> local.recognizeText(image)
            "online" -> online.recognizeText(image)
            else -> auto(image)
        }
    }

    private suspend fun auto(image: Bitmap): OcrResult {
        val onlineResult = runCatching { online.recognizeText(image) }
        if (onlineResult.isSuccess) {
            val r = onlineResult.getOrThrow()
            if (r.text.isNotBlank()) return r
        }
        Log.i(TAG, "online OCR failed/empty, falling back to local")
        return withContext(Dispatchers.Default) { local.recognizeText(image) }
    }

    /** Насколько текст читаем (доля нераспознанных символов и пустые области). */
    fun isUsable(result: OcrResult): Boolean {
        if (result.text.isBlank()) return false
        if (result.regions.isEmpty()) return false
        val questionMarks = result.text.count { it == '?' }
        return questionMarks.toFloat() / result.text.length < 0.2f
    }

    fun close() {
        runCatching { local.close() }
        runCatching { online.close() }
    }

    companion object {
        private const val TAG = "OverlayOcr"
    }
}