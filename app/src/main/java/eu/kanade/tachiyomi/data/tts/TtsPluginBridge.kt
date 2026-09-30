package eu.kohesive.tachiyomi.data.tts

/**
 * Плагин синтеза речи для звуков, которые обычный голос произнести не
 * умеет: вздох, вдох, стон, крик, смех, кашель. Обычный движок в лучшем
 * случае читает их буквами («А-а-а...»), и погружение от этого ломается.
 *
 * Плагин необязателен: читатель сам решает, нужен ли ему такой слой звука.
 * Пока плагин выключен или не скачан, звуки идут в голос как раньше — то
 * есть мост не меняет поведение приложения, пока его не включили явно.
 *
 * Вариантов несколько, у каждого свой помощник ([helperHint]): «помощник» —
 * это агент из доступных провайдеров, который помогает настроить именно
 * этот вариант (подобрать голос, подготовить образцы, объяснить, что
 * делать). Выбор варианта и выбор помощника — разные вещи: звук играет
 * всегда локально, а помогает ему кто может.
 */
object TtsPluginBridge {

    /** Вариант синтеза звука. */
    enum class Variant(
        val id: String,
        val title: String,
        /** Нужен интернет при первом получении звука; дальше работает из кэша. */
        val needsNetwork: Boolean,
        /** Признак, что вариант умеет играть звук на этом устройстве. */
        val playable: Boolean,
    ) {
        /**
         * Звук генерирует провайдер из настроек и кэшируется локально. Работает
         * сразу, без файлов от пользователя, поэтому это стартовый вариант.
         */
        AI_CACHED(
            id = "ai_cached",
            title = "AI-звук (с кэшем)",
            needsNetwork = true,
            playable = true,
        ),

        /**
         * Готовые образцы звуков, скачиваемые пакетом. Мгновенно, офлайн и
         * почти не грузит процессор, но включается только когда пакет есть на
         * устройстве: сам комплект звуков не создаётся и не выдумывается.
         */
        AUDIO_PACK(
            id = "audio_pack",
            title = "Аудиопак (офлайн)",
            needsNetwork = false,
            playable = true,
        ),

        /**
         * Голос читателя: нарезка из своего аудио/видео или чужого, с
         * привязкой как TTS-голос. Требует провайдера с клонированием.
         */
        CUSTOM_VOICE(
            id = "custom_voice",
            title = "Свой голос",
            needsNetwork = true,
            playable = true,
        );

        companion object {
            fun of(id: String?): Variant? = entries.firstOrNull { it.id == id }
        }
    }

    /** Что делает мост с куском реплики. */
    enum class Action {
        /** Обычный голос: это текст или междометие, либо плагин выключен. */
        VOICE,

        /** Плагин: настоящий звук, который голос не умеет. */
        PLUGIN,

        /** Плагин включён, но звук он отдать не смог — голосом. */
        VOICE_FALLBACK,
    }

    /**
     * Решение моста по одному куску реплики.
     *
     * Правило намеренно простое: плагин забирает только то, что он умеет
     * ([SpeechCue.Delivery.isSound]), и только когда он реально готов. Любой
     * сбой плагина — это откат на голос, а не пропавший звук.
     */
    fun decide(
        isSound: Boolean,
        variant: Variant?,
        downloaded: Boolean,
        enabled: Boolean,
    ): Action = when {
        !enabled -> Action.VOICE
        variant == null -> Action.VOICE
        !downloaded -> Action.VOICE
        !variant.playable -> Action.VOICE
        !isSound -> Action.VOICE
        else -> Action.PLUGIN
    }

    /** Уже включённый вариант или null, если плагин выключен/не скачан. */
    fun activeVariant(
        variantId: String?,
        downloaded: Boolean,
        enabled: Boolean,
    ): Variant? = if (enabled && downloaded) Variant.of(variantId) else null

    /**
     * Следующий вариант по кругу — «кому какой больше понравится»: читатель
     * щёлкает кнопкой и слушает, ничего не настраивая. Выключенный плагин
     * включается первым вариантом, из конца — снова выключается.
     */
    fun nextVariant(currentId: String?, enabled: Boolean): String? {
        val order = Variant.entries.map { it.id }
        if (!enabled) return order.first()
        val index = order.indexOf(Variant.of(currentId)?.id ?: order.last())
        return if (index < 0) order.first() else order[(index + 1) % order.size]
    }
}
