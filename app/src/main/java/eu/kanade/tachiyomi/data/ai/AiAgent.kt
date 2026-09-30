package eu.kanade.tachiyomi.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.ui.UiActionRegistry
import eu.kanade.tachiyomi.data.ui.UiTabRegistry
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.data.ui.UiTab
import mihon.data.ui.UiTabs
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.repository.OcrRepository
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URLEncoder

/**
 * Агентское ядро встроенного AI-чата (вкладка «AI»). Это НЕ заглушка:
 * модель (Zen/OpenRouter, как в AiAssistant) получает список реальных
 * инструментов и вызывает их через простой текстовый протокол
 * `@tool имя {json}` — одна строка на вызов. Результат инструмента
 * возвращается модели, и она отвечает пользователю.
 *
 * Реальные инструменты:
 *  • write_file    — сохранить файл в workspace (реальный путь подставляется в промпт)
 *  • gen_image     — сгенерировать картинку через Pollinations (без ключа)
 *  • check_site    — проверить, работает ли сайт (реальный HTTP-запрос)
 *  • list_ext      — перечислить установленные расширения-источники
 *  • filter_ext    — скрыть/показать источники по запросу (правит
 *                    hidden_catalogues — тот же механизм, что в настройках)
 *  • find_manga    — поиск тайтла по включённым источникам (реальный
 *                    getSearchManga каждого источника, до 8 источников)
 *  • zip_workspace — упаковать workspace в zip
 *
 * Смена доменов источников: URL расширений вшиты в их APK; агент не может
 * их «переписать», но check_site честно проверяет зеркала, а find_manga
 * показывает, в каком источнике тайтл РЕАЛЬНО открывается — это решает
 * задачу «не искать долго».
 */
object AiAgent {

    /**
     * Методы, которые агент может слать в [AiGithub]. Список явный, потому что
     * произвольный метод в HttpURLConnection — это и DELETE, и нештатные
     * вещи вроде TRACE, и оставлять такое строкой из ответа модели нельзя.
     */
    private val ALLOWED_GITHUB_METHODS = setOf("GET", "POST", "PATCH", "PUT", "DELETE")

    /**
     * Расширения, которые мы умеем отправить vision-модели. Всё остальное
     * (pdf, doc, архивы) — не картинка, и «посмотреть» его нечем.
     */
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    data class ToolCall(val name: String, val args: JSONObject)
    data class ToolResult(
        val name: String,
        val output: String,
        val fileProduced: File? = null,
        /**
         * Остальные файлы того же вызова. Одно поле на файл не годилось для
         * пакетной генерации: читатель просил «несколько картинок подряд», и
         * видно было только последнюю, а остальные приходилось искать в
         * «Файлах». Список попадает в карточки чата так же, как первый файл.
         */
        val extraFiles: List<File> = emptyList(),
        /** Аргументы вызова (для показа в карточке). */
        val args: String = "",
        /** Раунд агентского цикла (1, 2, …). */
        val round: Int = 0,
        /** Время исполнения инструмента, мс. */
        val tookMs: Long = 0,
        /** ok | error — статус TODO-списка. */
        val status: String = "ok",
    )

    data class AgentReply(
        val text: String,
        val toolResults: List<ToolResult>,
        val images: List<File>,
        val reasoning: String? = null,
        val model: String = "",
        /** Сколько токенов съели все запросы этого хода. */
        val tokens: Int = 0,
        /** Полное время хода, мс. */
        val tookMs: Long = 0,
        /** Число раундов инструментов. */
        val rounds: Int = 0,
        /** Кнопки-варианты для пользователя ([[...]] из ответа модели). */
        val choices: List<String> = emptyList(),
    )

    private val sourceManager: SourceManager by lazy { Injekt.get() }
    private val sourcePrefs: SourcePreferences by lazy { Injekt.get() }

    /**
     * Сколько символов одной реплики переписки уходит в промпт. Раньше стояло
     * 280 и резалась только голова: конец ответа агента (вывод, путь к файлу)
     * до модели не доходил.
     */
    private const val MAX_HISTORY_CHARS = 900

    /** Таймаут инструмента по умолчанию. */
    private const val DEFAULT_TOOL_TIMEOUT_MS = 120_000L

    /**
     * Инструменты с собственным, более длинным таймаутом. Раньше у всех был
     * общий 120-секундный, и `gen_video` (9 мин) и `runner_start` (12 мин)
     * физически не могли завершиться.
     */
    private val TOOL_TIMEOUT_MS = mapOf(
        "gen_video" to 10L * 60_000L,
        "runner_start" to 13L * 60_000L,
        "runner_runs" to 90_000L,
        "runner_events" to 90_000L,
        "gen_image" to 180_000L,
        "gen_images" to 300_000L,
        "check_site" to 90_000L,
        "web_search" to 90_000L,
        "web_fetch" to 90_000L,
    )

    /**
     * Длительность для человека. Раньше везде писали `tookMs / 1000` — целочисленное
     * деление миллисекунд, поэтому всё, что быстрее секунды (а это большинство
     * инструментов), показывалось как «0 с».
     */
    fun humanMs(ms: Long): String = when {
        ms < 0 -> "—"
        ms < 1_000 -> "$ms мс"
        ms < 60_000 -> "${ms / 1_000} с"
        else -> "${ms / 60_000} мин ${(ms % 60_000) / 1_000} с"
    }

    /** Сколько успешных поисков в сети разрешено за один ход агента. */
    const val SEARCH_BUDGET = 3

    /**
     * Сколько попыток поиска в сети разрешено за один ход агента.
     *
     * Попыток больше, чем успешных результатов: поиск может не сработать
     * (заблокировали, нет сети), и на каждую неудачу модель меняла
     * формулировку. Без отдельного потолка она крутила круги и выжигала
     * токены, так и не получив результата.
     */
    const val SEARCH_ATTEMPT_CAP = 6

    /**
     * Можно ли ещё выполнять поиск в сети.
     *
     * Чистая функция — её проверяет тест: баг с вечно молчащим `web_fetch`
     * как раз и вырос из того, что лимит был подмешан прямо в цикл и
     * отлаживался только вручную.
     */
    fun searchAllowed(searchesUsed: Int, searchAttempts: Int): Boolean =
        searchesUsed < SEARCH_BUDGET && searchAttempts < SEARCH_ATTEMPT_CAP

    /**
     * Каноническая подпись вызова инструмента: имя + аргументы по отсортированным
     * ключам. Ключ из `args.toString()` зависел от порядка, который написала
     * модель, и `{"query":"x","limit":5}` с `{"limit":5,"query":"x"}` считались
     * разными вызовами — а одинаковые стыковались «повтором».
     */
    internal fun callSignature(call: ToolCall): String = buildString {
        append(call.name).append('\u0000')
        val keys = call.args.keys().asSequence().sorted().toList()
        for (k in keys) {
            append(k).append('=').append(call.args.opt(k)).append('\u0001')
        }
    }

