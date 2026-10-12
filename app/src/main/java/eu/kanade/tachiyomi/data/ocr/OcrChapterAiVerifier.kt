package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import eu.kanade.tachiyomi.data.tts.PageRoleVerifier
import eu.kanade.tachiyomi.data.tts.VoiceRole
import eu.kanade.tachiyomi.data.tts.VoiceRoleDictionary
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

/**
 * Проверка страниц главы моделью с картинкой — вне читалки, где время не
 * ограничено.
 *
 * Внутри читалки важнее скорость, поэтому там атрибуция остаётся локальной.
 * Здесь, при подготовке главы, для каждой страницы выполняется один запрос к
 * модели: кто говорит, что пропущено, где OCR ошибся. Результат — словарь
 * ролей **этой книги**: голос персонажа работает в ней и не утекает в другие.
 *
 * Прогресс честный: [onStage] сообщает, что именно происходит сейчас
 * (OCR или ИИ), потому что запрос к модели на страницу занимает секунды и
 * без индикации выглядит как зависание.
 */
class OcrChapterAiVerifier(
    private val prefs: OcrPreferences,
) {

    /** Что сейчас делает скан — для индикации в диалоге. */
    data class Stage(
        val pageIndex: Int,
        val totalPages: Int,
        val phase: Phase,
        val detail: String,
    )

    enum class Phase {
        /** Идёт распознавание страницы локально. */
        OCR,

        /** Модель смотрит на страницу. */
        AI,

        /** Скан завершён, роли записаны. */
        DONE,
    }

    /** Счётчик страниц, на которых ИИ что-то изменил. */
    data class Summary(
        val pagesChecked: Int,
        val pagesChanged: Int,
        val rolesFound: Int,
        val skippedReason: String?,
    )

    suspend fun verifyPage(
        pageName: String,
        bitmap: Bitmap,
        regions: List<mihon.domain.ocr.model.OcrRegion>,
        onStage: (Phase, String) -> Unit = { _, _ -> },
    ): List<mihon.domain.ocr.model.OcrRegion>? {
        val ordered = regions.sortedBy { it.order }
        val draft = ordered.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (draft.isEmpty()) return null
        val jpeg = encodeJpeg(bitmap) ?: return null
        val check = PageRoleVerifier.verify(jpeg, pageName, draft, prefs)
        if (!check.checked) {
            // Модель не ответила или ответила мусором: страница остаётся
            // локальной. Иначе половина страницы была бы догадкой модели, а
            // половина — распознаванием, и отладить это было бы невозможно.
            onStage(Phase.OCR, "ИИ не ответил (${check.reason ?: "неизвестно"}) — беру локальный разбор")
            return null
        }
        collectRoles(check, onStage)
        return applyToRegions(check, ordered)
    }

    /**
     * Проверенный текст возвращается в страницу.
     *
     * Что можно сделать честно:
     *  • исправить текст региона, когда модель вернула строку из черновика с
     *    другим написанием (это настоящая правка OCR);
     *  • убрать регион, который модель признала не репликой (титул, примечание) —
     *    читать его нечего.
     *
     * Чего НЕ делаем: строки, которых не было в черновике, не добавляем. У них
     * нет рамки, а выдуманная рамка означала бы подсветку и тап в пустоте.
     * Такие строки остаются в отчёте как dropped/uncertain.
     */
    /**
     * Накопитель ролей главы.
     *
     * Имена собираются по страницам и пишутся в словарь книги один раз в
     * конце: переписывать словарь на каждой странице означало бы N записей в
     * настройки и риск потерять предыдущие страницы при обрыве связи.
     */
    private val collected = LinkedHashMap<String, RoleDraft>()

    private fun collectRoles(
        check: PageRoleVerifier.PageCheck,
        onStage: (Phase, String) -> Unit,
    ) {
        check.lines.forEach { line ->
            if (!line.kept) return@forEach
            val role = line.role.trim()
            // "?" — модель не смогла определить говорящего, а "narrator" —
            // внеэкранный текст: у narrator нет голоса персонажа.
            if (role.isEmpty() || role == PageRoleVerifier.UNKNOWN_ROLE || role.equals("narrator", true)) {
                return@forEach
            }
            val key = role.lowercase()
            val draftEntry = collected.getOrPut(key) { RoleDraft(role) }
            draftEntry.pages++
            if (draftEntry.text.isBlank()) draftEntry.text = line.text
        }
        onStage(Phase.AI, "найдено персонажей ${collected.size}, не-реплик ${check.dropped.size}")
    }

    private data class RoleDraft(val name: String) {
        var pages: Int = 0
        var text: String = ""
    }

    /**
     * Записать собранные роли в словарь книги.
     *
     * Голос НЕ назначается здесь: системный TTS не умеет создавать голоса, и
     * без проверки доступности движок молча ушёл бы на другой. Имя и пол —
     * это то, что реально можно записать; конкретный голос выбирается движком
     * по полу через VoicePlugins.planAiVoice либо вручную.
     */
    fun commit(bookId: Long): Summary {
        if (bookId <= 0L || collected.isEmpty()) {
            return Summary(0, 0, collected.size, "ролей не найдено")
        }
        val existing = VoiceRoleDictionary.parseBookRoles(
            prefs.voiceRolesByBook().get(),
            bookId,
        )
        val roles = collected.values.map { draft ->
            VoiceRole(
                id = draft.name.lowercase(),
                name = draft.name,
                // Пол модели здесь не приходит: определение пола по лицу
                // делает SpeakerGenderService, и без него честнее "auto",
                // чем выдуманное значение.
                gender = VoiceRole.GENDER_AUTO,
                age = "adult",
                voice = "",
                pitch = 1.0f,
                rate = 1.0f,
                markers = listOf(draft.name),
            )
        }
        VoiceRoleDictionary.saveForBook(prefs, bookId, mergeRoles(existing, roles))
        return Summary(
            pagesChecked = collected.size,
            pagesChanged = 0,
            rolesFound = roles.size,
            skippedReason = null,
        )
    }

    fun reset() = collected.clear()

    private fun encodeJpeg(bitmap: Bitmap): ByteArray? = runCatching {
        if (bitmap.isRecycled) return null
        val out = ByteArrayOutputStream()
        // Модели достаточно уменьшенного кадра: она ищет форму облачков, а не
        // буквы, поэтому полный размер только тратит трафик и время.
        val scaled = if (bitmap.width > MAX_JPEG_EDGE) {
            val h = bitmap.height * MAX_JPEG_EDGE / bitmap.width
            Bitmap.createScaledBitmap(bitmap, MAX_JPEG_EDGE, h, true)
        } else {
            bitmap
        }
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        out.toByteArray().takeIf { it.isNotEmpty() }
    }.onFailure { logcat(LogPriority.WARN, it) { "Chapter AI jpeg encode failed" } }.getOrNull()

    companion object {
        /**
         * Правило слияния: ручная настройка главнее модели.
         *
         * Раньше здесь отбрасывалась роль с тем же именем и писалась роль от
         * модели с ПУСТЫМ voice — то есть голос, который читатель назначил
         * руками, стирался на каждом скане главы. Теперь для имени с ручным
         * голосом сохраняется ручная роль, а модель добавляет только новые
         * имена.
         */
        fun mergeRoles(
            existing: List<VoiceRole>,
            fromAi: List<VoiceRole>,
        ): List<VoiceRole> {
            val manualNames = existing.filter { it.voice.isNotBlank() }
                .map { it.name.lowercase() }
                .toSet()
            val manual = existing.filter { it.voice.isNotBlank() }
            return manual + fromAi.filter { r -> r.name.lowercase() !in manualNames }
        }

        /**
         * Чистая функция: проверка страницы -> новые регионы.
         *
         * Объект-обёртка не нужен и состояния не касается, поэтому вынесена в
         * companion: её проверяют тесты без Android и без Injekt.
         */
        fun applyToRegions(
        check: PageRoleVerifier.PageCheck,
        ordered: List<mihon.domain.ocr.model.OcrRegion>,
    ): List<mihon.domain.ocr.model.OcrRegion>? {
        val byText = check.lines
            .filter { it.kept && !it.uncertain }
            .associateBy { normalize(it.text) }
        val corrected = ordered.mapNotNull { region ->
            val fixed = byText[normalize(region.text)]?.text
            when {
                // Модель признала это не репликой — регион уходит из чтения.
                check.lines.any { !it.kept && normalize(it.text) == normalize(region.text) } -> null
                fixed != null && fixed != region.text -> region.copy(text = fixed)
                else -> region
            }
        }
        if (corrected.size == ordered.size && corrected.zip(ordered).all { (a, b) -> a.text == b.text }) {
            return null // ничего не изменилось — кэш не трогаем зря
        }
        return corrected.mapIndexed { i, r -> r.copy(order = i) }
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }


        private const val MAX_JPEG_EDGE = 1024
        private const val JPEG_QUALITY = 80
    }
}