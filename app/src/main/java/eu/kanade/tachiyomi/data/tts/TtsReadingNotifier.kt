package eu.kanade.tachiyomi.data.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Уведомление «Сейчас читается»: распознанный текст страницы + кнопка
 * «⏹ Остановить» прямо в шторке. Текст остаётся в уведомлении, даже когда
 * озвучка закончилась — можно перечитать.
 */
object TtsReadingNotifier {

    private const val CHANNEL_ID = "yomikai_tts_reading"
    private const val NOTIFICATION_ID = 0x77A1
    private const val ACTION_STOP = "app.yomikai.TTS_STOP"

    @Volatile
    private var receiverRegistered = false

    /**
     * Читатель нажал «⏹ Остановить» в шторке.
     *
     * Кнопка раньше только глушила синтез: цикл авточтения продолжал листать
     * главу, кадр за кадром проглатывался в тишину, и читатель получал
     * пролистанную главу вместо остановки. Теперь запрос доходит до цикла
     * через [consumeStopRequest].
     */
    @Volatile
    private var stopRequested = false

    /**
     * Забрать запрос «остановить», если он был. Одноразово.
     *
     * Запрос сбрасывается чтением, а не показом уведомления: показ случается
     * в начале каждого кадра и затер бы ответ, который читатель уже дал.
     */
    fun consumeStopRequest(): Boolean {
        if (!stopRequested) return false
        stopRequested = false
        return true
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STOP) {
                stopRequested = true
                TtsSpeaker.stop()
                dismiss(context)
            }
        }
    }

    /**
     * Лимит текста в уведомлении.
     *
     * Android отбрасывает Notification, чей суммарный размер превышает
     * ~500 КБ (RemoteViews / Bundle), и падает с TransactionTooLargeException
     * либо DeadObjectException, залив logcat простынёй. Полная страница OCR
     * легко даёт несколько тысяч символов, поэтому режем жёстко.
     */
    private const val MAX_NOTIFICATION_TEXT = 800

    /**
     * Показать уведомление о чтении.
     *
     * [pageLabel] — где читатель находится в главе («Страница 3 из 20»).
     * Показывается под заголовком: авточтение читает главу, а не одну
     * страницу, и без такой подсказки шторка выглядит как зависшая на первой.
     */
    fun show(context: Context, text: String, pageLabel: String? = null) {
        if (text.isBlank()) return
        val app = context.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Озвучка страниц",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_STOP)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    app.registerReceiver(stopReceiver, filter)
                }
                receiverRegistered = true
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to register TTS stop receiver" }
            }
        }

        val stopIntent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(ACTION_STOP).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val safeText = text.take(MAX_NOTIFICATION_TEXT)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mihon)
            .setContentTitle(pageLabel?.let { "🔊 Читается глава · $it" } ?: "🔊 Читается страница")
            .setContentText(safeText.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeText))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .addAction(0, "⏹ Остановить", stopIntent)
            .build()

        // Без разрешения POST_NOTIFICATIONS (Android 13+) notify() бросает
        // SecurityException; уведомление — не повод ронять читалку.
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "TTS notification rejected" }
        }
    }

    fun dismiss(context: Context) {
        val nm = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    /**
     * Снять уведомление и дать задерегистрировать приёмник «⏹ Остановить».
     *
     * Регистрация шла на applicationContext, поэтому без явного shutdown
     * приёмник висел навсегда: процесс держал ссылку на объект-сироту до
     * самого перезапуска приложения. Вызывается из `destroy()` владельцев
     * чтения (ReaderActivity, BrowserTab, OcrOverlayService).
     */
    fun shutdown(context: Context) {
        dismiss(context)
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching {
            context.applicationContext.unregisterReceiver(stopReceiver)
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "Failed to unregister TTS stop receiver" }
        }
    }
}
