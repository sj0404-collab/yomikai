package eu.kanade.tachiyomi.data.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Workspace встроенного AI-ассистента: реальная папка на диске, куда
 * ассистент складывает результаты (файлы, картинки, архивы), а пользователь
 * может забрать их в любой момент — файловым менеджером или из UI чата.
 *
 * Расположение выбирается в [root] при первом обращении: общая папка
 * `/sdcard/Yomikai/AI`, если её разрешено создать, иначе внешнее хранилище
 * приложения, и только в последнюю очередь приватное. Точный путь показывается
 * в интерфейсе и подставляется в промпт агента — раньше он обещал `/sdcard`, а
 * файлы оказывались в приватном хранилище. Если внешнее
 * хранилище недоступно — приватная папка приложения (files/ai_workspace).
 *
 * ## Контракт надёжности
 *
 * Ни один публичный метод не бросает исключение наружу. Workspace живёт на
 * общем хранилище, которое пользователь может размонтировать, а права —
 * отозвать в любой момент; кроме того, часть методов вызывается прямо из
 * Compose. Поэтому каждая операция обёрнута в `runCatching`, а неудача
 * возвращается как `null` / `false` / пустой список и логируется. Падение
 * одной операции с файлом не должно уносить приложение.
 */
object AiWorkspace {

    private const val DIR_NAME = "AI"

    /** Ограничение на число записей в списке, чтобы большой workspace не съел память. */
    private const val MAX_LIST_ENTRIES = 5_000

    /**
     * Кэш разрешённого корня. `root()` дёргается из `relPath()` на каждую
     * строку списка файлов, а раньше каждый вызов заново делал `mkdirs()` —
     * то есть дисковый ввод на главном потоке. Корень процесса не меняется,
     * поэтому запоминаем его один раз.
     */
    @Volatile
    private var cachedRoot: File? = null

    /**
     * Папка годится только если её удалось создать И в неё можно писать.
     * Существования родителя мало: на Android 11+ `/sdcard` существует, но
     * создать в нём свою папку приложение не может.
     */
    private fun usable(dir: File): Boolean =
        runCatching { (dir.isDirectory || dir.mkdirs()) && dir.canWrite() }.getOrDefault(false)

    fun root(context: Context): File {
        cachedRoot?.let { return it }
        val resolved = runCatching {
            // Порядок важен: сначала по-настоящему общая папка (её видно в
            // проводнике), потом внешнее хранилище приложения (видно в
            // «Android/data/<пакет>/files», не требует разрешений), и лишь
            // затем приватное хранилище — в нём файлы не видны пользователю
            // вообще, поэтому это последний вариант, а не молчаливый.
            val candidates = listOfNotNull(
                File(Environment.getExternalStorageDirectory(), "Yomikai/$DIR_NAME"),
                context.getExternalFilesDir(null)?.let { File(it, "Yomikai/$DIR_NAME") },
                File(context.filesDir, "ai_workspace"),
                File(context.cacheDir, "ai_workspace"),
            )
            val dir = candidates.firstOrNull { usable(it) }
                ?: File(context.filesDir, "ai_workspace").apply { mkdirs() }
            File(dir, "images").mkdirs()
            File(dir, "inbox").mkdirs()
            dir
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiWorkspace root failed, falling back to internal storage" }
            runCatching { File(context.filesDir, "ai_workspace").apply { mkdirs() } }
                .getOrElse { File(context.cacheDir, "ai_workspace").apply { mkdirs() } }
        }
        logcat(LogPriority.INFO) { "AiWorkspace root = ${resolved.absolutePath}" }
        cachedRoot = resolved
        return resolved
    }

    /**
     * Где на самом деле лежат файлы — строкой для интерфейса. Раньше путь
     * `/sdcard/Yomikai/AI` был обещан и в подсказке агента, и в настройках, но
     * на Android 11+ он не создавался, и читатель искал папку, которой нет.
     */
    fun storageHint(context: Context): String = root(context).absolutePath

    /**
     * Все файлы workspace (рекурсивно), отсортированы: папки → новые файлы.
     * Пустой список — и когда workspace пуст, и когда хранилище недоступно.
     */
    fun listAll(context: Context): List<File> = runCatching {
        val r = root(context)
        r.walkTopDown()
            .onEnter { dir -> runCatching { dir.canRead() }.getOrDefault(false) }
            .filter { it != r }
            .take(MAX_LIST_ENTRIES)
            .sortedWith(compareBy({ !it.isDirectory }, { -runCatching { it.lastModified() }.getOrDefault(0L) }))
            .toList()
    }.getOrElse { e ->
        logcat(LogPriority.WARN, e) { "AiWorkspace listAll failed" }
        emptyList()
    }

    fun relPath(context: Context, f: File): String = runCatching {
        f.absolutePath.removePrefix(root(context).absolutePath).trimStart('/')
    }.getOrDefault(f.name)

    fun relPathOrNull(context: Context, f: File): String? = runCatching {
        val rootPath = root(context).canonicalPath
        val filePath = f.canonicalPath
        if (filePath.startsWith(rootPath) && filePath != rootPath) {
            filePath.removePrefix(rootPath).trimStart('/')
        } else {
            null
        }
    }.getOrNull()

