package eu.kanade.tachiyomi.ui.books

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.locallibrary.BooksLibraryScreen
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * Отдельная вкладка «Книги».
 *
 * Раньше книги были не разделом, а третьим режимом «Локальной библиотеки»
 * (переключатель Манга / Книги / Настройки), поверх которого ещё и
 * пушился отдельный экран [BooksLibraryScreen] — то есть два уровня
 * вложенности ради простого списка. Вкладка закрывает оба уровня сразу.
 *
 * Содержимое не дублируется: корнем остаётся тот же [BooksLibraryScreen],
 * поэтому читалка открывается и возвращается ровно как раньше.
 */
data object BooksTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 7u,
            title = "Книги",
            icon = rememberVectorPainter(Icons.Outlined.MenuBook),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        androidx.compose.runtime.key("books-tab") {
            Navigator(screen = BooksLibraryScreen) { inner ->
                DefaultNavigatorScreenTransition(navigator = inner)
            }
        }
    }
}