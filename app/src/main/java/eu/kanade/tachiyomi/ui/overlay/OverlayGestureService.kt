package eu.kanade.tachiyomi.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Служба доступности — только чтобы отправлять чужому приложению свайпы.
 *
 * Своей прокрутки у чужого приложения взять нельзя: листать его можно
 * лишь жестом, который кто-то должен отправить. Единственный доступный
 * способ — [android.accessibilityservice.AccessibilityService].
 *
 * Ничего больше служба не делает: события не читает, узлы не обходит и
 * ничего не выполняет. Ровно один жест — свайп по заданным координатам.
 */
class OverlayGestureService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Служба ни на что не подписана и не должна мешатьTalkBack. */
    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    companion object {
        @Volatile
        var instance: OverlayGestureService? = null
            private set

        /**
         * Поток, на котором Android ждёт результат жеста.
         *
         * Отдельный от потока сервиса оверлея: жест отправляет цикл чтения, а
         * колбэк Android обязан получить на лоопере, который живёт, пока
         * работает сама служба доступности.
         */
        private val callbackHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Включена ли служба в настройках Android. */
        fun isEnabled(): Boolean = instance != null

        /**
         * Отправляет один свайп.
         *
         * @param fromX,fromY начало жеста в пикселях экрана
         * @param toX,toY конец жеста
         * @param durationMs длительность жеста: слишком короткий пролистывает
         *   на полстраницы мимо текста, слишком длинный Android не засчитывает
         *   как свайп вовсе
         * @return true, если жест ушёл в систему
         */
        fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = SWIPE_MS): Boolean {
            val service = instance ?: return false
            if (abs(fromX - toX) < 1 && abs(fromY - toY) < 1) return false
            val path = Path().apply {
                moveTo(fromX.toFloat(), fromY.toFloat())
                lineTo(toX.toFloat(), toY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 3_000L)),
                )
                .build()
            return service.dispatchGesture(gesture, null, null) == true
        }

        /**
         * Свайп с ожиданием результата.
         *
         * [swipe] возвращает лишь «жест отправлен в систему» — этого
         * недостаточно: Android может отклонить жест уже по ходу (занято
         * окно, приложение перехватило), и читатель узнаёт об этом только по
         * тому, что следующий кадр совпал с предыдущим. Здесь результат
         * сообщается системой: true — жест отработал, false — отклонён.
         */
        suspend fun swipeAndAwait(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long = SWIPE_MS,
        ): Boolean = swipeAndAwait(GestureSwipe(fromX, fromY, toX, toY), durationMs)

        /** То же для готового описания жеста. */
        suspend fun swipeAndAwait(swipe: GestureSwipe, durationMs: Long = SWIPE_MS): Boolean {
            val service = instance ?: return false
            if (abs(swipe.fromX - swipe.toX) < 1 && abs(swipe.fromY - swipe.toY) < 1) return false
            val path = Path().apply {
                moveTo(swipe.fromX.toFloat(), swipe.fromY.toFloat())
                lineTo(swipe.toX.toFloat(), swipe.toY.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 3_000L)),
                )
                .build()
            val result = CompletableDeferred<Boolean>()
            val sent = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        result.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        result.complete(false)
                    }
                },
                callbackHandler,
            )
            if (!sent) return false
            return withTimeoutOrNull(GESTURE_TIMEOUT_MS) { result.await() } ?: false
        }

        /**
         * Свайп вверх по центру области: так листают вперёд.
         *
         * Жест идёт по вертикали центра рамки, а не всего экрана, иначе
         * палец уезжал бы в область, которую приложение не слушает.
         */
        fun scrollForward(rect: android.graphics.Rect): Boolean {
            if (rect.width() <= 0 || rect.height() <= 0) return false
            val cx = rect.centerX()
            val fromY = rect.centerY() + (rect.height() * 0.30f).toInt()
            val toY = rect.centerY() - (rect.height() * 0.30f).toInt()
            return swipe(cx, fromY, cx, toY)
        }

        /** Свайп вниз — вернуться назад. */
        fun scrollBack(rect: android.graphics.Rect): Boolean {
            if (rect.width() <= 0 || rect.height() <= 0) return false
            val cx = rect.centerX()
            val fromY = rect.centerY() - (rect.height() * 0.30f).toInt()
            val toY = rect.centerY() + (rect.height() * 0.30f).toInt()
            return swipe(cx, fromY, cx, toY)
        }

        private const val SWIPE_MS = 320L

        /** Сколько ждать ответа системы по жесту, мс. */
        private const val GESTURE_TIMEOUT_MS = 3_000L
    }
}

/** Прямоугольный жест: откуда и куда вести пальцем, в пикселях экрана. */
data class GestureSwipe(
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
)

/** Показывает, что служба выключена, и ведёт в настройки доступности. */
fun Context.requestGestureService() {
    runCatching {
        startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { toast("Открой Настройки → Специальные возможности") }
}
