package dev.aichallenge.workspacemcp

import dev.aichallenge.workspacemcp.storage.WorkspaceStore
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

/** Реальный HTTP MCP handshake, schema и чтение, без LLM и без доступа к настоящей игре. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceProtocolTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var store: WorkspaceStore
    companion object {
        val root = Files.createTempDirectory("workspace-protocol")
        val source = root.resolve("game").also { Files.createDirectories(it); Files.writeString(it.resolve("README.md"), "# Test\n") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("WORKSPACE_SOURCE") { source.toString() }; registry.add("WORKSPACE_DATA") { root.resolve("owned").toString() }
        }
    }
    @Test fun handshakeAndServerValidation() {
        val snapshot = store.create(UUID.randomUUID().toString())
        val scope = mapOf<String, Any>("snapshot_id" to snapshot.id, "run_id" to UUID.randomUUID().toString())
        val transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port").endpoint("/mcp").build()
        McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build().use { client ->
            assertEquals("game-workspace", client.initialize().serverInfo().name())
            assertEquals(setOf("list_files", "search_files", "read_file", "create_document", "propose_file_change"),
                client.listTools().tools().map { it.name() }.toSet())
            val result = client.callTool(McpSchema.CallToolRequest("read_file", scope + ("path" to "README.md")))
            assertNotEquals(true, result.isError())
            assertTrue(result.content().filterIsInstance<McpSchema.TextContent>().single().text().contains("# Test"))
            val denied = client.callTool(McpSchema.CallToolRequest("read_file", scope + ("path" to ".env")))
            assertEquals(true, denied.isError())
            val unknown = client.callTool(McpSchema.CallToolRequest("list_files", scope + ("shell" to "delete")))
            assertEquals(true, unknown.isError())
        }
    }
}
