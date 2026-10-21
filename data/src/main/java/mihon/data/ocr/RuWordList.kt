package mihon.data.ocr

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Частотный словарь русских словоформ для разбиения слипшегося OCR-текста
 * на слова (только вставка пробелов, без замены символов — словарная
 * «коррекция» запрещена правилами проекта).
 *
 * Источники (пересборка — `scripts/gen_ruwordlist.py`): лексикон OpenCorpora,
 * ё/е-варианты каждой формы, литературная лексика прежних версий, сиды
 * device-регрессий и однобуквенные служебные слова. Всего 3 014 492 формы.
 *
 * ПОЧЕМУ НЕ В КОДЕ. Раньше формы лежали 603 сгенерированными файлами
 * `RuWordListPartNNN.kt` (90 МБ исходников). Это стоило ~30 МБ в APK (все
 * строки попадали в DEX) и, что хуже, при первом распознавании строилось
 * `HashSet` из 3 млн строк — на телефоне это сотни мегабайт и `OutOfMemoryError`
 * прямо в читалке («то падает, то пусто»). Теперь формы лежат одним бинарным
 * ресурсом `ru_words.bin` в корне APK: 32-битные хеши, отсортированные по
 * возрастанию. Память — 12 МБ примитивов вместо гигабайта объектов, поиск —
 * бинарный, словарь не создаёт ни одной строки.
 *
 * Формат `ru_words.bin`: `"RUW1"`, int32 LE — количество, далее int32 LE —
 * хеши по возрастанию. Хеш — FNV-1a по UTF-16 code units формы в нижнем
 * регистре; 3 млн значений в 2^32 дают ~0.07 % ложных срабатываний, для
 * «разрешить поставить пробел» это безвредно.
 */
object RuWordList {

    private const val RESOURCE = "ru_words.bin"
    private const val MAGIC = 0x31575552 // "RUW1" в little-endian

    /** FNV-1a offset basis 0x811C9DC5. */
    private const val FNV_OFFSET = -0x7EE3623B

    /** FNV-1a prime 0x01000193. */
    private const val FNV_PRIME = 0x01000193

    private val hashes: IntArray by lazy { load() }

    /**
     * Разбор словаря заранее (в фоновом потоке старта приложения): первая
     * страница не должна ждать чтения 12 МБ.
     */
    fun warmUp() {
        hashes.size
    }

    /** Правда, если форма есть в словаре. Регистр не важен. */
    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        val table = hashes
        if (table.isEmpty()) return false
        return table.binarySearch(hash(word)) >= 0
    }

    private fun hash(word: String): Int {
        val normalized = word.lowercase(Locale.ROOT)
        var h = FNV_OFFSET
        for (index in 0 until normalized.length) {
            h = h xor normalized[index].code
            h *= FNV_PRIME
        }
        return h
    }

    private fun load(): IntArray {
        val stream = openResource() ?: run {
            logcat(LogPriority.WARN) { "RuWordList: ресурс $RESOURCE не найден, разбиение слипшихся слов отключено" }
            return IntArray(0)
        }
        return runCatching {
            DataInputStream(BufferedInputStream(stream, 1 shl 16)).use { input ->
                val magic = input.readInt()
                require(magic == MAGIC) { "Ожидался заголовок RUW1, получено $magic" }
                val count = input.readInt()
                require(count in 0..16_000_000) { "Некорректный размер словаря: $count" }
                val table = IntArray(count)
                for (index in 0 until count) table[index] = input.readInt()
                table
            }
        }.getOrElse { error ->
            // Без словаря OCR продолжает работать: слипшиеся прогоны просто не
            // делятся — это деградация, а не падение читалки.
            logcat(LogPriority.WARN, error) { "RuWordList: словарь не загружен" }
            IntArray(0)
        }
    }

    /**
     * Ресурсы Java-библиотеки пакуются в корень APK, поэтому читаются
     * загрузчиком классов без `Context`. Оба варианта пробуются потому, что
     * реализация загрузчика у разных сборщиков ищет ресурс по разному.
     */
    private fun openResource(): InputStream? {
        val loader = RuWordList::class.java.classLoader
        return runCatching { loader?.getResourceAsStream(RESOURCE) }.getOrNull()
            ?: runCatching { RuWordList::class.java.getResourceAsStream("/$RESOURCE") }.getOrNull()
    }
}
