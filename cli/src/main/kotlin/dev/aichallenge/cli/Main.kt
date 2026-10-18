package dev.aichallenge.cli

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.PrintStream
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.UUID
import kotlin.system.exitProcess

/** Лёгкий терминальный клиент: вся агентская логика и ключи остаются в backend. */
class AssistantCli(private val url: String, private val out: PrintStream = System.out,
    private val err: PrintStream = System.err) {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    init { require(URI.create(url).let { it.scheme in setOf("http", "https") && it.host != null && it.rawUserInfo == null }) }
    /** Отправляет только явно выбранную команду; автоматических повторов POST нет. */
    private fun call(path: String, body: Any? = null, text: Boolean = false): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create(url.trimEnd('/') + "/api/workspace" + path)).timeout(Duration.ofSeconds(75))
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = response.body().use { it.readNBytes(4_000_001) }
        require(bytes.size <= 4_000_000) { "Response exceeds CLI limit" }
        if (response.statusCode() !in 200..299) {
            val error = runCatching { mapper.readTree(bytes) }.getOrNull()
            throw IllegalStateException(error?.path("message")?.asText() ?: "HTTP ${response.statusCode()}")
        }
        return if (text) mapper.readTree(mapper.writeValueAsString(bytes.toString(Charsets.UTF_8))) else mapper.readTree(bytes)
    }
    /** Не позволяет использовать ID в качестве фрагмента URL. */
    private fun id(value: String): String {
        require(runCatching { UUID.fromString(value).toString() }.getOrNull() == value) { "Expected canonical UUID" }
        return value
    }
    /** Разбирает небольшой стабильный набор команд без зависимости на Spring runtime. */
    fun execute(args: List<String>): Int {
        try {
            when (args.firstOrNull()) {
                null, "help", "--help" -> out.println(HELP)
                "snapshots" -> out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(call("/snapshots")))
                "snapshot" -> {
                    require(args.size <= 2) { "snapshot [request UUID]" }
                    val requestId = args.getOrNull(1)?.let(::id) ?: UUID.randomUUID().toString()
                    err.println("Snapshot request ID: $requestId")
                    out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(call("/snapshots", mapOf("id" to requestId))))
                }
                "runs" -> out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(call("/runs")))
                "show" -> {
                    require(args.size == 2) { "show RUN_UUID" }
                    val run = call("/runs/${id(args[1])}")
                    out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(run))
                }
                "artifacts" -> {
                    require(args.size == 2) { "artifacts RUN_UUID" }
                    out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(call("/runs/${id(args[1])}/artifacts")))
                }
                "export" -> {
                    require(args.size == 4 && args[3] in setOf("md", "patch")) { "export RUN_UUID ARTIFACT_UUID md|patch" }
                    out.print(call("/runs/${id(args[1])}/artifacts/${id(args[2])}/${args[3]}", text = true).asText())
                }
                "run", "continue" -> {
                    val continuation = args[0] == "continue"
                    require(args.size >= 4) { "run SNAPSHOT_UUID LOCAL|DEEPSEEK GOAL [--id REQUEST_UUID] [--no-wait]" }
                    val target = id(args[1])
                    val provider = args[2].uppercase()
                    require(provider in setOf("LOCAL", "DEEPSEEK")) { "Choose LOCAL or DEEPSEEK" }
                    val rest = args.drop(3).toMutableList()
                    val wait = !rest.remove("--no-wait")
                    var requestId = UUID.randomUUID().toString()
                    val position = rest.indexOf("--id")
                    if (position >= 0) { require(position + 1 < rest.size); requestId = id(rest[position + 1]); rest.removeAt(position + 1); rest.removeAt(position) }
                    require(rest.isNotEmpty()) { "Goal is required" }
                    val parent = if (continuation) call("/runs/$target") else null
                    val snapshot = parent?.path("snapshotId")?.asText() ?: target
                    val body = mapOf("id" to requestId, "snapshotId" to snapshot, "provider" to provider,
                        "goal" to rest.joinToString(" "), "parentId" to if (continuation) target else null)
                    err.println("Run ID: $requestId · повтор POST только с тем же --id и той же целью.")
                    val initial = call("/runs", body)
                    if (!wait) { out.println(mapper.writeValueAsString(initial)); return 0 }
                    var count = 0
                    val deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos()
                    while (true) {
                        val run = call("/runs/$requestId")
                        val events = run.path("events").toList()
                        events.drop(count).forEach { err.println("[${it.path("status").asText()}] ${it.path("stage").asText()}: ${it.path("details").asText()}") }
                        count = events.size
                        if (run.path("status").asText() != "RUNNING") {
                            out.println(run.path("answer").asText())
                            out.println("\nStatus: " + run.path("status").asText())
                            if (!run.path("error").isNull) err.println(run.path("error").asText(""))
                            val artifacts = call("/runs/$requestId/artifacts")
                            artifacts.forEach { out.println("Artifact: ${it.path("id").asText()} · ${it.path("path").asText()}") }
                            return if (run.path("status").asText() == "COMPLETED") 0 else 2
                        }
                        if (System.nanoTime() > deadline) {
                            err.println("Ожидание завершено. Backend может продолжать работу; проверьте show $requestId."); return 3
                        }
                        Thread.sleep(1200)
                    }
                }
                else -> throw IllegalArgumentException("Неизвестная команда. assistant help")
            }
            return 0
        } catch (_: InterruptedException) {
            err.println("CLI отключён. Это не отменяет backend-запуск; используйте show с сохранённым Run ID."); return 130
        } catch (e: IllegalArgumentException) { err.println(e.message ?: "Неверные параметры."); return 2 }
        catch (_: java.io.IOException) { err.println("Backend недоступен. Проверьте сервис; повтор POST делайте только с прежним --id."); return 1 }
        catch (e: IllegalStateException) { err.println(e.message ?: "Операция не выполнена."); return 1 }
    }
    companion object {
        val HELP = """
            assistant — день 34, клиент локального backend
              snapshot [REQUEST_UUID]                 создать изолированный снимок
              snapshots                               показать снимки
              run SNAPSHOT_UUID LOCAL|DEEPSEEK GOAL    выполнить цель и показать шаги
              continue RUN_UUID LOCAL|DEEPSEEK GOAL    продолжить диалог на том же снимке
              runs | show RUN_UUID                    посмотреть сохранённые запуски
              artifacts RUN_UUID                      показать документы и patches
              export RUN_UUID ARTIFACT_UUID md|patch   вывести содержимое в stdout
            Флаги run/continue: --id REQUEST_UUID, --no-wait.
            ASSISTANT_URL задаёт backend (по умолчанию http://127.0.0.1:8682).
            CLI не читает локальные ключи, не меняет игру, не делает commit/push.
        """.trimIndent()
    }
}
/** Запускается JVM-дистрибутивом или Docker CLI-контейнером. */
fun main(args: Array<String>) {
    exitProcess(AssistantCli(System.getenv("ASSISTANT_URL") ?: "http://127.0.0.1:8682").execute(args.toList()))
}
