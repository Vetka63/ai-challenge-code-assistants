package dev.aichallenge.assistants.project

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.config.RuntimeConfig
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant

/** Актуальные Git-данные получены через MCP, а не из embedding-индекса. */
data class ProjectContext(val project: String, val branch: String?, val sha: String,
    val dirty: Boolean, val fingerprint: String, val fileCount: Int)
/** Документ получен от read-only сервера с точным исходным текстом. */
data class ProjectDocument(val path: String, val hash: String, val text: String, val status: String)
/** Вход индексатора, согласованный с конкретной версией проекта. */
data class ProjectSnapshot(val context: ProjectContext, val documents: List<ProjectDocument>)
/** Публичный журнал реальных операций без секретов и полных дубликатов корпуса. */
data class McpEvent(val method: String, val status: String, val durationMs: Long,
    val at: String = Instant.now().toString(), val details: String = "")

/** Порт контекста проекта; в тестах заменяется без доступа к настоящей игре. */
interface ProjectGateway {
    fun context(events: MutableList<McpEvent>): ProjectContext
    fun snapshot(events: MutableList<McpEvent>): ProjectSnapshot
}

/** Настоящий MCP-клиент SDK: initialize → tools/list → tools/call, без REST-подмены. */
@Component
class McpProjectGateway(private val config: RuntimeConfig, private val mapper: ObjectMapper) : ProjectGateway {
    /** Один короткий сеанс на операцию; после перезапуска MCP нет устаревшего session ID. */
    private fun <T> call(tool: String, type: Class<T>, events: MutableList<McpEvent>): T {
        var stage = "initialize"
        var started = System.nanoTime()
        try {
            val transport = HttpClientStreamableHttpTransport.builder(config.mcpUrl).endpoint("/mcp").build()
            McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build().use { client ->
                client.initialize()
                events += McpEvent(stage, "SUCCESS", elapsed(started), details = "game-project-readonly")
                stage = "tools/list"; started = System.nanoTime()
                val tools = client.listTools().tools().map { it.name() }
                require(tool in tools)
                events += McpEvent(stage, "SUCCESS", elapsed(started), details = tools.joinToString(", "))
                stage = "tools/call:$tool"; started = System.nanoTime()
                val result = client.callTool(McpSchema.CallToolRequest(tool, emptyMap()))
                if (result.isError() == true) throw IllegalStateException("MCP tool error")
                val text = result.content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }
                val value = mapper.readValue(text, type)
                events += McpEvent(stage, "SUCCESS", elapsed(started), details = if (tool == "get_document_snapshot") "README + docs" else text)
                return value
            }
        } catch (_: Exception) {
            events += McpEvent(stage, "FAILED", elapsed(started), details = "MCP недоступен или вернул ошибку")
            throw AssistantFailure("MCP_UNAVAILABLE", "Не удалось получить текущий контекст игры через MCP. Проверьте Project MCP и Git.", 503)
        }
    }
    /** Переводит монотонные наносекунды в длительность операции. */
    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    /** Получает текущую ветку и хеши. */
    override fun context(events: MutableList<McpEvent>) = call("get_project_context", ProjectContext::class.java, events)
    /** Получает фактический корпус README/docs через инструмент MCP. */
    override fun snapshot(events: MutableList<McpEvent>) = call("get_document_snapshot", ProjectSnapshot::class.java, events)
}
