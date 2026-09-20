package com.yomikai.overlayreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/**
 * Экран настройки/запуска читалки: запрос разрешения на оверлей, уведомления
 * и захват экрана, затем запуск [OverlayReaderService].
 *
 * Собран на чистом Android Framework (без androidx), как и весь остальной код
 * читалки, чтобы держать зависимости минимальными.
 */
class MainActivity : Activity() {

    private val CAPTURE_REQUEST = 1001
    private val NOTIFICATION_REQUEST = 1002

    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Prefs.init(this)

        findViewById<Button>(R.id.btn_start).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            OverlayReaderService.stop(this)
            showStatus("Читалка остановлена")
        }
        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // После возврата с экрана «Разрешить поверх других приложений».
        if (pendingStart && canDrawOverlays()) {
            pendingStart = false
            launchCapture()
        }
    }

    private fun onStartClicked() {
        if (!canDrawOverlays()) {
            pendingStart = true
            requestOverlayPermission()
            return
        }
        launchCapture()
    }

    private fun launchCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), CAPTURE_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                OverlayReaderService.start(this, resultCode, data)
                showStatus("Читалка запущена. Разрешите захват экрана в системном диалоге.")
                finish()
            } else {
                showStatus("Захват экрана не разрешён")
            }
        }
    }

    private fun canDrawOverlays(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ).let(::startActivity)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun showStatus(text: String) {
        findViewById<TextView>(R.id.tv_status)?.text = text
    }
}