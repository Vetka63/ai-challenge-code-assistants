package dev.aichallenge.cli

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.*
import java.net.InetSocketAddress
import java.util.UUID

/** Настоящий локальный HTTP fixture проверяет wire-команды CLI без доступа к модели. */
class AssistantCliTest {
    private lateinit var server: HttpServer
    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private lateinit var cli: AssistantCli
    private var requests = 0
    private var body = ""
    @BeforeEach fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests++; body = exchange.requestBody.bufferedReader().readText()
            val value = if (exchange.requestURI.path.endsWith("/snapshots")) "[]" else """{"id":"test","status":"RUNNING"}"""
            val bytes = value.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        cli = AssistantCli("http://127.0.0.1:${server.address.port}", PrintStream(output), PrintStream(errors))
    }
    @AfterEach fun stop() { server.stop(0) }
    @Test fun helpDoesNotCallBackend() { assertEquals(0, cli.execute(listOf("help"))); assertEquals(0, requests); assertTrue(output.toString().contains("snapshot")) }
    @Test fun listsSnapshotsThroughSharedApi() { assertEquals(0, cli.execute(listOf("snapshots"))); assertEquals(1, requests); assertEquals("[]", output.toString().replace(Regex("\\s+"), "")) }
    @Test fun noWaitPostsExplicitIdAndGoalExactlyOnce() {
        val snapshot = UUID.randomUUID().toString(); val id = UUID.randomUUID().toString()
        assertEquals(0, cli.execute(listOf("run", snapshot, "DEEPSEEK", "Проверь docs", "--id", id, "--no-wait")))
        assertEquals(1, requests); assertTrue(body.contains(id)); assertTrue(body.contains("Проверь docs")); assertFalse(body.contains("key"))
    }
    @Test fun invalidUuidIsNotSentAsPath() { assertEquals(2, cli.execute(listOf("show", "../secret"))); assertEquals(0, requests) }
    @Test fun providerIsExplicitAndNoFallback() {
        assertEquals(2, cli.execute(listOf("run", UUID.randomUUID().toString(), "UNKNOWN", "goal"))); assertEquals(0, requests)
    }
}