    // Не const: в промпт подставляется документация инструментов читалки
    // из AiReaderTools, а это выражение, а не литерал.
    private val SYSTEM_PROMPT =
        "Ты — встроенный AI-агент манга-читалки Yomikai (как arena.ai agent, но внутри приложения). " +
            "Отвечай кратко и по-русски. У тебя есть ИНСТРУМЕНТЫ. Чтобы вызвать инструмент, " +
            "напиши отдельной строкой: @tool имя {json-аргументы}. Доступные инструменты:\n" +
            "@tool write_file {\"name\":\"путь/файл.txt\",\"content\":\"текст\"} — сохранить файл в workspace\n" +
            "@tool edit_file {\"name\":\"путь/файл.txt\",\"find\":\"что\",\"replace\":\"чем\"} — правка файла (бэкап создаётся автоматически)\n" +
            "@tool append_file {\"name\":\"путь/файл.txt\",\"content\":\"текст\"} — дописать в конец (для длинных книг по главам, бэкап автоматически)\n" +
            "@tool read_file {\"name\":\"путь/файл.txt\"} — прочитать файл workspace (для продолжения без потери контекста)\n" +
            "@tool gen_image {\"prompt\":\"описание на английском\"} — нарисовать картинку (Pollinations)\n" +
            "@tool gen_images {\"prompts\":[\"первый\",\"второй\"],\"folder\":\"имя_папки\",\"count\":3} — несколько картинок за один вызов, в одной папке; count>1 при одном промпте даёт варианты. Все файлы приходят карточками в чат\n" +
            "@tool check_site {\"url\":\"https://...\"} — проверить, работает ли сайт\n" +
            "@tool web_search {\"query\":\"поисковый запрос\"} — поиск в интернете, вернёт список результатов со ссылками\n" +
            "@tool web_fetch {\"url\":\"https://...\",\"maxChars\":4000} — скачать страницу и вернуть её текст без разметки\n" +
            "@tool web_screenshot {\"url\":\"https://...\",\"width\":1280} — ОТРЕНДЕРИТЬ веб-страницу в PNG и положить в workspace, " +
            "файл придёт карточкой в чат; дальше спрашивай vision через see_image или читай через read_file\n" +
            "РАЗНИЦА, не путай: web_fetch отдаёт ТОЛЬКО текст — ни вёрстки, ни цветов, ни картинок он не показывает. " +
            "see_page смотрит на страницу КНИГИ в читалке и НЕ открывает веб-страницы. " +
            "Когда читатель просит «посмотри страницу и сделай похожее», речь о ВЕБ-странице: " +
            "это web_screenshot (увидеть) + gen_image (нарисовать похожее), а не see_page.\n" +
            "@tool book_recall {} — что уже известно о текущей книге (где читать, о чём, как читать, заметки, советы)\n" +
            "@tool book_remember {\"kind\":\"site|summary|fact|advice|заметка\",\"text\":\"значение\"} — сохранить знание о книге\n" +
            "@tool book_learn {\"url\":\"https://...\"} — прочитать страницу и сохранить её как источник правил\n" +
            "@tool book_learn {\"edition\":\"название издания/перевода\"} — зафиксировать, какое издание читаем; " +
            "сначала web_search, если переводов несколько\n" +
            "@tool book_learn {\"kind\":\"reading_order|region|bubble|transcription|voice|advice\",\"text\":\"правило\"} — " +
            "выучить правило книги: порядок чтения, область/рамка, баблы, расшифровка, голоса и роли\n" +
            "@tool book_learn {\"kind\":\"...\",\"text\":\"- правило\\n- правило\",\"user\":true} — список правил; " +
            "user=true, если правило продиктовал пользователь\n" +
            "@tool book_learn {\"kind\":\"fact|voice|transcription\",\"text\":\"знание\",\"scope\":\"series|global\"} — " +
            "положить знание в память ВНЕ книги: series — термины, имена и перевод для всей серии, " +
            "global — для всех книг. Порядок чтения и области сюда НЕ кладут: они остаются книжными\n" +
            "@tool reader_actions {} — какие действия доступны в открытой читалке\n" +
            "@tool reader_do {\"action\":\"speak_page\"} — выполнить действие читалки: " +
            "speak_page (озвучить текущую страницу), speak_chapter (озвучивать всю главу), " +
            "stop_speak (прекратить озвучку), page_text (распознать текст текущей страницы), " +
            "see_page (СПРОСИТЬ ВИЖУЩУЮ МОДЕЛЬ про открытую в читалке страницу книги, " +
            "обязательно с вопросом: {\"action\":\"see_page\",\"question\":\"что нарисовано на этой странице?\"} — " +
            "это твои глаза на КНИГУ, веб-страницу так не посмотреть), " +
            "page_count (сколько страниц в главе), " +
            "turn_page (ЛИСТАТЬ САМ: {\"action\":\"turn_page\",\"to\":\"next|prev|first|last\"}), " +
            "auto_read ({\"action\":\"auto_read\",\"to\":\"on|off\"} — самому включить или " +
            "выключить авточтение и читать главу)\n" +
            "ВАЖНО: чтобы вести читателя постранично, листай сам через turn_page, а не " +
            "проси прислать скриншоты. На каждой странице смотри see_page и page_text.\n" +
            "@tool list_ext {} — список установленных расширений-источников с их доменами\n" +
            "@tool filter_ext {\"hide\":\"подстрока\",\"show\":\"подстрока\"} — скрыть/показать источники по имени/языку\n" +
            "@tool find_manga {\"title\":\"название\"} — найти мангу по включённым источникам, вернёт где реально открывается\n" +
            "@tool zip_workspace {} — упаковать workspace в zip\n" +
            "ПАПКА WORKSPACE (сначала осмотри её, потом действуй — не выдумывай имена файлов):\n" +
            "@tool workspace_list {\"path\":\"book\",\"limit\":200} — что лежит в папке (пустое path — весь корень)\n" +
            "@tool read_many {\"names\":[\"book/1.md\",\"book/2.md\"]} — прочитать несколько файлов по очереди в пределах бюджета; можно строкой через запятую\n" +
            "КАРТИНКИ: спросить про изображение слов \"see_image {\\\"name\\\":\\\"images/x.png\\\",\\\"question\\\":\\\"что на ней и что исправить\\\"}\" — ответит vision-модель (нужен движок ZenFree, Google AI или OpenRouter). " +
            "read_file на картинке тоже спросит vision, но see_image даёт задать конкретный вопрос. " +
            "Правки самой картинки (убрать объект, шум, изменить) инструментов не имеют — умею только смотреть и объяснять словами.\n" +
            "Порядок работы с папкой: сначала workspace_list, потом read_many на нужное, и только потом правки. " +
            "Не вываливай в ответ содержимое всего подряд. Если задача неоднозначна (какая глава, какой файл, " +
            "что именно исправлять) — спроси читателя ДО того, как что-то менять, и перечисли варианты.\n" +
            "ПЛАГИНЫ (самодельные инструменты, без ограничений по количеству):\n" +
            "@tool plugin_create {\"name\":\"имя\",\"kind\":\"http|prompt\",\"description\":\"что делает\"," +
            "\"template\":\"https://api...?q={query} ИЛИ текст-инструкция с {input}\"," +
            "\"method\":\"GET\",\"body\":\"\"} — создать/починить инструмент; после создания вызывай его по имени\n" +
            "@tool plugin_edit {\"name\":\"имя\",\"template\":\"новый шаблон\"} — исправить плагин (менять можно любое поле)\n" +
            "@tool plugin_delete {\"name\":\"имя\"} — удалить плагин\n" +
            "@tool plugin_list {} — список своих плагинов\n" +
            "СВОИ НАВЫКИ (рецепты из перечисленных выше инструментов — если нужного тебе инструмента нет, " +
            "собери его сам, не выдумывая новые команды):\n" +
            "@tool skill_create {\"name\":\"имя\",\"description\":\"что делает\",\"steps\":[{\"tool\":\"write_file\",\"args\":{\"name\":\"book/{вход}.md\",\"content\":\"...\"}}]} — сохранить навык\n" +
            "@tool skill_run {\"name\":\"имя\",\"input\":\"значение\"} — выполнить навык; {вход} в аргументах шага заменяется на input\n" +
            "@tool skill_list {} — список навыков\n" +
            "В steps можно ставить только инструменты из этого списка. Шаги выполняются по порядку, " +
            "после первого с ошибкой выполнение останавливается.\n" +
            "ПРОВАЙДЕРЫ AI (сторонние сервисы и локальные LLM пользователя):\n" +
            "@tool provider_create {\"id\":\"ollama\",\"title\":\"название\",\"baseUrl\":\"http://192.168.1.10:11434/v1\"," +
            "\"model\":\"qwen2.5:7b\",\"apiKey\":\"\"} — подключить свой OpenAI-совместимый провайдер " +
            "(Ollama, LM Studio, llama.cpp, корпоративный прокси); baseUrl без /chat/completions\n" +
            "@tool provider_edit {\"id\":\"ollama\",\"model\":\"новая-модель\"} — изменить провайдер (любое поле)\n" +
            "@tool provider_delete {\"id\":\"ollama\"} — отключить провайдер\n" +
            "@tool provider_list {} — список провайдеров: встроенные и свои\n" +
            "КНОПКИ В UI ЧИТАЛКИ (пользовательские действия, без исполняемого кода):\n" +
            "@tool ui_action_create {\"id\":\"my_manhwa\",\"title\":\"Манхва одним тапом\"," +
            "\"placement\":\"floating_menu|reader_top_bar|ocr_card\"," +
            "\"effect\":\"ocr_preset|scan_region|reading_mode|voice_engine|ai_provider\"," +
            "\"value\":\"manhwa\",\"order\":100} — добавить свою кнопку в меню читалки; " +
            "значение выбирается из списка эффекта (для ocr_preset — manga|manhwa|manhua|comic|balanced)\n" +
            "@tool ui_action_edit {\"id\":\"my_manhwa\",\"title\":\"новое название\"} — изменить кнопку\n" +
            "@tool ui_action_delete {\"id\":\"my_manhwa\"} — убрать кнопку\n" +
            "@tool ui_action_list {} — все кнопки: встроенные и пользовательские\n" +
            "ВКЛАДКИ ПРИЛОЖЕНИЯ (нижняя навигация; скрываем только то, что просит пользователь):\n" +
            "@tool ui_tab_hide {\"id\":\"browser\"} — скрыть вкладку. Доступны: " +
            "local_library|updates|history|browse|browser|ai (library и more закреплены, их скрыть нельзя)\n" +
            "@tool ui_tab_show {\"id\":\"browser\"} — вернуть вкладку\n" +
            "@tool ui_tab_list {} — все вкладки и какие из них скрыты\n" +
            "ЛОГИ: пользователь жалуется на озвучку/скачивание голосов — читай logs/tts.log через read_file.\n" +
            "@tool runner_chat {\"text\":\"вопрос\"} — спросить LLM на GitHub-ранере (если сессия жива и разрешено в настройках)\n" +
            "@tool runner_start {\"model\":\"qwen2.5-1.5b\",\"os\":\"linux|windows\"} — запустить новую ранер-сессию (если разрешено)\n" +
            "@tool github_api {\"path\":\"/repos/OWNER/REPO/issues\",\"method\":\"POST\",\"body\":\"{\\\"title\\\":\\\"...\\\"}\"} — запрос к GitHub API привязанным токеном (если разрешено). method по умолчанию GET; POST/PATCH/PUT/DELETE — для issue, комментариев и PR. Хост всегда api.github.com\n" +
            "@tool runner_runs {} — последние прогоны сценариев (что ранер запускал)\n" +
            "@tool runner_events {\"run_id\":123} — пошаговая лента задач прогона: что он делал и где упал\n" +
            "@tool repo_pulls {} — открытые PR репозитория\n" +
            "ЧИТАЛКА, РАСПОЗНАВАНИЕ И ОЗВУЧКА (реестры плагинов приложения):\n" +
            AiReaderTools.SYSTEM_PROMPT_LINES.joinToString("\n") { it } + "\n" +
            "ГЕНЕРАЦИЯ И ГОЛОСА AI-ЧАТА (файлы создаются в workspace и появляются в чате готовыми):\n" +
            AiChatTools.SYSTEM_PROMPT_LINES.joinToString("\n") { it } + "\n" +
            "Если пользователь жалуется, что текст распознаётся плохо или не тем порядком, — " +
            "сначала reader_status, затем ocr_preset с подходящим id (manga/manhwa/comic/balanced).\n" +
            "Если пользователь жалуется на озвучку/голоса/синтез (молчит, один голос на всех ролей, " +
            "движок «исчез» из списка) — ПЕРВЫМ вызывай tts_status и опирайся на него; " +
            "reader_status и plugins_list для голосовых жалоб не подходят.\n" +
            "Если пользователь просит новый инструмент — СОЗДАЙ его через plugin_create и сразу проверь вызовом.\n" +
            "НЕЙРО-КНИГИ и НЕЙРО-КОМИКСЫ: пиши книгу по главам через append_file " +
            "(book/название.md), перед продолжением читай хвост через read_file — так контекст не теряется. " +
            "Для комикса: сцены текстом в comic/сценарий.md + gen_image на каждый кадр.\n" +
            "Можно несколько @tool в одном ответе. После строк @tool больше ничего не пиши — " +
            "результаты придут следующим сообщением, тогда и ответишь пользователю.\n" +
            "ВАРИАНТЫ ВЫБОРА: если уместно предложить пользователю выбор (что делать дальше, " +
            "какой вариант взять), закончи ответ строками вида [[Текст варианта]] — по одной на строку, " +
            "2-4 варианта. Они превратятся в кнопки под сообщением."

    /**
     * Один ход агента: prompt пользователя (+опц. текст из вложений) →
     * модель → выполнение @tool-вызовов → второй запрос модели с
     * результатами → финальный ответ.
     */
    /**
     * Функция чата бэкенда: (prompt, systemPrompt) -> ChatReply?.
     * Позволяет гонять ОДИН И ТОТ ЖЕ агентский цикл с инструментами через
     * ЛЮБОЙ бэкенд: онлайн (Zen/OpenRouter), ЛОКАЛЬНУЮ модель на телефоне
     * (LocalLlm) и полу-онлайн ранер (RunnerLlm). Инструменты (@tool)
     * исполняются самим приложением — модели достаточно уметь писать текст.
     */
    private val onlineChat: suspend (String, String) -> AiAssistant.ChatReply? = { p, sys ->
        AiAssistant.chatFull(p, sys, maxTokens = 1800)
    }

    /**
     * A tool turn must survive a provider hiccup after side effects already
     * happened. Retry only the model call and join max-token continuations;
     * never execute a tool merely because the network response was lost.
     */
    private suspend fun reliableChat(
        chat: suspend (String, String) -> AiAssistant.ChatReply?,
        prompt: String,
        systemPrompt: String,
    ): AiAssistant.ChatReply? {
        var reply: AiAssistant.ChatReply? = null
        for (attempt in 0 until 3) {
            reply = runCatching { chat(prompt, systemPrompt) }.getOrNull()
            if (reply != null) break
            kotlinx.coroutines.delay(800L * (attempt + 1))
        }
        var combined = reply ?: return null
        for (continuation in 0 until 2) {
            if (combined.complete) break
            val next = runCatching {
                chat(
                    prompt + "\n\nОтвет оборвался по лимиту. Уже полученная часть:\n" +
                        combined.content.takeLast(6000) +
                        "\n\nПродолжи строго с места обрыва. Не повторяй уже написанное.",
                    systemPrompt,
                )
            }.getOrNull() ?: break
            combined = combined.copy(
                content = combined.content + "\n" + next.content,
                reasoning = next.reasoning ?: combined.reasoning,
                model = next.model,
                tokens = combined.tokens + next.tokens,
                complete = next.complete,
            )
        }
        return combined
    }

