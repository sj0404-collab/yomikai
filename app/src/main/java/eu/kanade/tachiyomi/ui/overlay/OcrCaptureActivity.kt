package eu.kanade.tachiyomi.ui.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Добывает системное разрешение на захват экрана и передаёт его оверлею.
 *
 * Android не даёт начать MediaProjection из фонового сервиса: диалог
 * разрешения и его результат доступны только activity. Поэтому панель
 * оверлея (это сервис) поднимает прозрачную activity, которая на экране
 * не видна: она только спрашивает и сразу закрывается.
 */
class OcrCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            fail("Захват экрана недоступен")
            return
        }
        if (!hasRegion()) {
            fail("Сначала выделите область рамки кнопкой ✏")
            return
        }
        runCatching { captureLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { fail("Захват экрана недоступен") }
    }

    /** Задана ли область рамки: без неё захват запускать незачем. */
    private fun hasRegion(): Boolean =
        Injekt.get<OcrPreferences>().overlayFixedRegion().get()
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .takeIf { it.size == 4 }
            ?.let { (l, t, r, b) -> l < r && t < b } == true

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            fail("Захват экрана не разрешён")
            return@registerForActivityResult
        }
        OcrOverlayService.startReading(this, result.resultCode, data)
        finish()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        /** Поднимает прозрачную activity, которая спросит разрешение. */
        fun request(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, OcrCaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Toast.makeText(context, "Захват экрана недоступен", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
