package dev.aichallenge.assistants.workspace.gateway

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.llm.ToolDefinition
import dev.aichallenge.workspace.contracts.*
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration

/** Управление снимками отдельно от инструментов модели. */
interface WorkspaceData {
    fun create(id: String): SnapshotInfo
    fun list(): List<SnapshotInfo>
    fun info(id: String): SnapshotInfo
    fun artifacts(snapshot: String, run: String): List<WorkspaceArtifact>
}
/** Долгоживущая MCP-сессия привязана backend к одному снимку и запуску. */
interface WorkspaceSession : AutoCloseable {
    val tools: List<ToolDefinition>
    fun call(name: String, args: Map<String, Any>): ToolResult
}
/** Ошибка отдельного tool возвращается модели, сетевой сбой останавливает запуск. */
data class ToolResult(val text: String, val failed: Boolean)
/** Порт позволяет бесплатно воспроизводить выбор инструментов и отказы в тестах. */
interface WorkspaceTools { fun open(snapshot: String, run: String, events: MutableList<WorkspaceEvent>): WorkspaceSession }

/** Внутренний REST-клиент не предоставляет модели root/path или произвольный URL. */
@Component
class HttpWorkspaceData(@Value("$"+"{WORKSPACE_SERVICE_URL:http://workspace-mcp:8080}") private val url: String,
    private val mapper: ObjectMapper) : WorkspaceData {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun <T> call(path: String, type: Class<T>, body: Any? = null): T {
        try {
            val builder = HttpRequest.newBuilder(URI.create("$url/internal/workspaces$path")).timeout(Duration.ofSeconds(60))
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(4_000_001) }
            require(bytes.size <= 4_000_000)
            if (response.statusCode() !in 200..299) {
                val error = mapper.readTree(bytes)
                throw AssistantFailure(error.path("code").asText("WORKSPACE_UNAVAILABLE"),
                    error.path("message").asText("Workspace MCP недоступен."), response.statusCode())
            }
            return mapper.readValue(bytes, type)
        } catch (e: AssistantFailure) { throw e }
        catch (_: Exception) { throw AssistantFailure("WORKSPACE_UNAVAILABLE", "Не удалось получить рабочую область.", 503) }
    }
    override fun create(id: String) = call("", SnapshotInfo::class.java, SnapshotRequest(id))
    override fun list() = call("", Array<SnapshotInfo>::class.java).toList()
    override fun info(id: String) = call("/$id", SnapshotInfo::class.java)
    override fun artifacts(snapshot: String, run: String) = call("/$snapshot/runs/$run/artifacts", Array<WorkspaceArtifact>::class.java).toList()
}

/** Настоящий SDK initialize/list/call. Scope-параметры добавляются backend, не моделью. */
@Component
class McpWorkspaceTools(@Value("$"+"{WORKSPACE_SERVICE_URL:http://workspace-mcp:8080}") private val url: String,
    private val mapper: ObjectMapper) : WorkspaceTools {
    private val permitted = setOf("list_files", "search_files", "read_file", "create_document", "propose_file_change")
    @Suppress("UNCHECKED_CAST")
    override fun open(snapshot: String, run: String, events: MutableList<WorkspaceEvent>): WorkspaceSession {
        val transport = HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp").build()
        val client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build()
        try {
            require(client.initialize().serverInfo().name() == "game-workspace")
            events += WorkspaceEvent("MCP initialize", "SUCCESS", "game-workspace")
            val list = client.listTools().tools()
            require(list.map { it.name() }.toSet() == permitted)
            val definitions = list.map { tool ->
                val schema = mapper.readValue(mapper.writeValueAsString(tool.inputSchema()), Map::class.java) as Map<String, Any>
                val properties = (schema["properties"] as Map<String, Any>).filterKeys { it !in setOf("snapshot_id", "run_id") }
                val required = (schema["required"] as List<String>).filter { it !in setOf("snapshot_id", "run_id") }
                ToolDefinition(tool.name(), tool.description() ?: "", mapOf("type" to "object",
                    "properties" to properties, "required" to required, "additionalProperties" to false))
            }
            events += WorkspaceEvent("MCP tools/list", "SUCCESS", definitions.joinToString { it.name })
            return object : WorkspaceSession {
                override val tools = definitions
                override fun call(name: String, args: Map<String, Any>): ToolResult {
                    val definition = tools.find { it.name == name } ?: throw AssistantFailure("TOOL_DENIED", "Неизвестный инструмент.", 403)
                    val fields = (definition.parameters["properties"] as Map<String, Any>).keys
                    val required = definition.parameters["required"] as List<String>
                    if (!args.keys.all { it in fields } || !args.keys.containsAll(required))
                        return ToolResult("""{"code":"INVALID_TOOL_ARGUMENTS","message":"Неизвестные или отсутствующие аргументы; scope задаёт backend."}""", true)
                    try {
                        val result = client.callTool(McpSchema.CallToolRequest(name, args + mapOf("snapshot_id" to snapshot, "run_id" to run)))
                        val text = result.content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }
                        require(text.length <= 60_000)
                        return ToolResult(text, result.isError() == true)
                    } catch (_: Exception) { throw AssistantFailure("WORKSPACE_MCP_UNAVAILABLE", "Соединение с Workspace MCP прервано. Автоматического повтора нет.", 503) }
                }
                override fun close() { client.close() }
            }
        } catch (e: Exception) {
            client.close()
            throw AssistantFailure("WORKSPACE_MCP_UNAVAILABLE", "Не удалось открыть проверенную сессию Workspace MCP.", 503)
        }
    }
}
