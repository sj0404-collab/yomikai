package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.ui.koma.KomaOcrEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диагностика OCR «одной кнопкой».
 *
 * Задача — перестать угадывать. Пользователь присылает не «вылет», а отчёт:
 * какая версия, сколько памяти, распаковались ли модели, поднялись ли сессии
 * ONNX, что движок прочитал на картинке с известным текстом, и что было в
 * последнем падении. Всё это доступно на устройстве и копируется одной кнопкой.
 */
object OcrDiagnostics {

    private const val CRASH_FILE = "last_crash.txt"
    private const val BREADCRUMB_FILE = "ocr_breadcrumbs.txt"
    private const val MAX_CRUMBS = 60

    /** След последнего действия OCR: при нативном падении Java-трейса не будет. */
    fun mark(context: Context, event: String) {
        runCatching {
            val file = File(context.filesDir, BREADCRUMB_FILE)
            val line = "${timestamp()} $event"
            val lines = (file.takeIf { it.isFile }?.readLines().orEmpty() + line).takeLast(MAX_CRUMBS)
            file.writeText(lines.joinToString("\n"))
        }
    }

    /** Сохранение последнего Java-падения (зовётся из GlobalExceptionHandler). */
    fun saveCrash(context: Context, throwable: Throwable) {
        runCatching {
            File(context.filesDir, CRASH_FILE).writeText(
                "${timestamp()} ${throwable.stackTraceToString().take(8000)}",
            )
        }
    }

    fun lastCrash(context: Context): String? =
        runCatching { File(context.filesDir, CRASH_FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    fun breadcrumbs(context: Context): String? =
        runCatching { File(context.filesDir, BREADCRUMB_FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, CRASH_FILE).delete() }
        runCatching { File(context.filesDir, BREADCRUMB_FILE).delete() }
    }

    private const val ONNX_STATUS_FILE = "onnx_status.txt"

    /**
     * Проверка ОДНОГО факта: поднимаются ли сессии ONNX с моделями из APK.
     * Запускается в фоне при старте, поэтому состояние видно в настройках без
     * нажатия кнопки — и его достаточно, чтобы понять «ONNX не работает».
     */
    suspend fun preflight(context: Context): String {
        // Саму проверку делает модуль data (движок там internal): из app видно
        // только готовый статус одной строкой.
        val result = mihon.data.ocr.OcrSelfCheck.status(context)
        runCatching { File(context.filesDir, ONNX_STATUS_FILE).writeText("${timestamp()} $result") }
        return result
    }

    /** Последний результат preflight (или «ещё не проверялся»). */
    fun onnxStatus(context: Context): String =
        runCatching { File(context.filesDir, ONNX_STATUS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
            ?: "ещё не проверялся — откройте приложение ещё раз"

    /** Полный отчёт: устройство, движки, последнее падение, след действий. */
    suspend fun report(context: Context): String {
        val runtime = Runtime.getRuntime()
        val version = runCatching { "${info(context).versionName} (${versionCode(context)})" }
            .getOrDefault("?")
        return buildString {
            appendLine("Yomikai $version")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(
                "Память: максимум ${runtime.maxMemory() / 1048576} МБ, " +
                    "свободно ${runtime.freeMemory() / 1048576} МБ",
            )
            appendLine("ML Kit (латиница/японский): есть в APK")
            appendLine()
            appendLine(safeRun { mihon.data.ocr.OcrSelfCheck.run(context) })
            appendLine(safeRun { KomaOcrEngine(context).let { engine -> try { engine.selfTest() } finally { engine.close() } } })
            appendLine()
            val crash = lastCrash(context)
            appendLine("Последнее падение: " + (crash?.take(1500)?.let { "\n$it" } ?: "нет записи"))
            appendLine()
            appendLine("След действий:\n" + (breadcrumbs(context)?.takeLast(1200) ?: "нет записи"))
        }
    }

    private suspend fun safeRun(block: suspend () -> String): String = try {
        block()
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        "ОШИБКА проверки: ${t::class.simpleName}: ${t.message}"
    }

    @Suppress("DEPRECATION")
    private fun versionCode(context: Context): Long {
        val info = info(context)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun info(context: Context): android.content.pm.PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, 0)

    private fun timestamp(): String =
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date())
}
