package dev.aichallenge.projectmcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.time.Duration

/** Интеграция настоящего MCP-клиента и HTTP-сервера, не mock REST. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpProtocolTest {
    @LocalServerPort var port: Int = 0
    companion object {
        private val root = Files.createTempDirectory("day31-mcp-protocol")
        init {
            Files.writeString(root.resolve("README.md"), "# Game\nFixture")
            val init = ProcessBuilder("git", "-C", root.toString(), "init", "--initial-branch=main").start()
            check(init.waitFor() == 0)
            val commit = ProcessBuilder("git", "-C", root.toString(), "-c", "user.name=Fixture", "-c",
                "user.email=fixture@example.invalid", "commit", "--allow-empty", "-m", "fixture").start()
            check(commit.waitFor() == 0)
        }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) { registry.add("PROJECT_ROOT") { root.toString() } }
    }
    @Test fun handshakeDiscoveryAndCalls() {
        val transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port").endpoint("/mcp").build()
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build().use { client ->
            assertEquals("game-project-readonly", client.initialize().serverInfo().name())
            assertEquals(5, client.listTools().tools().size)
            val context = client.callTool(McpSchema.CallToolRequest("get_project_context", emptyMap()))
            assertNotEquals(true, context.isError())
            assertTrue(context.content().filterIsInstance<McpSchema.TextContent>().single().text().contains("\"branch\":\"main\""))
            val forbidden = client.callTool(McpSchema.CallToolRequest("read_project_file", mapOf("path" to ".git/config")))
            assertEquals(true, forbidden.isError())
            val extra = client.callTool(McpSchema.CallToolRequest("get_project_context", mapOf("command" to "git reset")))
            assertEquals(true, extra.isError())
        }
    }
}
