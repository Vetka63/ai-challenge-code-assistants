package dev.aichallenge.workspacemcp

import dev.aichallenge.workspacemcp.passport.PassportCollector
import dev.aichallenge.workspacemcp.storage.WorkspaceStore
import dev.aichallenge.workspace.contracts.WorkspaceFailure
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class PassportCollectorTest {
    @TempDir lateinit var root: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private lateinit var store: WorkspaceStore
    private lateinit var collector: PassportCollector
    private lateinit var source: Path
    private lateinit var data: Path
    private fun write(path: String, text: String) { source.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
    @BeforeEach fun setup() {
        source = root.resolve("source"); data = root.resolve("owned")
        write("README.md", "# Game\n![Screen](art/playtests/current/screen.png)")
        write("docs/rules.md", "# Rules"); write("AsciiArena/ProjectSettings/EditorBuildSettings.asset", "m_Scenes:\n- path: Assets/Scenes/Arena.unity")
        write(".env", "private"); write("Library/private.md", "private")
        val image = source.resolve("art/playtests/current/screen.png"); Files.createDirectories(image.parent)
        ImageIO.write(BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
        store = WorkspaceStore(source.toString(), data.toString(), mapper)
        collector = PassportCollector(store, mapper, source.toString(), data.toString())
    }
    @AfterEach fun close() { store.close() }
    @Test fun snapshotsDeduplicateAndIncludeOnlyExactBuildSettings() {
        val first = collector.collect(); val second = collector.collect()
        assertEquals(first.snapshot.info, second.snapshot.info); assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(1, store.list().size); assertEquals(3, first.snapshot.files.size)
        assertTrue(first.snapshot.files.any { it.path.endsWith("EditorBuildSettings.asset") })
        write("AsciiArena/ProjectSettings/secret.asset", "private")
        assertEquals(first.fingerprint, collector.collect().fingerprint)
    }
    @Test fun mediaAndOldSnapshotsRemainImmutableWhenGameChanges() {
        val first = collector.collect(); val image = first.images.single()
        val bytes = collector.image(image.id).second
        write("README.md", "# Changed"); Files.delete(source.resolve(image.path))
        val second = collector.collect()
        assertNotEquals(first.fingerprint, second.fingerprint); assertTrue(second.images.isEmpty())
        assertEquals(first.snapshot, store.snapshot(first.snapshot.info.id))
        assertArrayEquals(bytes, collector.image(image.id).second)
    }
    @Test fun ordinaryMarkdownRasterLinksAreIncludedAndImportDateIsStable() {
        write("README.md", "# Game\n[Обзор](art/playtests/current/screen.png)")
        val first = collector.collect(); val second = collector.collect()
        assertEquals(1, first.images.size); assertEquals(first.images.single().importedAt, second.images.single().importedAt)
        assertEquals(listOf("README.md"), first.images.single().linkedFrom)
    }
    @Test fun noExternalUrlsSvgHtmlOrSecretLinksAreImported() {
        write("README.md", "# Game\n![A](https://example.test/a.png)\n![B](.env)\n![C](art/playtests/x.svg)\n![D](art/playtests/fake.png)")
        write("art/playtests/fake.png", "<html>not an image</html>")
        val result = collector.collect()
        assertTrue(result.images.isEmpty()); assertFalse(result.warnings.isEmpty())
        assertThrows(WorkspaceFailure::class.java) { collector.image("../.env") }
    }
    @Test fun symlinkImageIsNeverFollowed() {
        val image = source.resolve("art/playtests/current/screen.png"); Files.delete(image)
        Files.createSymbolicLink(image, source.resolve(".env"))
        assertTrue(collector.collect().images.isEmpty())
    }
    @Test fun removingAFileChangesSnapshotAndSourceIsNeverWritten() {
        val original = Files.readString(source.resolve("README.md")); val first = collector.collect()
        Files.delete(source.resolve("docs/rules.md")); val next = collector.collect()
        assertNotEquals(first.snapshot.info.fingerprint, next.snapshot.info.fingerprint)
        assertFalse(next.snapshot.files.any { it.path == "docs/rules.md" })
        assertEquals(original, Files.readString(source.resolve("README.md")))
    }
}
