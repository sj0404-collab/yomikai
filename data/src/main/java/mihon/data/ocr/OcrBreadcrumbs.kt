package mihon.data.ocr

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * След последних шагов OCR на диске.
 *
 * Зачем: если падает НАТИВНЫЙ слой (ONNX Runtime, libc++), Java-трейса нет
 * вообще — приложение просто закрывается. Единственное, что остаётся, —
 * последняя запись, которую успели сбросить на диск. Поэтому каждый шаг
 * распознавания (какая модель, какой кроп, сколько боксов) пишется сразу, без
 * буферизации, и переживает падение процесса.
 *
 * Файл читает экран настроек («Последняя ошибка»).
 */
object OcrBreadcrumbs {

    private const val FILE = "ocr_breadcrumbs.txt"
    private const val LIMIT = 200

    fun mark(context: Context?, event: String) {
        val ctx = context ?: return
        runCatching {
            val file = File(ctx.filesDir, FILE)
            val lines = (if (file.isFile) file.readLines() else emptyList()) + "${stamp()} $event"
            file.writeText(lines.takeLast(LIMIT).joinToString("\n"))
        }
    }

    private fun stamp(): String = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.ROOT).format(Date())
}
