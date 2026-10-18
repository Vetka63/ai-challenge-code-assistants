package dev.aichallenge.support.mcp

import dev.aichallenge.support.contracts.SupportFailure
import dev.aichallenge.support.service.SupportTickets
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.*
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.*
import tools.jackson.databind.ObjectMapper

/** MCP открывает только чтение: модель не создаёт и не закрывает обращения. */
@Configuration
class SupportMcpConfiguration {
    @Bean fun transport() = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build()
    @Bean fun servlet(transport: HttpServletStreamableServerTransportProvider) =
        ServletRegistrationBean(transport, "/mcp").apply { isAsyncSupported = true }
    /** Все инструменты проверяют профиль и владельца на стороне сервера. Это учебная identity, не login. */
    @Bean(destroyMethod = "close") fun server(transport: HttpServletStreamableServerTransportProvider,
        tickets: SupportTickets, mapper: ObjectMapper): McpSyncServer {
        val server = McpServer.sync(transport).serverInfo("game-support-readonly", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).build()
        fun add(name: String, description: String, ticket: Boolean, action: (Map<String, Any>) -> Any) {
            val properties = linkedMapOf<String, Any>("user_id" to mapOf("type" to "string"))
            if (ticket) properties["ticket_id"] = mapOf("type" to "string", "format" to "uuid")
            val schema = mapper.writeValueAsString(mapOf("type" to "object", "properties" to properties,
                "required" to properties.keys.toList(), "additionalProperties" to false))
            server.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(McpSchema.Tool.builder()
                .name(name).description(description).inputSchema(McpJsonDefaults.getMapper(), schema).build()).callHandler { _, request ->
                try {
                    val args = request.arguments() ?: emptyMap()
                    require(args.keys == if (ticket) setOf("user_id", "ticket_id") else setOf("user_id"))
                    McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(action(args))).build()
                } catch (e: Exception) {
                    val failure = e as? SupportFailure
                    McpSchema.CallToolResult.builder().isError(true).addTextContent(mapper.writeValueAsString(mapOf(
                        "code" to (failure?.code ?: "INVALID_REQUEST"), "message" to (failure?.message ?: "Неверные аргументы инструмента.")))).build()
                }
            }.build())
        }
        add("get_user_context", "Учебный профиль выбранного пользователя, без чужих обращений.", false) {
            tickets.user(it["user_id"] as? String ?: error("user_id"))
        }
        add("list_my_tickets", "Список обращений только выбранного учебного пользователя.", false) {
            tickets.list(it["user_id"] as? String ?: error("user_id"))
        }
        add("get_ticket_context", "Обстоятельства выбранного обращения и последние 3 завершённых обмена; читать перед ответом по тикету.", true) {
            tickets.context(it["user_id"] as? String ?: error("user_id"), it["ticket_id"] as? String ?: error("ticket_id"))
        }
        return server
    }
}
