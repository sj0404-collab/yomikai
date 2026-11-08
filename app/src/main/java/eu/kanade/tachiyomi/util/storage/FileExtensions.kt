package eu.kanade.tachiyomi.util.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import eu.kanade.tachiyomi.BuildConfig
import java.io.File
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

val Context.cacheImageDir: File
    get() = File(cacheDir, "shared_image")

/**
 * MIME-тип файла по расширению — для `ACTION_VIEW`.
 *
 * Без него намерение «открыть» уходило в приложение, которое вообще не
 * понимает формат, и читатель получал ошибку вместо просмотра.
 */
fun File.mimeType(): String = when (extension.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "mp3" -> "audio/mpeg"
    "m4a", "m4b" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "flac" -> "audio/flac"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "3gp" -> "video/3gpp"
    "pdf" -> "application/pdf"
    "epub" -> "application/epub+zip"
    "md", "txt", "log", "csv" -> "text/plain"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "xml" -> "text/xml"
    "zip" -> "application/zip"
    "tflite" -> "application/octet-stream"
    else -> "*/*"
}

/**
 * Намерение «показать файл», а не «поделиться».
 *
 * Раньше кнопка «Открыть» собирала `ACTION_SEND` через `toShareIntent`, то
 * есть открывала системный лист отправки: читателю приходилось сначала
 * выбрать приложение, а потом уже копировать файл или отправлять его себе.
 * Здесь — `ACTION_VIEW` с типом по расширению.
 */
fun File.toViewIntent(context: Context): Intent = Intent(Intent.ACTION_VIEW).apply {
    val uri = getUriCompat(context)
    setDataAndType(uri, mimeType())
    clipData = ClipData.newRawUri(null, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/**
 * Returns the uri of a file
 *
 * @param context context of application
 */
fun File.getUriCompat(context: Context): Uri {
    return try {
        FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", this)
    } catch (e: IllegalArgumentException) {
        // Файл лежит вне настроенных корней FileProvider (например, внутренний
        // files/ai_workspace на устройстве без внешнего хранилища, либо путь
        // оказался не покрыт provider_paths.xml). Копируем во внутренний
        // cacheDir — он всегда настроен как cache-path — и шарим оттуда, чтобы
        // «Поделиться» никогда не роняло приложение.
        logcat(LogPriority.WARN, e) { "File not covered by FileProvider; sharing from cache: $absolutePath" }
        val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val copy = File(sharedDir, name).apply {
            if (!exists()) {
                inputStream().use { i -> outputStream().use { o -> i.copyTo(o) } }
            }
        }
        FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", copy)
    }
}

/**
 * Copies this file to the given [target] file while marking the file as read-only.
 *
 * @see File.copyTo
 */
fun File.copyAndSetReadOnlyTo(target: File, overwrite: Boolean = false, bufferSize: Int = DEFAULT_BUFFER_SIZE): File {
    if (!this.exists()) {
        throw NoSuchFileException(file = this, reason = "The source file doesn't exist.")
    }

    if (target.exists()) {
        if (!overwrite) {
            throw FileAlreadyExistsException(
                file = this,
                other = target,
                reason = "The destination file already exists.",
            )
        } else if (!target.delete()) {
            throw FileAlreadyExistsException(
                file = this,
                other = target,
                reason = "Tried to overwrite the destination, but failed to delete it.",
            )
        }
    }

    if (this.isDirectory) {
        if (!target.mkdirs()) {
            throw FileSystemException(file = this, other = target, reason = "Failed to create target directory.")
        }
    } else {
        target.parentFile?.mkdirs()

        this.inputStream().use { input ->
            target.outputStream().use { output ->
                // Set read-only
                target.setReadOnly()

                input.copyTo(output, bufferSize)
            }
        }
    }

    return target
}
