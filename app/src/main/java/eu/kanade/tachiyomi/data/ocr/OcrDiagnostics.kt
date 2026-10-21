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
     * Короткий статус «сработал ли ONNX» — для строки в настройках.
     * Саму проверку делает модуль data: движок там internal.
     */
    suspend fun preflight(context: Context): String {
        val result = mihon.data.ocr.OcrSelfCheck.status(context)
        runCatching { File(context.filesDir, ONNX_STATUS_FILE).writeText("${timestamp()} $result") }
        return result
    }

    /**
     * Запуск проверки в отдельном процессе и ожидание результата.
     *
     * Возвращает человекочитаемый итог. Если процесс проверки умер, не дописав
     * DONE, — это нативное падение: приложение при этом остаётся живым, а в
     * отчёте так и сказано (иначе пользователь видел «простой вылет»).
     */
    suspend fun runProbe(context: Context, timeoutMs: Long = 40_000): String {
        val file = File(context.filesDir, OcrProbeService.PROBE_FILE)
        OcrProbeService.start(context)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(400)
            val text = read(file)
            if (text.contains("\nDONE\n")) {
                val report = text.substringAfter("\nDONE\n").trim()
                val failed = INFERENCE_STEPS.any { step ->
                    report.lineSequence().any { it.contains(step) && it.contains("ОШИБКА") }
                }
                mihon.data.ocr.OcrEngineHealth.setOnnx(
                    context,
                    if (failed) mihon.data.ocr.OcrEngineHealth.State.DEAD else mihon.data.ocr.OcrEngineHealth.State.ALIVE,
                    report.lineSequence().firstOrNull { it.contains("ОШИБКА") },
                )
                return report
            }
            if (text.startsWith("FAILED")) {
                return "Не удалось запустить проверку в отдельном процессе:\n" + text.substringAfter('\n').trim()
            }
        }

        val text = read(file)
        if (!text.startsWith("START")) {
            return "Проверка не завершилась за ${timeoutMs / 1000} с (процесс не ответил).\n" +
                "Последние шаги OCR:\n" + (breadcrumbs(context) ?: "нет записи")
        }

        // Процесс проверки умер, не дописав DONE: нативное падение. Из
        // дописанных шагов видно, на каком именно — и это разные диагнозы.
        val steps = text.lineSequence().filter { it.startsWith("STEP ") }.map { it.removePrefix("STEP ") }.toList()
        val lastStarted = steps.lastOrNull { it.endsWith(": начат") || it.endsWith(": начата") || it.endsWith(": начаты") }
            ?.substringBefore(':') ?: "неизвестно"
        val passed = steps.filter { it.endsWith(": OK") }
        val diagnosis = when {
            lastStarted.startsWith("0/7") ->
                "Падает САМ ONNX Runtime на крошечной модели, которая не имеет отношения к KOMA.\n" +
                    "Значит дело не в моделях и не в размере входа: этот ONNX Runtime на вашем\n" +
                    "процессоре не работает вообще (обычно это неподдерживаемая инструкция CPU)."
            lastStarted.startsWith("2/7") ->
                "Падают сессии моделей KOMA (открытие графа)."
            lastStarted.startsWith("3/7") ->
                "Падает вывод детектора на синтетическом входе 64x64 — то есть размер входа\n" +
                    "действительно имеет значение (ваша версия)."
            lastStarted.startsWith("4/7") ->
                "Открытие сессий и вход 64x64 прошли, падение — на РАБОЧЕМ размере детектора\n" +
                    "с настоящими пикселями. Маленький вход тут не виноват."
            lastStarted.startsWith("5/7") -> "Падает распознаватель на настоящих пикселях."
            lastStarted.startsWith("6/7") -> "Падает полное распознавание страницы."
            else -> "Место падения не определено."
        }
        mihon.data.ocr.OcrEngineHealth.setOnnx(
            context,
            mihon.data.ocr.OcrEngineHealth.State.DEAD,
            "нативное падение на шаге $lastStarted",
        )
        return buildString {
            appendLine("ONNX УПАЛ НАТИВНО на шаге $lastStarted (без DONE).")
            appendLine()
            appendLine(diagnosis)
            appendLine()
            appendLine("Прошли шаги: " + passed.joinToString(", ").ifBlank { "ни одного" })
            appendLine("Приложение осталось живым — проверка шла в отдельном процессе.")
            appendLine("Кириллица уже читается Tesseract (рус. модель в APK): падений на страницах не будет.")
            appendLine()
            appendLine("Журнал шагов:")
            appendLine(steps.joinToString("\n"))
        }
    }

    /** Шаги, где считается настоящий вывод моделей: ошибка на любом = ONNX не годится. */
    private val INFERENCE_STEPS = listOf("3/7", "4/7", "5/7", "6/7")

    private fun read(file: File): String =
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()

    /** Последний результат preflight (или «ещё не проверялся»). */
    fun onnxStatus(context: Context): String =
        runCatching { File(context.filesDir, ONNX_STATUS_FILE).takeIf { it.isFile }?.readText() }.getOrNull()
            ?: "ещё не проверялся — откройте приложение ещё раз"

    /** Полный отчёт: устройство, движки, последнее падение, след действий. */
    suspend fun report(context: Context, progress: (String) -> Unit = {}): String {
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
            appendLine(safeRun { mihon.data.ocr.OcrSelfCheck.run(context, progress) })
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
