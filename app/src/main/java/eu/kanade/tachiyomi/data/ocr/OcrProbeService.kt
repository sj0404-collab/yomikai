package eu.kanade.tachiyomi.data.ocr

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Проверка OCR в ОТДЕЛЬНОМ процессе.
 *
 * Зачем отдельный процесс: падение нативного слоя (ONNX Runtime, libc++,
 * драйвер) не перехватывается ни `try/catch`, ни обработчиком исключений —
 * процесс просто умирает. Если это происходит в основном процессе, читалка
 * закрывается вместе с приложением, и пользователь видит «вылет».
 *
 * Здесь проверка вынесена в процесс `:ocrprobe`: если он падает, приложение
 * остаётся живым, а на диске остаются метки START без DONE — по ним видно, что
 * именно упало нативно, и на каком шаге.
 */
class OcrProbeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val file = File(filesDir, PROBE_FILE)
            // Метка «начали» пишется ДО любой работы с нативным кодом.
            file.writeText("START ${System.currentTimeMillis()}\n")
            // Каждый шаг дописывается сразу: если нативный слой убьёт процесс,
            // на диске останутся все пройденные шаги и последний начатый.
            val report = runCatching {
                OcrDiagnostics.report(this@OcrProbeService) { step ->
                    runCatching { file.appendText("STEP $step\n") }
                }
            }.getOrElse { "ОШИБКА проверки: ${it::class.simpleName}: ${it.message}" }
            file.writeText("START\nDONE\n$report\n")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PROBE_FILE = "onnx_probe.txt"

        /**
         * Запускает проверку в отдельном процессе.
         *
         * @return момент запуска — по нему UI понимает, что проверка «в пути»,
         * а не «ещё не начиналась».
         */
        fun start(context: Context): Long {
            File(context.filesDir, PROBE_FILE).writeText("QUEUED ${System.currentTimeMillis()}\n")
            runCatching {
                context.startService(Intent(context, OcrProbeService::class.java))
            }.onFailure {
                File(context.filesDir, PROBE_FILE)
                    .writeText("FAILED ${System.currentTimeMillis()}\n${it::class.simpleName}: ${it.message}\n")
            }
            return System.currentTimeMillis()
        }
    }
}
