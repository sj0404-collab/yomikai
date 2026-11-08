package eu.kanade.tachiyomi.data.tts

import android.content.Context
import eu.kanade.tachiyomi.data.ai.DocExporter
import mihon.domain.ocr.model.OcrPageResult
import java.io.File

/**
 * Собирает распознанный текст главы в единый документ со спикерами.
 *
 * После фонового скана ([eu.kanade.tachiyomi.data.ocr.OcrChapterScanner]) каждая
 * страница кэшируется как [OcrPageResult]. Этот хелпер читает эти кэши,
 * приписывает говорящему пол/голос (через локальную морфологию [LocalSpeakerAi])
 * и отдаёт текст в виде страниц-блоков. Готовый документ можно сохранить как
 * TXT / Markdown, а также выгрузить в PDF или DOCX через существующий
 * [DocExporter] (тот самый, что уже делает PDF/DOCX для AI-чата).
 *
 * Формат «кто говорит»: если пол не определён, реплика идёт как есть; если
 * определён — перед текстом ставится `[Ж]` / `[М]` (голос женский/мужской).
 */
object ChapterOcrTranscript {

    data class PageBlock(
        val pageIndex: Int,
        val title: String,
        val lines: List<SpokenLine>,
    )

    data class SpokenLine(
        val text: String,
        val gender: String?, // "female" | "male" | null
        val narrator: Boolean,
    )

    /**
     * Построить блоки страниц из кэшированных результатов.
     *
     * @param pages результаты OCR по порядку страниц (пустые страницы допустимы —
     *              они будут отмечены как «нет текста», а не выброшены).
     */
    fun build(pages: List<OcrPageResult>, chapterName: String): List<PageBlock> {
        return pages.mapIndexed { index, page ->
            val lines = page.regions
                .map { it.text.trim() }
                .filter { it.isNotBlank() }
                .map { text ->
                    val g = LocalSpeakerAi.guessGenders(listOf(text)).firstOrNull()
                    SpokenLine(
                        text = text,
                        gender = g?.takeIf { it == "female" || it == "male" },
                        narrator = false,
                    )
                }
            PageBlock(
                pageIndex = page.pageIndex,
                title = "$chapterName — страница ${index + 1}",
                lines = lines,
            )
        }
    }

    /** Превращает блоки в читаемый Markdown (заголовок + реплики с [[Ж]]/[[М]]). */
    fun toMarkdown(blocks: List<PageBlock>, title: String): String {
        val sb = StringBuilder()
        sb.append("# $title\n\n")
        blocks.forEach { b ->
            sb.append("## ${b.title}\n\n")
            if (b.lines.isEmpty()) {
                sb.append("_(текст не распознан)_\n\n")
            } else {
                b.lines.forEach { line ->
                    val gender = when (line.gender) {
                        "female" -> "[Ж] "
                        "male" -> "[М] "
                        else -> ""
                    }
                    sb.append("- $gender${line.text}\n")
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** Превращает блоки в плоский TXT (просто реплики в порядке чтения). */
    fun toPlainText(blocks: List<PageBlock>, title: String): String {
        val sb = StringBuilder()
        sb.append(title).append("\n").append("=".repeat(title.length)).append("\n\n")
        blocks.forEachIndexed { index, b ->
            sb.append("[${index + 1}] ${b.title}\n")
            b.lines.forEach { line ->
                val gender = when (line.gender) {
                    "female" -> "[Ж] "
                    "male" -> "[М] "
                    else -> ""
                }
                sb.append("  $gender${line.text}\n")
            }
            if (b.lines.isEmpty()) sb.append("  (текст не распознан)\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Сохранить документ в папку экспорта воркспейса.
     *
     * @param format "md" | "txt" | "pdf" | "docx"
     * @return созданный файл.
     */
    fun write(context: Context, blocks: List<PageBlock>, title: String, format: String): File {
        val safeTitle = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(60)
        val fileName = "$safeTitle.${if (format == "markdown") "md" else format}"
        return when (format) {
            "pdf" -> {
                val items = blocks.map { b ->
                    DocExporter.Item(
                        title = b.title,
                        text = b.lines.joinToString("\n") {
                            val g = when (it.gender) {
                                "female" -> "[Ж] " else -> if (it.gender == "male") "[М] " else ""
                            }
                            g + it.text
                        }.ifBlank { "—" },
                        imagePath = null,
                    )
                }
                DocExporter.exportPdf(context, items, fileName)
            }
            "docx" -> {
                val items = blocks.map { b ->
                    DocExporter.Item(
                        title = b.title,
                        text = b.lines.joinToString("\n") {
                            val g = when (it.gender) {
                                "female" -> "[Ж] " else -> if (it.gender == "male") "[М] " else ""
                            }
                            g + it.text
                        }.ifBlank { "—" },
                        imagePath = null,
                    )
                }
                DocExporter.exportDocx(context, items, fileName)
            }
            else -> {
                // TXT и MD — простые текстовые файлы.
                val text = if (format == "md" || format == "markdown") toMarkdown(blocks, title) else toPlainText(blocks, title)
                File(eu.kanade.tachiyomi.data.ai.AiWorkspace.root(context), "export")
                    .apply { mkdirs() }
                    .let { dir ->
                        File(dir, fileName).apply { writeText(text, Charsets.UTF_8) }
                    }
            }
        }
    }
}
