package dev.aichallenge.assistants

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.passport.model.*
import dev.aichallenge.assistants.passport.policy.PassportPolicy
import dev.aichallenge.assistants.passport.repository.PassportRepository
import dev.aichallenge.assistants.passport.source.*
import dev.aichallenge.assistants.passport.usecase.RefreshPassport
import dev.aichallenge.assistants.workspace.gateway.*
import dev.aichallenge.workspace.contracts.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Бесплатные проверки на настоящем SQLite и executor. Модель и MCP здесь явно тестовые. */
class PassportTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private lateinit var repository: PassportRepository
    private lateinit var jobs: Jobs
    private lateinit var refresh: RefreshPassport
    private val calls = AtomicInteger()
    private var fail = false
    private var malformed = false
    private var corruptRead = false
    private var collection = collection("first")
    private fun id() = UUID.randomUUID().toString()
    private fun collection(version: String): PassportCollection {
        val files = listOf(SourceFile("README.md", "r-$version", "# Game\nMode $version\n[Rules](docs/rules.md)"),
            SourceFile("docs/rules.md", "d-$version", "# Rules\nJump $version"),
            SourceFile("AsciiArena/Assets/Prototype/Settings.cs", "c-$version", "class Settings {}"))
        return PassportCollection(WorkspaceSnapshot(SnapshotInfo("00000000-0000-4000-8000-000000000001", version, files.size, Instant.now().toString()), files), version, emptyList(), emptyList(), Instant.now().toString())
    }
    private val draft = """{"title":"Game","overview":[{"text":"Game","kind":"DOCUMENTED","sources":["S1"]}],"mechanics":[{"text":"Jump","kind":"DOCUMENTED","sources":["S2"]}],"levels":[{"id":"run","name":"Run","kind":"MODE","details":[{"text":"Mode","kind":"DOCUMENTED","sources":["S1"]}],"imageIds":[]}],"questions":["Проверить игру"]}"""
    @BeforeEach fun setup() {
        repository = PassportRepository(RuntimeConfig(root.resolve("db.sqlite").toString(), "", "", "", "", "", ""), mapper)
        jobs = Jobs()
        val sources = object : PassportSources {
            override fun collect() = collection
            override fun image(id: String): Pair<String, ByteArray> = error("not used")
        }
        val tools = object : WorkspaceTools {
            override fun open(snapshot: String, run: String, events: MutableList<WorkspaceEvent>) = object : WorkspaceSession {
                override val tools = emptyList<ToolDefinition>()
                override fun close() {}
                override fun call(name: String, args: Map<String, Any>): ToolResult {
                    assertEquals("read_file", name)
                    val file = collection.snapshot.files.first { it.path == args["path"] }
                    val end = args["end_line"] as Int
                    return ToolResult(mapper.writeValueAsString(mapOf("path" to file.path, "hash" to if (corruptRead) "wrong-version" else file.hash,
                        "startLine" to 1, "endLine" to end, "numberedText" to file.text.lines().take(end).mapIndexed { i, line -> "${i+1}: $line" }.joinToString("\n"))), false)
                }
            }
        }
        val model = object : JsonGenerator {
            override fun generateJson(provider: String, messages: List<LlmMessage>): Generation {
                calls.incrementAndGet()
                if (fail) throw AssistantFailure("TEST_MODEL_FAILURE", "fixture failure", 502)
                assertFalse(messages.any { it.content.contains("prior invented fact") })
                return Generation(if (malformed) "not json" else draft, "fixture", Usage(100, 40), "stop", 1)
            }
        }
        refresh = RefreshPassport(repository, sources, PassportEvidenceReader(tools, mapper), model, PassportPolicy(mapper), jobs)
        refresh.recover()
    }
    @AfterEach fun close() { jobs.close() }
    private fun run(command: PassportCommand = PassportCommand(id())): PassportCheck {
        refresh.start(command)
        repeat(300) { val value = repository.get(command.id)!!; if (value.finishedAt != null) return value; Thread.sleep(10) }
        error("Timed out")
    }
    @Test fun createsPassportAndIdempotentUnchangedChecksNeverCallModel() {
        val command = PassportCommand(id()); val first = run(command)
        assertEquals("COMPLETED", first.status); assertEquals(3, first.sources.size); assertEquals(1, calls.get())
        assertEquals(first, refresh.start(command)); assertEquals(1, calls.get())
        assertEquals("NO_CHANGES", run().status); assertEquals(1, calls.get())
        assertEquals(first.id, repository.state().currentId)
    }
    @Test fun modelFailureKeepsCurrentAndRequiresConfirmedRetry() {
        val first = run(); collection = collection("second"); fail = true
        val failed = run(); assertEquals("FAILED", failed.status); assertNotNull(failed.attemptedAt)
        assertEquals(first.id, repository.state().currentId)
        fail = false; assertEquals("BLOCKED", run().status); assertEquals(2, calls.get())
        assertEquals("COMPLETED", run(PassportCommand(id(), true)).status); assertEquals(3, calls.get())
    }
    @Test fun invalidOutputPreservesRawAndCannotPublish() {
        malformed = true
        val check = run(); assertEquals("FAILED", check.status); assertEquals("INVALID_PASSPORT", check.errorCode)
        assertEquals("not json", check.generation!!.text); assertNull(repository.state().currentId)
        assertEquals("BLOCKED", run().status); assertEquals(1, calls.get())
    }
    @Test fun dailyQuotaAppliesToConfirmedManualAndScheduledChecks() {
        refresh.settings(PassportSettings(dailyLimit = 1))
        run(); collection = collection("second")
        val blocked = run(PassportCommand(id(), true))
        assertEquals("BLOCKED", blocked.status); assertEquals("PASSPORT_DAILY_LIMIT", blocked.errorCode)
        assertNull(blocked.attemptedAt); assertEquals(1, calls.get())
    }
    @Test fun deletionIsComparedAgainstLastSuccessNotFailedAttempt() {
        collection = collection.copy(snapshot = collection.snapshot.copy(files = collection.snapshot.files + SourceFile("docs/old.md", "old", "# Old")))
        val first = run(); collection = collection("failed"); fail = true; run()
        fail = false
        collection = collection("third").let { it.copy(snapshot = it.snapshot.copy(files = it.snapshot.files + SourceFile("docs/extra.md", "new", "# Extra"))) }
        val next = run()
        assertEquals(first.fileHashes.keys - "docs/old.md" + "docs/extra.md", next.fileHashes.keys)
        assertEquals("ADDED", next.changes.first { it.path == "docs/extra.md" }.kind)
        assertEquals("DELETED", next.changes.first { it.path == "docs/old.md" }.kind)
        assertFalse(next.events.isEmpty())
    }
    @Test fun atomicClaimOnlyAllowsOneOwnerAcrossRepositories() {
        val record = PassportCheck(id(), "MANUAL", "LOCAL", false)
        repository.claim(record)
        val other = PassportRepository(RuntimeConfig(root.resolve("db.sqlite").toString(), "", "", "", "", "", ""), mapper)
        val error = assertThrows(AssistantFailure::class.java) { other.claim(record.copy(id = id())) }
        assertEquals("PASSPORT_BUSY", error.code); assertEquals(record, repository.claim(record))
    }
    @Test fun restartDoesNotRepeatUncertainPaidAttemptAndPreservesSettings() {
        refresh.settings(PassportSettings(true, 30, "DEEPSEEK", 9))
        val record = PassportCheck(id(), "SCHEDULED", "DEEPSEEK", false, fingerprint = "first")
        repository.claim(record); repository.attempt(record, Instant.now()); refresh.recover()
        assertEquals("INTERRUPTED", repository.get(record.id)!!.status)
        assertEquals("first", repository.state().blockedFingerprint); assertEquals(9, repository.state().settings.dailyLimit)
        assertEquals("BLOCKED", run().status); assertEquals(0, calls.get())
    }
    @Test fun pausePreventsNewScheduledWorkAndSettingsValidate() {
        assertEquals("PASSPORT_NOT_DUE", assertThrows(AssistantFailure::class.java) { refresh.start(PassportCommand(id()), "SCHEDULED") }.code)
        assertThrows(AssistantFailure::class.java) { refresh.settings(PassportSettings(intervalSeconds = 1)) }
        assertThrows(AssistantFailure::class.java) { refresh.settings(PassportSettings(provider = "OTHER")) }
    }
    @Test fun dueScheduledCheckUsesTheSamePipelineAndReschedulesAfterFinish() {
        repository.settings(PassportSettings(true, 30, "LOCAL", 3), Instant.now().minusSeconds(60))
        val command = PassportCommand(id()); refresh.start(command, "SCHEDULED")
        repeat(300) { if (repository.get(command.id)?.finishedAt == null) Thread.sleep(10) }
        val check = repository.get(command.id)!!
        assertEquals("COMPLETED", check.status); assertEquals("SCHEDULED", check.trigger)
        assertEquals(Instant.parse(check.finishedAt).plusSeconds(30), Instant.parse(repository.state().nextCheckAt))
        refresh.settings(PassportSettings(false, 30)); assertNull(repository.state().nextCheckAt)
    }
    @Test fun referencesMustBelongToActualReadsAndPlansCannotPretendImplemented() {
        val check = run(); val policy = PassportPolicy(mapper)
        assertThrows(AssistantFailure::class.java) { policy.validate(draft.replace("S1", "S99"), check) }
        assertThrows(AssistantFailure::class.java) { policy.validate(draft.replace("\"kind\":\"MODE\"", "\"kind\":\"PLANNED\""), check) }
        assertThrows(AssistantFailure::class.java) { policy.validate(draft.replace("DOCUMENTED", "CODE_PRESENT"), check) }
    }
    @Test fun buildConfigurationIsTechnicalEvidenceNotOnlyCSharp() {
        val check = run().let { it.copy(sources = it.sources.map { source -> if (source.id == "S1") source.copy(path = "AsciiArena/ProjectSettings/EditorBuildSettings.asset") else source }) }
        val technical = draft.replace("\"text\":\"Game\",\"kind\":\"DOCUMENTED\"", "\"text\":\"Game\",\"kind\":\"CODE_PRESENT\"")
        assertEquals("CODE_PRESENT", PassportPolicy(mapper).validate(technical, check).overview.single().kind)
    }
    @Test fun corruptMcpEvidenceStopsBeforeAnyModelCall() {
        corruptRead = true
        val check = run(); assertEquals("FAILED", check.status); assertEquals("SOURCE_MISMATCH", check.errorCode)
        assertNull(check.attemptedAt); assertEquals(0, calls.get())
    }
    @Test fun pauseWinsBeforeAtomicClaimAndDoesNotCreateAJob() {
        repository.settings(PassportSettings(true, 30), Instant.now().minusSeconds(60))
        val expected = repository.state()
        repository.settings(expected.settings.copy(enabled = false), Instant.now())
        val check = PassportCheck(id(), "SCHEDULED", "LOCAL", false)
        assertEquals("PASSPORT_SETTINGS_CHANGED", assertThrows(AssistantFailure::class.java) { repository.claim(check, expected) }.code)
        assertNull(repository.get(check.id)); assertNull(repository.state().activeId)
    }
    @Test fun originalTwoLevelScopeCannotBePublishedAsCompletedWork() {
        val check = run().let { it.copy(sources = it.sources.map { source ->
            if (source.id == "S1") source.copy(text = "1: Исходный объём — два законченных уровня; достаточность ещё предстоит оценить.") else source }) }
        val wrong = draft.replace("\"text\":\"Game\",\"kind\":\"DOCUMENTED\"", "\"text\":\"Игра включает два законченных уровня\",\"kind\":\"DOCUMENTED\"")
        assertEquals("PASSPORT_PLAN_AS_FACT", assertThrows(AssistantFailure::class.java) { PassportPolicy(mapper).validate(wrong, check) }.code)
        val plan = wrong.replace("\"text\":\"Игра включает два законченных уровня\",\"kind\":\"DOCUMENTED\"", "\"text\":\"Плановый объём — два уровня\",\"kind\":\"PLANNED\"")
        assertEquals("PLANNED", PassportPolicy(mapper).validate(plan, check).overview.single().kind)
    }
}
