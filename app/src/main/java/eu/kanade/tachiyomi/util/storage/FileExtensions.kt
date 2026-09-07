package eu.kanade.tachiyomi.util.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import eu.kanade.tachiyomi.BuildConfig
import java.io.File
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

val Context.cacheImageDir: File
    get() = File(cacheDir, "shared_image")

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
