package eu.kanade.tachiyomi.data.ai

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

object BookKnowledge {

    private const val MAX_FACTS = 40
    private const val MAX_NOTES = 60

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Note(
        val time: Long = System.currentTimeMillis(),
        val kind: String = "note",
        val text: String = "",
    )

    @Serializable
    data class Book(
        val mangaId: Long,
        var title: String = "",
        var contentType: String = "",
        var readingOrder: String = "",
        var site: String = "",
        var summary: String = "",
        var advice: MutableList<String> = mutableListOf(),
        var facts: MutableList<String> = mutableListOf(),
        var notes: MutableList<Note> = mutableListOf(),
        var updatedAt: Long = 0L,
    ) {
        val researched: Boolean get() = site.isNotBlank() || summary.isNotBlank() || facts.isNotEmpty()
    }

    fun booksDir(context: Context): File {
        val dir = File(AiWorkspace.root(context), "books")
        dir.mkdirs()
        return dir
    }

    fun file(context: Context, mangaId: Long): File = File(booksDir(context), "$mangaId.json")

    fun load(context: Context, mangaId: Long): Book {
        val f = file(context, mangaId)
        if (!f.exists()) return Book(mangaId = mangaId)
        return runCatching { json.decodeFromString<Book>(f.readText()) }
            .getOrElse { e ->
                logcat(LogPriority.WARN, e) { "BookKnowledge load failed for $mangaId" }
                Book(mangaId = mangaId)
            }
    }

    fun save(context: Context, book: Book) {
        book.updatedAt = System.currentTimeMillis()
        runCatching {
            book.advice = book.advice.takeLast(MAX_FACTS).toMutableList()
            book.facts = book.facts.takeLast(MAX_FACTS).toMutableList()
            book.notes = book.notes.takeLast(MAX_NOTES).toMutableList()
            file(context, book.mangaId).writeText(json.encodeToString(book))
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "BookKnowledge save failed for ${book.mangaId}" }
        }
    }

    fun remember(context: Context, mangaId: Long, kind: String, text: String): Boolean {
        if (text.isBlank()) return false
        val book = load(context, mangaId)
        val value = text.trim()
        var stored = false
        when (kind.lowercase()) {
            "site" -> {
                if (book.site != value) {
                    book.site = value
                    stored = true
                }
            }
            "summary" -> {
                if (book.summary != value) {
                    book.summary = value
                    stored = true
                }
            }
            "fact" -> {
                book.facts.add(value)
                stored = true
            }
            "advice", "совет" -> {
                book.advice.add(value)
                stored = true
            }
            else -> {
                book.notes.add(Note(kind = kind.lowercase(), text = value))
                stored = true
            }
        }
        if (stored) save(context, book)
        return stored
    }

    fun renderAdvice(context: Context, mangaId: Long?, limit: Int = 8): String? {
        if (mangaId == null) return null
        val advice = load(context, mangaId).advice.takeLast(limit)
        if (advice.isEmpty()) return null
        return "Советы читателя, соблюдай их: " + advice.joinToString("; ")
    }

    fun render(context: Context, mangaId: Long?): String {
        if (mangaId == null) return ""
        val book = load(context, mangaId)
        if (!book.researched && book.contentType.isBlank()) return ""
        return buildString {
            append("ЗНАНИЕ О КНИГЕ")
            if (book.title.isNotBlank()) append("\nНазвание: ").append(book.title)
            if (book.contentType.isNotBlank()) append("\nТип: ").append(book.contentType)
            if (book.readingOrder.isNotBlank()) append("\nПорядок чтения: ").append(book.readingOrder)
            if (book.site.isNotBlank()) append("\nГде читать: ").append(book.site)
            if (book.summary.isNotBlank()) append("\nО чём: ").append(book.summary)
            if (book.facts.isNotEmpty()) {
                append("\nФакты:")
                book.facts.takeLast(12).forEach { append("\n- ").append(it) }
            }
            if (book.advice.isNotEmpty()) {
                append("\nСоветы читателя (использовать при авточтении):")
                book.advice.takeLast(12).forEach { append("\n- ").append(it) }
            }
            if (book.notes.isNotEmpty()) {
                append("\nЗаметки:")
                book.notes.takeLast(8).forEach { append("\n- ").append(it.text) }
            }
        }
    }
}
