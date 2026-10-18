package dev.aichallenge.assistants.support.gateway

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.support.contracts.*
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.*
import java.time.Duration

/** REST-контракт владельца тикетов. JSON не монтируется в backend вторым писателем. */
interface SupportData {
    fun users(): List<SupportUser>
    fun list(user: String): List<TicketSummary>
    fun ticket(user: String, id: String): SupportTicket
    fun create(user: String, draft: TicketDraft): SupportTicket
    fun add(user: String, id: String, input: SupportQuestion): SupportTurn
    fun save(user: String, id: String, turn: SupportTurn): SupportTurn
    fun recover()
}
/** Контекст для ответа приходит именно через MCP, не из UI или REST-чтения. */
interface SupportContextGateway {
    fun context(user: String, ticket: String, events: MutableList<SupportEvent>): TicketContext
}
/** Один адрес внутреннего сервиса, ограниченные размеры и безопасные ошибки. */
@Component
class HttpSupportData(@Value("$"+"{SUPPORT_SERVICE_URL:http://support-mcp:8080}") private val url: String,
    private val mapper: ObjectMapper) : SupportData {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun <T> call(path: String, user: String?, type: Class<T>, body: Any? = null, method: String = "POST"): T {
        try {
            val builder = HttpRequest.newBuilder(URI.create("$url/internal/support$path")).timeout(Duration.ofSeconds(20))
            if (user != null) builder.header("X-Demo-User", user)
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(8_100_001) }
            require(bytes.size <= 8_100_000)
            if (response.statusCode() !in 200..299) {
                val error = mapper.readTree(bytes)
                throw AssistantFailure(error.path("code").asText("SUPPORT_UNAVAILABLE"),
                    error.path("message").asText("Сервис поддержки недоступен."), response.statusCode())
            }
            return mapper.readValue(bytes, type)
        } catch (e: AssistantFailure) { throw e }
        catch (_: Exception) { throw AssistantFailure("SUPPORT_UNAVAILABLE", "Не удалось связаться с JSON-хранилищем поддержки.", 503) }
    }
    override fun users() = call("/users", null, Array<SupportUser>::class.java).toList()
    override fun list(user: String) = call("/tickets", user, Array<TicketSummary>::class.java).toList()
    override fun ticket(user: String, id: String) = call("/tickets/$id", user, SupportTicket::class.java)
    override fun create(user: String, draft: TicketDraft) = call("/tickets", user, SupportTicket::class.java, draft)
    override fun add(user: String, id: String, input: SupportQuestion) = call("/tickets/$id/turns", user, SupportTurn::class.java, input)
    override fun save(user: String, id: String, turn: SupportTurn) =
        call("/tickets/$id/turns", user, SupportTurn::class.java, SaveTurn(turn, turn.revision), "PUT")
    override fun recover() { call("/recover", null, Map::class.java, emptyMap<String, String>()) }
}
/** Настоящий MCP SDK: initialize, tools/list и get_ticket_context с проверкой границы профиля. */
@Component
class McpSupportContext(@Value("$"+"{SUPPORT_SERVICE_URL:http://support-mcp:8080}") private val url: String,
    private val mapper: ObjectMapper) : SupportContextGateway {
    override fun context(user: String, ticket: String, events: MutableList<SupportEvent>): TicketContext {
        var stage = "initialize"
        var started = System.nanoTime()
        fun event(status: String, details: String) {
            events += SupportEvent(stage, status, (System.nanoTime() - started) / 1_000_000, details)
        }
        try {
            val transport = HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp").build()
            McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build().use { client ->
                val server = client.initialize()
                require(server.serverInfo().name() == "game-support-readonly")
                event("SUCCESS", "game-support-readonly")
                stage = "tools/list"; started = System.nanoTime()
                val tools = client.listTools().tools().map { it.name() }
                require("get_ticket_context" in tools)
                event("SUCCESS", tools.joinToString(", "))
                stage = "tools/call:get_ticket_context"; started = System.nanoTime()
                val result = client.callTool(McpSchema.CallToolRequest("get_ticket_context", mapOf("user_id" to user, "ticket_id" to ticket)))
                if (result.isError() == true) throw AssistantFailure("SUPPORT_CONTEXT_DENIED", "Контекст обращения недоступен через MCP.", 403)
                val text = result.content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }
                require(text.length <= 50_000)
                val context = mapper.readValue(text, TicketContext::class.java)
                require(context.user.id == user && context.ticketId == ticket)
                event("SUCCESS", "Контекст одного обращения; история до 3 завершённых обменов.")
                return context
            }
        } catch (e: Exception) {
            event("FAILED", "Контекст MCP не получен; генерация не запускается.")
            if (e is AssistantFailure) throw e
            throw AssistantFailure("SUPPORT_MCP_UNAVAILABLE", "Support MCP недоступен. Вопрос сохранён, модель не вызывалась.", 503)
        }
    }
}
