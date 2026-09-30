package com.yomikai.overlayreader.ocr

import android.graphics.Bitmap

/**
 * Контракт OCR-движка читалки.
 *
 * Реализации бывают двух видов:
 *  - [OnlineOcrEngine] (Google Lens) — точный, но требует сеть;
 *  - локальный движок без внешних моделей (template-matching, без Tesseract и
 *    без ML Kit) — офлайн, для авточтения поверх любого приложения.
 */
interface OcrEngine {
    /** Распознать текст на картинке и вернуть готовый результат. */
    suspend fun recognizeText(image: Bitmap): OcrResult

    fun close() = Unit
}

/** Результат распознавания: общий текст + отдельные области (реплики/строки). */
class OcrResult(
    /** Полный текст, области склеены по одному на строку. */
    val text: String,
    /** Области текста в нормализованных координатах 0..1. */
    val regions: List<OcrRegion>,
) {
    companion object {
        fun empty(): OcrResult = OcrResult("", emptyList())
    }
}

class OcrRegion(
    val text: String,
    /** left, top, right, bottom в нормализованных координатах 0..1. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)