package mihon.data.ocr

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Пользовательский словарь OCR.
 *
 * Слова, добавленные на карточке результата («＋ Словарь»), вручную в
 * настройках или накопленные при считывании глав. Используется вторым
 * источником слов (после встроенного [RuWordList]):
 *  * [OcrTextCleaner.splitGluedRun] разбивает слипшиеся прогоны капса и по
 *    словам пользователя — без этого «имя персонажа» не делилось на слова;
 *  * [CyrillicOcrEngine.candidateQuality] даёт бонус кандидату v3/v5, слова
 *    которого реально есть в словаре, — выбор «читаемого» результата.
 *
 * Это не словарная коррекция: словарь только разбивает/подсвечивает уже
 * распознанное, ничего не подменяя.
 *
 * Хранилище — текстовый файл (UTF-8, одно слово на строку, регистр не
 * важен). Путь задаётся [configure] на старте приложения; без файла словарь
 * живёт в памяти (режим тестов).
 */
object OcrVocabulary {

    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var file: File? = null

    private val lower = HashSet<String>()
    private val upper = HashSet<String>()

    /** Максимальная длина слова — дольше не бывает осмысленного токена. */
    private const val MAX_WORD_LEN = 48

    /**
     * Привязывает словарь к файлу и загружает его. null/пустая строка =
     * только оперативная память (тесты). Замена файла очищает словарь.
     */
    fun configure(path: String?) {
        lock.write {
            file = path?.takeIf { it.isNotBlank() }?.let { File(it) }
            lower.clear()
            upper.clear()
            val active = file
            if (active != null && active.exists()) {
                runCatching {
                    active.useLines { lines ->
                        for (line in lines) {
                            val word = line.trim()
                            if (word.isNotEmpty() && word.length <= MAX_WORD_LEN) {
                                lower.add(word.lowercase())
                                upper.add(word.uppercase())
                            }
                        }
                    }
                }
            }
        }
    }

    /** Добавляет все буквенно-цифровые токены текста. Возвращает число новых слов. */
    fun addFromText(text: String): Int {
        if (text.isBlank()) return 0
        var added = 0
        lock.write {
            text.split(Regex("[^\\p{L}\\p{N}-]+"))
                .map(String::trim)
                .filter { it.length in 2..MAX_WORD_LEN }
                .forEach { raw ->
                    // Разрешаем и кириллицу, и латынь, и смешанные токены
                    // («Wi-Fi»), чтобы пользователь мог учить двигатель своим
                    // словам. Пунктуация уже отрезана разбиением.
                    if (upper.add(raw.uppercase())) {
                        lower.add(raw.lowercase())
                        added++
                    }
                }
            if (added > 0) persistLocked()
        }
        return added
    }

    /** Добавляет одно слово. Возвращает true, если слово реально добавлено. */
    fun addWord(word: String): Boolean {
        val clean = word.trim()
        if (clean.isEmpty() || clean.length > MAX_WORD_LEN) return false
        lock.write {
            if (upper.add(clean.uppercase())) {
                lower.add(clean.lowercase())
                persistLocked()
                return true
            }
        }
        return false
    }

    /** Удаляет слово. Возвращает true, если слово было в словаре. */
    fun removeWord(word: String): Boolean {
        val clean = word.trim()
        if (clean.isEmpty()) return false
        lock.write {
            if (upper.remove(clean.uppercase())) {
                lower.remove(clean.lowercase())
                persistLocked()
                return true
            }
        }
        return false
    }

    fun clear() {
        lock.write {
            lower.clear()
            upper.clear()
            persistLocked()
        }
    }

    fun size(): Int = lock.read { upper.size }

    fun words(): List<String> = lock.read { lower.toList().sorted() }

    /** Правда, если слово есть в пользовательском словаре (без учёта регистра). */
    fun isUserWord(word: String): Boolean {
        if (word.isEmpty()) return false
        return lock.read { upper.contains(word.uppercase()) }
    }

    /** Правда, если слово есть в пользовательском словаре ИЛИ в [RuWordList]. */
    fun isKnownWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val up = word.uppercase()
        if (up in RuWordList.upper) return true
        return lock.read { upper.contains(up) }
    }

    private fun persistLocked() {
        val active = file ?: return
        runCatching {
            val parent = active.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            // Атомарная замена: пишем во временный файл, затем переименовываем.
            // Это защищает от «оборванной» записи при случайном падении процесса.
            val tmp = File(active.parentFile, active.name + ".tmp")
            tmp.writeText(lower.joinToString("\n", postfix = "\n"))
            val renamed = tmp.renameTo(active)
            if (!renamed) {
                // На некоторых ФС renameTo поверх существующего не срабатывает:
                // пишем напрямую и убираем временный файл.
                active.writeText(lower.joinToString("\n", postfix = "\n"))
                tmp.delete()
            }
        }
    }
}