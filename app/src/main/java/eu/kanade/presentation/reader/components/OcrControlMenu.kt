package eu.kanade.presentation.reader.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Одна круглая кнопка строки меню.
 *
 * Наполнение — либо [icon], либо текстовый [glyph] («♀»/«♂» для выбора пола
 * голоса: символ не подобрать в наборе иконок). Цвета задаёт вызывающий,
 * потому что у разных пунктов смысл подсветки разный (идёт чтение / включён
 * тумблер / просто действие).
 */
data class OcrMenuAction(
    val icon: ImageVector? = null,
    /** Текстовый глиф вместо иконки: «♀»/«♂». */
    val glyph: String? = null,
    val onClick: () -> Unit,
    /** Подсветить кнопку как активную, если [containerColor] не задан явно. */
    val active: Boolean = false,
    val enabled: Boolean = true,
    val containerColor: Color? = null,
    /** Цвет иконки ИЛИ глифа; по умолчанию — контрастный к контейнеру. */
    val contentColor: Color? = null,
    val contentDescription: String? = null,
)

/**
 * Строка меню: подпись слева, одна-три круглые кнопки справа.
 */
data class OcrMenuRow(
    val label: String,
    val action: OcrMenuAction,
    val secondary: OcrMenuAction? = null,
    val tertiary: OcrMenuAction? = null,
    /**
     * Строка-вставка под этой строкой (слайдер скорости и т.п.): модель
     * описывает только круглые кнопки, а такие блоки привязаны к конкретному
     * пункту и должны идти сразу за ним, а не в общий хвост карточки.
     */
    val below: (@Composable ColumnScope.() -> Unit)? = null,
)

/**
 * Карточка плавающего меню. Общая для читалки и (в следующей волне) для
 * оверлея поверх чужих приложений, поэтому всё, что влияет на внешний вид
 * и поведение, задаётся параметрами, а не зашито внутрь.
 *
 * Ширина фиксированная (220..320.dp): раньше карточка была wrap-content, и
 * подписи «рвались» — короткие прижимались к кнопке, длинные уезжали влево.
 * Теперь у всех строк один левый край, а разрыв между подписью и кнопкой
 * даёт отступ.
 */
@Composable
fun OcrControlMenuCard(
    rows: List<OcrMenuRow>,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 480.dp,
    /** Строка статуса над пунктами (в читалке не нужна, в оверлее — да). */
    status: String? = null,
    statusAction: OcrMenuAction? = null,
    /** Подпись версии внизу карточки. */
    footer: String? = null,
    /** Дополнительные строки, которые не влезают в модель (слайдер, конструктор). */
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier.widthIn(min = 220.dp, max = 320.dp),
        // Фон строго непрозрачный. Эта же карточка потом поедет оверлеем поверх
        // чужих приложений: при alpha ~0.96 сквозь панель просвечивал чужой
        // текст и подписи рядом с ним становились нечитаемыми.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                // Пунктов бывает много (конструктор добавляет свои), но панель
                // не должна вылезать за экран — вызывающий подставляет своё
                // значение (в читалке 480.dp, в оверлее — по высоте экрана).
                .heightIn(max = maxHeight)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (statusAction != null) {
                        Spacer(Modifier.width(8.dp))
                        OcrMenuActionButton(statusAction)
                    }
                }
            }
            rows.forEach { row ->
                OcrMenuRowContent(row)
                // Вставка под конкретным пунктом (например, слайдером
                // скорости под строкой автопрокрутки).
                val below = row.below
                if (below != null) {
                    below(this)
                }
            }
            extraContent()
            if (footer != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
    }
}

/**
 * Строка меню целиком. Подпись занимает [Modifier.weight], кнопки прижаты
 * к правому краю — отступы между ними одинаковые на каждой строке.
 */
@Composable
private fun OcrMenuRowContent(row: OcrMenuRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        // Подпись строки — запасное описание для кнопки: у глифов («♀»/«♂»)
        // TalkBack нечего прочитать, а у иконок описание может быть пустым.
        OcrMenuActionButton(row.action.withFallbackDescription(row.label))
        row.secondary?.let {
            Spacer(Modifier.width(8.dp))
            OcrMenuActionButton(it.withFallbackDescription(row.label))
        }
        row.tertiary?.let {
            Spacer(Modifier.width(8.dp))
            OcrMenuActionButton(it.withFallbackDescription(row.label))
        }
    }
}

private fun OcrMenuAction.withFallbackDescription(fallback: String) =
    if (contentDescription.isNullOrBlank()) copy(contentDescription = fallback) else this

/**
 * Круглая кнопка строки меню. Вынесена наружу, чтобы [OcrControlMenuCard]
 * и дополнительные блоки (слайдер, кнопки конструктора) рисовали кнопки
 * одинаково.
 */
@Composable
fun OcrMenuActionButton(
    action: OcrMenuAction,
    modifier: Modifier = Modifier,
) {
    // Контейнер по умолчанию — тот же surfaceContainerHigh, что у фона карточки:
    // кнопка должна читаться как элемент панели, а не как пятно на её фоне.
    val container = when {
        action.containerColor != null -> action.containerColor
        action.active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        action.contentColor != null -> action.contentColor
        action.active && action.containerColor == null -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val description = action.contentDescription
    SmallFloatingActionButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier.then(
            // Описание вешаем на саму кнопку, а не на иконку: у глифа-текста
            // иконки нет вообще, и TalkBack молчал бы.
            if (description.isNullOrBlank()) {
                Modifier
            } else {
                Modifier.semantics { contentDescription = description }
            },
        ),
        containerColor = container,
        contentColor = content,
    ) {
        val glyph = action.glyph
        val icon = action.icon
        when {
            glyph != null -> Text(glyph)
            icon != null -> Icon(icon, contentDescription = null)
        }
    }
}

/**
 * Кнопка-тумблер меню: тап открывает/закрывает панель, перетаскивание двигает
 * её по экрану.
 *
 * Жест разведён отдельной обёрткой [Box] с [detectDragGestures] вокруг FAB —
 * иначе тап и перетаскивание конфликтуют и кнопка «уезжает» вместо
 * открытия меню. Смещения наружу не пишем намеренно: вызывающий держит
 * позицию (и сохраняет её сам), компонент про смещения ничего не знает.
 */
@Composable
fun OcrControlFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    /** Описание для TalkBack; если не задано — по состоянию кнопки. */
    contentDescription: String? = null,
) {
    // pointerInput не перезапускаем на каждый рекомпозиш (иначе жест рвётся
    // посреди перетаскивания), поэтому берём свежие колбэки через state.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { currentOnDragEnd() },
            ) { change, dragAmount ->
                change.consume()
                currentOnDrag(dragAmount)
            }
        },
    ) {
        FloatingActionButton(
            onClick = onToggle,
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                if (expanded) Icons.Outlined.Close else Icons.Outlined.Menu,
                contentDescription = contentDescription
                    ?: if (expanded) "Закрыть меню" else "Меню",
            )
        }
    }
}