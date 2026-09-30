package com.yomikai.overlayreader

import android.content.Context
import android.content.SharedPreferences

/**
 * Простые сохранённые настройки читалки: положение/размер рамки зампинга,
 * выбранный движок, настройки озвучки.
 *
 * Всё хранится во float/boolean/string — не требует Android-инфраструктуры и
 * переживает перезапуск сервиса поверх любого приложения.
 */
object Prefs {

    private const val NAME = "overlay_reader"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // ---- Рамка захвата (в долях экрана 0..1) ----
    fun frame() = RectFBean(
        left = sp.getFloat("frame_left", 0.06f),
        top = sp.getFloat("frame_top", 0.16f),
        right = sp.getFloat("frame_right", 0.94f),
        bottom = sp.getFloat("frame_bottom", 0.55f),
    )

    fun saveFrame(f: RectFBean) {
        sp.edit()
            .putFloat("frame_left", f.left)
            .putFloat("frame_top", f.top)
            .putFloat("frame_right", f.right)
            .putFloat("frame_bottom", f.bottom)
            .apply()
    }

    // ---- Движок OCR: "auto" | "local" | "online" ----
    fun ocrMode(): String = sp.getString("ocr_mode", "auto") ?: "auto"

    fun setOcrMode(value: String) {
        sp.edit().putString("ocr_mode", value).apply()
    }

    // ---- Озвучка ----
    fun autoTts(): Boolean = sp.getBoolean("auto_tts", true)

    fun setAutoTts(value: Boolean) {
        sp.edit().putBoolean("auto_tts", value).apply()
    }

    fun ttsVoiceIndex(): Int = sp.getInt("tts_voice_index", 0)

    fun setTtsVoiceIndex(value: Int) {
        sp.edit().putInt("tts_voice_index", value).apply()
    }

    // ---- Прочее UI ----
    fun overlayOpacity(): Float = sp.getFloat("overlay_opacity", 0.32f)

    fun setOverlayOpacity(value: Float) {
        sp.edit().putFloat("overlay_opacity", value).apply()
    }

    fun hiddenUntilTap(): Boolean = sp.getBoolean("hidden_until_tap", false)
}

/** Прямоугольник рамки в долях 0..1. */
data class RectFBean(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0.1f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.1f)
}