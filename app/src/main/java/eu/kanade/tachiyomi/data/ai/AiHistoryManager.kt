package eu.kanade.tachiyomi.data.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Продвинутое управление историей AI-чата.
 *
 * Проблемы, которые решает:
 *  - История терялась при пересоздании вкладки / смене процесса (не было persist)
 *  - Тратил до 50к токенов на reasoning двух языков (нет лимита, нет сокращения)
 *  - Не говорил что доступно/невозможно (нет capability report)
 *
 * Решения:
 *  - Файл `workspace/ai_history.json` + StateFlow + лимит из `pref_ai_history_limit` (дефолт 12)
 *  - Токен-бюджет `pref_ai_token_budget` (дефолт 4000) — обрезка истории и max_tokens в запросе
 *  - Availability report перед каждым ходом: сеть, ключи, модели, ранер, локальная LLM
 *  - Сжатие истории: старые сообщения суммируются в краткий «контекст» вместо удаления
 */
object AiHistoryManager {

    @Serializable
    data class Msg(
        val role: String, // user | ai
        val text: String,
        val time: Long = System.currentTimeMillis(),
        val tokens: Int = 0,
        val model: String = "",
        val reasoning: String = "",
        val tools: List<String> = emptyList(),
        val files: List<String> = emptyList(),
        /** Кнопки-варианты [[...]] из ответа модели — повторная отправка текстом. */
        val choices: List<String> = emptyList(),
    )

    private const val FILE = "ai_history.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun prefs(): OcrPreferences = Injekt.get()

    /**
     * Канал истории. У книги их два: обычный чат и отыгрыш.
     *
     * Раньше файл был один на `mangaId`, поэтому переключение режима
     * подмешивало в реплику чужой разговор: в чате могли всплыть слова
     * отыгрыша, и наоборот. У отыгрыша своя история, общие знания книги
     * при этом остаются общими — изолируется переписка, а не память.
     */
    const val CHANNEL_CHAT = "chat"
    const val CHANNEL_ROLEPLAY = "roleplay"

    fun channelOf(mode: String): String =
        if (mode == BookChatProfile.MODE_ROLEPLAY) CHANNEL_ROLEPLAY else CHANNEL_CHAT

    /**
     * Файл истории. Старые файлы книг (`ai_history_<id>.json`) остаются
     * историей обычного чата — переименовывать их не нужно, иначе читатель
     * потерял бы переписку при обновлении.
     */
    fun historyFile(context: Context, mangaId: Long? = null, channel: String = CHANNEL_CHAT): File {
        val ws = aiWorkspaceDir(context)
        ws.mkdirs()
        val target = when {
            mangaId == null -> File(ws, FILE)
            channel == CHANNEL_CHAT -> File(ws, "ai_history_${mangaId}.json")
            else -> File(ws, "ai_history_${mangaId}_$channel.json")
        }
        if (!target.exists()) migrateLegacyFile(context, target)
        return target
    }

    /**
     * Старый каталог истории: `/sdcard/Android/Yomikai/AI`.
     *
     * Именно его вычисляла прежняя версия [aiWorkspaceDir]. Файлы назывались
     * так же, поэтому достаточно перенести совпадение по имени — иначе после
     * обновления переписка просто исчезла бы, хотя нигде не удалялась.
     */
    private fun legacyDir(context: Context): File? {
        val external = context.getExternalFilesDir(null) ?: return null
        val android = external.parentFile?.parentFile ?: return null
        return File(android, "Yomikai/AI")
    }

    private fun migrateLegacyFile(context: Context, target: File) {
        val legacy = legacyDir(context) ?: return
        val old = File(legacy, target.name)
        if (!old.exists() || old.length() == 0L) return
        synchronized(saveLock) {
            try {
                // Файлы одного тома, rename атомарен; если система отказала
                // (например, каталог назначения на другом mount), копируем.
                if (!old.renameTo(target)) {
                    target.writeText(old.readText())
                    old.delete()
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "AiHistoryManager legacy move failed: ${target.name}" }
            }
        }
    }

