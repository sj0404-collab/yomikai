package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager

actual class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    // Кэш «какие папки-манги есть»: хранится ТОЛЬКО лёгкий список записей
    // (папки/архивы верхнего уровня). Никакие главы и их содержимое НЕ
    // кэшируются — главы читаются с диска только при открытии конкретной
    // манги (их могут быть сотни/тысячи и они тяжёлые). Папки пользователя
    // при скане только ЧИТАЮТСЯ (listFiles()), ничего в них не пишется,
    // не переименовывается и не удаляется.
    //
    // Сброс кэша: при изменении набора корневых папок (rootsKey) или после
    // перезапуска приложения. Точно так же ведёт себя кэш статистики в
    // LocalLibraryTab — новые папки внутри корня подхватываются после
    // изменения корней/перезапуска, вход на вкладку при этом мгновенный.
    @Volatile
    private var cachedFiles: List<UniFile>? = null

    @Volatile
    private var cachedRootsKey: String = ""

    private fun rootsKey(): String {
        val baseUrl = storageManager.getLocalSourceDirectory()?.uri?.toString().orEmpty()
        val external = storageManager.getExternalLibraryRoots()
            .map { root -> root.uri.toString() }
            .sorted()
        return baseUrl + "|" + external.joinToString(",")
    }

    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    /**
     * Сторонняя библиотека НЕ смешивается с хранилищем приложения:
     * сканируются подпапка local/ (манга самого приложения) и все внешние
     * папки-корни, добавленные пользователем (сколько угодно, из любых мест,
     * включая Android/data через SAF). Корень основного хранилища (папка
     * загрузок из сети) больше не сканируется — никаких дублей.
     */
    actual fun getFilesInBaseDirectory(): List<UniFile> {
        val key = rootsKey()
        cachedFiles?.let { cached ->
            if (cachedRootsKey == key) return cached
        }
        val local = getBaseDirectory()?.listFiles().orEmpty().toList()
        val external = storageManager.getExternalLibraryRoots()
            .flatMap { root -> root.listFiles().orEmpty().toList() }
        val result = (local + external).filterNot { it.name.orEmpty().lowercase() in RESERVED_DIR_NAMES }
        cachedFiles = result
        cachedRootsKey = key
        return result
    }

    actual fun getMangaDirectory(name: String): UniFile? {
        return findEntry(name)
            ?.takeIf { it.isDirectory }
    }

    actual fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }

    /** Ищет запись (папку или файл) в local/, затем во всех внешних корнях. */
    fun findEntry(name: String): UniFile? {
        getBaseDirectory()?.findFile(name)?.let { return it }
        for (root in storageManager.getExternalLibraryRoots()) {
            root.findFile(name)?.let { return it }
        }
        return null
    }
}

private val RESERVED_DIR_NAMES = hashSetOf(
    "local", "downloads", "backup", "autobackup", "automatic_backups", "covers", ".thumbnails",
)