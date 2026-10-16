package dev.aichallenge.assistants.llm

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.config.RuntimeConfig
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt

/** Identity включает digest весов и шаблон входа, а не только название модели. */
data class EmbeddingIdentity(val model: String, val digest: String, val template: String = "project-docs-v1")
/** Точное usage из ответа API, без подмены локальным подсчётом символов. */
data class Usage(val inputTokens: Long?, val outputTokens: Long?)
/** Сообщение LLM, отделённое от HTTP-конверта конкретного провайдера. */
data class LlmMessage(val role: String, val content: String)
/** Итог одного вызова; обрезанный ответ сохраняется, не выдаётся за полный. */
data class Generation(val text: String, val model: String, val usage: Usage,
    val finishReason: String, val durationMs: Long)
/** Контракт embeddings для реальной Ollama и детерминированных тестов. */
interface Embeddings {
    fun identity(): EmbeddingIdentity
    fun embed(texts: List<String>): List<List<Double>>
}
/** Контракт генерации без скрытого fallback или автоматических повторов. */
interface Generator { fun generate(provider: String, messages: List<LlmMessage>): Generation }

/** HTTP-адаптеры локальных embeddings и двух независимых генераторов. */
@Component
class ModelGateway(private val config: RuntimeConfig, private val mapper: ObjectMapper) : Embeddings, Generator {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    /** Отправляет JSON; ошибки не содержат ключ, request body или сырой ответ провайдера. */
    private fun call(url: String, body: Any? = null, key: String? = null): JsonNode {
        try {
            val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180))
            if (key != null) builder.header("Authorization", "Bearer $key")
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) throw AssistantFailure("MODEL_HTTP", "Провайдер вернул HTTP ${response.statusCode()}. Повтор автоматически не отправлялся.", 502)
            return mapper.readTree(response.body())
        } catch (e: AssistantFailure) { throw e }
        catch (_: Exception) { throw AssistantFailure("MODEL_UNAVAILABLE", "Модель не ответила или соединение прервано. Повтор автоматически не отправлялся.", 502) }
    }
    /** Проверяет наличие именно локальной embedding-модели и её digest. */
    override fun identity(): EmbeddingIdentity {
        val models = call(config.ollamaUrl + "/api/tags").path("models")
        val model = models.firstOrNull { it.path("name").asText() == config.embeddingModel }
            ?: throw AssistantFailure("EMBEDDING_MISSING", "Не установлена локальная модель ${config.embeddingModel}.", 503)
        if (model.hasNonNull("remote_host") || model.hasNonNull("remote_model"))
            throw AssistantFailure("REMOTE_EMBEDDINGS", "Embeddings должны выполняться локально.", 503)
        return EmbeddingIdentity(config.embeddingModel, model.path("digest").asText().also { require(it.isNotBlank()) })
    }
    /** Ollama не имеет права тихо обрезать индексируемый текст. */
    override fun embed(texts: List<String>): List<List<Double>> {
        val result = call(config.ollamaUrl + "/api/embed", mapOf("model" to config.embeddingModel,
            "input" to texts, "truncate" to false, "keep_alive" to "5m"))
        require(result.path("model").asText() == config.embeddingModel) { "Неверная embedding-модель" }
        val vectors = result.path("embeddings").toList().map { node -> node.toList().map {
            require(it.isNumber); it.asDouble()
        }.also(::validateVector) }
        require(vectors.size == texts.size && vectors.map { it.size }.distinct().size == 1) { "Неверное число или размерность embeddings" }
        return vectors
    }
    /** Явно выбирает одного провайдера; ключ передаётся только заголовком HTTP. */
    override fun generate(provider: String, messages: List<LlmMessage>): Generation {
        val started = System.nanoTime()
        val cloud = provider == "DEEPSEEK"
        if (provider !in setOf("LOCAL", "DEEPSEEK")) throw AssistantFailure("PROVIDER", "Неизвестный провайдер")
        if (cloud && config.deepseekKey.isBlank()) throw AssistantFailure("KEY_MISSING", "Добавьте DEEPSEEK_API_KEY в локальный .env.", 503)
        val model = if (cloud) config.deepseekModel else config.localModel
        val result = if (cloud) call("https://api.deepseek.com/chat/completions",
            mapOf("model" to model, "messages" to messages, "stream" to false, "temperature" to 0.2,
                "thinking" to mapOf("type" to "disabled")), config.deepseekKey)
        else call(config.ollamaUrl + "/api/chat", mapOf("model" to model, "messages" to messages,
            "stream" to false, "think" to false, "options" to mapOf("temperature" to 0.2, "num_ctx" to 8192), "keep_alive" to "5m"))
        val text = if (cloud) result.path("choices").path(0).path("message").path("content").asText("") else result.path("message").path("content").asText("")
        val reason = if (cloud) result.path("choices").path(0).path("finish_reason").asText("") else result.path("done_reason").asText("")
        if (text.isBlank()) throw AssistantFailure("EMPTY_ANSWER", "Модель вернула пустой текст. Сообщение и диагностика сохранены.", 502)
        fun count(node: JsonNode) = if (node.isIntegralNumber) node.asLong() else null
        val usage = if (cloud) Usage(count(result.path("usage").path("prompt_tokens")), count(result.path("usage").path("completion_tokens")))
            else Usage(count(result.path("prompt_eval_count")), count(result.path("eval_count")))
        return Generation(text, model, usage, reason, (System.nanoTime() - started) / 1_000_000)
    }
}

/** Отклоняет пустые, бесконечные и нулевые векторы до сохранения/поиска. */
fun validateVector(v: List<Double>) { require(v.isNotEmpty() && v.all { it.isFinite() } && v.sumOf { it * it } > 0) { "Некорректный embedding" } }
/** Exact cosine; значение не является вероятностью истинности ответа. */
fun cosine(a: List<Double>, b: List<Double>): Double {
    validateVector(a); validateVector(b); require(a.size == b.size) { "Размерности индекса и вопроса не совпадают" }
    return a.indices.sumOf { a[it] * b[it] } / sqrt(a.sumOf { it * it } * b.sumOf { it * it })
}
