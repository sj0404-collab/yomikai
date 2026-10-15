package mihon.data.ocr

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream

/**
 * Частотный словарь русских словоформ для разбиения слипшегося OCR-текста
 * на слова (только вставка пробелов, без замены символов — словарная
 * «коррекция» запрещена правилами проекта).
 *
 * Источники (генератор — `scripts/gen_ruwordlist.py`, извлечение в ассет —
 * `data/src/main/assets/ocr/ru_wordlist.txt`):
 *  * OpenSubtitles через FrequencyWords — словоформы с частотой >= 20;
 *  * ё/е-варианты каждой формы: OCR читает ё непоследовательно;
 *  * литературная лексика из прежней версии словаря (её в субтитрах нет);
 *  * полный морфологический лексикон OpenCorpora (3 014 492 формы);
 *  * сиды device-регрессий и однобуквенные служебные слова.
 *
 * == Почему не строки ==
 *
 * До v2.0.8 словарь жил в 602 сгенерированных `.kt`-частях (~90 МБ исходников)
 * и при первом `knownWord()` материализовал ДВА `HashSet` по 3 014 492 строки
 * (оригиналы + `uppercase()`-копии): ~0.5–0.7 ГБ кучи на телефоне и секунды
 * работы GC на устройстве. На реальных аппаратах первый прогон чистилки
 * уходил в OOM/ANR, и с v2.0.5 «слова перестали находиться» вовсе.
 *
 * Теперь словарь — это отсортированный `LongArray` FNV-1a-64 хешей
 * нормализованных (lowercase) форм: ~24 МБ памяти, загрузка стримингом из
 * ассета ~1 с один раз за процесс, поиск — бинарный. Строка после чтения
 * строки тут же выбрасывается, ничего лишнего в куче не задерживается.
 * Коллизия 64-битного хеша на 3М формах не наблюдается (проверялось при
 * генерации), а её цена — случайное разрешение одной границы при
 * DP-разбиении, т.е. не критична для фильтра.
 */
object RuWordList {

    private const val ASSET_PATH = "ocr/ru_wordlist.txt"

    @Volatile
    private var hashes: LongArray? = null

    @Volatile
    private var loader: (() -> InputStream)? = null

    private val loadLock = Any()

    /**
     * Привязка к приложению: словарь читается из ассетов APK. Вызывается из
     * `App.onCreate` до первого OCR-прогона. Повторный вызов безопасен.
     */
    fun configure(context: Context) {
        val app = context.applicationContext
        loader = { app.assets.open(ASSET_PATH) }
    }

    /** Точка входа для JVM-тестов: словарь читается из файла напрямую. */
    internal fun configureForTest(filePath: String) {
        synchronized(loadLock) {
            hashes = null
        }
        loader = { java.io.FileInputStream(filePath) }
    }

    /** Сброс кэша (только для тестов). */
    internal fun resetForTest() {
        synchronized(loadLock) {
            hashes = null
        }
    }

    /**
     * Словоформа есть в словаре. Регистр запроса неважен: словарь хранит
     * нормализованные (lowercase) формы, запрос приводится так же. Пустые и
     * служебные строки — всегда «нет».
     *
     * Если ассет по какой-то причине не прочитался, деградируем мягко:
     * «слова нет» (DP-разбиение просто не сработает, текст не портится —
     * ровно то же поведение, что и у неизвестного слова).
     */
    fun contains(word: String): Boolean {
        val w = word.trim { !it.isLetterOrDigit() }.lowercase()
        if (w.isEmpty()) return false
        val table = hashes ?: load() ?: return false
        val h = fnv1a64(w)
        return table.binarySearch(h) >= 0
    }

    /** Загружен ли словарь (диагностика/теплый старт). */
    fun isLoaded(): Boolean = hashes != null

    /**
     * Принудительная фоновая загрузка (можно звать из `App.onCreate` в
     * worker-корутине, чтобы первый скан не ждал поток).
     */
    fun preload() {
        if (hashes == null) load()
    }

    private fun load(): LongArray? {
        hashes?.let { return it }
        synchronized(loadLock) {
            hashes?.let { return it }
            val stream = runCatching {
                (loader ?: defaultJvmLoader())?.invoke()
            }.getOrNull() ?: return null
            return runCatching {
                stream.use { input ->
                    readHashTable(input)
                }
            }.getOrNull().also { table ->
                hashes = table
            }
        }
    }

    /**
     * Читает словарь построчно И НЕ удерживает сами строки: каждая строка
     * превращается в хеш и выбрасывается из кучи сразу после обработки.
     */
    private fun readHashTable(input: InputStream): LongArray {
        var table = LongArray(DEFAULT_WORD_CAPACITY)
        var size = 0
        val reader = BufferedReader(input.reader(Charsets.UTF_8), 1 shl 20)
        for (lineRaw in reader.lineSequence()) {
            val line = lineRaw.trim()
            if (line.isEmpty()) continue
            if (size == table.size) {
                table = table.copyOf(table.size + (table.size shr 1))
            }
            table[size++] = fnv1a64(line)
        }
        if (size == 0) return LongArray(0)
        if (size != table.size) {
            table = table.copyOf(size)
        }
        table.sort()
        // Дедупликация смежных одинаковых хешей (словарь подаётся уже
        // уникальным, но не грех защититься от дублей на будущих перегенерациях).
        var unique = 0
        for (i in 1 until size) {
            if (table[i] != table[i - 1]) {
                unique++
                table[unique] = table[i]
            }
        }
        return table.copyOf(unique + 1)
    }

    /** На JVM-юнит-тестах Android Context недоступен: пробуем файловые пути. */
    private fun defaultJvmLoader(): (() -> InputStream)? {
        val candidates = listOf(
            "src/main/assets/$ASSET_PATH",
            "data/src/main/assets/$ASSET_PATH",
            "../data/src/main/assets/$ASSET_PATH",
        )
        val file = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.isFile } ?: return null
        return { file.inputStream() }
    }

    /** FNV-1a 64 поверх UTF-16 кодов — быстрый и достаточно равномерный. */
    private fun fnv1a64(s: String): Long {
        var h = FNV_OFFSET_BASIS
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= FNV_PRIME
        }
        return h
    }

    private fun LongArray.binarySearch(key: Long): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = this[mid]
            when {
                midVal < key -> low = mid + 1
                midVal > key -> high = mid - 1
                else -> return mid
            }
        }
        return -(low + 1)
    }

    private const val DEFAULT_WORD_CAPACITY = 3_200_000
    private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
}
