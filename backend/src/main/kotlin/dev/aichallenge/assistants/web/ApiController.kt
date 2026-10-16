package dev.aichallenge.assistants.web

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.project.McpEvent
import dev.aichallenge.assistants.project.ProjectGateway
import dev.aichallenge.assistants.projecthelp.models.HelpRequest
import dev.aichallenge.assistants.projecthelp.service.ProjectHelpAgent
import dev.aichallenge.assistants.rag.indexing.IndexService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

/** Тонкая HTTP-граница: настройки и агентская логика не хранятся в контроллере. */
@RestController
@RequestMapping("/api")
class ApiController(private val agent: ProjectHelpAgent, private val indexes: IndexService,
    private val project: ProjectGateway, private val config: RuntimeConfig) {
    /** Публичная конфигурация не содержит ни ключа, ни абсолютного пути к игре. */
    @GetMapping("/config")
    fun config() = mapOf("agent" to "project-help", "day" to 31, "localModel" to config.localModel,
        "deepseekModel" to config.deepseekModel, "cloudAvailable" to config.deepseekKey.isNotBlank(),
        "embeddingModel" to config.embeddingModel, "readOnly" to true)
    /** Ручное обновление контекста всегда выполняет реальный MCP-запрос. */
    @GetMapping("/project/context")
    fun context(): Map<String, Any> {
        val events = mutableListOf<McpEvent>()
        return mapOf("context" to project.context(events), "events" to events)
    }
    /** Возвращает состояние построения и предыдущий READY индекс. */
    @GetMapping("/indexes") fun indexes() = indexes.status()
    /** Построение запускается только явным действием пользователя. */
    @PostMapping("/indexes") fun index() = ResponseEntity.accepted().body(indexes.start())
    /** Список диалогов отдельного агента. */
    @GetMapping("/chats") fun chats() = agent.chats()
    /** Создание диалога не обращается к LLM. */
    @PostMapping("/chats") fun createChat() = agent.createChat()
    /** История включает failed/interrupted ходы. */
    @GetMapping("/chats/{id}") fun chat(@PathVariable id: String) = mapOf("chat" to agent.chat(id), "turns" to agent.turns(id))
    /** Приём вопроса возвращается сразу с сохранённым UUID. */
    @PostMapping("/chats/{id}/messages")
    fun send(@PathVariable id: String, @RequestBody request: HelpRequest) = ResponseEntity.accepted().body(agent.send(id, request))
    /** Polling читает БД без повторной генерации. */
    @GetMapping("/turns/{id}") fun turn(@PathVariable id: String) = agent.turn(id)
    /** Markdown-экспорт буквальный, без нового вызова LLM и исполнения HTML. */
    @GetMapping("/turns/{id}/report")
    fun report(@PathVariable id: String): ResponseEntity<String> {
        val turn = agent.turn(id)
        val content = buildString {
            appendLine("# Помощник разработчика — день 31\n")
            appendLine("Результат: ${turn.id}; статус: ${turn.status}; провайдер: ${turn.provider}\n")
            appendLine("Git SHA: ${turn.context?.sha}; ветка: ${turn.context?.branch ?: "detached HEAD"}\n")
            fun literal(text: String) { val fence = "~".repeat(maxOf(4, Regex("~+").findAll(text).maxOfOrNull { it.value.length + 1 } ?: 4)); appendLine(fence); appendLine(text); appendLine(fence); appendLine() }
            turn.indexContext?.let {
                appendLine("Версия источников индекса: Git SHA ${it.sha}; ветка: ${it.branch ?: "detached HEAD"}; fingerprint: ${it.fingerprint}\n")
            }
            if (turn.warnings.isNotEmpty()) {
                appendLine("## Предупреждения\n")
                turn.warnings.forEach { literal("${it.code}: ${it.message}") }
            }
            appendLine("## Вопрос\n"); literal(turn.question)
            appendLine("## Ответ\n"); literal(turn.answer)
            if (turn.error != null) { appendLine("## Диагностика\n"); literal(turn.error) }
            appendLine("## Источники\n")
            turn.sources.forEach { appendLine("### ${it.id}: ${it.chunk.path}:${it.chunk.startLine}–${it.chunk.endLine}\n"); literal(it.chunk.text) }
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=day31-${turn.id}.md")
            .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(content)
    }
}

/** Единая безопасная обработка ошибок, без stack trace/секретов в HTTP. */
@RestControllerAdvice
class ApiErrors {
    /** Известная ошибка сохраняет машинный код для UI. */
    @ExceptionHandler(AssistantFailure::class)
    fun known(e: AssistantFailure) = ResponseEntity.status(e.status).body(mapOf("code" to e.code, "message" to e.message, "at" to Instant.now().toString()))
    /** Ошибочные аргументы не маскируются успешным результатом. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun argument(e: IllegalArgumentException) = ResponseEntity.badRequest().body(mapOf("code" to "INVALID_REQUEST", "message" to "Неверные параметры запроса."))
    /** Непредвиденная ошибка не раскрывает пути, тела API и ключи. */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception) = ResponseEntity.status(HttpStatusCode.valueOf(500)).body(mapOf("code" to "INTERNAL", "message" to "Внутренняя ошибка. Проверьте состояние сервисов."))
}
