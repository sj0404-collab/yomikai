package eu.kanade.tachiyomi.data.ai

import java.io.File

/**
 * Обзор workspace: что вообще лежит в папке и как это прочитать целиком.
 *
 * Читатель просил, чтобы агент «видел сразу всё, что в воркспейсе, анализировал
 * по очереди и задавал уточняющие вопросы». Раньше у агента был только
 * `read_file` по одному файлу: чтобы понять, что есть, нужно было угадать имя
 * и проверить всё подряд, а на несуществующем файле он получал «Файл не
 * найден» и строил выводы по неполной картине.
 *
 * Здесь две чистые функции (без `Context`, с `File` вместо него) — их можно
 * проверить обычными unit-тестами на JVM, и в них же живёт главное: **бюджет
 * символов**. Список из сотни файлов, выведенный в ответ целиком, съедает
 * контекст хода, после которого модель начинает отвечать мусором, поэтому
 * список обрезается по лимиту и честно говорит, что не показал.
 */
object AiWorkspaceSurvey {

    /** Сколько символов отдаём под один обзор по умолчанию. */
    const val DEFAULT_TREE_BUDGET = 6_000

    /** Сколько символов текста читать за один пакетный вызов. */
    const val DEFAULT_READ_BUDGET = 12_000

    /**
     * Дерево файлов: папки и файлы с размерами, от самых свежих.
     *
     * @param subdir подкаталог относительно корня, "" или "." — весь корень.
     * @param limit максимум строк, даже если символы ещё остались.
     * @param maxBudget обрезка по символам.
     */
    fun renderTree(root: File, subdir: String = "", limit: Int = 200, maxBudget: Int = DEFAULT_TREE_BUDGET): String {
        val base = resolveDir(root, subdir) ?: return "Каталог не найден: $subdir"
        val entries = base.walkTopDown()
            .onEnter { it.canRead() }
            .filter { it != base }
            .sortedWith(
                compareBy(
                    { it.path.count { ch -> ch == '/' || ch == java.io.File.separatorChar } },
                    { it.name.lowercase() },
                ),
            )
            .toList()

        if (entries.isEmpty()) return "В каталоге ${describe(root, base)} пусто"

        val sb = StringBuilder("В папке ${describe(root, base)} — ${entries.size} объект(ов):\n")
        var shown = 0
        var truncated = false
        // Место под завершающую пометку резервируем заранее, иначе она сама
        // вылезет за бюджет: бюджет — это предел ответа модели, а не «список
        // плюс ещё немножка».
        val reserve = 70 + entries.size.toString().length
        for (f in entries) {
            if (shown >= limit) {
                truncated = true
                break
            }
            val rel = relative(root, f)
            val line = buildString {
                append("  ")
                append(rel)
                if (f.isDirectory) {
                    append('/')
                } else {
                    append(" — ")
                    append(f.length() / 1024)
                    append(" КБ")
                }
            }
            // Обрезаем по символам, но не посреди строки: недописанный путь
            // хуже, чем лишняя строка в ответе.
            if (sb.length + line.length + 1 + reserve > maxBudget) {
                truncated = true
                break
            }
            sb.append(line).append('\n')
            shown++
        }
        if (truncated) {
            sb.append("… показано $shown из ${entries.size}. Смотри по частям: workspace_list с path.")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Чтение нескольких файлов по очереди в пределах бюджета символов.
     *
     * Порядок сохраняется: для книги «глава 1 → глава 2» это важно, а сортировка
     * по имени переставляла бы их произвольно. Файл, который не влез в бюджет,
     * помечается, а не молча пропускается — иначе агент решит, что прочитал всё.
     */
    fun readMany(root: File, names: List<String>, budget: Int = DEFAULT_READ_BUDGET): String {
        if (names.isEmpty()) return "Не указано ни одного файла"
        val sb = StringBuilder()
        var spent = 0
        for (name in names) {
            val f = resolveFile(root, name)
            if (f == null || !f.isFile) {
                sb.append("— $name: НЕ НАЙДЕН\n")
                continue
            }
            if (f.extension.lowercase() in IMAGE_EXT) {
                // Раньше здесь было «я не вижу картинки». Это неправда: и
                // read_file, и see_image спрашивают vision-модель. Одно
                // сообщение говорило «не вижу», другое — «спрошу», и модель
                // верила тому, что прочитала последним.
                sb.append("— $name: изображение ${f.length() / 1024} КБ; текста нет. ")
                sb.append("Спросить vision можно see_image {\"name\":\"$name\",\"question\":\"что на ней\"}, ")
                sb.append("прочитать — read_file\n")
                continue
            }
            val text = runCatching { f.readText() }.getOrElse {
                sb.append("— $name: НЕ ЧИТАЕТСЯ\n")
                continue
            }
            val header = "— $name (${text.length} симв.)\n"
            val room = budget - spent - header.length
            if (room <= 0) {
                sb.append("— $name: не вошёл в бюджет чтения, читай отдельно read_file\n")
                continue
            }
            val body = if (text.length > room) text.take(room) + "\n… (файл обрезан по бюджету)" else text
            sb.append(header).append(body).append('\n')
            spent += header.length + body.length
            if (spent >= budget) {
                val rest = names.drop(names.indexOf(name) + 1)
                if (rest.isNotEmpty()) {
                    sb.append("Бюджет чтения исчерпан, не прочитано: ${rest.joinToString(", ")}")
                }
                break
            }
        }
        return sb.toString().trimEnd()
    }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    private fun resolveDir(root: File, subdir: String): File? {
        val clean = subdir.trim().trimStart('/')
        if (clean.isEmpty() || clean == ".") return root
        val f = File(root, clean)
        return f.takeIf { it.isDirectory }
    }

    private fun resolveFile(root: File, name: String): File? {
        val clean = name.trim().trimStart('/')
        if (clean.isEmpty()) return null
        val f = File(root, clean)
        // Выход за пределы workspace запрещён: путь из модели не должен
        // приводить к файлам приложения.
        return runCatching {
            if (f.canonicalPath.startsWith(root.canonicalPath)) f else null
        }.getOrNull()
    }

    private fun relative(root: File, f: File): String = runCatching {
        f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
    }.getOrDefault(f.name)

    private fun describe(root: File, dir: File): String =
        if (dir == root) "workspace (корень)" else relative(root, dir)
}
