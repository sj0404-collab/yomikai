package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.Migration
import mihon.core.migration.Migration.Companion.ALWAYS
import mihon.core.migration.MigrationContext
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Переключает движок распознавания на RapidOCR (eslav).
 *
 * В 2.0.5 RapidOCR сделан значением по умолчанию, но умолчание действует
 * только на чистые установки: у обновляющихся пользователей в настройках уже
 * лежит `pref_ocr_model = cyrillic`, и они так и остались на старом движке —
 * ровно то, чего они не ожидали. Поэтому переносим выбор один раз.
 *
 * Миграция срабатывает только если движок ещё не трогали (`!isSet()`) — если
 * пользователь сам выбрал что-то другое, не перебиваем его выбор ни разу,
 * ни второй.
 *
 * Сами модели при этом могут быть не скачаны: тогда репозиторий читает
 * страницу прежним движком и пишет об этом в OcrConsole, так что подмена
 * незаметно не проходит.
 */
class OcrEngineDefaultToRapidMigration : Migration {

    /**
     * ALWAYS (-1): стратегия запускает такие миграции при каждом обновлении.
     * Иначе фильтр `version in (old+1)..new` с new = 20005 никогда бы не
     * пропустил номер вроде 26 — там ждут code версии, а не порядковый номер.
     * Идемпотентность обеспечивает проверка `isSet()` внутри.
     */
    override val version: Float = ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false
        val ocrPreferences = migrationContext.get<OcrPreferences>() ?: return@withIOContext false

        // Движок выбирали руками — не лезем.
        if (!ocrPreferences.ocrModel().isSet()) {
            ocrPreferences.ocrModel().set(OcrModel.RAPID)
        }
        if (!ocrPreferences.bookOcrEngine().isSet()) {
            ocrPreferences.bookOcrEngine().set(OcrModel.RAPID)
        }
        if (!ocrPreferences.appOcrEngine().isSet()) {
            ocrPreferences.appOcrEngine().set(OcrModel.RAPID)
        }

        // Старый флаг режима книг: вкладка «Книги» вынесена отдельно, режим
        // внутри локальной библиотеки больше не читается. Гасим, чтобы
        // состояние не вводило в заблуждение.
        val booksMode = preferenceStore.getBoolean(
            Preference.appStateKey("local_library_books_mode"),
            false,
        )
        if (booksMode.get()) booksMode.set(false)

        return@withIOContext true
    }
}