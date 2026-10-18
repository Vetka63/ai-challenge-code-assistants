package dev.aichallenge.support

import dev.aichallenge.support.contracts.*
import dev.aichallenge.support.repository.JsonTicketRepository
import dev.aichallenge.support.service.SupportTickets
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.Executors

/** Настоящие файлы: атомарность, восстановление, CAS и отсутствие смешивания пользователей. */
class JsonTicketsTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private fun draft() = TicketDraft(UUID.randomUUID().toString(), "Нет продолжения", "После переустановки продолжить не получается.", "HeroAnimations", "WINDOWS")
    @Test fun createIdempotentlyAndRejectChangedPayload() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val input = draft()
            val first = repo.create("demo-anna", input)
            assertEquals(first, repo.create("demo-anna", input))
            assertEquals(1, repo.list("demo-anna").size)
            assertEquals("ID_CONFLICT", assertThrows(SupportFailure::class.java) { repo.create("demo-anna", input.copy(title = "Другой вопрос")) }.code)
            assertTrue(Files.readString(root.resolve("tickets/" + first.id + ".json")).contains("HeroAnimations"))
        }
    }
    @Test fun otherProfileCannotReadOrDiscoverTicket() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val ticket = repo.create("demo-anna", draft())
            assertTrue(repo.list("demo-boris").isEmpty())
            assertEquals("TICKET_NOT_FOUND", assertThrows(SupportFailure::class.java) { repo.ticket("demo-boris", ticket.id) }.code)
            assertThrows(SupportFailure::class.java) { repo.context("demo-boris", ticket.id) }
        }
    }
    @Test fun cannotUseTicketIdAsFilePath() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            assertThrows(SupportFailure::class.java) { repo.ticket("demo-anna", "../../users") }
            assertThrows(SupportFailure::class.java) { repo.create("demo-anna", draft().copy(id = "../outside")) }
            assertThrows(SupportFailure::class.java) { repo.list("unknown") }
        }
    }
    @Test fun persistenceAndHistorySurviveRepositoryRestart() {
        val input = draft()
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            repo.create("demo-anna", input)
            val turn = repo.addTurn("demo-anna", input.id, SupportQuestion(UUID.randomUUID().toString(), "Что делать?"))
            repo.update("demo-anna", input.id, SaveTurn(turn.copy(status = "COMPLETED", answer = "Сохранения локальные [S1]"), 0))
        }
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            assertEquals("Сохранения локальные [S1]", repo.ticket("demo-anna", input.id).turns.single().answer)
            assertEquals(1, SupportTickets(repo).context("demo-anna", input.id).recentConversation.size)
        }
    }
    @Test fun staleResultCannotOverwriteCompletedTurn() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val ticket = repo.create("demo-anna", draft())
            val input = SupportQuestion(UUID.randomUUID().toString(), "Что делать?")
            val initial = repo.addTurn("demo-anna", ticket.id, input)
            assertEquals(initial, repo.addTurn("demo-anna", ticket.id, input))
            repo.update("demo-anna", ticket.id, SaveTurn(initial.copy(status = "COMPLETED", answer = "OK"), 0))
            assertEquals("TURN_CONFLICT", assertThrows(SupportFailure::class.java) {
                repo.update("demo-anna", ticket.id, SaveTurn(initial.copy(answer = "stale"), 0))
            }.code)
            assertEquals("OK", repo.ticket("demo-anna", ticket.id).turns.single().answer)
        }
    }
    @Test fun runningBecomesInterruptedWithoutRetryAndRetainsQuestion() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val ticket = repo.create("demo-anna", draft())
            repo.addTurn("demo-anna", ticket.id, SupportQuestion(UUID.randomUUID().toString(), "Мой вопрос"))
            assertEquals(1, repo.recover()); assertEquals(0, repo.recover())
            val saved = repo.ticket("demo-anna", ticket.id).turns.single()
            assertEquals("INTERRUPTED", saved.status); assertEquals("Мой вопрос", saved.question)
        }
    }
    @Test fun parallelCreatesDoNotLoseFiles() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val pool = Executors.newFixedThreadPool(6)
            try {
                val futures = (1..24).map { pool.submit<SupportTicket> { repo.create("demo-anna", draft()) } }
                assertEquals(24, futures.map { it.get().id }.distinct().size)
                assertEquals(24, repo.list("demo-anna").size)
                assertEquals(0, Files.list(root.resolve("tickets")).use { it.filter { p -> p.toString().endsWith(".tmp") }.count() })
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun secondWriterForSameDirectoryIsRejected() {
        JsonTicketRepository(root.toString(), mapper).use {
            assertThrows(Exception::class.java) { JsonTicketRepository(root.toString(), mapper) }
        }
    }
    @Test fun corruptedDataIsNotReplacedWithEmptyTicket() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val ticket = repo.create("demo-anna", draft())
            val path = root.resolve("tickets/" + ticket.id + ".json")
            Files.writeString(path, "{broken")
            assertThrows(Exception::class.java) { repo.ticket("demo-anna", ticket.id) }
            assertEquals("{broken", Files.readString(path))
        }
    }
    @Test fun onlyThreeRecentCompletedExchangesAreInMcpProjection() {
        JsonTicketRepository(root.toString(), mapper).use { repo ->
            val ticket = repo.create("demo-anna", draft())
            repeat(5) { i ->
                val turn = repo.addTurn("demo-anna", ticket.id, SupportQuestion(UUID.randomUUID().toString(), "Вопрос $i"))
                repo.update("demo-anna", ticket.id, SaveTurn(turn.copy(status = "COMPLETED", answer = "Ответ $i"), 0))
            }
            val context = SupportTickets(repo).context("demo-anna", ticket.id)
            assertEquals(listOf("Вопрос 2", "Вопрос 3", "Вопрос 4"), context.recentConversation.map { it.question })
            assertEquals(5, repo.ticket("demo-anna", ticket.id).turns.size)
        }
    }
}
