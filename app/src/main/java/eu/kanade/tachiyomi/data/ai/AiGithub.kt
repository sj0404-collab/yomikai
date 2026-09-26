package eu.kanade.tachiyomi.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.net.HttpURLConnection

/**
 * GitHub: прогоняемые сценарии, их шаги и вызовы API с правом записи.
 *
 * Читатель просил две вещи: «видеть события, что ранер делал» и «чтобы он мог
 * с моими репозиториями работать». Первое — это журнал GitHub Actions: у
 * каждого запуска есть шаги с именем, статусом и временем, и это честный
 * источник событий, а не «мы что-то делали в фоне». Второе — обычные вызовы
 * API, где раньше был только GET.
 *
 * Форматирование вынесено в чистые функции, чтобы его можно было проверить
 * unit-тестами без сети.
 */
object AiGithub {

    const val DEFAULT_REPO = "sj0404-collab/yomikai"

    data class Run(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String?,
        val createdAt: String,
        val url: String,
    ) {
        /** Человеческое «идёт / успешно / упало» вместо английских слов. */
        val stateRu: String
            get() = when {
                status != "completed" -> "идёт"
                conclusion == "success" -> "успешно"
                conclusion == "failure" -> "упало"
                conclusion == "cancelled" -> "отменено"
                conclusion == "skipped" -> "пропущено"
                conclusion != null -> conclusion
                else -> "неизвестно"
            }
    }

    data class Step(
        val number: Int,
        val name: String,
        val status: String,
        val conclusion: String?,
    ) {
        val stateRu: String
            get() = when {
                status != "completed" -> "идёт"
                conclusion == "success" -> "ок"
                conclusion == "failure" -> "ошибка"
                conclusion == "skipped" -> "пропущено"
                conclusion != null -> conclusion
                else -> "—"
            }
    }

    data class Job(
        val name: String,
        val status: String,
        val conclusion: String?,
        val steps: List<Step>,
    )

    /** Ответ API: код и тело — как есть, разбор ошибки делает вызывающий. */
    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * Вызов GitHub API.
     *
     * Метод по умолчанию GET, но принимает и POST/PATCH/PUT/DELETE: право
     * записи нужно, чтобы агент мог завести issue, оставить комментарий или
     * открыть PR. Хост жёстко задан — PAT не должен уходить на сторонний
     * адрес, который подсунул бы шаблон.
     */
    suspend fun api(
        token: String,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): Response = withContext(Dispatchers.IO) {
        val conn = try {
            AiAssistant.openConnection("https://api.github.com$path") as HttpURLConnection
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "AiGithub connect failed for $path" }
            return@withContext Response(0, "не удалось подключиться: ${e.message}")
        }
        try {
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "Yomikai")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Response(code, stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "AiGithub request failed: $method $path" }
            Response(0, "ошибка сети: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Последние прогоны сценариев — «что ранер делал». */
    suspend fun runs(token: String, repo: String = DEFAULT_REPO, perPage: Int = 10): List<Run> {
        val r = api(token, "/repos/$repo/actions/runs?per_page=${perPage.coerceIn(1, 50)}")
        if (!r.ok) return emptyList()
        return runCatching {
            val arr = JSONObject(r.body).optJSONArray("workflow_runs") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Run(
                            id = o.optLong("id"),
                            name = o.optString("name").ifBlank { "сценарий" },
                            status = o.optString("status"),
                            conclusion = o.optString("conclusion").ifBlank { null },
                            createdAt = o.optString("created_at"),
                            url = o.optString("html_url"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Шаги всех задач прогона — пошаговая лента событий. */
    suspend fun jobs(token: String, runId: Long, repo: String = DEFAULT_REPO): List<Job> {
        val r = api(token, "/repos/$repo/actions/runs/$runId/jobs?per_page=20")
        if (!r.ok) return emptyList()
        return runCatching {
            val arr = JSONObject(r.body).optJSONArray("jobs") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val stepsArr = o.optJSONArray("steps") ?: JSONArray()
                    val steps = buildList {
                        for (j in 0 until stepsArr.length()) {
                            val s = stepsArr.optJSONObject(j) ?: continue
                            add(
                                Step(
                                    number = s.optInt("number", j + 1),
                                    name = s.optString("name"),
                                    status = s.optString("status"),
                                    conclusion = s.optString("conclusion").ifBlank { null },
                                ),
                            )
                        }
                    }
                    add(
                        Job(
                            name = o.optString("name"),
                            status = o.optString("status"),
                            conclusion = o.optString("conclusion").ifBlank { null },
                            steps = steps,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Проверки в открытом PR — «что делает бот/раннер в репозитории» с другой
     * стороны. Отдельный запрос, потому что у PR и прогонов разные ключи.
     */
    suspend fun openPulls(token: String, repo: String = DEFAULT_REPO, perPage: Int = 10): List<Pair<String, String>> {
        val r = api(token, "/repos/$repo/pulls?state=open&per_page=${perPage.coerceIn(1, 50)}")
        if (!r.ok) return emptyList()
        return runCatching {
            val arr = JSONArray(r.body)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add("#${o.optInt("number")} ${o.optString("title")}" to o.optString("html_url"))
                }
            }
        }.getOrDefault(emptyList())
    }

    // --- Форматирование: чистые функции, проверяются тестами без сети ---

    fun renderRuns(runs: List<Run>): String {
        if (runs.isEmpty()) return "Прогонов не найдено"
        return buildString {
            append("Прогоны сценариев (новые сверху):")
            runs.forEach { r ->
                append("\n• #${r.id} ${r.name} — ${r.stateRu}")
                if (r.createdAt.isNotBlank()) append(" · ${r.createdAt}")
            }
            append("\nШаги конкретного прогона: runner_events с run_id.")
        }
    }

    fun renderJobs(jobs: List<Job>): String {
        if (jobs.isEmpty()) return "У прогона нет задач (возможно, он ещё не начался)"
        return buildString {
            jobs.forEach { j ->
                append("Задача: ${j.name}")
                if (j.conclusion != null) append(" — ${j.conclusion}")
                append('\n')
                j.steps.forEach { s ->
                    append("  ${s.number}. ${s.name} — ${s.stateRu}\n")
                }
            }
        }.trimEnd()
    }

    fun renderPulls(pulls: List<Pair<String, String>>): String {
        if (pulls.isEmpty()) return "Открытых PR нет"
        return pulls.joinToString("\n") { "• ${it.first}\n  ${it.second}" }
    }
}
