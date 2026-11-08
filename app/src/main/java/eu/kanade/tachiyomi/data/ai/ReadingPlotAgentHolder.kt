package eu.kanade.tachiyomi.data.ai

import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Единственный экземпляр суб-агента сюжета на процесс.
 *
 * Движок озвучки живёт дольше экранов и не должен знать, где показывается
 * пересказ, поэтому агент вынесен в отдельный держатель: его состояние
 * (накопленные реплики, идущий запрос) обязано быть общим для движка и вкладки.
 */
object ReadingPlotAgentHolder {
    val agent: ReadingPlotAgent by lazy {
        ReadingPlotAgent(
            store = FilePlotStore(Injekt.get<android.content.Context>().filesDir),
            aiKeyPresent = {
                val prefs = Injekt.get<OcrPreferences>()
                val p = prefs.aiProvider().get()
                val custom = AiProviders.userProvider(Injekt.get<android.content.Context>(), p)
                when {
                    custom != null -> custom.apiKey.isNotBlank()
                    p == AiAssistant.PROVIDER_OPENROUTER -> prefs.openrouterApiKey().get().isNotBlank()
                    else -> prefs.googleApiKey().get().isNotBlank()
                }
            },
        )
    }
}
