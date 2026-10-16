package dev.aichallenge.assistants.review.github

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.review.policy.*
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.Base64
import java.util.zip.ZipInputStream

data class Delivery(val key: String, val request: ReviewRequest)
data class Discovery(val deliveries: List<Delivery>, val seen: List<String>, val warnings: List<String>)
/** Один контракт GitHub для use case, worker и детерминированных тестов. */
interface GitHubGateway {
    fun pull(number: Int): PullRequest
    fun snapshot(request: ReviewRequest): ReviewSnapshot
    fun discover(seen: Set<String>): Discovery
    fun publish(job: ReviewJob, markdown: String): String
    fun verifyConnection(): String
}
data class GitHubResponse(val status: Int, val bytes: ByteArray, val location: String? = null)
/** Сеть отделена для проверки протокола без настоящих токенов и внешних комментариев. */
interface GitHubTransport {
    fun send(uri: URI, method: String, token: String?, body: String?, limit: Int): GitHubResponse
}
@Component
class JdkGitHubTransport : GitHubTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    override fun send(uri: URI, method: String, token: String?, body: String?, limit: Int): GitHubResponse {
        try {
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "ai-challenge-day32")
            if (token != null) builder.header("Authorization", "Bearer " + token).header("X-GitHub-Api-Version", "2026-03-10")
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(limit + 1) }
            if (bytes.size > limit) throw AssistantFailure("GITHUB_TOO_LARGE", "Ответ GitHub превышает безопасный лимит.")
            return GitHubResponse(response.statusCode(), bytes, response.headers().firstValue("Location").orElse(null))
        } catch (e: AssistantFailure) { throw e }
        catch (_: Exception) { throw AssistantFailure("GITHUB_UNAVAILABLE", "GitHub не ответил. Проверьте сеть; токен и сырые HTTP-данные не выводятся.", 503) }
    }
}

