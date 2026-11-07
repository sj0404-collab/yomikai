package mihon.data.ocr.vlm

/**
 * Какие файлы репозитория надо скачать для записи каталога и сколько это места.
 *
 * Правила простые и проверяемые юнит-тестом (сети не требуют):
 *  • если у модели задана подпапка — берём только её (у VLM в репозитории лежат
 *    cpu/gpu/int4-варианты, и качать все три — это гигабайты зря);
 *  • выкидываем то, что не нужно рантайму: картинки, .md, .gitattributes,
 *    датасеты и «примеры»;
 *  • пустой список файлов означает «формат не распознан» — такой записи нет
 *    смысла качать, и это должно быть видно до загрузки, а не после.
 */
internal object VlmInstall {

    private val JUNK = listOf(
        ".md", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
        ".gitattributes", ".txt.bak", ".pth", ".pt", ".bin.bak",
    )

    /** Служебные файлы, без которых модель не запустится, — их не выкидываем никогда. */
    private val KEEP_ALWAYS = listOf(
        "genai_config.json", "tokenizer.json", "tokenizer_config.json",
        "processor_config.json", "preprocessor_config.json", "special_tokens_map.json",
        "config.json", "vocab.json", "merges.txt",
    )

    fun pickFiles(folder: String, files: List<HfModelHub.Blob>): List<HfModelHub.Blob> {
        val prefix = if (folder.isEmpty()) "" else folder.trimEnd('/') + "/"
        return files.filter { b ->
            val inFolder = prefix.isEmpty() || b.name.startsWith(prefix)
            if (!inFolder) return@filter false
            val leaf = b.name.substringAfterLast('/').lowercase()
            if (KEEP_ALWAYS.any { it == leaf }) return@filter true
            JUNK.none { leaf.endsWith(it) }
        }.sortedBy { it.name }
    }

    /** Суммарный размер в мегабайтах (для честной строки «скачать 1,31 ГБ»). */
    fun totalMb(files: List<HfModelHub.Blob>): Long =
        (files.sumOf { if (it.size > 0) it.size else 0L } + 524_288L) / 1_048_576L

    /**
     * Что человек увидит до скачивания: формат, нужен ли рантайм и сколько качать.
     * Одна строка, потому что именно её читают в списке моделей.
     */
    fun describe(entry: VlmCatalogEntry, files: List<HfModelHub.Blob>): String {
        val picked = pickFiles(entry.folder, files)
        if (picked.isEmpty()) return "файлы модели не найдены — качать нечего"
        val fmt = VlmFormat.detect(picked.map { it.name })
        val vision = if (VlmFormat.looksVisibility(picked.map { it.name })) "видящая" else "только текст"
        val rt = if (fmt.runtimeInApk) "рантайм есть" else "нужен рантайм: ${fmt.runtime}"
        return "${fmt.title} · $vision · ${totalMb(picked)} МБ · $rt"
    }
}