    suspend fun run(
        context: Context,
        userText: String,
        attachmentsInfo: String? = null,
        history: List<Pair<String, String>> = emptyList(), // role to content
        chatFn: (suspend (String, String) -> AiAssistant.ChatReply?)? = null,
        mangaId: Long? = null,
        bookContext: String? = null,
        onProgress: ((String) -> Unit)? = null,
    ): AgentReply = withContext(Dispatchers.IO) {
        val chat = chatFn ?: onlineChat
        val results = mutableListOf<ToolResult>()
        val images = mutableListOf<File>()

        // Продвинутый лимит истории и токен-бюджет (запрос: не тратить 50к токенов)
        val prefs = runCatching { uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>() }.getOrNull()
        val historyLimit = prefs?.aiHistoryLimit()?.get()?.coerceIn(4, 100) ?: 12
        val tokenBudget = prefs?.aiTokenBudget()?.get()?.coerceIn(1000, 16000) ?: 4000
        // Грубая оценка токенов: 3.5 символа ≈ 1 токен
        fun est(s: String) = (s.length / 3.5).toInt()
        var budgetedHistory = history
        var total = history.sumOf { est(it.second) }
        while (budgetedHistory.size > 4 && total > (tokenBudget * 0.7).toInt()) {
            val removed = budgetedHistory.first()
            total -= est(removed.second)
            budgetedHistory = budgetedHistory.drop(1)
        }
        val trimmedHistory = budgetedHistory
        // Реплики режем ГОЛОВОЙ и ХВОСТОМ, а не только головой: в ответе агента
        // вывод и путь к файлу стоят в конце, и take(280) их отбрасывал —
        // модель теряла ровно то, ради чего ход затевался.
        val historyBlock = trimmedHistory.takeLast(historyLimit).joinToString("\n\n") { (role, c) ->
            val body = if (c.length > MAX_HISTORY_CHARS) {
                c.take(MAX_HISTORY_CHARS / 2) + " … " + c.takeLast(MAX_HISTORY_CHARS / 2)
            } else {
                c
            }
            (if (role == "user") "Пользователь: " else "Ассистент: ") + body
        }
        val capabilityBlock = runCatching { AiCapabilityReporter.renderForPrompt(context) }.getOrNull().orEmpty()
        // Сессия книги создаётся при первом обращении к AI. Пока правил нет,
        // агенту говорится: сначала изучи источник и зафиксируй правила, иначе
        // он ответит по общему пресету и «выучит» потом неправильный порядок.
        val bookSession = mangaId?.let { BookKnowledge.ensureSession(context, it) }
        val onboarding = when {
            bookSession == null -> null
            bookSession.book.rules.isNotEmpty() -> null
            else -> "СЕССИЯ КНИГИ НОВАЯ (${bookSession.book.sessionId}). Правил о книге ещё нет. " +
                "Прежде чем отвечать по существу: web_search «<название> где читать/описание» → " +
                "book_learn {\"url\":\"<найденная страница>\"} → выведи конкретные правила книги " +
                "(порядок чтения, области и рамки, баблы, расшифровка, голоса и роли) и зафиксируй их " +
                "вызовами book_learn с kind. Не выдумывай правил: если источник не нашёлся — скажи об этом. " +
                "У этой манги обычно несколько переводов, и они отличаются словарём и порядком сцен. " +
                "Найди web_search, какие издания/переводы существуют, и book_learn {\"edition\":\"<перевод, год, " +
                "группа>\"} — какое издание читаем. Если перевод не уточнён читателем, спроси один раз и " +
                "запомни ответ, а не выбирай молча."
        }
        // Память из других книг: имена персонажей, термины серии и особенности
        // перевода не должны открываться заново в каждой манге.
        val sharedMemory = bookSession?.book?.title
            ?.let { BookSharedMemory.render(context, it) }
            .orEmpty()
        // Как работать с ЭТОЙ книгой: режим (чат/отыгрыш), 18+, цензура и
        // издание. Выбрано читателем, поэтому идёт жёстким блоком, а не
        // просьбой модели решить самой. Вне читалки книги нет — блок не нужен
        // и только путал бы общий чат словами про «книгу».
        val chatSettings = BookKnowledge.profile(context, mangaId)
        val modeBlock = if (mangaId == null) {
            ""
        } else {
            BookChatProfile.render(
                mode = chatSettings.mode,
                matureAllowed = chatSettings.matureAllowed,
                censorship = chatSettings.censorship,
                bookTitle = chatSettings.title.ifBlank { bookSession?.book?.title.orEmpty() },
            )
        }
        val editionHint = chatSettings.edition.takeIf { it.isNotBlank() }?.let {
            "ИЗДАНИЕ КНИГИ (читать именно его): $it"
        }.orEmpty()
        val prompt = buildString {
            if (!bookContext.isNullOrBlank()) append(bookContext).append("\n\n")
            if (editionHint.isNotBlank()) append(editionHint).append("\n\n")
            if (sharedMemory.isNotBlank()) append(sharedMemory).append("\n\n")
            if (onboarding != null) append(onboarding).append("\n\n")
            if (modeBlock.isNotBlank()) append(modeBlock).append("\n\n")
            if (!attachmentsInfo.isNullOrBlank()) append("Вложения пользователя:\n").append(attachmentsInfo).append("\n\n")
            append(userText)
            // История стоит СРАЗУ под вопросом, а не в начале промпта. Перед
            // ней — книга, память, правила, вложения, блок возможностей:
            // на локальных моделях это ~11 КБ текста, и реплики, лежавшие в
            // начале, выпадали из окна внимания — агент «забывал» то, что
            // было пару секунд назад.
            if (historyBlock.isNotBlank()) {
                append("\n\nПереписка (последние ${trimmedHistory.size} реплик, бюджет ${tokenBudget} токенов):\n")
                append(historyBlock)
            }
            append("\n\n[Инструкция: отвечай кратко на русском, одним языком, reasoning ≤250 токенов, укажи что доступно/недоступно из блока выше, не повторяй запрос; токен-бюджет хода ${tokenBudget}.]")
        }
        // Настоящий путь workspace вместо обещанного в тексте промпта
        // /sdcard/Yomikai/AI: на Android 11+ приложение не может создать
        // папку в корне хранилища, и агент писал «в /sdcard/Yomikai/AI»,
        // а файлы оказывались в приватном хранилище, где их не видит ни
        // проводник, ни сам читатель. Модели говорим ровно то, что есть.
        val workspaceBlock = "WORKSPACE (реальный путь, всё в нём и лежит): " +
            "${AiWorkspace.storageHint(context)}. Инструменты write_file/edit_file/append_file/read_file " +
            "принимают путь ОТНОСИТЕЛЬНО этого каталога (например book/заметка.md). " +
            "Не выдумывай другие пути (/sdcard/Yomikai и т.п.) — их на устройстве нет. " +
            "После записи назови путь результата из ответа инструмента, чтобы пользователь нашёл файл."
        // Список своих навыков — агент должен знать о них до просьбы читателя,
        // иначе он создаст дубль уже существующему.
        val skillsBlock = runCatching { AiSkills.promptLines(context) }.getOrElse { "" }
        val systemPromptEffective = SYSTEM_PROMPT + "\n\n" + workspaceBlock + "\n\n" +
            skillsBlock + "\n\n" + capabilityBlock

        // Монотонные часы: currentTimeMillis() — настенные, и смена времени
        // телефона посреди хода давала отрицательную или завышенную длительность.
        val turnStarted = System.nanoTime()
        onProgress?.invoke("Запрос к модели…")
        var totalTokens = 0
        var roundsDone = 0

        var reply = reliableChat(chat, prompt, systemPromptEffective)
            ?: return@withContext AgentReply(
                buildString {
                    val failure = AiAssistant.lastFailure()
                    // Закрытый провайдером бесплатный уровень — это не «проверьте
                    // прокси»: сначала показываем причину, иначе пользователь
                    // по кругу чинит то, что сломано не у него.
                    val blockedFreeTier = failure == AiAssistant.FREE_TIER_BLOCKED_MESSAGE
                    if (blockedFreeTier) {
                        append(failure)
                    } else {
                        append("Нет ответа от AI-бэкенда после повторов и ротации.")
                        failure.takeIf { it.isNotBlank() }?.let {
                            append(" Последняя ошибка: ").append(it)
                        }
                        append(" Проверьте прокси в ⚙ или смените бэкенд.")
                    }
                },
                emptyList(), emptyList(),
            )
        totalTokens += reply.tokens
        var answer = reply.content
        var reasoning = reply.reasoning
        var usedModel = reply.model

        // Раундов стало больше (было 6 — сложные задачи обрывались на середине),
        // дубликаты вызовов по-прежнему не исполняются дважды за один ход
        // (важно для append_file/write_file).
        val executedCalls = mutableSetOf<String>()
        // Поиск по сети — единственный инструмент, который модель в цикле
        // повторяла по чуть-чуть разными запросами. На скрине это 8 вызовов
        // и 33 тысячи токенов на одну фразу «продолжить постранично».
        // Дальше поиск не идёт, а модели прямо говорится, что искать больше
        // нечего: дешевле закончить ход, чем выжигать токены.
        val searchBudget = SEARCH_BUDGET
        val searchAttemptCap = SEARCH_ATTEMPT_CAP
        var searchesUsed = 0
        var searchAttempts = 0
        // Лимит поиска объясняется модели ровно один раз: иначе она
        // возвращается к нему на каждом круге.
        var searchLimitTold = false
        for (round in 1..12) {
            val parsedCalls = parseToolCalls(context, answer)
            if (parsedCalls.isEmpty()) break
            // Считаем, что ПОВТОР за ход и ЛИМИТ — разные вещи, и агент
            // должен называть причину, а не «уже использовано».
            // Главное: в executedCalls попадает только то, что РЕАЛЬНО ушло
            // в execute(). Раньше отклонённый вызов тоже туда попадал, и на
            // следующем круге тот же инструмент отваливался уже как «повтор» —
            // агент писал «пропускаю» про то, что никогда не выполнялось.
            val takenThisRound = mutableSetOf<String>()
            val calls = mutableListOf<ToolCall>()
            val skipped = mutableListOf<Pair<ToolCall, String>>()
            for (call in parsedCalls) {
                val sig = callSignature(call)
                when {
                    sig in executedCalls || sig in takenThisRound ->
                        skipped += call to "тот же вызов уже выполнялся в этом ходе"
                    call.name == "web_search" && !searchAllowed(searchesUsed, searchAttempts) ->
                        skipped += call to "лимит web_search — $searchBudget поиска за ход"
                    else -> {
                        takenThisRound += sig
                        calls += call
                    }
                }
            }
            executedCalls += takenThisRound
            if (calls.isEmpty() && parsedCalls.any { it.name == "web_search" } &&
                !searchAllowed(searchesUsed, searchAttempts)
            ) {
                // Раньше здесь стоял `break` с готовым текстом ответа. Модель
                // при этом НЕ получала шанса сделать ход: цикл умирал, и её
                // обещание «сделаю один прямой запрос через web_fetch» так и
                // оставалось обещанием — инструмент больше не вызывался.
                // Теперь лимит — это сообщение модели, а не конец хода.
                if (!searchLimitTold) {
                    searchLimitTold = true
                    val next = reliableChat(
                        chat,
                        prompt + "\n\n(поиск в сети недоступен: лимит $searchBudget " +
                            "запросов на этот ход)\n" +
                            "Дальше искать нельзя. Ответь по тому, что уже есть. " +
                            "Если знаешь точный адрес страницы — сделай ОДИН прямой " +
                            "вызов web_fetch {\"url\":\"...\"}, он лимитом не ограничен. " +
                            "Если адреса нет — просто ответь, не предлагай поиск снова.",
                        systemPromptEffective,
                    )
                    if (next != null) {
                        totalTokens += next.tokens
                        answer = next.content
                        reasoning = next.reasoning
                        usedModel = next.model
                        continue
                    }
                }
                answer = stripToolSyntax(context, answer).ifBlank {
                    "Поиск в сети уже выполнялся $searchBudget раза за этот ход, и результат использован. " +
                        "Дальше искать нечего — отвечаю по тому, что уже есть."
                }
                break
            }
            searchAttempts += calls.count { it.name == "web_search" }
            if (calls.isEmpty()) {
                val why = skipped.joinToString(", ") { (c, r) -> "${c.name}: $r" }.take(240)
                answer = stripToolSyntax(context, answer).ifBlank {
                    "Инструменты не выполнены — $why. " +
                        "Используй уже полученный результат или измени аргументы; " +
                        "остальные инструменты (кроме web_search) лимитом не ограничены."
                }
                break
            }
            roundsDone = round
            onProgress?.invoke("Инструменты: ${calls.joinToString(", ") { it.name }}")
            val outputs = calls.map { call ->
                val t0 = System.nanoTime()
                // Инструменты не могут зависнуть навсегда, но и не должны
                // обрываться раньше, чем их собственные таймауты: gen_video
                // ждёт 9 минут, runner_start — 12, и общий 120-секундный
                // ограничитель убивал их всегда, то есть эти инструменты не
                // могли succeed НИКОГДА.
                val limit = TOOL_TIMEOUT_MS[call.name] ?: DEFAULT_TOOL_TIMEOUT_MS
                val r = runCatching {
                    withTimeoutOrNull(limit) { execute(context, call, chat, mangaId, onProgress) }
                        ?: ToolResult(
                            call.name,
                            "ОШИБКА: инструмент не ответил за ${humanMs(limit)}",
                            status = "error",
                        )
                }
                    .getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}", status = "error") }
                    .copy(
                        args = call.args.toString().take(200),
                        round = round,
                        tookMs = (System.nanoTime() - t0) / 1_000_000,
                    )
                val finalR = if (r.output.startsWith("ОШИБКА")) r.copy(status = "error") else r
                // Успешный поиск — тот, что вернул результат. Раньше счётчик
                // рос на КАЖДОМ вызове, включая упавший: на скрине один из трёх
                // поисков был с ошибкой, и лимит всё равно выбирался, хотя
                // читатель получил два результата, а не три.
                if (call.name == "web_search" && finalR.status != "error") searchesUsed++
                if (finalR.fileProduced != null && finalR.name == "gen_image") images += finalR.fileProduced
                results += finalR
                val file = finalR.fileProduced
                onProgress?.invoke(
                    buildString {
                        append(finalR.name)
                        if (finalR.tookMs > 0) append(" · ").append(humanMs(finalR.tookMs))
                        if (file != null) {
                            append(" · файл ").append(file.name).append(" · ")
                            append(file.length() / 1024).append(" КБ")
                        }
                        if (finalR.status == "error") append(" · ошибка")
                    },
                )
                "${finalR.name}: ${finalR.output.take(700)}"
            }
            val followUp = "Твой предыдущий ответ с вызовами:\n${answer.take(6000)}\n\n" +
                "Результаты инструментов:\n" + outputs.joinToString("\n---\n") +
                // Отклонённые вызовы тоже показываем: молча их пропустив,
                // мы оставляли модель в неведении, и она на следующем круге
                // предлагала ровно то, что было отклонено.
                (if (skipped.isEmpty()) {
                    ""
                } else {
                    "\n\nНЕ ВЫПОЛНЕНО (не повторяй эти вызовы):\n" +
                        skipped.joinToString("\n") { (c, r) -> "${c.name}: $r" }
                }) +
                "\n\nПродолжи задачу. Если всё сделано — дай полный финальный ответ без @tool."
            onProgress?.invoke("Инструменты выполнены, жду ответ модели…")
            val next = reliableChat(
                chat,
                prompt + "\n\n(вызовы выполнены приложением)\n" + followUp,
                systemPromptEffective,
            )
            if (next != null) {
                totalTokens += next.tokens
                answer = next.content
                if (next.reasoning != null) reasoning = next.reasoning
                usedModel = next.model
            } else {
                // Network/provider failed after tools already made changes.
                // Return a complete local checkpoint instead of losing the
                // turn or forcing the user to execute the same tools again.
                answer = buildString {
                    append(stripToolSyntax(context, answer).takeIf { it.isNotBlank() }.orEmpty())
                    if (isNotEmpty()) append("\n\n")
                    append("Инструменты выполнены, но финальный ответ модели не получен:\n")
                    results.forEach { result ->
                        append("• ").append(result.name).append(": ")
                            .append(result.output.take(700)).append('\n')
                    }
                    append("Результаты сохранены; повторять инструменты не требуется.")
                }
                break
            }
        }