/** Разрешены только фиксированный GitHub API и проверенный redirect для скачивания artifact. */
@Component
class GitHubApi(private val config: ReviewConfig, private val policy: ReviewInputPolicy,
    private val mapper: ObjectMapper, private val transport: GitHubTransport) : GitHubGateway {
    private val prefix get() = "/repos/" + config.repository
    private fun request(path: String, method: String = "GET", body: Any? = null): GitHubResponse {
        val token = config.token()
        if (token.isBlank()) throw AssistantFailure("GITHUB_TOKEN_MISSING", "Сохраните GitHub-токен локально по инструкции дня 32.", 409)
        require(path.startsWith(prefix + "/") || path == prefix)
        val response = transport.send(URI.create("https://api.github.com" + path), method, token,
            body?.let { mapper.writeValueAsString(it) }, 8_000_000)
        if (response.status !in 200..299 && response.status != 302)
            throw AssistantFailure("GITHUB_HTTP_" + response.status, when (response.status) {
                401 -> "GitHub не принял токен: проверьте срок действия."
                403, 429 -> "GitHub ограничил запрос или у токена недостаточно прав."
                404 -> "GitHub не нашёл ресурс: проверьте репозиторий, workflow и права токена."
                else -> "Запрос к GitHub не выполнен; результат ревью не подменён успехом."
            }, 502)
        return response
    }
    private fun json(path: String, method: String = "GET", body: Any? = null): JsonNode {
        val response = request(path, method, body)
        require(response.status != 302) { "Неожиданный redirect API" }
        return mapper.readTree(response.bytes)
    }
    override fun verifyConnection(): String = json(prefix)["full_name"].asText().also { require(it == config.repository) }
    override fun pull(number: Int): PullRequest {
        require(number in 1..10_000_000)
        val node = json(prefix + "/pulls/" + number)
        return PullRequest(node["number"].asInt(), node["title"].asText().take(1000), node["body"]?.asText("")?.take(10000) ?: "",
            node["base"]["sha"].asText(), node["head"]["sha"].asText(), node["base"]["repo"]["full_name"].asText(),
            node["head"]["repo"]?.get("full_name")?.asText() ?: "", node["user"]["login"].asText(),
            node["state"].asText(), node["changed_files"].asInt(), node["html_url"].asText())
    }
    /** Документы и код читаются через git blobs неизменяемых деревьев, не local checkout. */
    override fun snapshot(request: ReviewRequest): ReviewSnapshot {
        policy.request(request)
        val pr = pull(request.pr); policy.matches(request, pr)
        if (pr.changedFiles > 300) throw AssistantFailure("PR_TOO_LARGE", "В PR больше 300 файлов. Разделите его: частичный список не выдаётся за полный.")
        val notes = mutableListOf<String>()
        val allFiles = mutableListOf<ChangedFile>()
        for (page in 1..3) {
            val nodes = json(prefix + "/pulls/" + request.pr + "/files?per_page=100&page=" + page)
            nodes.forEach { node ->
                val patch = node["patch"]?.takeIf { it.isTextual }?.asText()
                val lines = changedLines(patch)
                allFiles += ChangedFile(node["filename"].asText(), node["previous_filename"]?.asText(),
                    node["status"].asText(), patch, lines.first, lines.second,
                    lines.first.size == (node["additions"]?.asInt() ?: lines.first.size) &&
                        lines.second.size == (node["deletions"]?.asInt() ?: lines.second.size))
            }
            if (nodes.size() < 100) break
        }
        if (allFiles.size != pr.changedFiles) throw AssistantFailure("FILES_INCOMPLETE", "GitHub вернул неполный список файлов; ревью остановлено.")
        var diffSize = 0
        val files = allFiles.filter { file ->
            val reason = when {
                !reviewPath(file.path) || (file.previousPath != null && !reviewPath(file.previousPath)) -> "неподдерживаемый путь/формат"
                file.patch.isNullOrBlank() -> "нет текстового patch"
                !file.completePatch -> "GitHub вернул неполный patch"
                file.patch.length > 12000 || diffSize + file.patch.length > 18000 -> "лимит контекста diff"
                else -> null
            }
            if (reason != null) { notes += file.path + ": " + reason; false }
            else { diffSize += file.patch!!.length; true }
        }
        val documents = mutableListOf<ReviewDocument>(); var characters = 0
        listOf("BASE" to request.baseSha, "HEAD" to request.headSha).forEach { (side, sha) ->
            val tree = json(prefix + "/git/trees/" + sha + "?recursive=1")
            if (tree["truncated"]?.asBoolean() == true) throw AssistantFailure("TREE_INCOMPLETE", "GitHub обрезал дерево файлов; анализ остановлен.")
            val allowed = tree["tree"].filter { it["type"].asText() == "blob" && reviewPath(it["path"].asText()) }
            val candidates = allowed.filter { node ->
                val path = node["path"].asText()
                if (side == "BASE") path.endsWith(".md") || files.any { (it.previousPath ?: it.path) == path }
                else path.endsWith(".cs") || files.any { it.path == path }
            }.sortedBy { node ->
                val path = node["path"].asText()
                when { files.any { it.path == path || it.previousPath == path } -> "0" + path; path == "README.md" -> "1"; else -> "2" + path }
            }
            if (candidates.size > 100) notes += side + ": доступны только первые 100 релевантных файлов"
            candidates.take(100).forEach { node ->
                val path = node["path"].asText()
                val size = node["size"]?.asInt() ?: 0
                if (size > 256000 || characters + size > 1_000_000) notes += side + " " + path + ": превышен размер корпуса"
                else {
                    val blobSha = node["sha"].asText(); require(Regex("[0-9a-f]{40}").matches(blobSha))
                    val blob = json(prefix + "/git/blobs/" + blobSha)
                    require(blob["encoding"].asText() == "base64")
                    val bytes = Base64.getMimeDecoder().decode(blob["content"].asText())
                    if (bytes.size > 256000 || bytes.any { it == 0.toByte() }) notes += side + " " + path + ": не текстовый/слишком большой файл"
                    else {
                        val text = bytes.toString(Charsets.UTF_8); characters += text.length
                        documents += ReviewDocument(path, text, sha, side)
                    }
                }
            }
        }
        policy.matches(request, pull(request.pr))
        return ReviewSnapshot(pr, files, documents, notes)
    }
    /** ZIP читается в память с лимитом и ровно одним именованным JSON, без извлечения на диск. */
    fun manifest(artifactId: Long): ReviewRequest {
        require(artifactId > 0)
        val response = request(prefix + "/actions/artifacts/" + artifactId + "/zip")
        val bytes = if (response.status == 302) {
            val uri = URI.create(response.location ?: error("No artifact location"))
            val host = uri.host ?: ""
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
                (host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com") || host.endsWith(".githubusercontent.com")))
            // API-токен никогда не пересылается presigned redirect-хосту.
            val download = transport.send(uri, "GET", null, null, 128000)
            require(download.status == 200)
            download.bytes
        } else response.bytes
        require(bytes.size <= 128000)
        return ZipInputStream(bytes.inputStream()).use { zip ->
            val entry = zip.nextEntry ?: error("Empty artifact")
            require(entry.name == "review-request.json" && !entry.isDirectory)
            val text = zip.readNBytes(16385); require(text.size <= 16384)
            val result = mapper.readValue(text, ReviewRequest::class.java)
            require(zip.nextEntry == null); policy.request(result); result
        }
    }
    /** Доставка проверяется по run, workflow, actor и текущему PR; старые SHA не запускают LLM. */
    override fun discover(seen: Set<String>): Discovery {
        val deliveries = mutableListOf<Delivery>(); val observed = mutableListOf<String>(); val warnings = mutableListOf<String>()
        for (page in 1..3) {
            val result = json(prefix + "/actions/workflows/" + config.workflow + "/runs?event=pull_request&status=success&per_page=100&page=" + page)
            val runs = result["workflow_runs"]
            runs.forEach { run ->
                val key = run["id"].asLong().toString() + ":" + run["run_attempt"].asInt(1)
                if (key in seen) return@forEach
                try {
                val trusted = run["event"].asText() == "pull_request" && run["conclusion"].asText() == "success" &&
                    run["path"].asText() == ".github/workflows/" + config.workflow &&
                    run["head_repository"]?.get("full_name")?.asText() == config.repository &&
                    run["actor"]?.get("login")?.asText() == config.author
                if (!trusted) { observed += key; warnings += "Пропущен недоверенный запуск " + key; return@forEach }
                val artifacts = json(prefix + "/actions/runs/" + run["id"].asLong() + "/artifacts?per_page=100")["artifacts"]
                val artifact = artifacts.firstOrNull { it["name"].asText() == "ai-review-request" && !it["expired"].asBoolean() }
                val input = if (artifact != null) {
                    if (artifact["size_in_bytes"].asLong() > 128000) { observed += key; warnings += "Artifact превышает лимит: " + key; return@forEach }
                    manifest(artifact["id"].asLong())
                } else {
                    val item = run["pull_requests"]?.firstOrNull()
                    if (item == null) { warnings += "Нет доступного задания/PR для запуска " + key; return@forEach }
                    val pr = pull(item["number"].asInt())
                    if (pr.headSha != item["head"]["sha"].asText()) { observed += key; return@forEach }
                    ReviewRequest(config.repository, pr.number, pr.baseSha, pr.headSha, run["id"].asLong())
                }
                try {
                    policy.matches(input, pull(input.pr))
                    require(input.runId == run["id"].asLong()) { "Artifact run ID mismatch" }
                    val linked = run["pull_requests"]
                    if (linked != null && linked.size() > 0)
                        require(linked.any { it["number"].asInt() == input.pr && it["head"]["sha"].asText() == input.headSha })
                    deliveries += Delivery(key, input); observed += key
                } catch (e: AssistantFailure) {
                    if (e.code in setOf("PR_CHANGED", "PR_CLOSED", "UNTRUSTED_PR")) { observed += key; warnings += "Неактуальное/недопустимое задание " + key }
                    else throw e
                }
                } catch (e: Exception) {
                    val code = (e as? AssistantFailure)?.code
                    if (code in setOf("GITHUB_HTTP_401", "GITHUB_HTTP_403", "GITHUB_HTTP_429")) throw e
                    if (e !is AssistantFailure) observed += key
                    warnings += "Задание " + key + " не принято: " + (code ?: "некорректный artifact")
                }
            }
            if (runs.size() < 100) break
            if (page == 3) warnings += "Проверены последние 300 запусков workflow; более старая история не охвачена"
        }
        return Discovery(deliveries, observed, warnings)
    }
    /** Устойчивый маркер позволяет повторить публикацию, не создавая второй комментарий. */
    override fun publish(job: ReviewJob, markdown: String): String {
        if (markdown.length > 60000) throw AssistantFailure("REPORT_TOO_LARGE", "Отчёт сохранён, но превышает безопасный размер GitHub-комментария.")
        policy.matches(job.request, pull(job.request.pr))
        val marker = "<!-- ai-review:" + job.id + " -->"
        val path = prefix + "/issues/" + job.request.pr + "/comments"
        for (page in 1..10) {
            val comments = json(path + "?per_page=100&page=" + page)
            val existing = comments.firstOrNull { it["body"].asText("").startsWith(marker) && it["user"]["login"].asText() == config.author }
            if (existing != null) return existing["html_url"].asText()
            if (comments.size() < 100) return json(path, "POST", mapOf("body" to marker + "\n" + markdown))["html_url"].asText()
        }
        throw AssistantFailure("COMMENTS_LIMIT", "Не удалось проверить всю историю комментариев; повторная публикация остановлена.")
    }
}
