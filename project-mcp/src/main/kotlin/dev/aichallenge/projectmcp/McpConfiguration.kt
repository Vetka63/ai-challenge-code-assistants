package dev.aichallenge.projectmcp

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/** Официальный SDK обслуживает MCP handshake; здесь только регистрация read-only tools. */
@Configuration
class McpConfiguration {
    /** HTTP транспорт MCP, отдельный от прикладного API ассистента. */
    @Bean
    fun transport(): HttpServletStreamableServerTransportProvider =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build()

    /** Регистрирует асинхронный servlet Streamable HTTP. */
    @Bean
    fun servlet(transport: HttpServletStreamableServerTransportProvider) =
        ServletRegistrationBean(transport, "/mcp").apply { isAsyncSupported = true }

    /** Создаёт сервер с жёсткими схемами параметров и безопасными сообщениями ошибок. */
    @Bean(destroyMethod = "close")
    fun server(transport: HttpServletStreamableServerTransportProvider, files: ProjectFiles, mapper: ObjectMapper): McpSyncServer {
        val server = McpServer.sync(transport).serverInfo("game-project-readonly", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).build()
        fun register(name: String, description: String, properties: String = "", required: String = "", handler: (Map<String, Any>) -> Any) {
            val schema = """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}"""
            val tool = McpSchema.Tool.builder().name(name).description(description)
                .inputSchema(McpJsonDefaults.getMapper(), schema).build()
            server.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler { _, request ->
                try {
                    val arguments = request.arguments() ?: emptyMap()
                    val allowed = when (name) { "read_project_file" -> setOf("path"); "search_project" -> setOf("query"); else -> emptySet() }
                    require(arguments.keys.all { it in allowed }) { "Неизвестный аргумент" }
                    McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(handler(arguments))).build()
                } catch (_: Exception) {
                    McpSchema.CallToolResult.builder().isError(true)
                        .addTextContent("Инструмент не выполнен: проверьте разрешённый путь, аргументы и доступность Git.").build()
                }
            }.build())
        }
        register("get_project_context", "Текущая ветка, Git SHA, dirty и fingerprint README/docs игры.") { files.context() }
        register("get_document_snapshot", "Согласованный read-only снимок README и docs игры для RAG.") { files.snapshot() }
        register("list_project_files", "Документы и C# прототипа; без секретов и кешей.") { files.files(true) }
        register("read_project_file", "Чтение одного разрешённого файла.",
            """"path":{"type":"string","maxLength":240}""", """"path"""") {
            files.read(it["path"] as? String ?: error("Нужен path"))
        }
        register("search_project", "Литеральный поиск в разрешённых документах и C#.",
            """"query":{"type":"string","minLength":2,"maxLength":120}""", """"query"""") {
            files.search(it["query"] as? String ?: error("Нужен query"))
        }
        return server
    }
}
