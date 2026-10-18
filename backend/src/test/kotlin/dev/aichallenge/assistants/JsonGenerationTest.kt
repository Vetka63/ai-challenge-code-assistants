package dev.aichallenge.assistants

import com.sun.net.httpserver.HttpServer
import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.llm.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.InetSocketAddress

/** Проверяет настоящий HTTP-конверт адаптера без Ollama и платного API. */
class JsonGenerationTest {
    @Test fun jsonModeIsExplicitAndDoesNotChangePlainTextAgents() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { request ->
            bodies += request.requestBody.bufferedReader().readText()
            val result = """{"message":{"content":"{\"title\":\"Game\"}"},"done_reason":"stop","prompt_eval_count":9,"eval_count":6}""".toByteArray()
            request.sendResponseHeaders(200, result.size.toLong()); request.responseBody.use { it.write(result) }
        }
        server.start()
        try {
            val gateway = ModelGateway(RuntimeConfig("unused", "", "http://127.0.0.1:${server.address.port}", "embed", "fixture", "", ""), mapper)
            val messages = listOf(LlmMessage("user", "Верни JSON"))
            assertEquals(Usage(9, 6), gateway.generateJson("LOCAL", messages).usage)
            gateway.generate("LOCAL", messages)
            assertEquals("json", mapper.readTree(bodies[0]).path("format").asText())
            assertFalse(mapper.readTree(bodies[1]).has("format"))
            assertFalse(mapper.readTree(bodies[0]).has("max_tokens"))
        } finally { server.stop(0) }
    }
}
