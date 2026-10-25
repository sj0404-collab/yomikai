package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.koma.KomaReaderActivity

/**
 * Какую читалку открывать для главы.
 *
 * Раньше решение было одно на всё приложение (`komaReaderFirst`, по умолчанию
 * включено): любая глава уходила в KOMA. Для манхвы это правильно — сплошная
 * лента и чтение по репликам. Для манги с кадрами это путаница: KOMA режет
 * страницу на полосы, читает не то, что видно, и «правильный» вариант не
 * читается вовсе.
 *
 * Теперь два явно подписанных пути, и выбор запоминается для серии:
 * - [MODE_PAGES] — «Манга · постранично»: основная читалка (страницы, зум,
 *   свои настройки, без рамок по репликам);
 * - [MODE_STRIP] — «Манхва · вертикальная лента»: читалка KOMA (рамки по
 *   репликам, озвучка, авточтение).
 */
object ReaderLauncher {

    const val MODE_PAGES = "pages"
    const val MODE_STRIP = "strip"

    /** Что написано на кнопке и под ней — подписи не должны путать. */
    data class Option(
        val mode: String,
        val title: String,
        val subtitle: String,
    )

    val PAGES = Option(
        mode = MODE_PAGES,
        title = "Манга · постранично",
        subtitle = "Страницы, зум и настройки прежней читалки. Рамки по репликам, значок " +
            "озвучки у каждой реплики и авточтение здесь тоже есть.",
    )

    val STRIP = Option(
        mode = MODE_STRIP,
        title = "Манхва · вертикальная лента",
        subtitle = "Длинный кадр режется на полосы: читается и то, что выше или ниже " +
            "экрана. Рамки по репликам, озвучка и авточтение.",
    )

    /** Запомненный выбор для серии: null — ещё не выбирали. */
    fun savedMode(context: Context, mangaId: Long): String? =
        prefs(context).getString(key(mangaId), null)

    fun remember(context: Context, mangaId: Long, mode: String) {
        prefs(context).edit().putString(key(mangaId), mode).apply()
    }

    fun forget(context: Context, mangaId: Long) {
        prefs(context).edit().remove(key(mangaId)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("reader_choice", Context.MODE_PRIVATE)

    private fun key(mangaId: Long) = "mode_$mangaId"

    /**
     * Открыть главу в выбранной читалке.
     *
     * [autoRead] — авточтение (после скана главы): его умеет только KOMA,
     * поэтому при нём выбор игнорируется.
     */
    fun open(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        mode: String,
        autoRead: Boolean = false,
    ) {
        if (mode == MODE_STRIP || autoRead) {
            val opened = runCatching {
                context.startActivity(
                    KomaReaderActivity.newIntent(context, mangaId, chapterId)
                        .putExtra("autoread_start", autoRead)
                        .addFlags(if (context !is android.app.Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0),
                )
            }.isSuccess
            if (opened) return
        }
        // Постранично — основная читалка. skipKoma обязателен: без него прежняя
        // логика снова увела бы главу в KOMA.
        // Короткая подпись при входе: в этой читалке нет шапки KOMA, и без
        // подписи две читалки снова не отличить друг от друга.
        runCatching {
            android.widget.Toast
                .makeText(context, "Основная читалка · постранично", android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        context.startActivity(
            ReaderActivity.newIntent(context, mangaId, chapterId)
                .putExtra("skipKoma", true)
                .addFlags(if (context !is android.app.Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0),
        )
    }
}

/**
 * Диалог выбора читалки: два варианта, оба подписаны, чем именно читают.
 *
 * Показывается при открытии главы, пока для серии не запомнили выбор.
 */
@Composable
fun ReaderChoiceDialog(
    mangaTitle: String?,
    onChoose: (mode: String, rememberForManga: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var rememberForManga by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Как читать главу?") },
        text = {
            Column {
                if (!mangaTitle.isNullOrBlank()) {
                    Text(
                        mangaTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                ReaderChoiceOption(
                    option = ReaderLauncher.PAGES,
                    onClick = { onChoose(ReaderLauncher.MODE_PAGES, rememberForManga) },
                )
                ReaderChoiceOption(
                    option = ReaderLauncher.STRIP,
                    onClick = { onChoose(ReaderLauncher.MODE_STRIP, rememberForManga) },
                )
                // Главное, что нельзя терять из виду: рамки реплик, значок 🔊 и
                // авточтение работают В ОБЕИХ читалках. Выбор — только про то,
                // как листается страница, а не про то, есть ли рамки.
                Text(
                    "Рамки реплик, 🔈 у каждой реплики и авточтение есть в обеих " +
                        "читалках. Разница только в том, как листается страница: " +
                        "по одной или лентой с разбором на полосы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable { rememberForManga = !rememberForManga },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = rememberForManga,
                        onCheckedChange = { rememberForManga = it },
                    )
                    Text(
                        "Запомнить для этой серии",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text("Отмена") }
        },
    )
}

@Composable
private fun ReaderChoiceOption(
    option: ReaderLauncher.Option,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                option.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                option.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
