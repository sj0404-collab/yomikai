package mihon.data.ocr

import android.content.Context

/**
 * «Здоровье» движка ONNX на конкретном устройстве.
 *
 * Зачем: падение нативного слоя ONNX Runtime не перехватывается ни `try/catch`,
 * ни обработчиком исключений — процесс умирает целиком. Проверка, которая
 * гибнет вместе с процессом, — единственный способ это заметить: её метка
 * `START` остаётся без `DONE`. После этого приложение не пытается снова лезть в
 * ONNX на каждой странице, а честно переключается на Tesseract.
 *
 * Состояния:
 *  * [State.UNKNOWN] — ещё не проверяли;
 *  * [State.ALIVE] — проверка прошла в отдельном процессе;
 *  * [State.DEAD] — процесс проверки умер: ONNX на этом устройстве не работает.
 */
object OcrEngineHealth {

    private const val PREFS = "ocr_engine_health"
    private const val KEY_ONNX = "onnx_state"
    private const val KEY_ONNX_NOTE = "onnx_note"

    enum class State { UNKNOWN, ALIVE, DEAD }

    fun onnxState(context: Context): State {
        val raw = prefs(context).getString(KEY_ONNX, null) ?: return State.UNKNOWN
        return runCatching { State.valueOf(raw) }.getOrDefault(State.UNKNOWN)
    }

    fun onnxNote(context: Context): String? = prefs(context).getString(KEY_ONNX_NOTE, null)

    fun setOnnx(context: Context, state: State, note: String? = null) {
        prefs(context).edit().putString(KEY_ONNX, state.name).putString(KEY_ONNX_NOTE, note).apply()
    }

    /**
     * Правда, если кириллицу надо читать через Tesseract: ONNX либо уже
     * признан нерабочим, либо пользователь выбрал Tesseract руками.
     */
    fun useTesseractForCyrillic(context: Context, userWantsTesseract: Boolean): Boolean =
        userWantsTesseract || onnxState(context) == State.DEAD

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
