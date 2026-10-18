package dev.aichallenge.support.service

import dev.aichallenge.support.contracts.*
import dev.aichallenge.support.repository.JsonTicketRepository
import org.springframework.stereotype.Service

/** Единый прикладной контракт REST/MCP, владелец формы и проекции контекста. */
@Service
class SupportTickets(private val repository: JsonTicketRepository) {
    fun users() = repository.users()
    fun list(user: String) = repository.list(user)
    fun ticket(user: String, id: String) = repository.ticket(user, id)
    /** Нормализация до атомарного create; повтор остаётся связанным с исходной командой. */
    fun create(user: String, draft: TicketDraft): SupportTicket {
        require(draft.kind in setOf("ISSUE", "QUESTION"))
        return repository.create(user, draft.copy(title = draft.title.trim(), description = draft.description.trim(),
            gameVersion = draft.gameVersion.trim()))
    }
    fun addTurn(user: String, id: String, input: SupportQuestion) = repository.addTurn(user, id, input.copy(question = input.question.trim()))
    fun update(user: String, id: String, input: SaveTurn) = repository.update(user, id, input)
    fun recover() = repository.recover()
    fun user(id: String) = repository.user(id)
    /** Контекст — отдельная ограниченная read model, а не весь JSON-файл. */
    fun context(user: String, id: String): TicketContext {
        val ticket = repository.ticket(user, id)
        return TicketContext(repository.user(user), id, ticket.kind, ticket.title, ticket.description,
            ticket.gameVersion, ticket.platform, ticket.version,
            ticket.turns.filter { it.status == "COMPLETED" }.takeLast(3).map { SupportHistory(it.question, it.answer.take(2000)) })
    }
}
