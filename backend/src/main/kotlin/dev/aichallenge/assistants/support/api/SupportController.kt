package dev.aichallenge.assistants.support.api

import dev.aichallenge.assistants.support.service.SupportAgent
import dev.aichallenge.assistants.support.rag.SupportKnowledge
import dev.aichallenge.support.contracts.*
import org.springframework.http.*
import org.springframework.web.bind.annotation.*

/** BFF для UI с явным demo-профилем; владелец JSON дополнительно проверяет доступ. */
@RestController
@RequestMapping("/api/support")
class SupportController(private val agent: SupportAgent, private val knowledge: SupportKnowledge) {
    @GetMapping("/users") fun users() = agent.users()
    @GetMapping("/tickets") fun list(@RequestHeader("X-Demo-User") user: String) = agent.tickets(user)
    @PostMapping("/tickets") fun create(@RequestHeader("X-Demo-User") user: String, @RequestBody draft: TicketDraft) =
        ResponseEntity.status(201).body(agent.create(user, draft))
    @GetMapping("/tickets/{id}") fun ticket(@RequestHeader("X-Demo-User") user: String, @PathVariable id: String) = agent.ticket(user, id)
    @PostMapping("/tickets/{id}/messages") fun send(@RequestHeader("X-Demo-User") user: String,
        @PathVariable id: String, @RequestBody question: SupportQuestion) = ResponseEntity.accepted().body(agent.send(user, id, question))
    @GetMapping("/knowledge") fun knowledge() = knowledge.status()
    @PostMapping("/knowledge") fun index() = ResponseEntity.accepted().body(knowledge.start())
    /** Экспорт проверяет профиль: прямая ссылка не даёт читать чужой тикет. */
    @GetMapping("/tickets/{id}/report") fun report(@RequestHeader("X-Demo-User") user: String, @PathVariable id: String): ResponseEntity<String> {
        val ticket = agent.ticket(user, id)
        fun literal(text: String) = "~".repeat(maxOf(4, (Regex("~+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1)).let { "$it\n$text\n$it\n" }
        val report = buildString {
            appendLine("# Поддержка игроков · день 33\n")
            appendLine(literal("Обращение ${ticket.id}\n${ticket.title}\n${ticket.description}\nСборка: ${ticket.gameVersion}; платформа: ${ticket.platform}"))
            ticket.turns.forEach { turn ->
                appendLine("## Вопрос\n"); appendLine(literal(turn.question))
                appendLine("## Ответ · ${turn.status}\n"); appendLine(literal(turn.answer))
                turn.error?.let { appendLine(literal(it)) }
                appendLine("### Контекст MCP\n"); appendLine(literal(turn.context.toString()))
                appendLine("### Источники · ${turn.knowledgeVersion}\n")
                turn.sources.forEach { source -> appendLine(literal("${source.id}: ${source.path}:${source.startLine}–${source.endLine}\n${source.text}")) }
            }
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=day33-$id.md")
            .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(report)
    }
}
