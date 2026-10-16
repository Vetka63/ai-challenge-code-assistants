package dev.aichallenge.assistants

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.project.*
import dev.aichallenge.assistants.projecthelp.models.*
import dev.aichallenge.assistants.projecthelp.policy.*
import dev.aichallenge.assistants.projecthelp.service.ProjectHelpAgent
import dev.aichallenge.assistants.rag.indexing.*
import dev.aichallenge.assistants.rag.models.*
import dev.aichallenge.assistants.rag.retrieval.Retriever
import dev.aichallenge.assistants.storage.RecordStore
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.util.UUID

/** Сквозные сценарии агента на fake-портах: без платных вызовов и изменения игры. */
class HelpAgentTest {
    @TempDir lateinit var dir: Path
    private lateinit var store: RecordStore
    private lateinit var jobs: Jobs
    private lateinit var agent: ProjectHelpAgent
    private var calls = 0
    private var failGeneration = false
    private var failMcp = false
    private var failAfterGeneration = false
    private var changeAfterGeneration = false
    private var lastMessages = emptyList<LlmMessage>()
    private var current = ProjectContext("game", "feature/game", "sha", true, "fingerprint", 1)
    private var identity = EmbeddingIdentity("embedding", "digest")
    private lateinit var index: IndexRecord
    @BeforeEach fun setup() {
        val config = RuntimeConfig(dir.resolve("db.sqlite").toString(), "", "", "embedding", "local", "", "cloud")
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        store = RecordStore(config, mapper); jobs = Jobs()
        val project = object : ProjectGateway {
            override fun context(events: MutableList<McpEvent>): ProjectContext {
                if (failMcp || (failAfterGeneration && calls > 0)) throw AssistantFailure("MCP_UNAVAILABLE", "MCP unavailable", 503)
                events += McpEvent("tools/call:get_project_context", "SUCCESS", 1)
                return current
            }
            override fun snapshot(events: MutableList<McpEvent>) = ProjectSnapshot(current, emptyList())
        }
        val embeddings = object : Embeddings {
            override fun identity() = identity
            override fun embed(texts: List<String>) = texts.map { listOf(1.0, 0.0) }
        }
        val generator = object : Generator {
            override fun generate(provider: String, messages: List<LlmMessage>): Generation {
                calls++
                lastMessages = messages
                if (changeAfterGeneration) current = current.copy(fingerprint = "changed-during-generation")
                if (failGeneration) throw AssistantFailure("EMPTY_ANSWER", "Empty")
                assertTrue(messages.last().content.contains("feature/game"))
                return Generation("Правила проекта [S1]", "fake", Usage(20, 5), "stop", 1)
            }
        }
        val chunk = Chunk("chunk", "README.md", "Project", 1, 2, "# Project\nUnity ECS Zenject", "hash", "PROJECT_DOCUMENT")
        index = IndexRecord("idx", "READY", context = current, identity = identity, chunks = listOf(IndexedChunk(chunk, listOf(1.0, 0.0))), processed = 1, total = 1)
        store.put("index", index.id, index)
        val indexes = IndexService(store, project, embeddings, MarkdownChunker(), jobs)
        agent = ProjectHelpAgent(store, project, embeddings, generator, indexes, Retriever(), HelpInputPolicy(), HelpOutputPolicy(), jobs, mapper)
    }
    @AfterEach fun close() { jobs.close() }
    private fun await(id: String): HelpTurn {
        repeat(500) { val turn = agent.turn(id); if (turn.status != "RUNNING") return turn; Thread.sleep(10) }
        error("Timeout")
    }
    @Test fun answerHasLiveBranchSourcesAndUsage() {
        val chat = agent.createChat()
        val result = await(agent.send(chat.id, HelpRequest(UUID.randomUUID().toString(), "/help Как устроен ECS?")).id)
        assertEquals("COMPLETED", result.status); assertEquals("feature/game", result.context?.branch)
        assertEquals("IDS_VALID", result.citationStatus); assertEquals(20, result.generation?.usage?.inputTokens)
        assertEquals(1, calls); assertEquals(1, agent.turns(chat.id).size)
        assertTrue(result.warnings.isEmpty()); assertEquals(index.context, result.indexContext)
    }
    @Test fun requestRetryDoesNotCallModelTwice() {
        val chat = agent.createChat(); val request = HelpRequest(UUID.randomUUID().toString(), "/help Как устроен ECS?")
        await(agent.send(chat.id, request).id)
        agent.send(chat.id, request)
        assertEquals(1, calls)
        assertThrows(AssistantFailure::class.java) { agent.send(chat.id, request.copy(message = "Другой вопрос")) }
    }
    @Test fun staleUncommittedDocsAllowGenerationWithPersistedWarning() {
        current = current.copy(fingerprint = "changed")
        val result = await(agent.send(agent.createChat().id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("COMPLETED", result.status); assertEquals(1, calls); assertTrue(result.errorCode == null)
        assertEquals("STALE_INDEX", result.warnings.single().code)
        assertEquals("changed", result.context?.fingerprint); assertEquals("fingerprint", result.indexContext?.fingerprint)
        assertEquals(index.chunks.single().chunk, result.sources.single().chunk)
        assertEquals(result.warnings, agent.turn(result.id).warnings)
        val payload = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build().readTree(lastMessages.last().content)
        assertEquals("changed", payload["liveProjectContext"]["fingerprint"].asText())
        assertEquals("fingerprint", payload["indexedProjectContext"]["fingerprint"].asText())
        assertEquals("STALE_INDEX", payload["sourceWarnings"][0]["code"].asText())
        assertEquals("Вопрос о проекте", result.question)
    }
    @Test fun aNewCommitAlsoWarnsInsteadOfBlocking() {
        current = current.copy(sha = "new-sha")
        val result = await(agent.send(agent.createChat().id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("COMPLETED", result.status); assertEquals(1, calls)
        assertEquals("STALE_INDEX", result.warnings.single().code)
        assertEquals("sha", result.indexContext?.sha); assertEquals("new-sha", result.context?.sha)
    }
    @Test fun staleWarningSurvivesAChangeDuringGeneration() {
        current = current.copy(fingerprint = "already-stale"); changeAfterGeneration = true
        val result = await(agent.send(agent.createChat().id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("COMPLETED", result.status); assertEquals("SOURCE_CHANGED_AFTER_ANSWER", result.errorCode)
        assertEquals("STALE_INDEX", result.warnings.single().code); assertEquals(1, calls)
    }
    @Test fun incompatibleEmbeddingsStillBlockGeneration() {
        identity = identity.copy(digest = "different-weights")
        val result = await(agent.send(agent.createChat().id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("FAILED", result.status); assertEquals("EMBEDDING_CHANGED", result.errorCode); assertEquals(0, calls)
    }
    @Test fun oldSavedTurnsWithoutNewFieldsStillLoad() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val old = """{"id":"old","chatId":"chat","question":"Question","provider":"LOCAL","status":"COMPLETED","answer":"Old answer"}"""
        val restored = mapper.readValue(old, HelpTurn::class.java)
        assertEquals("Old answer", restored.answer); assertTrue(restored.indexContext == null); assertTrue(restored.warnings.isEmpty())
        store.put("help-turn", restored.id, restored)
        assertEquals(restored, agent.turn(restored.id))
    }
    @Test fun mcpFailureDoesNotDisappearFromHistory() {
        failMcp = true
        val chat = agent.createChat()
        val result = await(agent.send(chat.id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("MCP_UNAVAILABLE", result.errorCode)
        assertEquals(1, agent.turns(chat.id).size); assertEquals(0, calls)
    }
    @Test fun emptyModelAnswerIsSavedAsFailure() {
        failGeneration = true
        val result = await(agent.send(agent.createChat().id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("FAILED", result.status); assertEquals("EMPTY_ANSWER", result.errorCode)
        assertEquals(1, result.sources.size)
    }
    @Test fun postGenerationMcpFailureDoesNotLosePaidAnswer() {
        failAfterGeneration = true
        val result = await(agent.send(agent.createChat().id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertEquals("COMPLETED", result.status); assertTrue(result.answer.isNotBlank())
    }
    @Test fun historiesAreIsolatedAndPersisted() {
        val a = agent.createChat(); val b = agent.createChat()
        val result = await(agent.send(a.id, HelpRequest(UUID.randomUUID().toString(), "/help Вопрос о проекте")).id)
        assertTrue(agent.turns(b.id).isEmpty())
        assertEquals(result.id, store.get("help-turn", result.id, HelpTurn::class.java)?.id)
    }
    @Test fun restartMarksRunningWithoutRetry() {
        val pending = HelpTurn(UUID.randomUUID().toString(), agent.createChat().id, "Question", "LOCAL")
        store.put("help-turn", pending.id, pending); agent.recover()
        assertEquals("INTERRUPTED", agent.turn(pending.id).status); assertEquals(0, calls)
    }
}
