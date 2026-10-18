package dev.aichallenge.support.api

import dev.aichallenge.support.contracts.*
import dev.aichallenge.support.service.SupportTickets
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Внутренний API владельца JSON. На хост не публикуется; браузер работает через backend. */
@RestController
@RequestMapping("/internal/support")
class SupportDataController(private val tickets: SupportTickets) {
    @GetMapping("/users") fun users() = tickets.users()
    @GetMapping("/tickets") fun list(@RequestHeader("X-Demo-User") user: String) = tickets.list(user)
    @PostMapping("/tickets") fun create(@RequestHeader("X-Demo-User") user: String, @RequestBody draft: TicketDraft) = tickets.create(user, draft)
    @GetMapping("/tickets/{id}") fun get(@RequestHeader("X-Demo-User") user: String, @PathVariable id: String) = tickets.ticket(user, id)
    @PostMapping("/tickets/{id}/turns") fun add(@RequestHeader("X-Demo-User") user: String,
        @PathVariable id: String, @RequestBody input: SupportQuestion) = tickets.addTurn(user, id, input)
    @PutMapping("/tickets/{id}/turns") fun update(@RequestHeader("X-Demo-User") user: String,
        @PathVariable id: String, @RequestBody input: SaveTurn) = tickets.update(user, id, input)
    @PostMapping("/recover") fun recover() = mapOf("interrupted" to tickets.recover())
}

/** Ошибки не содержат файловых путей, чужих тикетов или сырых stack trace. */
@RestControllerAdvice
class SupportDataErrors {
    @ExceptionHandler(SupportFailure::class) fun known(e: SupportFailure) =
        ResponseEntity.status(e.status).body(mapOf("code" to e.code, "message" to e.message))
    @ExceptionHandler(IllegalArgumentException::class) fun invalid(e: Exception) =
        ResponseEntity.badRequest().body(mapOf("code" to "INVALID_REQUEST", "message" to "Проверьте поля обращения."))
    @ExceptionHandler(Exception::class) fun unexpected(e: Exception) =
        ResponseEntity.internalServerError().body(mapOf("code" to "SUPPORT_STORAGE", "message" to "Хранилище поддержки недоступно; данные не перезаписаны."))
}
