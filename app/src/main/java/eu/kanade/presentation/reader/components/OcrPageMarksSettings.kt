package eu.kanade.presentation.reader.components

import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Показывать ли в читалке пометки порядка чтения поверх самой страницы.
 *
 * Переключатель вынесен сюда, а не в [mihon.domain.ocr.service.OcrPreferences]:
 * этот класс правят другие изменения, а лишний ключ в общем хранилище настроек
 * ничего не ломает. Значение по умолчанию включено, старые установки читаются
 * как раньше.
 *
 * Переключатель показан прямо в читалке (плашка «№» у верхнего края): рисунок
 * поверх страницы читателю нужно убирать одним касанием, не заходя в настройки.
 */
object OcrPageMarksSettings {

    private const val PREF_KEY = "pref_ocr_page_marks"

    private val preference: Preference<Boolean> by lazy {
        Injekt.get<PreferenceStore>().getBoolean(PREF_KEY, true)
    }

    /**
     * Палитра пометок — та же, что во вкладке «Скриншоты».
     *
     * Одна и та же раскраска в кадре и в книге: иначе непонятно, откуда взялись
     * разноцветные номера на странице.
     */
    val palette: IntArray = intArrayOf(
        0x8800E5FF.toInt(),
        0x88FF6D00.toInt(),
        0x88AA00FF.toInt(),
        0x8800E676.toInt(),
        0x88FFD600.toInt(),
        0x88FF1744.toInt(),
    )

    /** Рисовать ли пометки поверх страницы прямо сейчас. */
    fun isEnabled(): Boolean = preference.get()

    /** Изменения переключателя — плашка в читалке обновляется сразу. */
    fun changes(): Flow<Boolean> = preference.changes()

    fun setEnabled(enabled: Boolean) {
        preference.set(enabled)
    }
}
