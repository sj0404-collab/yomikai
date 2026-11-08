package mihon.feature.ocr

import dev.icerock.moko.resources.StringResource
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val OcrModel.titleRes: StringResource
    get() = when (this) {
        OcrModel.CYRILLIC -> MR.strings.ocr_model_cyrillic
        OcrModel.LEGACY -> MR.strings.ocr_model_legacy
        OcrModel.FAST -> MR.strings.ocr_model_fast
        OcrModel.GLENS -> MR.strings.ocr_model_glens
        OcrModel.OWOCR -> MR.strings.ocr_model_owocr
        OcrModel.OPENROUTER -> MR.strings.ocr_model_openrouter
        OcrModel.GOOGLE -> MR.strings.ocr_model_google
        OcrModel.ZEN_FREE -> MR.strings.ocr_model_zen_free
        OcrModel.TESSERACT -> MR.strings.ocr_model_tesseract
        OcrModel.MLKIT -> MR.strings.ocr_model_mlkit
    }

/**
 * Движок, ВЫБРАННЫЙ в настройках.
 *
 * Нужен, чтобы сказать «прочитал не тот»: без сети онлайн пропускается, и
 * текст читает локальный. Читателю важно видеть и выбранное, и фактическое.
 */
fun selectedOcrModel(): OcrModel = Injekt.get<OcrPreferences>().ocrModel().get()
