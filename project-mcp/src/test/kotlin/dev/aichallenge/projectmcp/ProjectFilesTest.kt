package dev.aichallenge.projectmcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Проверяет файловые ограничения и чтение реального Git без игры пользователя. */
class ProjectFilesTest {
    @TempDir lateinit var root: Path
    private lateinit var project: ProjectFiles
    @BeforeEach fun setup() {
        Files.createDirectories(root.resolve("docs"))
        Files.writeString(root.resolve("README.md"), "# Game\nCurrent version")
        Files.writeString(root.resolve("docs/rules.md"), "# Rules\nShift is dash")
        fun git(vararg args: String) {
            val p = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
            val output = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
            check(p.waitFor() == 0) { output }
        }
        git("init", "--initial-branch=main")
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--allow-empty", "-m", "fixture")
        project = ProjectFiles(root.toString())
    }
    @Test fun readsBranchAndUntrackedDocuments() {
        val result = project.snapshot()
        assertEquals("main", result.context.branch)
        assertEquals(40, result.context.sha.length)
        assertEquals(setOf("README.md", "docs/rules.md"), result.documents.map { it.path }.toSet())
    }
    @Test fun detectsUncommittedDocumentChange() {
        val first = project.context()
        Files.writeString(root.resolve("docs/rules.md"), "# Rules\nF2 is CRT")
        val next = project.context()
        assertEquals(first.sha, next.sha); assertNotEquals(first.fingerprint, next.fingerprint)
    }
    @Test fun rejectsTraversalWithinAndOutsideRoot() {
        Files.writeString(root.resolve("private.md"), "private")
        listOf("docs/../private.md", "docs/../../secret.md", ".git/config", ".env", "/etc/passwd", "docs\\rules.md").forEach {
            assertThrows(IllegalArgumentException::class.java) { project.read(it) }
        }
    }
    @Test fun rejectsLinkToSecretEvenInsideRoot() {
        Files.writeString(root.resolve(".env"), "secret")
        Files.createSymbolicLink(root.resolve("docs/leak.md"), root.resolve(".env"))
        assertThrows(IllegalArgumentException::class.java) { project.read("docs/leak.md") }
    }
    @Test fun literalSearchReturnsPathAndLine() {
        val hit = project.search("Shift").single()
        assertEquals("docs/rules.md", hit["path"]); assertEquals(2, hit["line"])
        assertTrue(project.search(".*").isEmpty())
    }
    @Test fun readingDoesNotChangeDocuments() {
        val before = project.snapshot()
        project.read("README.md"); project.search("Shift"); project.files()
        assertEquals(before, project.snapshot())
    }
    @Test fun researchIsNotClassifiedAsImplementation() {
        Files.createDirectories(root.resolve("docs/research"))
        Files.writeString(root.resolve("docs/research/idea.md"), "# Future\nTwo hours")
        assertEquals("PROPOSAL_OR_RESEARCH", project.read("docs/research/idea.md").status)
    }
}
