package dev.aichallenge.support

import dev.aichallenge.support.contracts.TicketDraft
import dev.aichallenge.support.repository.JsonTicketRepository
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.*
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

/** Реальный MCP Streamable HTTP: initialize/list/call, доступ к своему и отказ для чужого. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupportProtocolTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var repository: JsonTicketRepository
    companion object {
        private val root = Files.createTempDirectory("support-protocol")
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("SUPPORT_DATA") { root.toString() }
        }
    }
    @Test fun realHandshakeContextAndBoundary() {
        val ticket = repository.create("demo-anna", TicketDraft(UUID.randomUUID().toString(), "Нет продолжения",
            "После переустановки кнопка недоступна.", "HeroAnimations", "WINDOWS"))
        val transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port").endpoint("/mcp").build()
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build().use { client ->
            assertEquals("game-support-readonly", client.initialize().serverInfo().name())
            assertEquals(setOf("get_user_context", "list_my_tickets", "get_ticket_context"), client.listTools().tools().map { it.name() }.toSet())
            val result = client.callTool(McpSchema.CallToolRequest("get_ticket_context", mapOf("user_id" to "demo-anna", "ticket_id" to ticket.id)))
            assertNotEquals(true, result.isError())
            assertTrue(result.content().filterIsInstance<McpSchema.TextContent>().single().text().contains("HeroAnimations"))
            val denied = client.callTool(McpSchema.CallToolRequest("get_ticket_context", mapOf("user_id" to "demo-boris", "ticket_id" to ticket.id)))
            assertEquals(true, denied.isError())
            assertFalse(denied.content().toString().contains("После переустановки"))
            val unknown = client.callTool(McpSchema.CallToolRequest("list_my_tickets", mapOf("user_id" to "demo-anna", "command" to "delete")))
            assertEquals(true, unknown.isError())
            val list = client.callTool(McpSchema.CallToolRequest("list_my_tickets", mapOf("user_id" to "demo-boris")))
            assertEquals("[]", list.content().filterIsInstance<McpSchema.TextContent>().single().text())
        }
    }
}
