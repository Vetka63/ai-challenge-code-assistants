package dev.aichallenge.workspacemcp.mcp

import dev.aichallenge.workspace.contracts.*
import dev.aichallenge.workspacemcp.storage.WorkspaceStore
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.*
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.*
import tools.jackson.databind.ObjectMapper

/** MCP доступен только во внутренней Docker-сети; модель не получает пути ОС и секреты. */
@Configuration
class WorkspaceMcpConfiguration {
    @Bean fun transport() = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build()
    @Bean fun servlet(transport: HttpServletStreamableServerTransportProvider) =
        ServletRegistrationBean(transport, "/mcp").apply { isAsyncSupported = true }
    /** Регистрирует реальные инструменты SDK с обязательной серверной проверкой аргументов. */
    @Bean(destroyMethod = "close") fun server(transport: HttpServletStreamableServerTransportProvider,
        store: WorkspaceStore, mapper: ObjectMapper): McpSyncServer {
        val server = McpServer.sync(transport).serverInfo("game-workspace", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).build()
        val string = mapOf("type" to "string")
        val integer = mapOf("type" to "integer")
        fun text(args: Map<String, Any>, key: String) = args[key] as? String ?: throw IllegalArgumentException(key)
        fun number(args: Map<String, Any>, key: String, default: Int): Int {
            val value = args[key] ?: return default
            require(value is Number && value.toDouble() == value.toInt().toDouble())
            return value.toInt()
        }
        fun add(name: String, description: String, fields: Map<String, Any>, required: List<String>,
            action: (Map<String, Any>) -> Any) {
            val properties = linkedMapOf<String, Any>("snapshot_id" to string, "run_id" to string).apply { putAll(fields) }
            val schema = mapper.writeValueAsString(mapOf("type" to "object", "properties" to properties,
                "required" to listOf("snapshot_id", "run_id") + required, "additionalProperties" to false))
            server.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(McpSchema.Tool.builder()
                .name(name).description(description).inputSchema(McpJsonDefaults.getMapper(), schema).build()).callHandler { _, request ->
                try {
                    val args = request.arguments() ?: emptyMap()
                    require(args.keys.all { it in properties } && args.keys.containsAll(listOf("snapshot_id", "run_id") + required))
                    store.uuid(text(args, "snapshot_id")); store.uuid(text(args, "run_id"))
                    McpSchema.CallToolResult.builder().addTextContent(mapper.writeValueAsString(action(args))).build()
                } catch (e: Exception) {
                    val known = e as? WorkspaceFailure
                    McpSchema.CallToolResult.builder().isError(true).addTextContent(mapper.writeValueAsString(mapOf(
                        "code" to (known?.code ?: "INVALID_TOOL_ARGUMENTS"),
                        "message" to (known?.message ?: "Проверьте аргументы инструмента. Исходники не изменены.")))).build()
                }
            }.build())
        }
        add("list_files", "Список файлов снимка с хешами и количеством строк. Используйте offset для следующих страниц.",
            mapOf("offset" to integer, "limit" to integer), emptyList()) {
            store.files(text(it, "snapshot_id"), number(it, "offset", 0), number(it, "limit", 60))
        }
        add("search_files", "Буквальный поиск по README, docs и C#. Возвращает строки и total/hasMore; при необходимости продолжайте с offset.",
            mapOf("query" to string, "offset" to integer, "limit" to integer), listOf("query")) {
            store.search(text(it, "snapshot_id"), text(it, "query"), number(it, "offset", 0), number(it, "limit", 25))
        }
        add("read_file", "Прочитать 1–160 строк настоящего файла. Перед анализом и сохранением прочитайте минимум 3 разных файла. Диапазоны 1-based.",
            mapOf("path" to string, "start_line" to integer, "end_line" to integer), listOf("path")) {
            store.file(text(it, "snapshot_id"), text(it, "run_id"), text(it, "path"),
                number(it, "start_line", 1), number(it, "end_line", 100))
        }
        val writeFields = mapOf("path" to string, "content" to string)
        fun save(args: Map<String, Any>, create: Boolean): Any {
            val result = store.propose(text(args, "snapshot_id"), text(args, "run_id"), text(args, "path"),
                text(args, "content"), args["base_hash"] as? String,
                store.evidence(text(args, "snapshot_id"), text(args, "run_id")), create)
            return mapOf("id" to result.id, "path" to result.path, "kind" to result.kind, "baseHash" to (result.baseHash ?: ""),
                "evidence" to result.evidence, "saved" to true, "originalGameModified" to false)
        }
        add("create_document", "Сохранить НОВЫЙ Markdown-документ отдельно от игры. Сначала прочитай минимум 3 разных файла. Сервер сам приложит прочитанные источники. Указывай path:line в тексте.",
            writeFields, listOf("path", "content")) { save(it, true) }
        add("propose_file_change", "Сохранить исправленный Markdown и unified diff, не меняя исходник. Прочитай минимум 3 файла и ВЕСЬ исходный документ. Передай полный новый текст и точный base_hash из read_file. Источники приложит сервер.",
            writeFields + ("base_hash" to string), listOf("path", "content", "base_hash")) { save(it, false) }
        return server
    }
}
