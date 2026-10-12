package eu.kanade.tachiyomi.data.ocr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Пакет языковых данных Tesseract.
 *
 * Нативная библиотека уже в APK, а буквы Tesseract живут отдельными файлами
 * `.traineddata`. Без них движок не поднимается, поэтому пакет должен быть в
 * загрузчике, а пути — совпадать с тем, что ищет движок.
 */
class TesseractPackTest {

    @Test
    fun `в загрузчике есть пакет tesseract с русской и английской моделями`() {
        val files = OcrModelDownloader.PACKS["tesseract"]
        assertTrue(files != null, "пакет tesseract должен быть в загрузчике")
        val names = files.orEmpty().map { it.second }
        assertTrue(names.contains("rus.traineddata"), "нет rus.traineddata: $names")
        assertTrue(names.contains("eng.traineddata"), "нет eng.traineddata: $names")
    }

    @Test
    fun `пути пакета совпадают с теми, что ищет движок`() {
        // Движок ищет языковые данные по пути TESSERACT_RUS_PATH, поэтому его
        // файл обязан быть в списке путей пакета — иначе пакет считается
        // «скачанным, а на деле пустым».
        val paths = OcrModelDownloader.PACK_ASSET_PATHS["tesseract"].orEmpty()
        assertTrue(
            paths.contains(OcrModelDownloader.TESSERACT_RUS_PATH),
            "путь движка ${
                OcrModelDownloader.TESSERACT_RUS_PATH
            } отсутствует в путях пакета $paths",
        )
        assertFalse(paths.isEmpty(), "у пакета должны быть пути установки")
    }

    @Test
    fun `языки tesseract по умолчанию входят в скачанный пакет`() {
        // Настройка по умолчанию — «eng+rus»: если бы пакет не содержал
        // английский, движок отказался бы подниматься «из коробки».
        val names = OcrModelDownloader.PACKS["tesseract"].orEmpty().map { it.second }.toSet()
        for (lang in listOf("eng", "rus")) {
            assertTrue(names.contains("$lang.traineddata"), "нет модели языка $lang")
        }
    }

    @Test
    fun `все адреса пинованы на неизменяемый коммит`() {
        // Без пина файл может молча подмениться новой версией, и распознавание
        // «поплывёт» у пользователя без всяких причин.
        val urls = OcrModelDownloader.PACKS["tesseract"].orEmpty().map { it.first }
        assertTrue(urls.isNotEmpty())
        assertFalse(
            urls.any { it.contains("/main/") },
            "адрес без пина на неизменяемый коммит: $urls",
        )
    }
}
