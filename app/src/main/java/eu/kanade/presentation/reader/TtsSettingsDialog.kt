package eu.kanade.presentation.reader

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Настройки озвучки: три источника голосов.
 * • Системные — все голоса Android TTS (локальные и сетевые)
 * • Веб (без ключа) — Google Translate TTS прямо с сайта
 * • ElevenLabs — нейроголоса по API-ключу
 */
@Composable
fun TtsSettingsDialog(
    onDismissRequest: () -> Unit,
    onOpenFullSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }

    var engine by remember { mutableStateOf(prefs.voiceEngine().get()) }
    var selectedVoice by remember { mutableStateOf(prefs.voiceName().get()) }
    var rate by remember { mutableFloatStateOf(prefs.speechRate().get()) }
    var webLang by remember { mutableStateOf(prefs.ttsWebLanguage().get()) }
    var elevenKey by remember { mutableStateOf(prefs.elevenApiKey().get()) }
    var elevenVoice by remember { mutableStateOf(prefs.elevenVoiceId().get()) }

    var voiceFemale by remember { mutableStateOf(prefs.voiceFemale().get()) }
    var voiceMale by remember { mutableStateOf(prefs.voiceMale().get()) }
    var aiGender by remember { mutableStateOf(prefs.aiGenderVoices().get()) }
    var aiProvider by remember { mutableStateOf(prefs.aiProvider().get()) }
    var zenModel by remember { mutableStateOf(prefs.zenModel().get()) }
    var orFreeModel by remember { mutableStateOf(prefs.openrouterFreeModel().get()) }
    var orKey by remember { mutableStateOf(prefs.openrouterApiKey().get()) }
    var orModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAiLog by remember { mutableStateOf(false) }

    // Edge TTS (Microsoft, без ключа): голос + фильтр языка в списке.
    var edgeVoice by remember { mutableStateOf(prefs.edgeVoice().get()) }
    var edgeLanguage by remember { mutableStateOf(prefs.edgeLanguage().get()) }
    var edgeVoices by remember { mutableStateOf<List<eu.kanade.tachiyomi.data.tts.EdgeTts.EdgeVoice>>(emptyList()) }
    var edgeLoading by remember { mutableStateOf(false) }
    var edgeVoicesLoaded by remember { mutableStateOf(false) }
    var edgeProbingVoice by remember { mutableStateOf<String?>(null) }

    // Живой список :free моделей OpenRouter (фолбэк при оффлайне)
    androidx.compose.runtime.LaunchedEffect(aiProvider) {
        if (aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER && orModels.isEmpty()) {
            orModels = eu.kanade.tachiyomi.data.ai.AiAssistant.fetchOpenRouterFreeModels()
        }
    }
    var assignMode by remember { mutableStateOf(0) } // 0=основной, 1=женский, 2=мужской

    var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showAddSlot by remember { mutableStateOf(false) }
    var addSlotRole by remember { mutableStateOf<String?>(null) }
    var sysReady by remember { mutableStateOf(false) }

    // Живой список голосов Edge TTS (Microsoft, без ключа): тянется при первом
    // открытии, фильтруется по языку, мультиязычные голоса помечены 🌐.
    androidx.compose.runtime.LaunchedEffect(engine) {
        if (engine == TtsSpeaker.ENGINE_EDGE_TTS && !edgeVoicesLoaded) {
            edgeLoading = true
            edgeVoices = eu.kanade.tachiyomi.data.tts.EdgeTts.fetchVoices()
            edgeVoicesLoaded = true
            edgeLoading = false
        }
    }

    // Словари голосовых ролей и интонаций (JSON в настройках). Редактируются
    // здесь, сохраняются сразу же — как legacy-слоты голосов выше.
    var roles by remember {
        mutableStateOf(eu.kanade.tachiyomi.data.tts.VoiceRoleDictionary.load(prefs))
    }
    var rules by remember {
        mutableStateOf(eu.kanade.tachiyomi.data.tts.VoiceIntonationDictionary.load(prefs))
    }
    var showAddRole by remember { mutableStateOf(false) }
    var showAddRule by remember { mutableStateOf(false) }
    val systemEnginePkg = remember { prefs.systemTtsEngine().get() }
    var probe by remember { mutableStateOf<TextToSpeech?>(null) }
    var probeInitStatus by remember { mutableStateOf(Int.MIN_VALUE) }

    DisposableEffect(systemEnginePkg) {
        voices = emptyList()
        sysReady = false
        probe = null
        probeInitStatus = Int.MIN_VALUE
        var tts: TextToSpeech? = null
        var disposed = false
        val listener = TextToSpeech.OnInitListener { status ->
            // OEM implementations may invoke OnInit before the constructor
            // assignment above completes. Posting also lets lazy engines bind.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!disposed) {
                    probe = tts.takeIf { status == TextToSpeech.SUCCESS }
                    probeInitStatus = status
                }
            }
        }
        tts = if (systemEnginePkg.isBlank()) {
            TextToSpeech(context.applicationContext, listener)
        } else {
            TextToSpeech(context.applicationContext, listener, systemEnginePkg)
        }
        onDispose {
            disposed = true
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }

    androidx.compose.runtime.LaunchedEffect(probe, probeInitStatus, systemEnginePkg) {
        val activeProbe = probe
        if (probeInitStatus != TextToSpeech.SUCCESS || activeProbe == null) {
            sysReady = probeInitStatus != Int.MIN_VALUE
            return@LaunchedEffect
        }
        sysReady = false
        eu.kanade.tachiyomi.data.tts.VoiceHelper.prepareForLanguage(activeProbe, "ru")
        var found = emptyList<android.speech.tts.Voice>()
        for (attempt in 0 until 6) {
            found = eu.kanade.tachiyomi.data.tts.VoiceHelper
                .russianVoices(activeProbe, systemEnginePkg)
            if (found.isNotEmpty()) break
            kotlinx.coroutines.delay(250L + attempt * 150L)
        }
        voices = found
            .sortedWith(
                compareBy(
                    {
                        when (eu.kanade.tachiyomi.data.tts.VoiceHelper.classify(it)) {
                            eu.kanade.tachiyomi.data.tts.VoiceKind.FEMALE -> 0
                            eu.kanade.tachiyomi.data.tts.VoiceKind.MALE -> 1
                            eu.kanade.tachiyomi.data.tts.VoiceKind.TEEN -> 2
                            else -> 3
                        }
                    },
                    { it.isNetworkConnectionRequired },
                    { it.name },
                ),
            )
            .map { voice ->
                val kind = when (eu.kanade.tachiyomi.data.tts.VoiceHelper.classify(voice)) {
                    eu.kanade.tachiyomi.data.tts.VoiceKind.FEMALE -> "♀ Женский"
                    eu.kanade.tachiyomi.data.tts.VoiceKind.MALE -> "♂ Мужской"
                    eu.kanade.tachiyomi.data.tts.VoiceKind.TEEN -> "👦 Подросток"
                    else -> "Другой"
                }
                val net = if (voice.isNetworkConnectionRequired) "☁ сеть" else "📱 локальный"
                voice.name to "$kind • $net • ${voice.name.substringAfterLast(':')}"
            }

        // v1.9.39: голоса ВСЕХ установленных движков (RHVoice и др.), а не только
        // движка по умолчанию: каждый движок инициализируется явно своим пакетом.
        val extraVoices = mutableListOf<Pair<String, String>>()
        for ((pkg, label) in eu.kanade.tachiyomi.data.tts.TtsSpeaker.installedEngines(context)) {
            val isDefault = pkg == systemEnginePkg ||
                (systemEnginePkg.isBlank() && pkg == runCatching { activeProbe.defaultEngine }.getOrDefault(""))
            if (isDefault) continue
            val eng = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val latch = java.util.concurrent.CountDownLatch(1)
                var tts: TextToSpeech? = null
                tts = TextToSpeech(context.applicationContext, { latch.countDown() }, pkg)
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                tts
            }
            val vs = eu.kanade.tachiyomi.data.tts.VoiceHelper.russianVoices(eng, pkg)
            runCatching { eng?.shutdown() }
            vs.forEach { v ->
                val net = if (v.isNetworkConnectionRequired) "☁ сеть" else "📱 локальный"
                extraVoices.add(("$pkg::${v.name}") to "$label • $net • ${v.name}")
            }
        }
        voices = voices + extraVoices

        // Автовыбор выполняется по тому же выбранному пакету движка, включая
        // RHVoice fallback для прошивок с пустым getVoices().
        val names = voices.map { it.first }.toSet()
        if (selectedVoice.isBlank() || selectedVoice !in names) {
            eu.kanade.tachiyomi.data.tts.VoiceHelper
                .pick(
                    activeProbe,
                    eu.kanade.tachiyomi.data.tts.VoiceKind.FEMALE,
                    null,
                    systemEnginePkg,
                )
                ?.let { selectedVoice = it.name }
        }
        if (voiceFemale.isBlank() || voiceFemale !in names) {
            eu.kanade.tachiyomi.data.tts.VoiceHelper
                .pick(
                    activeProbe,
                    eu.kanade.tachiyomi.data.tts.VoiceKind.FEMALE,
                    null,
                    systemEnginePkg,
                )
                ?.let { voiceFemale = it.name }
        }
        if (voiceMale.isBlank() || voiceMale !in names) {
            eu.kanade.tachiyomi.data.tts.VoiceHelper
                .pick(
                    activeProbe,
                    eu.kanade.tachiyomi.data.tts.VoiceKind.MALE,
                    null,
                    systemEnginePkg,
                )
                ?.let { voiceMale = it.name }
        }
        sysReady = true
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null) },
        title = { Text("Озвучка (TTS)") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_SYSTEM,
                        onClick = { engine = TtsSpeaker.ENGINE_SYSTEM },
                        label = { Text("Системные") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_REMOTE,
                        onClick = { engine = TtsSpeaker.ENGINE_REMOTE },
                        label = { Text("🖥 Сервер") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_GOOGLE_WEB,
                        onClick = { engine = TtsSpeaker.ENGINE_GOOGLE_WEB },
                        label = { Text("Веб") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_EDGE_TTS,
                        onClick = { engine = TtsSpeaker.ENGINE_EDGE_TTS },
                        label = { Text("Edge TTS") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_ELEVENLABS,
                        onClick = { engine = TtsSpeaker.ENGINE_ELEVENLABS },
                        label = { Text("ElevenLabs") },
                    )
                }
                if (engine == TtsSpeaker.ENGINE_REMOTE) {
                    androidx.compose.material3.OutlinedTextField(
                        value = prefs.remoteTtsUrl().get(),
                        onValueChange = { prefs.remoteTtsUrl().set(it.trim()) },
                        label = { Text("Адрес сервера: http://192.168.1.10:8788") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "Нейроголоса (sherpa-onnx/Piper) работают на вашем ПК или ранере: " +
                            "запустите tools/remote_tts_server.py и укажите адрес. " +
                            "Приложение шлёт текст и проигрывает готовый wav.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Авточтение: автолистание вебтуна", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                val arSpeed = prefs.autoReadWebtoonSpeed().get()
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "phrase" to "Плавно пофразно",
                        "slow" to "Медленно",
                        "normal" to "Обычно",
                        "fast" to "Быстрее",
                        "max" to "Максимум",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = arSpeed == id,
                            onClick = { prefs.autoReadWebtoonSpeed().set(id) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Text(
                    when (arSpeed) {
                        "phrase" -> "Плавно: после каждой реплики экран опускается ровно на её высоту — следующая реплика уже внизу, границы кадров не перечитываются."
                        "slow" -> "Медленно: шаг ~15% экрана, большое перекрытие кадров (чаще повторное распознавание)."
                        "fast" -> "Быстрее: шаг ~55% экрана, меньше перекрытия."
                        "max" -> "Максимально: шаг ~80% экрана, почти без перекрытия."
                        else -> "Обычно: шаг ~35% экрана, с перекрытием — реплики на границе вьюпорта не пропускаются."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text("Голоса по ролям (пресеты озвучки)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                val slotsJsonNow = prefs.voiceSlots().get()
                val slotsNow = remember(slotsJsonNow) {
                    runCatching {
                        val arr = org.json.JSONArray(slotsJsonNow)
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            o.optString("role") to o.optString("voice")
                        }
                    }.getOrDefault(emptyList())
                }
                slotsNow.forEachIndexed { idx, (role, voice) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${when (role) { "male" -> "♂"; "female" -> "♀"; else -> "🎙" }} $voice",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            eu.kanade.tachiyomi.data.tts.TtsSpeaker.speakWithVoice(
                                context,
                                "Проба голоса роли: раз, два, три.",
                                voice,
                            )
                        }) { Text("Проба") }
                        TextButton(onClick = {
                            val arr = runCatching { org.json.JSONArray(prefs.voiceSlots().get()) }.getOrDefault(org.json.JSONArray())
                            val newArr = org.json.JSONArray()
                            for (i in 0 until arr.length()) if (i != idx) newArr.put(arr.get(i))
                            prefs.voiceSlots().set(newArr.toString())
                        }) { Text("Удалить") }
                    }
                }
                TextButton(onClick = { showAddSlot = true }) { Text("＋ Добавить голос") }
                if (showAddSlot) {
                    if (addSlotRole == null) {
                        AlertDialog(
                            onDismissRequest = { showAddSlot = false },
                            confirmButton = { TextButton(onClick = { showAddSlot = false }) { Text("Отмена") } },
                            title = { Text("Роль голоса") },
                            text = {
                                Column {
                                    TextButton(onClick = { addSlotRole = "male" }) { Text("♂ Мужской 1") }
                                    TextButton(onClick = { addSlotRole = "female" }) { Text("♀ Женский 1") }
                                    TextButton(onClick = { addSlotRole = "narrator" }) { Text("🎙 Нарратор 1") }
                                }
                            },
                        )
                    } else {
                        AlertDialog(
                            onDismissRequest = { addSlotRole = null; showAddSlot = false },
                            confirmButton = { TextButton(onClick = { addSlotRole = null; showAddSlot = false }) { Text("Отмена") } },
                            title = { Text("Голос для роли (локальный или онлайн)") },
                            text = {
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    voices.forEach { (name, label) ->
                                        TextButton(onClick = {
                                            val arr = runCatching { org.json.JSONArray(prefs.voiceSlots().get()) }.getOrDefault(org.json.JSONArray())
                                            arr.put(org.json.JSONObject().apply { put("role", addSlotRole); put("voice", name) })
                                            prefs.voiceSlots().set(arr.toString())
                                            addSlotRole = null
                                            showAddSlot = false
                                        }) { Text(label) }
                                    }
                                }
                            },
                        )
                    }
                }

                // ── Словарь голосовых ролей: имя/метка → голос, питч, темп ──
                Text(
                    "Словарь голосовых ролей (персонаж → голос)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Роль подбирается по имени из разметки {имя:Аки}, затем по полу. " +
                        "Голос и модификаторы питча/темпа перекрывают пресеты пола.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                roles.forEachIndexed { idx, role ->
                    val roleParams = buildString {
                        if (role.voice.isNotBlank()) append(" • ${role.voice}")
                        if (role.pitch != 1f || role.rate != 1f) {
                            append(" • ×${"%.2f".format(role.pitch)}/×${"%.2f".format(role.rate)}")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${role.name} — ${when (role.gender) {
                                    "male" -> "♂"
                                    "female" -> "♀"
                                    "neutral" -> "⚥"
                                    else -> "авто"
                                }}$roleParams",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                if (role.markers.isNotEmpty()) role.markers.joinToString(", ")
                                else role.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            eu.kanade.tachiyomi.data.tts.TtsSpeaker.speakWithVoice(
                                context,
                                "Проба голоса роли ${role.name}.",
                                role.voice.ifBlank { null },
                            )
                        }) { Text("Проба") }
                        TextButton(onClick = {
                            roles = roles.filterIndexed { i, _ -> i != idx }
                            eu.kanade.tachiyomi.data.tts.VoiceRoleDictionary.save(prefs, roles)
                        }) { Text("Удалить") }
                    }
                }
                TextButton(onClick = { showAddRole = true }) { Text("＋ Добавить роль") }

                // ── Словарь интонаций: узор фразы → пауза/питч/темп ──
                Text(
                    "Словарь интонаций (узор фразы → пауза и тон)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Применяется к каждому предложению: узор текста задаёт паузу, " +
                        "высоту и темп именно этой реплики.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rules.forEachIndexed { idx, rule ->
                    val ruleParams = buildString {
                        if (rule.pauseMs > 0) append(" • пауза ${rule.pauseMs}мс")
                        if (rule.pitch != 1f || rule.rate != 1f) {
                            append(" • ×${"%.2f".format(rule.pitch)}/×${"%.2f".format(rule.rate)}")
                        }
                        if (!rule.enabled) append(" • выкл")
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${if (rule.exact) "=" else "~"} «${rule.pattern}»$ruleParams",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = {
                            rules = rules.filterIndexed { i, _ -> i != idx }
                            eu.kanade.tachiyomi.data.tts.VoiceIntonationDictionary.save(prefs, rules)
                        }) { Text("Удалить") }
                    }
                }
                TextButton(onClick = { showAddRule = true }) { Text("＋ Добавить интонацию") }
                if (showAddRole) {
                    AddRoleDialog(
                        voices = voices,
                        onDismiss = { showAddRole = false },
                        onAdd = { role ->
                            roles = roles + role
                            eu.kanade.tachiyomi.data.tts.VoiceRoleDictionary.save(prefs, roles)
                            showAddRole = false
                        },
                    )
                }
                if (showAddRule) {
                    AddRuleDialog(
                        onDismiss = { showAddRule = false },
                        onAdd = { rule ->
                            rules = rules + rule
                            eu.kanade.tachiyomi.data.tts.VoiceIntonationDictionary.save(prefs, rules)
                            showAddRule = false
                        },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clickable { aiGender = !aiGender },
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = aiGender,
                        onCheckedChange = { aiGender = it },
                    )
                    Column {
                        Text("AI-голоса по полу говорящего", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Встроенная морфология + онлайн-ассистент (Zen — без ключа) определяют, кто говорит: реплики озвучиваются голосом ♀/♂.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (aiGender) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAiLog = !showAiLog }
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            "⚙ Скрытый чат ассистента (журнал)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (showAiLog) "  ▲" else "  ▼",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (showAiLog) {
                        val entries = eu.kanade.tachiyomi.data.ai.AiAssistant.log().asReversed()
                        if (entries.isEmpty()) {
                            Text(
                                "Пока пусто: журнал наполняется при авточтении.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                                entries.forEach { e ->
                                    Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                        Text(
                                            "${e.model} • ${e.tookMs}мс",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            "→ ${e.prompt}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                        )
                                        Text(
                                            "← ${e.answer}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        FilterChip(
                            selected = aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN,
                            onClick = { aiProvider = eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN },
                            label = { Text("Zen (без ключа)") },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        FilterChip(
                            selected = aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER,
                            onClick = { aiProvider = eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER },
                            label = { Text("OpenRouter") },
                        )
                    }
                    // Провайдеры пользователя из реестра AiProviders: свой
                    // endpoint (Ollama, LM Studio, прокси). Создаются файлом в
                    // workspace/providers или через AI-чат (provider_create).
                    val userProviders = remember(context) {
                        eu.kanade.tachiyomi.data.ai.AiProviders.list(context)
                    }
                    if (userProviders.isNotEmpty()) {
                        Text(
                            "Свои провайдеры:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        userProviders.forEach { spec ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                FilterChip(
                                    selected = aiProvider == spec.id,
                                    onClick = { aiProvider = spec.id },
                                    label = { Text(spec.title.ifBlank { spec.id }) },
                                )
                            }
                            Text(
                                "${spec.model} • ${spec.baseUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    if (aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN) {
                        Text(
                            "Модель Zen (бесплатно, без регистрации):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Column(modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                            eu.kanade.tachiyomi.data.ai.AiAssistant.ZEN_MODELS.forEach { m ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { zenModel = m },
                                ) {
                                    RadioButton(selected = zenModel == m, onClick = { zenModel = m })
                                    Text(m, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = orKey,
                            onValueChange = { orKey = it },
                            label = { Text("OpenRouter API-ключ") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                        Text(
                            if (orModels.isEmpty()) "Загрузка списка :free моделей…"
                            else "Бесплатные модели (:free):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Column(modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                            orModels.forEach { m ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { orFreeModel = m },
                                ) {
                                    RadioButton(selected = orFreeModel == m, onClick = { orFreeModel = m })
                                    Text(m, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Скорость: ${"%.1f".format(rate)}× (для системных голосов)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    valueRange = 0.5f..2f,
                )

                when (engine) {
                    TtsSpeaker.ENGINE_SYSTEM -> {
                        Row(modifier = Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState())) {
                            FilterChip(
                                selected = assignMode == 0,
                                onClick = { assignMode = 0 },
                                label = { Text("Основной") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            FilterChip(
                                selected = assignMode == 1,
                                onClick = { assignMode = 1 },
                                label = { Text("♀ Женский") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            FilterChip(
                                selected = assignMode == 2,
                                onClick = { assignMode = 2 },
                                label = { Text("♂ Мужской") },
                            )
                        }
                        Text(
                            when (assignMode) {
                                1 -> "Голос для женских реплик: " + (voiceFemale.ifBlank { "не задан" })
                                2 -> "Голос для мужских реплик: " + (voiceMale.ifBlank { "не задан" })
                                else -> "Основной голос озвучки"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            !sysReady -> Text("Инициализация системного TTS…")
                            voices.isEmpty() -> Text(
                                "Голосов не найдено. Установите TTS-движок " +
                                    "(Speech Services by Google, RHVoice) в настройках системы.",
                            )
                            else -> Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                                voices.forEach { (name, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                when (assignMode) {
                                                    1 -> voiceFemale = name
                                                    2 -> voiceMale = name
                                                    else -> selectedVoice = name
                                                }
                                            },
                                    ) {
                                        RadioButton(
                                            selected = when (assignMode) {
                                                1 -> voiceFemale == name
                                                2 -> voiceMale == name
                                                else -> selectedVoice == name
                                            },
                                            onClick = {
                                                when (assignMode) {
                                                    1 -> voiceFemale = name
                                                    2 -> voiceMale = name
                                                    else -> selectedVoice = name
                                                }
                                            },
                                        )
                                        Column {
                                            Text(label, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TtsSpeaker.ENGINE_GOOGLE_WEB -> {
                        Text(
                            "Озвучка с сайта Google Translate — без API-ключа, нужен интернет.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = webLang,
                            onValueChange = { webLang = it },
                            label = { Text("Язык (ru, en, ja…)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                    TtsSpeaker.ENGINE_EDGE_TTS -> {
                        Text(
                            "Онлайн-голоса Microsoft Edge (edge-tts) — без API-ключа, нужен интернет. " +
                                "Выберите голос; мультиязычные (🌐) читают текст любого языка.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            edgeLoading -> Text(
                                "Загрузка списка голосов Microsoft Edge…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            edgeVoices.isEmpty() -> Text(
                                "Список голосов не загрузился. Проверьте интернет и повторите позже.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            else -> {
                                val languages = remember(edgeVoices) {
                                    eu.kanade.tachiyomi.data.tts.EdgeTts.languagesOf(edgeVoices)
                                }
                                val multilingual = remember(edgeVoices) {
                                    eu.kanade.tachiyomi.data.tts.EdgeTts.multilingualVoices(edgeVoices)
                                }
                                val selectedLang = remember(edgeVoice, edgeVoices) {
                                    edgeVoices.firstOrNull { it.shortName == edgeVoice }?.language ?: "ru"
                                }
                                val currentFilter: String = remember(edgeLanguage, selectedLang, languages) {
                                    when {
                                        edgeLanguage == "" -> ""
                                        edgeLanguage == "🌐" -> "🌐"
                                        edgeLanguage in languages -> edgeLanguage
                                        else -> selectedLang
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    FilterChip(
                                        selected = edgeLanguage == "auto" || edgeLanguage.isBlank(),
                                        onClick = { edgeLanguage = "auto" },
                                        label = { Text("✨ Авто (язык голоса)") },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    FilterChip(
                                        selected = currentFilter == "",
                                        onClick = { edgeLanguage = "" },
                                        label = { Text("Все языки") },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    FilterChip(
                                        selected = currentFilter == "🌐",
                                        onClick = { edgeLanguage = "🌐" },
                                        label = { Text("🌐 Мультиязычные") },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    languages.forEach { lang ->
                                        FilterChip(
                                            selected = currentFilter == lang,
                                            onClick = { edgeLanguage = lang },
                                            label = { Text(lang.uppercase()) },
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                    }
                                }
                                val filtered = remember(edgeVoices, currentFilter) {
                                    when (currentFilter) {
                                        "🌐" -> multilingual
                                        "" -> edgeVoices
                                        else -> edgeVoices.filter { it.language == currentFilter }
                                    }.sortedWith(compareBy({ it.multilingual }, { it.gender }, { it.shortName }))
                                }
                                if (currentFilter == "🌐") {
                                    Text(
                                        "Все мультиязычные голоса: читают текст любого языка автоматически.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 260.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    filtered.forEach { v ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { edgeVoice = v.shortName },
                                        ) {
                                            RadioButton(
                                                selected = edgeVoice == v.shortName,
                                                onClick = { edgeVoice = v.shortName },
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    v.shortName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    "${v.genderIcon} ${v.locale}" +
                                                        if (v.multilingual) " • 🌐 мультиязычный" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            TextButton(
                                                enabled = edgeProbingVoice == null,
                                                onClick = {
                                                    edgeProbingVoice = v.shortName
                                                    eu.kanade.tachiyomi.data.tts.TtsSpeaker.speakEdgeVoiceTest(
                                                        context,
                                                        v.shortName,
                                                    )
                                                    edgeProbingVoice = null
                                                },
                                            ) { Text("Проба") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TtsSpeaker.ENGINE_ELEVENLABS -> {
                        Text(
                            "Нейроголоса ElevenLabs. Нужен API-ключ с elevenlabs.io. " +
                                "Без ключа автоматически используется веб-озвучка.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = elevenKey,
                            onValueChange = { elevenKey = it },
                            label = { Text("API-ключ") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = elevenVoice,
                            onValueChange = { elevenVoice = it },
                            label = { Text("Voice ID (пусто = Rachel)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.voiceEngine().set(engine)
                    prefs.voiceName().set(selectedVoice)
                    prefs.voiceFemale().set(voiceFemale)
                    prefs.voiceMale().set(voiceMale)
                    prefs.aiGenderVoices().set(aiGender)
                    prefs.aiProvider().set(aiProvider)
                    prefs.zenModel().set(zenModel)
                    prefs.openrouterFreeModel().set(orFreeModel)
                    prefs.openrouterApiKey().set(orKey.trim())
                    prefs.speechRate().set(rate.coerceIn(0.5f, 2f))
                    prefs.ttsWebLanguage().set(webLang.trim().ifBlank { "ru" })
                    prefs.elevenApiKey().set(elevenKey.trim())
                    prefs.elevenVoiceId().set(elevenVoice.trim())
                    prefs.edgeVoice().set(edgeVoice.trim())
                    prefs.edgeLanguage().set(edgeLanguage.trim())
                    context.toast("Настройки озвучки сохранены")
                    onDismissRequest()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        // Проба ТЕКУЩЕГО выбора без сохранения
                        prefs.voiceEngine().set(engine)
                        prefs.voiceName().set(selectedVoice)
                        prefs.speechRate().set(rate.coerceIn(0.5f, 2f))
                        prefs.ttsWebLanguage().set(webLang.trim().ifBlank { "ru" })
                        prefs.elevenApiKey().set(elevenKey.trim())
                        prefs.elevenVoiceId().set(elevenVoice.trim())
                        prefs.edgeVoice().set(edgeVoice.trim())
                        prefs.edgeLanguage().set(edgeLanguage.trim())
                        TtsSpeaker.speak(context, "Проверка выбранного голоса Ёмикай.")
                    },
                ) { Text("Проба") }
                TextButton(onClick = onOpenFullSettings) { Text("Ещё") }
            }
        },
    )
}

/**
 * Диалог добавления голосовой роли: имя/метки (для {имя:Аки}), пол, возраст,
 * необязательный голос из списка и модификаторы питча/темпа.
 */
@Composable
private fun AddRoleDialog(
    voices: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onAdd: (eu.kanade.tachiyomi.data.tts.VoiceRole) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var markers by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("auto") }
    var age by remember { mutableStateOf("adult") }
    var voice by remember { mutableStateOf("") }
    var pitch by remember { mutableFloatStateOf(1.0f) }
    var rate by remember { mutableFloatStateOf(1.0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isNotBlank()) {
                        onAdd(
                            eu.kanade.tachiyomi.data.tts.VoiceRole(
                                id = trimmedName,
                                name = trimmedName,
                                gender = gender,
                                age = age,
                                voice = voice,
                                pitch = pitch,
                                rate = rate,
                                markers = markers.split(',')
                                    .map(String::trim)
                                    .filter { it.isNotEmpty() },
                            ),
                        )
                    }
                },
            ) { Text("Добавить") }
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        title = { Text("Новая роль") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя (совпадает с {имя:Аки})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = markers,
                    onValueChange = { markers = it },
                    label = { Text("Доп. метки через запятую (необязательно)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text("Пол:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("auto" to "Авто", "male" to "♂ Мужской", "female" to "♀ Женский", "neutral" to "⚥ Средний")
                        .forEach { (value, label) ->
                            FilterChip(
                                selected = gender == value,
                                onClick = { gender = value },
                                label = { Text(label) },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                }
                Text("Возраст:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    eu.kanade.tachiyomi.data.tts.VoicePreset.Age.entries.forEach { a ->
                        FilterChip(
                            selected = age == a.id,
                            onClick = { age = a.id },
                            label = { Text(a.title) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Text("Голос (пусто = подбор по полу/возрасту):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Column(modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    voices.forEach { (vname, vlabel) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.RadioButton(
                                selected = voice == vname,
                                onClick = { voice = vname },
                            )
                            Text(vlabel, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "Питч: ×${"%.2f".format(pitch)} (1.0 = обычный)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
                Text(
                    "Темп: ×${"%.2f".format(rate)} (1.0 = обычный)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f)
            }
        },
    )
}

/**
 * Диалог добавления правила интонации: узор фразы → пауза, питч и темп.
 */
@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (eu.kanade.tachiyomi.data.tts.VoiceIntonationRule) -> Unit,
) {
    var pattern by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(false) }
    var pauseMs by remember { mutableStateOf("") }
    var pitch by remember { mutableFloatStateOf(1.0f) }
    var rate by remember { mutableFloatStateOf(1.0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (pattern.isNotBlank()) {
                        onAdd(
                            eu.kanade.tachiyomi.data.tts.VoiceIntonationRule(
                                pattern = pattern.trim(),
                                exact = exact,
                                pauseMs = pauseMs.toIntOrNull()?.coerceIn(0, 10000) ?: 0,
                                pitch = pitch,
                                rate = rate,
                                enabled = true,
                            ),
                        )
                    }
                },
            ) { Text("Добавить") }
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        title = { Text("Новая интонация") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Узор фразы (например «Что?!» или «.!»)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = exact,
                        onCheckedChange = { exact = it },
                    )
                    Text("Точное совпадение всего предложения", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = pauseMs,
                    onValueChange = { pauseMs = it },
                    label = { Text("Пауза после фразы, мс (0 = по знакам)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text(
                    "Питч: ×${"%.2f".format(pitch)} (1.0 = обычный)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
                Text(
                    "Темп: ×${"%.2f".format(rate)} (1.0 = обычный)",
                    style = MaterialTheme.typography.labelMedium,
                )
                Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f)
            }
        },
    )
}