        var cleanText = stripToolSyntax(context, answer)
        // Кнопки-варианты: [[Вариант]] по одной на строку в конце ответа
        val choices = parseChoices(cleanText)
        if (choices.isNotEmpty()) cleanText = stripChoices(cleanText)
        cleanText = cleanText.ifBlank { silentModelSummary(results) }
        AgentReply(
            cleanText, results, images,
            reasoning = reasoning, model = usedModel,
            tokens = totalTokens,
            tookMs = (System.nanoTime() - turnStarted) / 1_000_000,
            rounds = roundsDone,
            choices = choices,
        )
    }

    /** Кнопки-варианты: [[Текст]] по одной на строку в конце ответа модели. */
    private val choiceRe = Regex("\\[\\[(.{2,80}?)]]")

    /** Извлекает варианты действий [[…]] из ответа модели (не более 4). */
    internal fun parseChoices(text: String): List<String> =
        choiceRe.findAll(text).map { it.groupValues[1].trim() }.take(4).toList()

    /** Убирает из видимого текста разметку [[вариант]], оставляя сами тексты. */
    internal fun stripChoices(text: String): String = choiceRe.replace(text, "").trim()

    /** Имена всех известных инструментов — для «мягкого» синтаксиса без @tool. */
    /**
     * Действия читалки, которые промпт документирует как отдельные @tool.
     * Раньше они были описаны словами, но в [knownToolNames] не попадали —
     * `parseToolCalls` молча выбрасывал `@tool see_page {...}`, и модель
     * получала НИ результата, НИ ошибки: раунд просто исчезал.
     */
    private val READER_ACTION_ALIASES = listOf(
        "speak_page", "speak_chapter", "stop_speak", "page_text",
        "see_page", "page_count", "turn_page", "auto_read",
    )

    private fun knownToolNames(context: Context): Set<String> =
        setOf(
            "write_file", "edit_file", "append_file", "read_file", "gen_image",
            "check_site", "web_search", "web_fetch", "web_screenshot", "gen_images",
            "runner_runs", "runner_events", "repo_pulls", "book_recall", "book_remember", "book_learn",
            "reader_actions", "reader_do",
            "list_ext", "filter_ext", "find_manga", "zip_workspace",
            "plugin_create", "plugin_edit", "plugin_delete", "plugin_list",
            "skill_create", "skill_list", "skill_run",
            "workspace_list", "read_many", "see_image",
            "runner_chat", "runner_start", "github_api",
            "provider_create", "provider_edit", "provider_delete", "provider_list",
            "ui_action_create", "ui_action_edit", "ui_action_delete", "ui_action_list",
            "ui_tab_hide", "ui_tab_show", "ui_tab_list",
        ) + AiReaderTools.TOOL_NAMES + AiChatTools.TOOL_NAMES + READER_ACTION_ALIASES +
            AiPlugins.list(context).map { it.name } + AiSkills.list(context).map { it.name }

    /**
     * Разбор вызовов инструментов. Модели (особенно бесплатные) пишут вызов
     * как попало — поддерживаем все варианты (баги со скриншотов):
     *  • @tool list_ext {}      — канонический;
     *  • @list_ext {}           — без слова tool;
     *  • list_ext {}            — вообще без @, если имя известно;
     *  • `@tool list_ext {}`    — в бэктиках/код-блоке;
     *  • <tool_call>имя<arg_key>k</arg_key><arg_value>v</arg_value></tool_call>
     *    — XML-стиль, которым laguna пишет вызовы (второй скриншот).
     */
    private fun parseToolCalls(context: Context, text: String): List<ToolCall> {
        val known = knownToolNames(context)
        val out = mutableListOf<ToolCall>()

        // --- XML-стиль: <tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value>…</tool_call>
        val xmlRe = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val argRe = Regex("<arg_key>(.*?)</arg_key>\\s*<arg_value>(.*?)</arg_value>", RegexOption.DOT_MATCHES_ALL)
        for (m in xmlRe.findAll(text)) {
            val inner = m.groupValues[1]
            val name = inner.substringBefore("<arg_key>").trim()
                .removePrefix("@tool").removePrefix("@").trim()
            if (name !in known) continue
            val args = JSONObject()
            for (am in argRe.findAll(inner)) {
                args.put(am.groupValues[1].trim(), am.groupValues[2].trim())
            }
            out += ToolCall(name, args)
        }

        // --- Multiline @tool name { ... } with balanced JSON. This recovers
        // calls split across model lines or joined from a max-token continuation.
        val markerRe = Regex("(?:@tool\\s+|@)([A-Za-z0-9_]+)\\s*")
        for (marker in markerRe.findAll(text)) {
            val name = marker.groupValues[1]
            if (name !in known) continue
            val start = text.indexOf('{', marker.range.last + 1)
            if (start < 0) continue
            var depth = 0
            var quoted = false
            var escaped = false
            var end = -1
            for (i in start until text.length) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (quoted && ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') quoted = !quoted
                if (!quoted) {
                    if (ch == '{') depth++
                    if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }
            if (end > start) {
                runCatching { JSONObject(text.substring(start, end + 1)) }
                    .getOrNull()
                    ?.let { out += ToolCall(name, it) }
            }
        }

        // --- Строчные стили
        for (raw in text.lines()) {
            var t = raw.trim().trim('`').trim()
            if (t.isEmpty() || t.contains("<tool_call>")) continue
            if (t.startsWith("@tool ")) t = t.removePrefix("@tool ").trim()
            else if (t.startsWith("@")) t = t.removePrefix("@").trim()
            val space = t.indexOf(' ')
            val name = (if (space > 0) t.substring(0, space) else t).trim()
            if (name !in known) continue
            val json = if (space > 0) t.substring(space + 1).trim() else "{}"
            runCatching { ToolCall(name, JSONObject(json.ifBlank { "{}" })) }
                .getOrNull()
                ?.let(out::add) // incomplete JSON is recovered by the multiline parser
        }
        return out
    }

    /**
     * Что сказать, когда модель вернула только вызовы инструментов, а текста
     * не осталось (после [stripToolSyntax] он пустой).
     *
     * Раньше здесь была постоянная фраза «Готово. Результаты — в карточках
     * инструментов ниже и в workspace»: она звучала одинаково и когда всё
     * сделано, и когда не выполнено ни одного инструмента. Пользователь
     * видел «всё отправлено», хотя в workspace было пусто, а вкладки не
     * существовало. Теперь текст собирается из фактического состояния:
     * сколько инструментов выполнено, какие из них с ошибкой, есть ли файлы.
     */
    internal fun silentModelSummary(results: List<ToolResult>): String {
        if (results.isEmpty()) {
            return "Модель не вернула текст и не вызвала ни одного инструмента — " +
                "ничего не выполнено и не изменено."
        }
        val failed = results.count { it.status == "error" }
        val files = results.mapNotNull { it.fileProduced?.name }.filter { it.isNotBlank() }
        return buildString {
            append("Модель не вернула текст. Выполнено инструментов: ")
            append(results.size)
            if (failed > 0) append(", из них с ошибкой: ").append(failed)
            append('.')
            if (files.isNotEmpty()) {
                append("\nФайлы: ").append(files.joinToString(", ")).append('.')
            } else {
                append("\nФайлов не создано.")
            }
            append("\nПодробности — в карточках инструментов ниже.")
        }
    }

    /** Убирает незакрытые вызовы инструментов целиком.
     *
     * Список известных имён не спасал: действия читалки (`see_page`,
     * `page_count`) знакомыми не считаются — они аргументы `reader_do`. А
     * модель часто пишет `@tool page_text {` без закрывающей скобки, такой
     * вызов не разбирается, и сырой `@tool` с JSON-аргументами уезжал в
     * ответ как «размышления». Ловим по началу строки и снимаем блок, пока
     * скобки не сойдутся.
     */
    fun stripToolFragments(text: String): String {
        val out = StringBuilder()
        var skipping = false
        var depth = 0
        for (line in text.lines()) {
            val t = line.trim()
            if (skipping) {
                depth += t.count { it == '{' } - t.count { it == '}' }
                if (depth <= 0) skipping = false
                continue
            }
            // Строка — вызов инструмента только если после @имя идёт форма
            // аргументов: `{` (JSON) или `=` (arg-синтаксис). Раньше здесь
            // стояло «@имя + любой пробел», и обычная фраза модели вида
            // «@workspace всё отправлено» целиком удалялась — ответ
            // выглядел пустым, а приложение подставляло «Готово».
            val isToolStart = t.startsWith("@tool") ||
                (t.startsWith("@") && Regex("^@\\w+\\s*[\\{=]").containsMatchIn(t))
            if (isToolStart) {
                depth = t.count { it == '{' } - t.count { it == '}' }
                // Незакрытый вызов: снимаем и его хвост до баланса скобок.
                skipping = depth > 0
                continue
            }
            // Одиночный JSON-аргумент без строки @tool — тоже мусор.
            if (t.startsWith("{") && t.endsWith("}") && t.contains("\"")) continue
            out.append(line).append('\n')
        }
        return out.toString().trim()
    }

    fun stripToolSyntax(context: Context, text: String): String {
        val known = knownToolNames(context)
        var cleaned = text.replace(
            Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        // laguna пишет аргументы XML-тегами прямо после имени инструмента и
        // часто без обёртки <tool_call>: такие огрызки («@tool ocr_preset<arg_key>…»)
        // раньше оставались в пузыре ответа. Убираем и их.
        cleaned = cleaned.replace(
            Regex("<arg_key>.*?</arg_value>", RegexOption.DOT_MATCHES_ALL),
            " ",
        )
        cleaned = cleaned.lines().filterNot { line ->
            val t = line.trim().trim('`').trim()
                .removePrefix("@tool ").removePrefix("@").trim()
                .substringBefore('<')
            t.substringBefore(' ') in known && (
                line.trimStart().startsWith("@") || t.contains("{") || t.substringBefore(' ') == t
                )
        }.joinToString("\n")
        return stripToolFragments(cleaned)
    }

    private suspend fun execute(
        context: Context,
        call: ToolCall,
        chatFn: suspend (String, String) -> AiAssistant.ChatReply?,
        mangaId: Long? = null,
        onProgress: ((String) -> Unit)? = null,
    ): ToolResult = when (call.name) {
        // Инструменты читалки: видят те же реестры и настройки, что и экраны,
        // поэтому ответ агента не может разойтись с настройками пользователя.
        AiReaderTools.TOOL_READER_STATUS -> runCatching {
            ToolResult(call.name, AiReaderTools.readerStatus(context))
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}") }

        AiReaderTools.TOOL_OCR_PRESET -> runCatching {
            ToolResult(call.name, AiReaderTools.applyPreset(context, call.args.optString("id")))
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}") }

        AiReaderTools.TOOL_PLUGINS_LIST -> runCatching {
            ToolResult(call.name, AiReaderTools.pluginsReport(context))
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}") }

        AiReaderTools.TOOL_TTS_STATUS -> runCatching {
            ToolResult(call.name, AiReaderTools.ttsStatus(context))
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}") }

        AiChatTools.TOOL_VOICE_LIST -> runCatching {
            ToolResult(call.name, AiChatTools.voiceList(context), status = "ok")
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}", status = "error") }

        AiChatTools.TOOL_VOICE_PREVIEW -> runCatching {
            ToolResult(call.name, AiChatTools.previewVoice(context, call.args.optString("voice").ifBlank { null }), status = "ok")
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}", status = "error") }

        AiChatTools.TOOL_VOICE_SET -> runCatching {
            val out = AiChatTools.voiceSet(call.args.optString("voice"))
            ToolResult(call.name, out, status = if (out.startsWith("ОШИБКА")) "error" else "ok")
        }.getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}", status = "error") }

        AiChatTools.TOOL_RENDER_AUDIO -> runCatching {
            AiChatTools.renderAudio(
                context,
                call.args.optString("text"),
                call.args.optString("voice").ifBlank { null },
                call.args.optString("name").ifBlank { null },
            )
        }.fold(
            onSuccess = { outcome ->
                if (outcome.file != null) {
                    ToolResult(call.name, outcome.output, outcome.file, status = "ok")
                } else {
                    ToolResult(call.name, outcome.output, status = "error")
                }
            },
            onFailure = { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}", status = "error") },
        )

        AiChatTools.TOOL_GEN_VIDEO -> runCatching {
            val imagesArr = call.args.optJSONArray("images")
            val images = when {
                imagesArr != null -> (0 until imagesArr.length())
                    .map { imagesArr.getString(it).trim() }
                    .filter { it.startsWith("http", ignoreCase = true) }
                else -> call.args.optString("images")
                    .split('\n', ',')
                    .map { it.trim() }
                    .filter { it.startsWith("http", ignoreCase = true) }
            }
            AiChatTools.renderVideo(
                context,
                images,
                call.args.optString("text"),
                call.args.optInt("fps", 3),
                call.args.optString("name").ifBlank { null },
                // Раньше статус не показывался, и сборка видео висела молча до
                // 10 минут (лимит gen_video) — выглядело как зависание.
                onStatus = { st -> onProgress?.invoke("Видео: $st") },
            )
        }.fold(
            onSuccess = { outcome ->
                if (outcome.file != null) {
                    ToolResult(call.name, outcome.output, outcome.file, status = "ok")
                } else {
                    ToolResult(call.name, outcome.output, status = "error")
                }
            },
            onFailure = { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}", status = "error") },
        )

        "runner_chat" -> {
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            if (!prefsR.aiAllowRunner().get()) {
                ToolResult("runner_chat", "ЗАПРЕЩЕНО настройками: включите «Доступ агента к ранеру» в ⚙ вкладки AI")
            } else {
                val text = call.args.optString("text")
                val session = RunnerLlm.listSessions(context).firstOrNull { it.url != null }
                when {
                    text.isBlank() -> ToolResult("runner_chat", "ОШИБКА: пустой text")
                    session == null -> ToolResult("runner_chat", "Нет живой ранер-сессии — запусти runner_start или вручную на вкладке «AI»")
                    else -> {
                        val answer = RunnerLlm.chat(context, session, text)
                        ToolResult("runner_chat", answer ?: "Ранер не ответил (сессия могла умереть)")
                    }
                }
            }
        }

        "runner_start" -> {
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            if (!prefsR.aiAllowRunner().get()) {
                ToolResult("runner_start", "ЗАПРЕЩЕНО настройками: включите «Доступ агента к ранеру» в ⚙ вкладки AI")
            } else {
                val model = call.args.optString("model").ifBlank { "qwen2.5-0.5b" }
                val osArg = call.args.optString("os").ifBlank { "linux" }
                var last = ""
                val session = RunnerLlm.startSession(context, model, { st -> last = st }, osArg)
                if (session != null) {
                    ToolResult("runner_start", "Сессия запущена: ${session.model} @${session.os}, url=${session.url}")
                } else {
                    ToolResult("runner_start", "Не удалось запустить: $last")
                }
            }
        }

        "github_api" -> {
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            if (!prefsR.aiAllowGithub().get()) {
                ToolResult("github_api", "ЗАПРЕЩЕНО настройками: включите «Доступ агента к GitHub» в ⚙ вкладки AI")
            } else {
                val token = prefsR.githubPat().get()
                val path = call.args.optString("path")
                // Раньше был только GET, поэтому агент не мог завести issue,
                // оставить комментарий или открыть PR — «работать с моими
                // репозиториями» означало только смотреть.
                val method = call.args.optString("method", "GET").uppercase()
                val body = call.args.optString("body").ifBlank { null }
                when {
                    token.isBlank() -> ToolResult("github_api", "PAT не привязан: задайте его в ⚙ вкладки AI")
                    !path.startsWith("/") -> ToolResult("github_api", "ОШИБКА: path должен начинаться с /")
                    method !in ALLOWED_GITHUB_METHODS -> ToolResult(
                        "github_api",
                        "ОШИБКА: метод $method не поддерживается (${ALLOWED_GITHUB_METHODS.joinToString(", ")})",
                        status = "error",
                    )

                    body != null && runCatching { org.json.JSONObject(body) }.isFailure -> ToolResult(
                        "github_api",
                        "ОШИБКА: body должен быть корректным JSON",
                        status = "error",
                    )

                    else -> {
                        val resp = AiGithub.api(token, path, method, body)
                        // Метод и путь в ответе: запись в репозиторий должна быть
                        // видна читателю в логе хода, а не только в аргументах.
                        val head = if (method == "GET") "HTTP ${resp.code}" else "$method $path → HTTP ${resp.code}"
                        ToolResult(
                            "github_api",
                            "$head\n" + resp.body.take(1200),
                            status = if (resp.ok) "ok" else "error",
                        )
                    }
                }
            }
        }

        // Что ранер делал: прогоны сценариев и пошаговые ленты задач.
        "runner_runs" -> {
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            val token = prefsR.githubPat().get()
            if (token.isBlank()) {
                ToolResult("runner_runs", "PAT не привязан: задайте его в ⚙ вкладки AI")
            } else {
                val repo = call.args.optString("repo").ifBlank { AiGithub.DEFAULT_REPO }
                val list = AiGithub.runs(token, repo)
                ToolResult("runner_runs", AiGithub.renderRuns(list))
            }
        }

        "runner_events" -> {
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            val token = prefsR.githubPat().get()
            val runId = call.args.optString("run_id").trim().toLongOrNull()
            when {
                token.isBlank() -> ToolResult("runner_events", "PAT не привязан: задайте его в ⚙ вкладки AI")
                runId == null || runId <= 0 -> ToolResult(
                    "runner_events",
                    "ОШИБКА: нужен run_id — число из runner_runs",
                    status = "error",
                )

                else -> {
                    val repo = call.args.optString("repo").ifBlank { AiGithub.DEFAULT_REPO }
                    ToolResult("runner_events", AiGithub.renderJobs(AiGithub.jobs(token, runId, repo)))
                }
            }
        }

        "repo_pulls" -> {
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            val token = prefsR.githubPat().get()
            if (token.isBlank()) {
                ToolResult("repo_pulls", "PAT не привязан: задайте его в ⚙ вкладки AI")
            } else {
                val repo = call.args.optString("repo").ifBlank { AiGithub.DEFAULT_REPO }
                ToolResult("repo_pulls", AiGithub.renderPulls(AiGithub.openPulls(token, repo)))
            }
        }

        "plugin_create", "plugin_edit" -> {
            val name = call.args.optString("name")
            val existing = AiPlugins.get(context, name)
            if (call.name == "plugin_edit" && existing == null) {
                ToolResult(call.name, "Плагин «$name» не найден — сначала plugin_create")
            } else {
                val p = AiPlugins.Plugin(
                    name = name,
                    kind = call.args.optString("kind").ifBlank { existing?.kind ?: "prompt" },
                    description = call.args.optString("description").ifBlank { existing?.description.orEmpty() },
                    template = call.args.optString("template").ifBlank { existing?.template.orEmpty() },
                    method = call.args.optString("method").ifBlank { existing?.method ?: "GET" },
                    body = call.args.optString("body").ifBlank { existing?.body.orEmpty() },
                    headers = existing?.headers ?: emptyMap(),
                )
                if (p.template.isBlank()) {
                    ToolResult(call.name, "ОШИБКА: пустой template")
                } else if (AiPlugins.save(context, p)) {
                    ToolResult(call.name, "Плагин «${p.name}» (${p.kind}) сохранён. Вызывай: @tool ${p.name} {\"query\":\"...\"} или {\"input\":\"...\"}")
                } else {
                    ToolResult(call.name, "ОШИБКА: имя занято встроенным инструментом или некорректно")
                }
            }
        }

        "plugin_delete" -> {
            val name = call.args.optString("name")
            ToolResult("plugin_delete", if (AiPlugins.delete(context, name)) "Плагин «$name» удалён" else "Плагин «$name» не найден")
        }

        "plugin_list" -> {
            val ps = AiPlugins.list(context)
            ToolResult(
                "plugin_list",
                if (ps.isEmpty()) "Плагинов нет" else ps.joinToString("\n") { "• ${it.name} (${it.kind}) — ${it.description.take(80)}" },
            )
        }

        // Навыки: рецепты из встроенных инструментов, которые агент создаёт
        // сам. Читатель просил «инструмент, которого нет», и не хочет ждать
        // ручной правки. Исполняется только то, что уже есть в приложении.
        "skill_create" -> {
            val raw = call.args.optString("json").ifBlank { call.args.toString() }
            val parsed = AiSkills.parse(raw)
            when {
                parsed == null -> ToolResult(
                    "skill_create",
                    "ОШИБКА: нужен JSON с полями name, description и steps " +
                        "[{\"tool\":\"имя_инструмента\",\"args\":{...}}] — минимум один шаг",
                    status = "error",
                )

                !AiSkills.save(context, parsed) -> ToolResult(
                    "skill_create",
                    "ОШИБКА: не удалось сохранить навык «${parsed.name}»",
                    status = "error",
                )

                else -> ToolResult(
                    "skill_create",
                    "Навык «${parsed.name}» создан, шагов: ${parsed.steps.size}. " +
                        "Запуск: skill_run {\"name\":\"${parsed.name}\",\"input\":\"...\"}. " +
                        "В аргументах шага {вход} заменяется на input.",
                )
            }
        }

        "skill_list" -> {
            val list = AiSkills.list(context)
            ToolResult(
                "skill_list",
                if (list.isEmpty()) {
                    "Навыков нет"
                } else {
                    list.joinToString("\n") { "• ${it.name} — ${it.description} (шагов: ${it.steps.size})" }
                },
            )
        }

        "skill_run" -> {
            val name = call.args.optString("name").trim()
            val input = call.args.optString("input")
            val skill = AiSkills.get(context, name)
            when {
                skill == null -> ToolResult(
                    "skill_run",
                    "ОШИБКА: навык «$name» не найден. Список: skill_list",
                    status = "error",
                )

                else -> {
                    val steps = skill.withInput(input).steps
                    val lines = mutableListOf<String>()
                    val produced = mutableListOf<File>()
                    var failed = false
                    for ((index, step) in steps.withIndex()) {
                        // Навык внутри навыка — это способ зациклить ход и
                        // упереться в лимит шагов. Один уровень вложенности
                        // не даёт, и пользы от него тут нет.
                        if (step.tool == "skill_run") {
                            failed = true
                            lines += "${index + 1}/${steps.size} skill_run — ОТКАЗАНО: навык не может вызывать навык"
                            break
                        }
                        // Шаг за шагом: следующий может работать с файлом,
                        // который создал предыдущий, а после ошибки — уже нет.
                        val r = runCatching { execute(context, AiSkills.toCall(step), chatFn, mangaId) }
                            .getOrElse { e ->
                                AiAgent.ToolResult(step.tool, "ОШИБКА: ${e.message?.take(160)}", status = "error")
                            }
                        produced += listOfNotNull(r.fileProduced)
                        val isError = r.status == "error"
                        lines += "${index + 1}/${steps.size} ${step.tool}" +
                            (if (isError) " — ОШИБКА" else " — готово") +
                            ": " + r.output.take(200).replace("\n", " ")
                        if (isError) {
                            failed = true
                            break
                        }
                    }
                    // Последний файл, который создал навык, попадает в карточку
                    // чата: иначе результат не видно, пока не откроешь «Файлы».
                    val header = buildString {
                        append(if (failed) "Навык остановился с ошибкой" else "Навык выполнен")
                        if (produced.isNotEmpty()) append(". Создано файлов: ${produced.size}")
                    }
                    ToolResult(
                        "skill_run",
                        (listOf(header) + lines).joinToString("\n"),
                        produced.lastOrNull(),
                        status = if (failed) "error" else "ok",
                    )
                }
            }
        }

        "write_file" -> {
            val name = call.args.optString("name").ifBlank { "note_${System.currentTimeMillis() / 1000}.txt" }
            val content = call.args.optString("content")
            val f = AiWorkspace.writeText(context, name, content)
            if (f != null) {
                ToolResult("write_file", "Сохранено: ${AiWorkspace.relPath(context, f)} (${f.length()} байт)", f)
            } else {
                ToolResult("write_file", "ОШИБКА: некорректный путь")
            }
        }

        "gen_image" -> {
            val prompt = call.args.optString("prompt").ifBlank { "anime illustration" }
            val f = generateImage(context, prompt)
            if (f != null) {
                ToolResult("gen_image", "Картинка готова: ${AiWorkspace.relPath(context, f)}", f)
            } else {
                ToolResult("gen_image", "ОШИБКА: Pollinations не ответил (сеть?)")
            }
        }

        // Пакетная генерация: читатель просил «несколько фото подряд, в одной
        // папке». По одному gen_image на картинку — это отдельный раунд
        // агентского цикла на каждую, и при пяти картинках модель часто
        // останавливалась на третьей.
        "gen_images" -> {
            val prompts = buildList {
                call.args.optJSONArray("prompts")?.let { arr ->
                    for (i in 0 until arr.length()) add(arr.optString(i))
                }
                if (isEmpty()) {
                    call.args.optString("prompts")
                        .split('\n', ',')
                        .forEach { if (it.isNotBlank()) add(it.trim()) }
                }
            }.filter { it.isNotBlank() }
            if (prompts.isEmpty()) {
                ToolResult("gen_images", "ОШИБКА: передай prompts — массив промптов или строки через запятую", status = "error")
            } else {
                val limit = call.args.optInt("count", prompts.size).coerceIn(1, 12)
                val folder = AiWorkspacePaths.sanitize(
                    call.args.optString("folder").ifBlank { "batch" },
                ).ifBlank { "batch" }
                val todo = if (prompts.size == 1) {
                    // Один промпт и count>1 — «четыре варианта этой картинки».
                    List(limit) { prompts.first() }
                } else {
                    prompts.take(limit)
                }
                val made = mutableListOf<File>()
                val failedLines = mutableListOf<String>()
                todo.forEachIndexed { i, prompt ->
                    val f = generateImage(context, prompt, "images/$folder")
                    if (f != null) {
                        made += f
                    } else {
                        failedLines += "${i + 1}. не получилось: ${prompt.take(40)}"
                    }
                }
                if (made.isEmpty()) {
                    ToolResult(
                        "gen_images",
                        "ОШИБКА: Pollinations не ответил ни разу (нет сети?)\n" + failedLines.joinToString("\n"),
                        status = "error",
                    )
                } else {
                    val lines = mutableListOf(
                        "Создано ${made.size} из ${todo.size} в папке images/$folder:",
                    )
                    made.forEachIndexed { i, f ->
                        lines += "${i + 1}. ${AiWorkspace.relPath(context, f)} (${f.length() / 1024} КБ)"
                    }
                    if (failedLines.isNotEmpty()) {
                        lines += "Не получилось:" + failedLines.joinToString("; ")
                    }
                    ToolResult(
                        "gen_images",
                        lines.joinToString("\n"),
                        made.first(),
                        extraFiles = made.drop(1),
                    )
                }
            }
        }

        "check_site" -> {
            val url = call.args.optString("url")
            ToolResult("check_site", checkSite(url))
        }

        "web_search" -> {
            val query = call.args.optString("query")
            ToolResult("web_search", webSearch(query))
        }

        "web_fetch" -> {
            val url = call.args.optString("url")
            val maxChars = call.args.optInt("maxChars", 4000)
            ToolResult("web_fetch", webFetch(url, maxChars))
        }

        "web_screenshot" -> {
            val url = call.args.optString("url")
            val width = call.args.optInt("width", 1280).coerceIn(320, 2000)
            val question = call.args.optString("question")
            webScreenshot(context, url, width, question)
        }

        "book_recall" -> ToolResult("book_recall", BookKnowledge.render(context, mangaId))

        "reader_actions" -> ToolResult("reader_actions", ReaderAiActions.describe())

        "reader_do" -> {
            val action = call.args.optString("action")
            ToolResult("reader_do", ReaderAiActions.run(action, call.args))
        }

        // Прямые @tool see_page/turn_page/… идут в тот же реестр, что и
        // reader_do: промпт называет их отдельными инструментами, и раньше
        // такие вызовы просто исчезали без результата и без ошибки.
        in READER_ACTION_ALIASES -> ToolResult(
            call.name,
            ReaderAiActions.run(call.name, call.args),
        )

        "book_learn" -> {
            val manga = mangaId
            if (manga == null) {
                ToolResult(
                    "book_learn",
                    "ОШИБКА: книга не задана — правила сохраняются только в читалке",
                    status = "error",
                )
            } else {
                val url = call.args.optString("url").trim()
                val kind = call.args.optString("kind")
                val text = call.args.optString("text").ifBlank { call.args.optString("value") }
                // Какое именно издание/перевод читать. Разные переводы одной
                // манги отличаются словарём и порядком сцен, поэтому агенту
                // надо знать свой экземпляр, а не гадать.
                val edition = call.args.optString("edition").trim()
                // Правило, продиктованное читателем, помечается user=true — иначе
                // в источниках книги нельзя отличить слова пользователя от
                // догадок агента.
                val fromUser = call.args.optBoolean("user", false)
                if (edition.isNotBlank()) {
                    val stored = BookKnowledge.updateProfile(context, manga) {
                        it.edition = BookLearning.sanitize(edition, 300)
                    }.edition
                    ToolResult(
                        name = "book_learn",
                        output = "Издание зафиксировано: $stored. Дальше опирайся на него, " +
                            "а не на другой перевод той же манги.",
                        status = "ok",
                    )
                } else if (url.isBlank() && text.isBlank()) {
                    ToolResult(
                        "book_learn",
                        "ОШИБКА: нужен url страницы или text правила",
                        status = "error",
                    )
                } else if (url.isNotBlank()) {
                    if (!BookLearning.isHttpUrl(url)) {
                        ToolResult(
                            name = "book_learn",
                            output = "ОШИБКА: нужен обычный http(s)-адрес страницы",
                            status = "error",
                        )
                    } else {
                        // Страница целиком в знания не пишется: она приходит как
                        // источник, а правила модель выводит следующим вызовом.
                        val fetched = BookLearning.sanitize(webFetch(url, 20_000))
                        if (fetched.startsWith("ОШИБКА") || fetched.endsWith("— страница без текста")) {
                            ToolResult(
                                "book_learn",
                                "ОШИБКА чтения источника: $fetched",
                                status = "error",
                            )
                        } else {
                            val fresh = BookKnowledge.addSource(context, manga, url)
                            ToolResult(
                                name = "book_learn",
                                output = buildString {
                                    append(if (fresh) "Источник прочитан и записан: " else "Источник уже был записан: ")
                                    append(url)
                                    append(". Теперь выведи из него конкретные правила книги и ")
                                    append("зафиксируй их вызовами book_learn с kind (reading_order, region, ")
                                    append("bubble, transcription, voice) — без них авточтение ведёт себя ")
                                    append("по общему пресету.")
                                },
                                status = "ok",
                            )
                        }
                    }
                } else {
                    val author = if (fromUser) BookLearning.AUTHOR_USER else BookLearning.AUTHOR_AGENT
                    val scope = call.args.optString("scope").trim().lowercase()
                    // scope=series|global — знание нужно не только этой книге:
                    // имена героев, термины и перевод помогают в других томах.
                    // Правила чтения (порядок, области) остаются книжными.
                    if (scope == BookSharedMemory.SCOPE_SERIES || scope == BookSharedMemory.SCOPE_GLOBAL) {
                        val shared = BookSharedMemory.remember(
                            context = context,
                            scope = scope,
                            title = BookKnowledge.load(context, manga).title,
                            kind = kind.ifBlank { BookLearning.KIND_FACT },
                            text = text,
                            source = if (author == BookLearning.AUTHOR_USER) BookLearning.AUTHOR_USER else "",
                            author = author,
                        )
                        ToolResult(
                            name = "book_learn",
                            output = if (shared > 0) {
                                val where = if (scope == BookSharedMemory.SCOPE_SERIES) "в память серии" else "в общую память"
                                "Записано $where: $shared (${BookLearning.kindTitle(kind)}) — пригодится в других книгах"
                            } else {
                                "Такое знание уже есть в общей памяти — не дублирую"
                            },
                            status = "ok",
                        )
                    } else {
                        val stored = BookKnowledge.learn(
                            context = context,
                            mangaId = manga,
                            kind = kind.ifBlank { BookLearning.KIND_ADVICE },
                            text = text,
                            source = if (author == BookLearning.AUTHOR_USER) BookLearning.AUTHOR_USER else "",
                            author = author,
                        )
                        ToolResult(
                            name = "book_learn",
                            output = if (stored > 0) {
                                "Выучено правил: $stored (${BookLearning.kindTitle(kind)})"
                            } else {
                                "Такое правило уже выучено или текст пустой — не дублирую"
                            },
                            status = "ok",
                        )
                    }
                }
            }
        }

        "book_remember" -> {
            val kind = call.args.optString("kind", "fact")
            val value = call.args.optString("text").ifBlank { call.args.optString("value") }
            if (mangaId == null) {
                ToolResult("book_remember", "ОШИБКА: книга не задана — знания сохраняются только в читалке", status = "error")
            } else {
                val ok = BookKnowledge.remember(context, mangaId, kind, value)
                ToolResult(
                    name = "book_remember",
                    output = if (ok) "Сохранено в знания о книге: $kind" else "Пустое значение — не сохранял",
                    status = if (ok) "ok" else "error",
                )
            }
        }

        "list_ext" -> ToolResult("list_ext", listExtensions())

        "filter_ext" -> {
            val hide = call.args.optString("hide")
            val show = call.args.optString("show")
            ToolResult("filter_ext", filterExtensions(hide, show))
        }

        "find_manga" -> {
            val title = call.args.optString("title")
            ToolResult("find_manga", findManga(title))
        }

        "edit_file" -> {
            val name = call.args.optString("name")
            val find = call.args.optString("find")
            val replace = call.args.optString("replace")
            val f = AiWorkspace.resolve(context, name)
            when {
                f == null || !f.isFile -> ToolResult("edit_file", "ОШИБКА: файл не найден: $name")
                find.isBlank() -> ToolResult("edit_file", "ОШИБКА: пустой параметр find")
                else -> {
                    val original = f.readText()
                    if (!original.contains(find)) {
                        ToolResult("edit_file", "Текст «${find.take(60)}» не найден в $name — файл не тронут")
                    } else {
                        AiWorkspace.backup(context, f) // бэкап ДО правки
                        f.writeText(original.replace(find, replace))
                        ToolResult("edit_file", "Заменено в $name (бэкап в backups/)", f)
                    }
                }
            }
        }

        "append_file" -> {
            val name = call.args.optString("name")
            val content = call.args.optString("content")
            val f = AiWorkspace.resolve(context, name)
            if (f == null) {
                ToolResult("append_file", "ОШИБКА: некорректный путь")
            } else {
                if (f.isFile) AiWorkspace.backup(context, f)
                f.parentFile?.mkdirs()
                f.appendText(if (f.isFile && f.length() > 0) "\n$content" else content)
                ToolResult("append_file", "Дописано в $name (теперь ${f.length()} байт)", f)
            }
        }

        "read_file" -> {
            val name = call.args.optString("name")
            val f = AiWorkspace.resolve(context, name)
            if (f?.isFile == true && f.extension.lowercase() in IMAGE_EXTENSIONS) {
                // Раньше здесь был отказ: «я не вижу картинки, опиши словами».
                // Но движок умеет отвечать на вопросы об изображении — тем же
                // путём страница уходит в see_page. Отказывать, когда глаза
                // есть, значило отнимать у агента ровно то, за чем он и шёл.
                val question = call.args.optString("question")
                    .ifBlank { "Что изображено на картинке? Есть ли на ней текст, и какой?" }
                val answer = askAboutImageFile(f, question)
                ToolResult(
                    "read_file",
                    if (answer != null) {
                        "«$name» — изображение (${f.length() / 1024} КБ). Ответ vision-модели на вопрос " +
                            "«$question»:\n$answer"
                    } else {
                        "«$name» — изображение (${f.length() / 1024} КБ), но выбранный OCR-движок не умеет " +
                            "отвечать на вопросы о картинке (нужен vision: ZenFree, Google AI или OpenRouter). " +
                            "Переключи движок и спроси again, или попроси пользователя описать словами."
                    },
                    status = if (answer != null) "ok" else "error",
                )
            } else if (f?.isFile == true) {
                val text = f.readText()
                // Хвост файла важнее начала: продолжение книги пишется с конца
                val slice = if (text.length > 3000) "…" + text.takeLast(3000) else text
                ToolResult("read_file", "Содержимое $name (${f.length()} байт):\n$slice")
            } else {
                ToolResult("read_file", "Файл не найден: $name")
            }
        }

        // Вопрос к картинке из workspace. see_page работает только с открытой
        // страницей, а читатель просил, чтобы агент видел и свои файлы.
        "see_image" -> {
            val name = call.args.optString("name")
            val f = AiWorkspace.resolve(context, name)
            val question = call.args.optString("question")
            when {
                f == null || !f.isFile -> ToolResult("see_image", "ОШИБКА: файл не найден: $name", status = "error")
                question.isBlank() -> ToolResult(
                    "see_image",
                    "ОШИБКА: нужен question — спроси у картинки конкретное (что на ней, какой текст, что исправить)",
                    status = "error",
                )

                f.extension.lowercase() !in IMAGE_EXTENSIONS -> ToolResult(
                    "see_image",
                    "«$name» — не картинка (${f.extension.ifBlank { "без расширения" }}). " +
                        "Посмотреть можно только изображение: ${IMAGE_EXTENSIONS.joinToString(", ")}",
                    status = "error",
                )

                else -> {
                    val answer = askAboutImageFile(f, question)
                    if (answer == null) {
                        ToolResult(
                            "see_image",
                            "Выбранный OCR-движок не умеет отвечать на вопросы о картинке " +
                                "(нужен vision: ZenFree, Google AI или OpenRouter). Картинка не тронута.",
                            status = "error",
                        )
                    } else {
                        ToolResult("see_image", "«$name»: $answer")
                    }
                }
            }
        }

        // Обзор папки целиком: агент должен видеть, что в ней есть, прежде чем
        // строить план. Раньше для этого надо было угадывать имена файлов.
        "workspace_list" -> {
            val root = AiWorkspace.root(context)
            val path = call.args.optString("path")
            val limit = call.args.optInt("limit", 200).coerceIn(1, 2_000)
            ToolResult("workspace_list", AiWorkspaceSurvey.renderTree(root, path, limit))
        }

        // Чтение пачкой «по очереди»: книга разбита на главы, и агент должен
        // прочитать их в правильном порядке, а не по одному файлу за ход.
        "read_many" -> {
            val root = AiWorkspace.root(context)
            val names = call.args.optJSONArray("names")
            val list = buildList {
                if (names != null) {
                    for (i in 0 until names.length()) add(names.optString(i))
                } else {
                    // Файлы передали строкой через запятую — модели так пишут
                    // чаще, чем массивом, и раньше такой вызов молча ничего
                    // не читал.
                    call.args.optString("names").split(',').forEach {
                        if (it.isNotBlank()) add(it.trim())
                    }
                }
            }.filter { it.isNotBlank() }
            if (list.isEmpty()) {
                ToolResult("read_many", "ОШИБКА: передай names — массив имён или строку через запятую", status = "error")
            } else {
                ToolResult("read_many", AiWorkspaceSurvey.readMany(root, list))
            }
        }

        "zip_workspace" -> {
            val f = AiWorkspace.zipAll(context)
            if (f == null) {
                ToolResult(
                    "zip_workspace",
                    "ОШИБКА: не удалось собрать архив (нет места или хранилище недоступно)",
                    status = "error",
                )
            } else {
                ToolResult("zip_workspace", "Архив: ${AiWorkspace.relPath(context, f)} (${f.length() / 1024} КБ)", f)
            }
        }

        "provider_create", "provider_edit" -> {
            val id = call.args.optString("id")
            if (id.isBlank()) {
                ToolResult(call.name, "ОШИБКА: нужен id провайдера (например, ollama)", status = "error")
            } else {
                val existing = AiProviders.userProvider(context, id)
                if (call.name == "provider_edit" && existing == null) {
                    ToolResult(call.name, "Провайдер «$id» не найден — сначала provider_create", status = "error")
                } else {
                    // Правка меняет только переданные поля: иначе edit затирал бы
                    // ключ и модель, которые пользователь не трогал.
                    val spec = AiProviders.Spec(
                        id = id,
                        title = call.args.optString("title").ifBlank { existing?.title ?: id },
                        summary = call.args.optString("summary").ifBlank { existing?.summary.orEmpty() },
                        baseUrl = call.args.optString("baseUrl").ifBlank { existing?.baseUrl.orEmpty() },
                        model = call.args.optString("model").ifBlank { existing?.model.orEmpty() },
                        apiKey = if (call.args.has("apiKey")) call.args.optString("apiKey") else existing?.apiKey.orEmpty(),
                    )
                    val error = AiProviders.validate(spec)
                    when {
                        error != null -> ToolResult(call.name, "ОШИБКА: $error", status = "error")
                        AiProviders.save(context, spec) -> ToolResult(
                            call.name,
                            "Провайдер «${spec.title}» (${spec.model} @ ${spec.baseUrl}) сохранён. " +
                                "Выберите его в настройках озвучки/AI-чата.",
                        )
                        else -> ToolResult(call.name, "ОШИБКА: не удалось записать провайдер", status = "error")
                    }
                }
            }
        }

        "provider_delete" -> {
            val id = call.args.optString("id")
            when {
                id.isBlank() -> ToolResult(call.name, "ОШИБКА: нужен id провайдера", status = "error")
                AiProviders.delete(context, id) ->
                    ToolResult(call.name, "Провайдер «$id» отключён")
                else -> ToolResult(
                    call.name,
                    "ОШИБКА: провайдер «$id» не найден (встроенные zen/openrouter не удаляются)",
                    status = "error",
                )
            }
        }

        "provider_list" -> {
            val specs = AiProviders.all(context)
            val prefsR = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            val selected = prefsR.aiProvider().get()
            ToolResult(
                "provider_list",
                buildString {
                    appendLine("Провайдеров: ${specs.size} (выбран: $selected)")
                    specs.forEach { spec ->
                        append("• ${spec.id} — ${spec.title}: ${spec.model} @ ${spec.baseUrl}")
                        if (spec.builtIn) append(" [встроенный]")
                        if (spec.apiKey.isNotBlank()) append(" [ключ задан]")
                        appendLine()
                    }
                }.trim(),
            )
        }

        "ui_action_create", "ui_action_edit" -> {
            val id = call.args.optString("id")
            if (id.isBlank()) {
                ToolResult(call.name, "ОШИБКА: нужен id кнопки", status = "error")
            } else {
                val existing = UiActionRegistry.list(context).firstOrNull { it.id == id }
                if (call.name == "ui_action_edit" && existing == null) {
                    ToolResult(call.name, "Кнопка «$id» не найдена — сначала ui_action_create", status = "error")
                } else {
                    val placement = mihon.data.ui.UiPlacement.fromId(
                        call.args.optString("placement").ifBlank { existing?.placement?.id.orEmpty() },
                    )
                    val effect = mihon.data.ui.UiEffect.fromId(
                        call.args.optString("effect").ifBlank { existing?.effect?.id.orEmpty() },
                    )
                    when {
                        placement == null -> ToolResult(
                            call.name,
                            "ОШИБКА: неизвестное placement. Можно: " +
                                mihon.data.ui.UiPlacement.entries.joinToString { it.id },
                            status = "error",
                        )
                        effect == null -> ToolResult(
                            call.name,
                            "ОШИБКА: неизвестный effect. Можно: " +
                                mihon.data.ui.UiEffect.entries.joinToString { it.id },
                            status = "error",
                        )
                        else -> {
                            val spec = mihon.data.ui.UiActionSpec(
                                id = id,
                                title = call.args.optString("title").ifBlank { existing?.title ?: id },
                                placement = placement,
                                effect = effect,
                                value = call.args.optString("value").ifBlank { existing?.value.orEmpty() },
                                order = if (call.args.has("order")) call.args.optInt("order") else existing?.order ?: 100,
                            )
                            val error = mihon.data.ui.UiActions.validate(spec)
                            when {
                                error != null -> ToolResult(call.name, "ОШИБКА: $error", status = "error")
                                UiActionRegistry.save(context, spec) -> ToolResult(
                                    call.name,
                                    "Кнопка «${spec.title}» добавлена (${placement.title}, " +
                                        "эффект ${effect.title} = ${spec.value}). Откройте меню читалки.",
                                )
                                else -> ToolResult(call.name, "ОШИБКА: не удалось записать кнопку", status = "error")
                            }
                        }
                    }
                }
            }
        }

        "ui_action_delete" -> {
            val id = call.args.optString("id")
            when {
                id.isBlank() -> ToolResult(call.name, "ОШИБКА: нужен id кнопки", status = "error")
                UiActionRegistry.delete(context, id) -> ToolResult(call.name, "Кнопка «$id» убрана")
                else -> ToolResult(
                    call.name,
                    "ОШИБКА: кнопка «$id» не найдена (встроенные не удаляются)",
                    status = "error",
                )
            }
        }

        "ui_action_list" -> {
            val actions = UiActionRegistry.all(context)
            ToolResult(
                "ui_action_list",
                buildString {
                    appendLine("Кнопок: ${actions.size}")
                    actions.forEach { a ->
                        append("• ${a.id} — ${a.title}: ${a.placement.id}/${a.effect.id}=${a.value}")
                        if (a.builtIn) append(" [встроенная]")
                        appendLine()
                    }
                    appendLine("Эффекты: " + mihon.data.ui.UiEffect.entries.joinToString { "${it.id} (${it.title})" })
                    append("Размещения: " + mihon.data.ui.UiPlacement.entries.joinToString { it.id })
                },
            )
        }

        "ui_tab_hide", "ui_tab_show" -> {
            val id = call.args.optString("id")
            val hide = call.name == "ui_tab_hide"
            val ok = when {
                id.isBlank() -> false
                hide -> UiTabRegistry.hide(context, id)
                else -> UiTabRegistry.show(context, id)
            }
            when {
                id.isBlank() -> ToolResult(
                    call.name,
                    "ОШИБКА: нужен id вкладки. Доступны: " + UiTabs.IDS.joinToString(),
                    status = "error",
                )
                ok -> ToolResult(
                    call.name,
                    "Вкладка «${UiTab.fromId(id)?.title ?: id}» " +
                        (if (hide) "скрыта" else "возвращена") +
                        ". Панель обновилась сразу; если пользователь был на этой вкладке, " +
                        "содержимое останется до перехода на другую.",
                )
                hide && UiTabs.validate(id) != null ->
                    ToolResult(call.name, "ОШИБКА: " + UiTabs.validate(id), status = "error")
                UiTab.fromId(id) == null -> ToolResult(
                    call.name,
                    "ОШИБКА: неизвестная вкладка «$id». Доступны: " + UiTabs.IDS.joinToString(),
                    status = "error",
                )
                // Id годный, валидация прошла — значит не записался файл.
                else -> ToolResult(
                    call.name,
                    "ОШИБКА: не удалось записать список вкладок (workspace недоступен?)",
                    status = "error",
                )
            }
        }

        "ui_tab_list" -> {
            val hidden = UiTabRegistry.hidden(context)
            ToolResult(
                "ui_tab_list",
                buildString {
                    appendLine("Вкладок: ${UiTab.entries.size}, скрыто: ${hidden.size}")
                    UiTab.entries.forEach { tab ->
                        append("• ${tab.id} — ${tab.title}")
                        if (tab.pinned) append(" [закреплена]")
                        if (tab.id in hidden) append(" [скрыта]")
                        appendLine()
                    }
                    append("Скрыть можно: " + UiTabs.IDS.filterNot { it in UiTabs.PROTECTED_IDS }.joinToString())
                },
            )
        }

        else -> {
            // Самодельный плагин? Исполняем его (http или prompt через текущий бэкенд)
            val plugin = AiPlugins.get(context, call.name)
            if (plugin != null) {
                ToolResult(call.name, AiPlugins.execute(context, plugin, call.args, chatFn))
            } else {
                ToolResult(call.name, "Неизвестный инструмент")
            }
        }
    }

    // ---- Реализации инструментов ----

    /** Pollinations: бесплатная генерация картинок без ключа. */
    suspend fun generateImage(context: Context, prompt: String, subdir: String = ""): File? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://image.pollinations.ai/prompt/" +
                URLEncoder.encode(prompt.take(400), "UTF-8").replace("+", "%20") +
                "?width=768&height=768&nologo=true"
            val conn = AiAssistant.openConnection(url)
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "Yomikai/1.0")
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size < 1000) return@runCatching null
            val f = AiWorkspace.newImageFile(context, prompt, subdir)
            f.writeBytes(bytes)
            f
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Pollinations failed" }
            null
        }
    }

    /** Реальная проверка сайта: HTTP-статус, редиректы, время ответа. */
    private fun checkSite(rawUrl: String): String {
        if (rawUrl.isBlank()) return "ОШИБКА: пустой URL"
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        return runCatching {
            val started = System.currentTimeMillis()
            val conn = AiAssistant.openConnection(url)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            )
            val code = conn.responseCode
            val took = System.currentTimeMillis() - started
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            when {
                code in 200..299 -> "$url — РАБОТАЕТ (HTTP $code, ${took}мс)"
                code in 300..399 -> "$url — редирект на ${location ?: "?"} (HTTP $code)"
                code == 403 -> "$url — HTTP 403: вероятно, Cloudflare-защита; в браузере может открыться"
                else -> "$url — НЕ работает (HTTP $code)"
            }
        }.getOrElse { "$url — НЕ отвечает: ${it.message?.take(100)}" }
    }

    private fun webSearch(rawQuery: String): String {
        val query = rawQuery.trim()
        if (query.isBlank()) return "ОШИБКА: пустой запрос"
        return runCatching {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val html = fetchText(url, 120_000)
            val rows = Regex(
                "(?is)<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            ).findAll(html).take(8).map { m ->
                val href = htmlUnescape(m.groupValues[1])
                val title = stripHtml(m.groupValues[2])
                val real = if (href.contains("uddg=")) {
                    runCatching {
                        java.net.URLDecoder.decode(
                            href.substringAfter("uddg=").substringBefore("&"),
                            "UTF-8",
                        )
                    }.getOrDefault(href)
                } else {
                    href
                }
                "• $title\n  $real"
            }.toList()
            if (rows.isEmpty()) {
                "Ничего не найдено по запросу «$query»"
            } else {
                "Результаты по «$query»:\n" + rows.joinToString("\n")
            }
        }.getOrElse { "ОШИБКА поиска: ${it.message?.take(120)}" }
    }

    private fun webFetch(rawUrl: String, maxChars: Int): String {
        val url = rawUrl.trim()
        if (url.isBlank()) return "ОШИБКА: пустой URL"
        val normalized = if (url.startsWith("http")) url else "https://$url"
        return runCatching {
            val html = fetchText(normalized, maxChars.coerceIn(500, 60_000))
            val text = stripHtml(
                html
                    .replace(Regex("(?is)<script.*?</script>"), " ")
                    .replace(Regex("(?is)<style.*?</style>"), " "),
            ).replace(Regex("\\s{2,}"), " ").trim()
            if (text.isBlank()) {
                "$normalized — страница без текста (вероятно, JS-сайт)"
            } else {
                text.take(maxChars.coerceIn(500, 60_000))
            }
        }.getOrElse { "ОШИБКА загрузки: ${it.message?.take(120)}" }
    }

    /**
     * Скриншот ВЕБ-страницы. Без него «посмотри страницу и сделай похожее»
     * было нечем выполнить: `web_fetch` отдаёт только текст, а `see_page`
     * смотрит на страницу книги в читалке — то есть на совсем другую картинку.
     *
     * На устройстве нет headless-браузера, поэтому рендерим внешним сервисом
     * (mshots/thum.io, без ключа) и сразу спрашиваем vision — иначе агент
     * получил бы файл и должен был догадаться, что с ним делать.
     */
    private suspend fun webScreenshot(
        context: Context,
        rawUrl: String,
        width: Int,
        question: String,
    ): ToolResult {
        val url = rawUrl.trim()
        if (url.isBlank()) return ToolResult("web_screenshot", "ОШИБКА: пустой URL", status = "error")
        if (!url.startsWith("http")) return ToolResult("web_screenshot", "ОШИБКА: нужен полный URL с http(s)", status = "error")
        val enc = java.net.URLEncoder.encode(url, "UTF-8")
        val services = listOf(
            "https://s.wordpress.com/mshots/v1/$enc?w=$width" to "mshots",
            "https://image.thum.io/get/width/$width/noanimate/$enc" to "thum.io",
        )
        var lastError = "сервис не ответил"
        for ((endpoint, label) in services) {
            val bytes = runCatching { downloadBytes(endpoint, 30_000) }.getOrNull()
            if (bytes == null || bytes.size < 2_000 || !looksLikeImage(bytes)) {
                lastError = if (bytes != null && bytes.size < 2_000) {
                    "$label вернул ${bytes.size} Б — это не скриншот (страница отдала заглушку или редирект)"
                } else {
                    "$label недоступен"
                }
                continue
            }
            val f = AiWorkspace.newImageFile(context, "web", sanitizeForFile(url))
            f.writeBytes(bytes)
            val rel = AiWorkspace.relPath(context, f)
            val answer = askAboutImageFile(
                f,
                question.ifBlank { "Опиши эту веб-страницу: вёрстка, цвета, шрифты, композиция, и что именно можно повторить." },
            )
            return ToolResult(
                "web_screenshot",
                buildString {
                    append("Скриншот $url сохранён: ").append(rel)
                    append(" (").append(bytes.size / 1024).append(" КБ, ширина ").append(width).append(")")
                    if (answer != null) {
                        append("\n\nЧто на скриншоте (vision-модель):\n").append(answer)
                    } else {
                        append(
                            "\n\nВопрос к vision-модели не задан: нужен движок ZenFree, Google AI или OpenRouter. " +
                                "Посмотреть картинку позже можно see_image {\"name\":\"" + rel + "\"," +
                                "\"question\":\"...\"}.",
                        )
                    }
                },
                fileProduced = f,
            )
        }
        return ToolResult(
            "web_screenshot",
            "ОШИБКА: скриншот не получен ($lastError). Для текста страницы используй web_fetch — " +
                "он отдаст текст без разметки.",
            status = "error",
        )
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean =
        (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) || // PNG
            (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || // JPEG
            (bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) || // RIFF/WEBP
            (bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) // GIF

    private fun downloadBytes(url: String, timeoutMs: Int): ByteArray? {
        val conn = AiAssistant.openConnection(url)
        conn.connectTimeout = 12_000
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        )
        return try {
            conn.getInputStream().use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sanitizeForFile(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(30)
            .ifBlank { "page" }

    private fun fetchText(url: String, maxChars: Int): String {
        val conn = AiAssistant.openConnection(url)
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
        )
        conn.setRequestProperty("Accept-Language", "ru,en;q=0.8")
        return try {
            val stream = conn.getInputStream()
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.length > maxChars) text.take(maxChars) else text
        } finally {
            conn.disconnect()
        }
    }

    private fun stripHtml(raw: String): String {
        val unescaped = htmlUnescape(raw)
        return unescaped
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</(p|div|li|tr|h[1-6])>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .trim()
    }

    private fun htmlUnescape(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun listExtensions(): String {
        val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
        if (sources.isEmpty()) return "Расширения не установлены"
        val disabled = sourcePrefs.disabledSources.get()
        return sources.take(60).joinToString("\n") { s ->
            val domain = (s as? HttpSource)?.baseUrl ?: "локальный"
            val state = if (s.id.toString() in disabled) "СКРЫТ" else "виден"
            "• ${s.name} [${s.lang}] — $domain — $state (id=${s.id})"
        }
    }

    /** Скрыть/показать источники по подстроке имени, языка или домена. */
    private fun filterExtensions(hide: String, show: String): String {
        val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
        val pref = sourcePrefs.disabledSources
        val current = pref.get().toMutableSet()
        val log = StringBuilder()
        fun matches(s: CatalogueSource, q: String): Boolean {
            val d = (s as? HttpSource)?.baseUrl.orEmpty()
            return s.name.contains(q, true) || s.lang.contains(q, true) || d.contains(q, true)
        }
        if (hide.isNotBlank()) {
            val victims = sources.filter { matches(it, hide) }
            victims.forEach { current += it.id.toString() }
            log.append("Скрыто ${victims.size}: ${victims.joinToString { it.name }.take(200)}\n")
        }
        if (show.isNotBlank()) {
            val victims = sources.filter { matches(it, show) }
            victims.forEach { current -= it.id.toString() }
            log.append("Показано ${victims.size}: ${victims.joinToString { it.name }.take(200)}\n")
        }
        pref.set(current)
        return log.toString().ifBlank { "Ничего не найдено по запросу" }
    }

    /**
     * Реальный поиск тайтла по включённым источникам: до 8 источников,
     * каждому 12с. Возвращает, где тайтл реально находится.
     */
    private suspend fun findManga(title: String): String {
        if (title.isBlank()) return "ОШИБКА: пустое название"
        val disabled = sourcePrefs.disabledSources.get()
        val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id.toString() !in disabled }
            .take(8)
        if (sources.isEmpty()) return "Нет включённых источников"
        val sb = StringBuilder()
        for (s in sources) {
            val found = withTimeoutOrNull(12_000) {
                runCatching {
                    s.getSearchManga(1, title, eu.kanade.tachiyomi.source.model.FilterList()).mangas
                }.getOrNull()
            }
            when {
                found == null -> sb.append("• ${s.name} [${s.lang}] — таймаут/ошибка\n")
                found.isEmpty() -> sb.append("• ${s.name} [${s.lang}] — не найдено\n")
                else -> {
                    val top = found.take(3).joinToString("; ") { it.title.take(60) }
                    sb.append("• ${s.name} [${s.lang}] — НАЙДЕНО ${found.size}: $top\n")
                }
            }
        }
        return sb.toString()
    }

    /** OCR картинки-вложения текущим движком распознавания (с фолбэками). */
    suspend fun ocrAttachment(file: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options()
            var bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
            if (bmp.width > 1600) {
                val h = bmp.height * 1600 / bmp.width
                val scaled = Bitmap.createScaledBitmap(bmp, 1600, h, true)
                bmp.recycle()
                bmp = scaled
            }
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val image = OcrImage(bmp.width, bmp.height, pixels)
            bmp.recycle()
            val repo = Injekt.get<OcrRepository>()
            repo.recognizeText(image).trim().ifBlank { null }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "AI attachment OCR failed" }
            null
        }
    }

    /**
     * Спросить у выбранной vision-модели про кадр страницы.
     *
     * Это «глаза» оркестратора: текст страницы он и так читает через OCR, а
     * здесь получает возможность понять изображение — кто нарисован, что
     * происходит, как разбит на панели. null означает «движок не умеет
     * отвечать на вопросы» (локальный распознаватель) или ответ не пришёл;
     * оба случая не фатальны, вызывающий код продолжает разговор.
     */
    suspend fun askAboutPage(bitmap: Bitmap, question: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (bitmap.isRecycled) return@runCatching null
            val maxSide = maxOf(bitmap.width, bitmap.height)
            val source = if (maxSide > VISION_MAX_SIDE) {
                val scale = VISION_MAX_SIDE.toFloat() / maxSide
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                null
            }
            try {
                val scaled = source ?: bitmap
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                val image = OcrImage(scaled.width, scaled.height, pixels)
                Injekt.get<OcrRepository>().askAboutImage(image, question)?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } finally {
                if (source != null && !source.isRecycled) source.recycle()
            }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "AI page vision failed" }
            null
        }
    }

    private const val VISION_MAX_SIDE = 1600

    /**
     * Спросить vision-модель про файл-картинку из workspace.
     *
     * Отдельная функция, а не переиспользование askAboutPage, потому что там
     * уже есть готовая [android.graphics.Bitmap], а тут файл: его надо
     * декодировать, уменьшить (иначе в контекст уйдёт гигабайт пикселей) и
     * обязательно отдать переработанный bitmap — иначе течёт память на каждом
     * вызове. null — движок не vision, или ответ пустой.
     */
    private suspend fun askAboutImageFile(file: File, question: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching null
            if (file.length() > MAX_IMAGE_FILE_BYTES) {
                logcat(LogPriority.WARN) { "see_image: file too large (${file.length()} bytes)" }
                return@runCatching null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                // Выборка сразу по размеру, а не «декодировать в пол и потом
                // уменьшать»: фото с камеры весит 8 МБ, и полная декодировка
                // на телефоне означает OutOfMemory.
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, VISION_MAX_SIDE)
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
            val scaled = scaleDown(decoded, VISION_MAX_SIDE)
            try {
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                Injekt.get<OcrRepository>()
                    .askAboutImage(OcrImage(scaled.width, scaled.height, pixels), question)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } finally {
                // Ссылка на переработанный bitmap не переживает вызов, чистим
                // явно: иначе память утекает на каждом вопросе о картинке.
                if (scaled !== decoded && !scaled.isRecycled) scaled.recycle()
                if (!decoded.isRecycled) decoded.recycle()
            }
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "see_image failed for ${file.name}" }
            null
        }
    }

    /** Файл крупнее этого не отправляем: экономия трафика и времени модели. */
    private const val MAX_IMAGE_FILE_BYTES = 12L * 1024L * 1024L

    /**
     * Степень уменьшения при декодировании. Считается из реального размера:
     * для 12000×9000 это 8, и декодирование сразу даёт 1500×1125 — в
     * пределах того, что нужно модели, вместо 108 мегапикселей в памяти.
     */
    internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) / 2 >= maxSide && sample < 16) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Масштабирование до Vision_MAX_SIDE, если ещё не вписано. */
    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= maxSide) return src
        val k = maxSide.toFloat() / max
        return Bitmap.createScaledBitmap(
            src,
            (src.width * k).toInt().coerceAtLeast(1),
            (src.height * k).toInt().coerceAtLeast(1),
            true,
        )
    }

}
