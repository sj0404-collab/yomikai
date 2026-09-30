package mihon.domain.ocr.model

/**
 * Represents the available OCR and AI vision models.
 */
enum class OcrModel {
    /**
     * Default downloadable Russian/Cyrillic offline engine: PP-OCRv3 with
     * PP-OCRv5 verifier and PP-OCRv4 text detector.
     */
    CYRILLIC,

    /**
     * Legacy and slower model, supports GPU/CPU.
     */
    LEGACY,

    /**
     * Faster model designed for ARM CPU.
     */
    FAST,

    /**
     * Online Google Lens OCR model.
     */
    GLENS,

    /**
     * Self-hosted OwOCR model.
     */
    OWOCR,

    /**
     * OpenRouter online AI model.
     */
    OPENROUTER,

    /**
     * Google AI / Gemini Vision model.
     */
    GOOGLE,

    /**
     * Бесплатная vision-модель Space Bunny Free через OpenCode Zen.
     */
    ZEN_FREE,

    /**
     * Полностью офлайн Tesseract (модели eng+rus в tar.xz внутри APK,
     * активируются только при включении движка).
     */
    TESSERACT,

    /**
     * Google ML Kit Text Recognition для латинского текста (on-device, модель
     * внутри APK, сеть не нужна). Отдаёт текст построчно с координатами строк.
     */
    MLKIT,
}