    /**
     * История лежит в корне workspace.
     *
     * Раньше папка вычислялась здесь отдельно (`getExternalFilesDir/../../Yomikai/AI`,
     * то есть `/sdcard/Android/Yomikai/AI`) и не совпадала ни с одним
     * кандидатом [AiWorkspace.root]. Из-за этого история была не видна
     * `workspace_list`/`read_file` — хотя промпт агента прямо велит читать её
     * оттуда, — и не попадала в `zip_workspace`.
     */
    private fun aiWorkspaceDir(context: Context): File = AiWorkspace.root(context)

    suspend fun load(context: Context, mangaId: Long? = null, channel: String = CHANNEL_CHAT): MutableList<Msg> =
        withContext(Dispatchers.IO) {
            val f = historyFile(context, mangaId, channel)
            if (!f.exists()) return@withContext mutableListOf()
            try {
                val raw = f.readText()
                val list = json.decodeFromString<List<Msg>>(raw)
                // Обрезаем до лимита при загрузке
                val limit = prefs().aiHistoryLimit().get().coerceIn(4, 100)
                list.takeLast(limit).toMutableList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "AiHistoryManager load failed" }
                mutableListOf()
            }
        }

    private val saveLock = Any()

    suspend fun save(context: Context, history: List<Msg>, mangaId: Long? = null, channel: String = CHANNEL_CHAT) =
        withContext(Dispatchers.IO) {
            // Пишем через временный файл и переименовываем. Раньше шли три
            // несинхронизированных writeText() на один и тот же файл (append плюс
            // два вызова из UI), и оборванная запись оставляла битый JSON — после
            // него load() молча отдавал пустую историю, то есть вся переписка
            // исчезала без единого слова читателю.
            synchronized(saveLock) {
                try {
                    val limit = prefs().aiHistoryLimit().get().coerceIn(4, 100)
                    val toSave = history.takeLast(limit)
                    val target = historyFile(context, mangaId, channel)
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeText(json.encodeToString(toSave))
                    if (!tmp.renameTo(target)) {
                        target.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "AiHistoryManager save failed" }
                }
            }
        }

    /**
     * Добавляет сообщение и подрезает историю. Список правится в потоке
     * вызова (это SnapshotStateList в UI — с IO его трогать нельзя), а на
     * диск уходит уже готовая копия, поэтому синхронный дисковый ввод-вывод
     * больше не стоит на главном потоке.
 */
suspend fun append(
        context: Context,
        history: MutableList<Msg>,
        msg: Msg,
        mangaId: Long? = null,
        channel: String = CHANNEL_CHAT,
    ) {
        history.add(msg)
        val limit = prefs().aiHistoryLimit().get().coerceIn(4, 100)
        while (history.size > limit) {
            // Сжимаем: первые 2 сообщения → один summary
            val oldest = history.removeAt(0)
            val second = if (history.isNotEmpty()) history.removeAt(0) else null
            val summary = buildString {
                append("[Сводка прошлого контекста] ")
                append(oldest.role).append(": ").append(oldest.text.take(80))
                if (second != null) append(" | ").append(second.role).append(": ").append(second.text.take(80))
            }
            history.add(0, Msg(role = "ai", text = summary, time = System.currentTimeMillis()))
        }
        save(context, history.toList(), mangaId, channel)
    }

    /**
     * Оценка токенов грубо: 1 токен ≈ 4 символа (для ru/en). Точнее — после ответа модели.
     */
    fun estimateTokens(text: String): Int = (text.length / 3.5).toInt().coerceAtLeast(1)

    fun totalTokens(history: List<Msg>): Int = history.sumOf { if (it.tokens > 0) it.tokens else estimateTokens(it.text) }

    fun trimToBudget(history: List<Msg>, budget: Int): List<Msg> {
        if (budget <= 0) return history
        var total = totalTokens(history)
        if (total <= budget) return history
        // Убираем старые, оставляя последние дорогие
        val mutable = history.toMutableList()
        while (mutable.size > 4 && total > budget * 0.85) {
            val removed = mutable.removeAt(0)
            total -= if (removed.tokens > 0) removed.tokens else estimateTokens(removed.text)
        }
        return mutable
    }
}
