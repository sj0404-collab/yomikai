package eu.kanade.presentation.reader.components

import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Реплика, которую озвучивает TTS прямо сейчас.
 *
 * Раньше текущую реплику рисовал отдельный Compose-слой поверх страницы
 * (мягкое пятно/эллипс). Слой жил в координатах окна, а картинка под ним
 * зумится, панорамируется и обрезается — рамки «уезжали» от текста. Теперь
 * подсветку рисует сама вьюха страницы тем же холстом, что и картинку
 * (`ReaderPageImageView.drawOcrPageMarks`), а здесь лежит только текст
 * реплики: вьюха сравнивает его с рамками кадра.
 *
 * Потокобезопасность: текст ставит корутина авточтения (Main), читает
 * отрисовка (UI-поток). Достаточно volatile, но подписчики вызываются сразу,
 * чтобы вьюха перерисовалась, не дожидаясь следующего жеста.
 */
object OcrCurrentBubble {

    @Volatile
    var text: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Поставить текущую реплику. Повтор того же текста не дёргает слушателей. */
    fun set(value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() }
        if (normalized == text) return
        text = normalized
        listeners.forEach { runCatching { it() } }
    }

    fun clear() = set(null)

    /** Вьюха страницы подписывается, чтобы перерисоваться на смене реплики. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /**
     * Совпадает ли рамка кадра с текущей репликой.
     *
     * Сравнение по «ключу» (буквы и цифры в нижнем регистре): движок отдаёт
     * рамку и текст озвучки в разной разметке — с переносами, дефисами и
     * служебными пометками, и точное равенство строк не сработало бы никогда.
     */
    fun matches(candidate: String): Boolean {
        val current = text ?: return false
        val a = key(candidate)
        val b = key(current)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun key(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
