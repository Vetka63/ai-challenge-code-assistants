package dev.aichallenge.assistants

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.storage.RecordStore
import dev.aichallenge.assistants.workspace.gateway.*
import dev.aichallenge.assistants.workspace.service.WorkspaceAgent
import dev.aichallenge.workspace.contracts.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.util.UUID

/** Реальные SQLite и executor; детерминированная модель позволяет проверять побочные эффекты бесплатно. */
class WorkspaceAgentTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private lateinit var store: RecordStore
    private lateinit var jobs: Jobs
    private lateinit var agent: WorkspaceAgent
    private val snapshot = SnapshotInfo(UUID.randomUUID().toString(), "fingerprint", 3, "now")
    private val paths = listOf("README.md", "docs/controls.md", "AsciiArena/Assets/Prototype/A.cs")
    private val saved = mutableListOf<WorkspaceArtifact>()
    private val invocations = mutableListOf<String>()
    private val prompts = mutableListOf<List<Map<String, Any?>>>()
    private var modelCalls = 0
    private var mode = "normal"
    private fun id() = UUID.randomUUID().toString()
    private val data = object : WorkspaceData {
        override fun create(id: String) = snapshot
        override fun list() = listOf(snapshot)
        override fun info(id: String): SnapshotInfo { require(id == snapshot.id); return snapshot }
        override fun artifacts(snapshot: String, run: String) = saved.filter { it.runId == run }
    }
    private val tools = object : WorkspaceTools {
        override fun open(snapshot: String, run: String, events: MutableList<WorkspaceEvent>): WorkspaceSession {
            if (mode == "offline") throw AssistantFailure("WORKSPACE_MCP_UNAVAILABLE", "offline", 503)
            events += WorkspaceEvent("MCP initialize", "SUCCESS", "fixture")
            return object : WorkspaceSession {
                override val tools = listOf("search_files", "read_file", "create_document").map {
                    ToolDefinition(it, "fixture", emptyMap())
                }
                override fun close() {}
                override fun call(name: String, args: Map<String, Any>): ToolResult {
                    invocations += name
                    if (name == "read_file") return ToolResult(mapper.writeValueAsString(mapOf(
                        "path" to args["path"], "hash" to "hash", "startLine" to 1, "endLine" to 2, "text" to "actual source")), false)
                    if (name == "create_document") {
                        val artifact = WorkspaceArtifact(id(), snapshot, run, "docs/generated/report.md",
                            "CREATED_DOCUMENT", null, "# Result", "patch", paths)
                        saved += artifact
                        if (mode == "lostAck") throw AssistantFailure("WORKSPACE_MCP_UNAVAILABLE", "lost ack", 503)
                        return ToolResult(mapper.writeValueAsString(mapOf("id" to artifact.id)), false)
                    }
                    return ToolResult("""{"matches":[]}""", false)
                }
            }
        }
    }
    private val model = object : ToolGenerator {
        override fun step(provider: String, messages: List<Map<String, Any?>>, tools: List<ToolDefinition>): ToolReply {
            modelCalls++; prompts += messages.toList()
            if (mode == "modelFailure") throw AssistantFailure("MODEL_UNAVAILABLE", "model failed", 502)
            val calls = when {
                mode == "loop" -> listOf(RequestedTool(id(), "search_files", mapOf("query" to "x")))
                mode == "textOnly" || mode == "truncated" -> emptyList()
                modelCalls % 4 == 1 -> listOf(RequestedTool(id(), "search_files", mapOf("query" to "controls")))
                modelCalls % 4 == 2 -> paths.map { RequestedTool(id(), "read_file", mapOf("path" to it)) }
                modelCalls % 4 == 3 -> listOf(RequestedTool(id(), "create_document", mapOf("path" to "docs/generated/report.md")))
                else -> emptyList()
            }
            val message = mapOf<String, Any?>("role" to "assistant", "content" to if (calls.isEmpty()) "Result" else "",
                "tool_calls" to calls.map { mapOf("id" to it.id, "type" to "function", "function" to mapOf("name" to it.name, "arguments" to mapper.writeValueAsString(it.arguments))) })
            return ToolReply(message, calls, Generation(if (calls.isEmpty()) "Result" else "", "fixture-model", Usage(10, 2),
                if (mode == "truncated") "length" else if (calls.isEmpty()) "stop" else "tool_calls", 1))
        }
    }
    @BeforeEach fun setup() {
        jobs = Jobs()
        store = RecordStore(RuntimeConfig(root.resolve("db.sqlite").toString(), "", "", "", "", "", ""), mapper)
        agent = WorkspaceAgent(store, data, tools, model, jobs, mapper)
    }
    @AfterEach fun close() { jobs.close() }
    private fun input() = WorkspaceGoal(id(), snapshot.id, "Проверь управление", "DEEPSEEK")
    private fun finish(request: WorkspaceGoal): WorkspaceRun {
        agent.start(request)
        repeat(500) { val run = agent.run(request.id); if (run.status != "RUNNING") return run; Thread.sleep(10) }
        fail<Unit>("Agent did not finish"); error("unreachable")
    }
    @Test fun modelChoosesSeveralFilesThenActuallySaves() {
        val run = finish(input())
        assertEquals("COMPLETED", run.status)
        assertEquals(paths.toSet(), run.files.map { it.path }.toSet())
        assertEquals(listOf("search_files", "read_file", "read_file", "read_file", "create_document"), invocations)
        assertEquals(4, run.usage.size); assertEquals(1, agent.artifacts(run.id).size)
        assertEquals(paths.toSet(), agent.sources(run.id).map { it["path"] }.toSet())
        assertTrue(prompts[1].any { it["role"] == "tool" && it["tool_call_id"] != null })
        assertFalse(prompts.first().joinToString().contains("docs/controls.md"), "No predetermined file sequence in initial prompt")
    }
    @Test fun duplicateUuidDoesNotRepeatModelOrWrite() {
        val request = input(); val result = finish(request)
        assertEquals(result, agent.start(request)); assertEquals(4, modelCalls); assertEquals(1, saved.size)
    }
    @Test fun reusedIdWithDifferentGoalIsConflict() {
        val request = input(); finish(request)
        assertEquals("ID_CONFLICT", assertThrows(AssistantFailure::class.java) { agent.start(request.copy(goal = "other")) }.code)
    }
    @Test fun offlineMcpPreventsModelCall() {
        mode = "offline"; val run = finish(input())
        assertEquals("FAILED", run.status); assertEquals(0, modelCalls); assertTrue(saved.isEmpty())
    }
    @Test fun plainTextIsNotClaimedAsSavedChange() {
        mode = "textOnly"; val run = finish(input())
        assertEquals("ANALYZED", run.status); assertTrue(run.artifactIds.isEmpty()); assertEquals("Result", run.answer)
    }
    @Test fun budgetStopsToolLoopAndKeepsHistory() {
        mode = "loop"; val run = finish(input())
        assertEquals("STEP_BUDGET", run.errorCode); assertEquals(24, modelCalls)
        assertEquals(24, run.usage.size); assertTrue(run.events.isNotEmpty())
    }
    @Test fun lostToolAcknowledgementKeepsArtifactWithoutRetry() {
        mode = "lostAck"; val run = finish(input())
        assertEquals("FAILED", run.status); assertEquals(1, run.artifactIds.size)
        assertEquals(3, modelCalls); assertEquals(1, invocations.count { it == "create_document" })
    }
    @Test fun incompleteReplyIsRetainedNotMarkedCompleted() {
        mode = "truncated"; val run = finish(input())
        assertEquals("INCOMPLETE_MODEL_REPLY", run.errorCode); assertEquals("Result", run.answer)
        assertEquals(1, run.usage.size)
    }
    @Test fun modelFailureIsSavedAndNotRetried() {
        mode = "modelFailure"; val run = finish(input())
        assertEquals("MODEL_UNAVAILABLE", run.errorCode); assertEquals(1, modelCalls)
    }
    @Test fun restartMarksRunningInsteadOfRegenerating() {
        val request = input()
        store.put("workspace-run", request.id, WorkspaceRun(request.id, request.snapshotId, request.goal, request.provider))
        agent.recover()
        assertEquals("INTERRUPTED", agent.run(request.id).status); assertEquals(0, modelCalls)
    }
    @Test fun continuationUsesSameSnapshotAndSavedToolHistory() {
        val first = finish(input())
        val second = finish(input().copy(parentId = first.id, goal = "Уточни отчёт"))
        assertEquals("COMPLETED", second.status); assertEquals(first.snapshotId, second.snapshotId)
        assertTrue(prompts[4].any { it["role"] == "tool" })
        assertEquals(2, saved.size)
    }
}