    /** Безопасное разрешение относительного пути (без выхода из workspace). */
    fun resolve(context: Context, rel: String): File? = runCatching {
        val r = root(context)
        val f = File(r, rel.trim().trimStart('/'))
        if (f.canonicalPath.startsWith(r.canonicalPath)) f else null
    }.getOrElse { e ->
        logcat(LogPriority.WARN, e) { "AiWorkspace resolve failed for '$rel'" }
        null
    }

    /** Сохранить текстовый файл; подпапки в имени создаются автоматически. */
    fun writeText(context: Context, name: String, content: String): File? {
        val f = resolve(context, sanitize(name)) ?: return null
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            f
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiWorkspace writeText failed for '$name'" }
            null
        }
    }

    fun newImageFile(context: Context, hint: String): File {
        val safe = sanitize(hint).take(40).ifBlank { "image" }
        return File(File(root(context), "images"), "${safe}_${System.currentTimeMillis() % 100000}.jpg")
    }

    /** Копия вложения пользователя в workspace/inbox; `null`, если запись не удалась. */
    fun importAttachment(context: Context, displayName: String, bytes: ByteArray): File? {
        val f = File(
            File(root(context), "inbox"),
            sanitize(displayName).ifBlank { "file_${System.currentTimeMillis()}" },
        )
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            f
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiWorkspace importAttachment failed for '$displayName'" }
            null
        }
    }

    /**
     * Упаковать весь workspace (кроме прежних архивов) в zip.
     * `null`, если архив собрать не удалось (нет места, хранилище размонтировано).
     */
    fun zipAll(context: Context): File? {
        val r = root(context)
        val out = File(r, "workspace_${System.currentTimeMillis() / 1000}.zip")
        val ok = runCatching {
            // Имена записей обязаны быть уникальны, иначе ZipOutputStream бросает
            // ZipException("duplicate entry") — а файлы в разных папках могут
            // совпадать по имени, если relPath() упал и вернул только имя.
            val used = HashSet<String>()
            ZipOutputStream(FileOutputStream(out)).use { zos ->
                r.walkTopDown()
                    .onEnter { dir -> runCatching { dir.canRead() }.getOrDefault(false) }
                    .filter { it.isFile && it != out && !it.name.endsWith(".zip") }
                    .forEach { f ->
                        val name = AiWorkspacePaths.uniqueEntryName(
                            relPath(context, f).ifBlank { f.name },
                            used,
                        )
                        runCatching {
                            FileInputStream(f).use { fis ->
                                zos.putNextEntry(ZipEntry(name))
                                fis.copyTo(zos)
                                zos.closeEntry()
                            }
                        }.onFailure { e ->
                            logcat(LogPriority.WARN, e) { "AiWorkspace zip skipped '$name'" }
                        }
                    }
            }
            true
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiWorkspace zipAll failed" }
            false
        }
        if (!ok) {
            runCatching { out.delete() }
            return null
        }
        return out
    }

    /**
     * Скопировать файл в «Загрузки» — единственную папку, которую читатель
     * действительно открывает на телефоне. На Android 10+ общие папки закрыты для
     * прямой записи, поэтому копируем через MediaStore: разрешение не нужно,
     * файл появляется в «Загрузки» и в проводнике, и его можно переслать.
     * На Android 8-9 (minSdk 26) пишем напрямую — там ещё старая модель прав.
     *
     * @return Uri скопированного файла или null, если не вышло.
     */
    fun exportToDownloads(context: Context, f: File): Uri? = runCatching {
        if (!f.isFile) return null
        val name = sanitize(f.name).ifBlank { "file" }
        val mime = when (f.extension.lowercase()) {
            "txt", "md", "json", "log" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                ?: return null
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Yomikai",
            ).apply { mkdirs() }
            val dst = File(dir, name)
            f.copyTo(dst, overwrite = true)
            Uri.fromFile(dst)
        }
    }.getOrElse { e ->
        logcat(LogPriority.WARN, e) { "AiWorkspace exportToDownloads failed for ${f.name}" }
        null
    }

    /**
     * Бэкап файла перед правкой (чтобы агент «не сломал» файл): копия в
     * backups/<имя>.<timestamp>. Держим до 5 последних бэкапов на файл.
     */
    fun backup(context: Context, f: File): File? {
        if (!runCatching { f.isFile }.getOrDefault(false)) return null
        val dir = File(root(context), "backups").apply { mkdirs() }
        val stamp = System.currentTimeMillis() / 1000
        val dst = File(dir, "${f.name}.$stamp")
        return runCatching {
            f.copyTo(dst, overwrite = true)
            // Ротация: не больше 5 бэкапов на файл
            dir.listFiles { c -> c.name.startsWith(f.name + ".") }
                ?.sortedByDescending { it.name }
                ?.drop(5)
                ?.forEach { runCatching { it.delete() } }
            dst
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiWorkspace backup failed for '${f.name}'" }
            null
        }
    }

    fun delete(context: Context, rel: String): Boolean {
        val f = resolve(context, rel) ?: return false
        return runCatching { f.deleteRecursively() }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "AiWorkspace delete failed for '$rel'" }
            false
        }
    }

    /** Делегирует чистой реализации, которую покрывают unit-тесты. */
    private fun sanitize(name: String): String = AiWorkspacePaths.sanitize(name)
}
