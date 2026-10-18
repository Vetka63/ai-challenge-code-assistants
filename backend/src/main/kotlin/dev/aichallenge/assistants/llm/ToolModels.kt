package dev.aichallenge.assistants.llm

/** Декларация инструмента из MCP; описание — данные, не право на произвольное выполнение. */
data class ToolDefinition(val name: String, val description: String, val parameters: Map<String, Any>)
/** Предложение модели. Исполняется только после проверки приложением. */
data class RequestedTool(val id: String, val name: String, val arguments: Map<String, Any>)
/** Сохраняет исходное assistant message с tool_call_id для корректного следующего запроса. */
data class ToolReply(val message: Map<String, Any?>, val calls: List<RequestedTool>, val generation: Generation)
/** Отдельный контракт не меняет существующие текстовые сценарии дней 31–33. */
interface ToolGenerator {
    fun step(provider: String, messages: List<Map<String, Any?>>, tools: List<ToolDefinition>): ToolReply
}
