package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import eu.kanade.domain.manga.model.readingMode
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Единственная читалка: глава из библиотеки открывается экраном KOMA.
 *
 * Раньше их было две — лента Mihon со своим ворохом настроек и KOMA. Теперь
 * переход из списка глав ведёт сюда: страницы главы (локальная папка, архив,
 * скачанная глава или страница из источника) выгружаются в кэш как обычные
 * файлы, и дальше работает ровно тот же экран, что и для книг KOMA, — с
 * распознаванием, рамками, озвучкой и авточтением.
 *
 * Если страницы получить не удалось (источник недоступен, глава не скачана),
 * экран честно об этом скажет и предложит прежнюю читалку — вместо пустого
 * экрана или вылета.
 */
class KomaReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANGA = "manga"
        private const val EXTRA_CHAPTER = "chapter"

        fun newIntent(context: Context, mangaId: Long, chapterId: Long): Intent =
            Intent(context, KomaReaderActivity::class.java)
                .putExtra(EXTRA_MANGA, mangaId)
                .putExtra(EXTRA_CHAPTER, chapterId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra(EXTRA_MANGA, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER, -1L)

        setContent {
            var state by remember { mutableStateOf<State>(State.Loading) }
            val context = LocalContext.current
            LaunchedEffect(mangaId, chapterId) {
                state = runCatching { KomaChapterLoader(context).load(mangaId, chapterId) }
                    .fold(
                        onSuccess = { book -> if (book != null) State.Ready(book) else State.Empty },
                        onFailure = { State.Failed(it.message ?: it::class.simpleName ?: "неизвестная ошибка") },
                    )
            }
            when (val s = state) {
                is State.Loading -> Loading()
                is State.Ready -> {
                    val repo = remember { LibraryRepository(context) }
                    val engine = remember { KomaOcrEngine(context) }
                    KomaReaderScreen(
                        initial = s.book,
                        repo = repo,
                        engine = engine,
                        onClose = { finish() },
                        onUpdate = { updated -> KomaChapterProgress.save(context, updated) },
                        onDelete = { finish() },
                        // «Сканировать и читать» с экрана главы: та же кнопка
                        // раньше открывала основную читалку с автостартом, а
                        // теперь главу показывает KOMA — автостарт передаём ей.
                        autoStart = intent.getBooleanExtra("autoread_start", false),
                        // Резервная (прежняя) читалка — из меню KOMA, если
                        // понадобится какой-то её режим.
                        onOpenLegacy = {
                            startActivity(
                                ReaderActivity.newIntent(context, mangaId, chapterId)
                                    .putExtra("skipKoma", true),
                            )
                            finish()
                        },
                    )
                }
                is State.Empty -> Message(
                    title = "Не удалось открыть главу",
                    text = "Страницы главы не найдены: источник недоступен, глава не скачана или архив пуст.",
                    onOpenOld = {
                        startActivity(ReaderActivity.newIntent(context, mangaId, chapterId).putExtra("skipKoma", true))
                        finish()
                    },
                    onClose = { finish() },
                )
                is State.Failed -> Message(
                    title = "Ошибка чтения",
                    text = s.text,
                    onOpenOld = {
                        startActivity(ReaderActivity.newIntent(context, mangaId, chapterId).putExtra("skipKoma", true))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private sealed interface State {
        data object Loading : State
        data class Ready(val book: Book) : State
        data object Empty : State
        data class Failed(val text: String) : State
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF171A17)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Orange)
        Text("Открываем главу…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun Message(title: String, text: String, onOpenOld: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF171A17)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 18.sp)
        Text(text, color = Color(0xFFB2B8AD), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Button(onClick = onOpenOld, modifier = Modifier.padding(top = 20.dp)) { Text("Открыть в прежней читалке") }
        OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("Назад") }
    }
}

/** Прогресс чтения глав сохраняется отдельно от библиотеки KOMA. */
object KomaChapterProgress {
    private const val PREFS = "koma_chapter_progress"

    fun load(context: Context, id: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(id, 0)

    fun save(context: Context, book: Book) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(book.id, book.progress)
            .apply()
    }
}

/**
 * Превращает главу в книгу KOMA: каждая страница — файл в кэше.
 *
 * Источник страниц — тот же [OcrPageSourceResolver], которым прежняя читалка
 * кормила распознавание: он умеет локальные папки, архивы, скачанные главы и
 * страницы из источника. Файлы кладём один раз на главу: повторное открытие
 * не перекачивает ничего.
 */
internal class KomaChapterLoader(private val context: Context) {

    private val getManga: GetManga by lazy { Injekt.get() }
    private val getChapter: GetChapter by lazy { Injekt.get() }
    private val resolver: OcrPageSourceResolver by lazy { Injekt.get() }

    suspend fun load(mangaId: Long, chapterId: Long): Book? {
        if (mangaId <= 0 || chapterId <= 0) return null
        val manga = getManga.await(mangaId) ?: return null
        val chapter = getChapter.await(chapterId) ?: return null

        val id = "chapter-$chapterId"
        val dir = KomaChapterCache.dir(context, id)
        // ── Быстрый путь ─────────────────────────────────────────────────────
        // Глава уже разложена на диск в прошлый раз: источник не трогаем вообще.
        // Раньше здесь каждый раз заново создавались копии в cacheDir, поэтому
        // ЛОКАЛЬНАЯ глава открывалась как сетевая.
        val knownCount = runCatching { java.io.File(dir, ".count").readText().trim().toInt() }.getOrNull()
        if (knownCount != null && knownCount > 0) {
            val fastPaths = KomaChapterCache.paths(context, id, knownCount)
            if (fastPaths.all { KomaChapterCache.ready(it) }) {
                return buildBook(manga, chapter, id, fastPaths)
            }
        }

        val resolved = withIOContext { resolver.resolve(manga, chapter) }
        val count = resolved.pages.size
        if (count == 0) {
            runCatching { resolved.close() }
            return null
        }
        val paths = KomaChapterCache.paths(context, id, count)
        val progress = KomaChapterProgress.load(context, id).coerceIn(0, count - 1)
        // Копирование идёт в фоне: страница, которая нужна сейчас, — первой.
        // Читалка открывается сразу, а страница, которую ещё копируют, ждёт
        // своего файла (KomaOcrEngine → KomaChapterCache.awaitFile).
        val job = KomaChapterCache.prefetch(
            context = context,
            bookId = id,
            pages = resolved.pages,
            paths = paths,
            priority = listOf(progress, progress + 1, 0),
        )
        if (job == null) {
            runCatching { resolved.close() }
        } else {
            // Архив/PDF-дескриптор закрываем только после последней страницы,
            // иначе источник закроется под руками у фонового копирования.
            job.invokeOnCompletion {
                runCatching {
                    if (paths.all { KomaChapterCache.ready(it) }) {
                        java.io.File(dir, ".count").writeText(count.toString())
                    }
                }
                runCatching { resolved.close() }
            }
        }
        return buildBook(manga, chapter, id, paths, progress)
    }

    private fun buildBook(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        id: String,
        paths: List<String>,
        progress: Int = KomaChapterProgress.load(context, id).coerceIn(0, paths.lastIndex.coerceAtLeast(0)),
    ): Book {
        val title = buildString {
            append(manga.title)
            chapter.name.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        return Book(
            id = id,
            title = title,
            author = manga.author.orEmpty(),
            category = if (manga.genre?.any { it.contains("манхва", ignoreCase = true) } == true) "Манхва" else "Манга",
            pages = paths,
            progress = progress,
            // Режим берём у самой книги: вебтун и «вертикально» читаются
            // лентой, RTL — справа налево, LTR — слева направо. Раньше здесь
            // жёстко стоял "rtl", и любая манхва открывалась страницами.
            mode = when (manga.readingMode.toInt()) {
                3, 4, 5 -> "vertical"
                1 -> "ltr"
                else -> "rtl"
            },
        )
    }
}
