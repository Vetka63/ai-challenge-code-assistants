package dev.aichallenge.assistants.passport.source

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.passport.model.PassportEvidence
import dev.aichallenge.assistants.workspace.gateway.WorkspaceTools
import dev.aichallenge.workspace.contracts.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration

interface PassportSources {
    fun collect(): PassportCollection
    fun image(id: String): Pair<String, ByteArray>
}
/** HTTP только до настроенного Workspace MCP; root и внешние ссылки модель не задаёт. */
@Component
class HttpPassportSources(@Value("$"+"{WORKSPACE_SERVICE_URL:http://workspace-mcp:8080}") private val url: String,
    private val mapper: ObjectMapper) : PassportSources {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun call(path: String, post: Boolean): Pair<String, ByteArray> {
        try {
            val request = HttpRequest.newBuilder(URI.create("$url/internal/passport$path")).timeout(Duration.ofSeconds(90))
            if (post) request.POST(HttpRequest.BodyPublishers.noBody()) else request.GET()
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(30_000_001) }
            require(bytes.size <= 30_000_000)
            if (response.statusCode() !in 200..299) {
                val error = mapper.readTree(bytes)
                throw AssistantFailure(error.path("code").asText("COLLECTOR_UNAVAILABLE"), error.path("message").asText("Сборщик недоступен."), response.statusCode())
            }
            return response.headers().firstValue("Content-Type").orElse("") to bytes
        } catch (e: AssistantFailure) { throw e }
        catch (_: Exception) { throw AssistantFailure("COLLECTOR_UNAVAILABLE", "Не удалось собрать источники. Автоматического вызова модели нет.", 503) }
    }
    override fun collect() = mapper.readValue(call("/collect", true).second, PassportCollection::class.java)
    override fun image(id: String): Pair<String, ByteArray> {
        require(Regex("[a-f0-9]{64}").matches(id))
        return call("/images/$id", false).also { require(it.first in setOf("image/png", "image/jpeg") && it.second.size <= 8_000_000) }
    }
}

/** Ограниченный набор актуальных документов и кода; каждый диапазон действительно читается через MCP. */
@Component
class PassportEvidenceReader(private val tools: WorkspaceTools, private val mapper: ObjectMapper) {
    fun read(collection: PassportCollection, run: String, provider: String, events: MutableList<WorkspaceEvent>): List<PassportEvidence> {
        val files = collection.snapshot.files
        val readme = files.firstOrNull { it.path == "README.md" }
        val links = Regex("\\]\\((docs/[^) #]+\\.md)").findAll(readme?.text?.lines()?.take(100)?.joinToString("\n") ?: "")
            .map { it.groupValues[1] }.filterNot { it.contains("/research/") }.toList()
        val ordered = listOfNotNull(readme) + files.filter { it.path.endsWith("EditorBuildSettings.asset") } +
            links.distinct().take(if (provider == "LOCAL") 2 else 6).mapNotNull { link -> files.find { it.path == link } } +
            files.filter { it.path.endsWith(".cs") && !it.path.contains("/Tests/") }
                .sortedBy { if (it.path.endsWith("ArenaSettings.cs")) "0" else it.path }
        val limit = if (provider == "LOCAL") 11_000 else 46_000
        val sources = mutableListOf<PassportEvidence>()
        var remaining = limit
        tools.open(collection.snapshot.info.id, run, events).use { session ->
            for (file in ordered.distinctBy { it.path }) {
                if (remaining < 500 || sources.size >= 18) break
                val lines = file.text.lines()
                // README: актуальное начало. Документы: начало с явной областью/статусом, не только совпавшее слово.
                val maxChars = minOf(remaining, if (file.path == "README.md") (if (provider == "LOCAL") 3300 else 6500)
                    else if (file.path.endsWith(".cs")) 2600 else if (provider == "LOCAL") 1500 else 4200)
                var end = 0; var size = 0
                for (line in lines.take(160)) { if (size + line.length + 12 > maxChars) break; size += line.length + 12; end++ }
                if (end == 0) continue
                val result = session.call("read_file", mapOf("path" to file.path, "start_line" to 1, "end_line" to end))
                if (result.failed) throw AssistantFailure("PASSPORT_READ_FAILED", "MCP не подтвердил чтение источника.", 502)
                val node = mapper.readTree(result.text)
                if (node.path("path").asText() != file.path || node.path("hash").asText() != file.hash ||
                    node.path("startLine").asInt() != 1 || node.path("endLine").asInt() != end)
                    throw AssistantFailure("SOURCE_MISMATCH", "MCP вернул фрагмент другой версии/диапазона. Модель не вызывалась.", 502)
                val text = node.path("numberedText").asText()
                if (text != lines.take(end).mapIndexed { i, line -> "${i + 1}: $line" }.joinToString("\n"))
                    throw AssistantFailure("SOURCE_MISMATCH", "Текст MCP не совпал с сохранённым снимком. Модель не вызывалась.", 502)
                sources += PassportEvidence("S${sources.size + 1}", file.path, file.hash, 1, end, text)
                remaining -= text.length
                events += WorkspaceEvent("MCP read_file", "SUCCESS", "${file.path}:1–$end")
            }
        }
        if (sources.none { it.path == "README.md" } || sources.size < 3)
            throw AssistantFailure("PASSPORT_CONTEXT", "Для паспорта нужно минимум три прочитанных файла, включая README.", 409)
        return sources
    }
}
