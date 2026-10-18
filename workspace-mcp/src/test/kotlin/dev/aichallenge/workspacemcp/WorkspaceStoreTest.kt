package dev.aichallenge.workspacemcp

import dev.aichallenge.workspace.contracts.*
import dev.aichallenge.workspacemcp.storage.WorkspaceStore
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.*
import java.util.UUID

/** Настоящие временные файлы: изоляция игры, стабильность снимка, конфликты и восстановление. */
class WorkspaceStoreTest {
    @TempDir lateinit var temp: Path
    private lateinit var source: Path
    private lateinit var data: Path
    private lateinit var store: WorkspaceStore
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val paths = listOf("README.md", "docs/controls.md", "AsciiArena/Assets/Prototype/ArenaSettings.cs")
    private fun id() = UUID.randomUUID().toString()
    @BeforeEach fun setup() {
        source = temp.resolve("game"); data = temp.resolve("owned")
        paths.forEachIndexed { index, path -> Files.createDirectories(source.resolve(path).parent); Files.writeString(source.resolve(path), "line $index\nShift\n") }
        Files.writeString(source.resolve(".env"), "secret")
        store = WorkspaceStore(source.toString(), data.toString(), mapper)
    }
    @AfterEach fun close() { store.close() }
    private fun ready(): Pair<String, String> {
        val snapshot = store.create(id()).id; val run = id()
        paths.forEach { store.file(snapshot, run, it, 1, 100) }
        return snapshot to run
    }
    @Test fun snapshotIsStableAndRequestIsIdempotent() {
        val first = store.create(id())
        Files.writeString(source.resolve("README.md"), "changed")
        assertEquals(first, store.create(first.id))
        assertEquals("line 0\nShift\n", store.snapshot(first.id).files.first { it.path == "README.md" }.text)
        assertNotEquals(first.fingerprint, store.create(id()).fingerprint)
    }
    @Test fun searchIsLiteralAndPaginated() {
        val snapshot = store.create(id()).id
        val result = store.search(snapshot, "Shift", 0, 1)
        assertEquals(3, result["total"]); assertEquals(true, result["hasMore"])
        assertEquals(0, store.search(snapshot, ".*", 0, 10)["total"])
        assertEquals(3, store.files(snapshot, 0, 1)["total"])
    }
    @Test fun refusesTraversalSecretsAdsAndAbsolutePaths() {
        listOf("../README.md", ".env", ".git/config", "docs/../README.md", "C:/x.md",
            "docs/a.md:secret", "docs\\a.md", "/README.md", "docs/.secret.md").forEach {
            assertThrows(WorkspaceFailure::class.java) { store.checkPath(it) }
        }
    }
    @Test fun externalLinksAreNotRead() {
        Files.createSymbolicLink(source.resolve("docs/leak.md"), source.resolve(".env"))
        val snap = store.create(id())
        assertFalse(store.snapshot(snap.id).files.any { it.path == "docs/leak.md" })
    }
    @Test fun writeNeedsThreeActuallyReadFiles() {
        val snapshot = store.create(id()).id
        val error = assertThrows(WorkspaceFailure::class.java) {
            store.propose(snapshot, id(), "docs/generated/report.md", "# Report\n", null, paths, true)
        }
        assertEquals("EVIDENCE_REQUIRED", error.code)
        assertTrue(store.artifacts(snapshot, id()).isEmpty())
    }
    @Test fun receiptsCannotBeBorrowedFromAnotherRun() {
        val (snapshot, _) = ready()
        assertThrows(WorkspaceFailure::class.java) { store.propose(snapshot, id(), "docs/generated/a.md", "# Result", null, paths, true) }
    }
    @Test fun newDocumentAndPatchPersistWithoutChangingGame() {
        val (snapshot, run) = ready()
        val artifact = store.propose(snapshot, run, "docs/generated/report.md", "# Report\n", null, paths, true)
        assertTrue(Files.readString(data.resolve(artifact.id + ".md")).contains("# Report"))
        assertEquals(artifact.content, Files.readString(data.resolve("outputs").resolve(artifact.id).resolve(artifact.path)))
        assertTrue(Files.readString(data.resolve(artifact.id + ".patch")).contains("--- /dev/null"))
        assertFalse(Files.exists(source.resolve("docs/generated/report.md")))
        assertEquals(artifact, store.propose(snapshot, run, artifact.path, artifact.content, null, paths, true))
        assertEquals(1, store.artifacts(snapshot, run).size)
    }
    @Test fun updateRequiresMatchingHashAndKeepsOriginal() {
        val (snapshot, run) = ready()
        val before = store.snapshot(snapshot).files.first { it.path == "docs/controls.md" }
        val error = assertThrows(WorkspaceFailure::class.java) {
            store.propose(snapshot, run, before.path, "new\n", "wrong", paths, false)
        }
        assertEquals("BASE_CONFLICT", error.code)
        val result = store.propose(snapshot, run, before.path, "new\n", before.hash, paths, false)
        assertEquals("PROPOSED_CHANGE", result.kind)
        assertTrue(result.diff.contains("-Shift"))
        assertEquals(before.text, Files.readString(source.resolve(before.path)))
    }
    @Test fun patchAppliesToExactBaseline() {
        val (snapshot, run) = ready()
        val before = store.snapshot(snapshot).files.first { it.path == "README.md" }
        val result = store.propose(snapshot, run, before.path, "new\n", before.hash, paths, false)
        val copy = temp.resolve("check"); Files.createDirectories(copy)
        Files.writeString(copy.resolve("README.md"), before.text)
        Files.writeString(copy.resolve("change.patch"), result.diff)
        val check = ProcessBuilder("git", "apply", "--check", "change.patch").directory(copy.toFile()).redirectErrorStream(true).start()
        val output = check.inputStream.bufferedReader().readText()
        assertEquals(0, check.waitFor(), output)
        val apply = ProcessBuilder("git", "apply", "change.patch").directory(copy.toFile()).redirectErrorStream(true).start()
        assertEquals(0, apply.waitFor())
        assertEquals(result.content, Files.readString(copy.resolve("README.md")))
    }
    @Test fun cannotWriteCodeOrOverwriteExistingFileAsNew() {
        val (snapshot, run) = ready()
        assertEquals("WRITE_DENIED", assertThrows(WorkspaceFailure::class.java) {
            store.propose(snapshot, run, paths.last(), "new", null, paths, true)
        }.code)
        assertEquals("FILE_EXISTS", assertThrows(WorkspaceFailure::class.java) {
            store.propose(snapshot, run, "README.md", "new", null, paths, true)
        }.code)
    }
    @Test fun partialReadCannotAuthorizeFullReplacement() {
        Files.writeString(source.resolve("docs/controls.md"), (1..200).joinToString("\n") { "line $it" })
        val snapshot = store.create(id()).id; val run = id()
        paths.forEach { store.file(snapshot, run, it, 1, 100) }
        val hash = store.snapshot(snapshot).files.first { it.path == "docs/controls.md" }.hash
        assertEquals("BASE_NOT_READ", assertThrows(WorkspaceFailure::class.java) {
            store.propose(snapshot, run, "docs/controls.md", "new", hash, paths, false)
        }.code)
        val fragment = store.file(snapshot, run, "docs/controls.md", 101, 200)
        assertTrue(fragment["numberedText"].toString().startsWith("101: line 101"))
        assertEquals("PROPOSED_CHANGE", store.propose(snapshot, run, "docs/controls.md", "new", hash, paths, false).kind)
    }
    @Test fun restartPreservesArtifactsAndSingleOwner() {
        val (snapshot, run) = ready()
        val result = store.propose(snapshot, run, "docs/generated/a.md", "# A", null, paths, true)
        assertThrows(Exception::class.java) { WorkspaceStore(source.toString(), data.toString(), mapper) }
        store.close()
        store = WorkspaceStore(source.toString(), data.toString(), mapper)
        assertEquals(result, store.artifacts(snapshot, run).single())
    }
    @Test fun corruptionIsNotReplacedWithEmptyState() {
        val snapshot = store.create(id())
        Files.writeString(data.resolve("snapshot-" + snapshot.id + ".json"), "broken")
        assertThrows(Exception::class.java) { store.create(snapshot.id) }
        assertEquals("broken", Files.readString(data.resolve("snapshot-" + snapshot.id + ".json")))
    }
    @Test fun historicalSectionsCannotBeRewrittenAsCurrentRules() {
        Files.writeString(source.resolve("README.md"), "# Current\nNow\nСледующие абзацы описывают историю предыдущих версий.\nSpace was pause\n## Links\nKeep\n")
        val (snapshot, run) = ready()
        val before = store.snapshot(snapshot).files.first { it.path == "README.md" }
        assertEquals("HISTORY_PROTECTED", assertThrows(WorkspaceFailure::class.java) {
            store.propose(snapshot, run, before.path, before.text.replace("Space was pause", "Esc is pause"), before.hash, paths, false)
        }.code)
        assertEquals("PROPOSED_CHANGE", store.propose(snapshot, run, before.path, before.text.replace("Now", "Current corrected"), before.hash, paths, false).kind)
    }
    @Test fun preservesCrLfInsteadOfReformattingWholeDocument() {
        Files.writeString(source.resolve("README.md"), "# Readme\r\nold\r\n")
        val (snapshot, run) = ready()
        val before = store.snapshot(snapshot).files.first { it.path == "README.md" }
        val result = store.propose(snapshot, run, before.path, "# Readme\nnew\n", before.hash, paths, false)
        assertEquals("# Readme\r\nnew\r\n", result.content)
    }
}
