package eu.kanade.tachiyomi.ui.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Приложения телефона, на которых оверлей имеет смысл.
 *
 * Читалка оверлея нужна не только для манги: игра на чужом языке, русский
 * текст без озвучки, субтитры к видео в реальном времени — во всех трёх
 * случаях нужен один и тот же список приложений, на которые её можно
 * наложить.
 *
 * Запрашиваются только запускаемые приложения (MAIN + LAUNCHER): этого хватает
 * для списка, и обходится без QUERY_ALL_PACKAGES, который в Google Play не
 * проходит.
 */
object InstalledApps {

    data class Entry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    /**
     * Состояние списка. Ошибка показывается читателю, а не молча даёт пустой
     * экран: иначе непонятно, приложений нет или список не прочитался.
     */
    sealed interface AppsState {
        data object Loading : AppsState
        data class Ready(val entries: List<Entry>) : AppsState
        data class Failed(val reason: String) : AppsState
    }

    fun loadState(context: Context): AppsState = runCatching { list(context) }
        .fold(
            onSuccess = { AppsState.Ready(it) },
            onFailure = { AppsState.Failed(it.message ?: it.javaClass.simpleName) },
        )

    fun list(context: Context): List<Entry> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            pm.queryIntentActivities(launchIntent, 0)
        }.getOrElse { emptyList() }
        return activities.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                // Свой оверлей поверх самого себя бессмысленен.
                if (pkg == context.packageName) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: pkg
                Entry(
                    packageName = pkg,
                    label = label,
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                )
            }
            // Одинаковые ярлыки бывают у дубликатов (рабочий/профиль) — в списке
            // это шум, поэтому оставляем по одному имени.
            .distinctBy { it.label }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Запустить выбранное приложение. */
    fun launch(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
