package dev.aichallenge.assistants

import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.project.*
import dev.aichallenge.assistants.projecthelp.models.HelpRequest
import dev.aichallenge.assistants.projecthelp.policy.*
import dev.aichallenge.assistants.rag.indexing.MarkdownChunker
import dev.aichallenge.assistants.rag.models.*
import dev.aichallenge.assistants.rag.retrieval.Retriever
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Детерминированные проверки чанков, embedding-space и честности проверки ссылок. */
class RagAndPolicyTest {
    @Test fun chunksPreserveExactSourceLines() {
        val text = "# Project\r\nIntro\r\n## Rules\r\nShift — dash\r\nF2 — CRT\r\n"
        val chunks = MarkdownChunker().split(ProjectDocument("README.md", "hash", text, "MIXED_HISTORY"))
        assertTrue(chunks.size >= 2)
        chunks.forEach { assertEquals(text.split("\n").subList(it.startLine - 1, it.endLine).joinToString("\n"), it.text) }
        assertEquals(chunks.size, chunks.map { it.chunkId }.distinct().size)
        assertTrue(chunks.any { it.section == "Rules" })
    }
    @Test fun longSectionsAreSplitWithoutLosingLines() {
        val text = "# Long\n" + (1..200).joinToString("\n") { "line $it " + "x".repeat(60) }
        val chunks = MarkdownChunker().split(ProjectDocument("docs/long.md", "hash", text, "PROJECT_DOCUMENT"))
        assertTrue(chunks.size > 3)
        assertEquals(text, chunks.joinToString("\n") { it.text })
    }
    @Test fun validatesVectorSpace() {
        assertEquals(1.0, cosine(listOf(1.0, 0.0), listOf(1.0, 0.0)), 0.00001)
        assertThrows(IllegalArgumentException::class.java) { cosine(listOf(1.0), listOf(1.0, 2.0)) }
        assertThrows(IllegalArgumentException::class.java) { validateVector(listOf(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { validateVector(listOf(0.0)) }
    }
    @Test fun enforcesQuestionAndRequestIdentity() {
        val policy = HelpInputPolicy()
        assertEquals("Как устроен проект?", policy.question(HelpRequest(UUID.randomUUID().toString(), "/help Как устроен проект?")))
        assertThrows(RuntimeException::class.java) { policy.question(HelpRequest("bad", "question")) }
        assertThrows(RuntimeException::class.java) { policy.question(HelpRequest(UUID.randomUUID().toString(), "/help")) }
    }
    @Test fun citationValidationDoesNotPretendToJudgeTruth() {
        val chunk = Chunk("chunk", "README.md", "Project", 1, 2, "Text", "hash", "PROJECT_DOCUMENT")
        val sources = listOf(Source("S1", chunk, 0.8))
        val policy = HelpOutputPolicy()
        assertEquals("IDS_VALID", policy.citations("Unverified claim [S1]", sources))
        assertEquals("INVALID", policy.citations("Claim [S999]", sources))
        assertEquals("MISSING", policy.citations("Unknown", sources))
    }
    @Test fun lexicalAndDenseRankingReturnLocalSources() {
        val a = Chunk("a", "docs/rules.md", "Boss schedule", 1, 2, "Boss appears after nine minutes", "hash", "PROJECT_DOCUMENT")
        val b = a.copy(chunkId = "b", path = "docs/input.md", section = "Input", text = "F11 fullscreen")
        val index = IndexRecord("idx", "READY", chunks = listOf(IndexedChunk(b, listOf(0.8, 0.2)), IndexedChunk(a, listOf(1.0, 0.0))))
        val sources = Retriever().search(index, "boss schedule", listOf(1.0, 0.0))
        assertEquals("a", sources.first().chunk.chunkId)
        assertEquals(listOf("S1", "S2"), sources.map { it.id })
    }
    @Test fun explicitCurrentStateIsVersionContextNotJustAnotherHistoricalHit() {
        val a = Chunk("a", "README.md", "Текущее состояние", 1, 2, "Current ActOne", "hash", "CURRENT_STATE")
        val b = a.copy(chunkId = "b", path = "docs/history.md", text = "Old boss schedule", status = "MIXED_HISTORY")
        val index = IndexRecord("idx", "READY", chunks = listOf(IndexedChunk(b, listOf(1.0, 0.0)), IndexedChunk(a, listOf(0.7, 0.7))))
        assertEquals("a", Retriever().search(index, "old boss schedule", listOf(1.0, 0.0)).first().chunk.chunkId)
    }
    @Test fun currentReadmeLinksProvideRelatedDocumentationEvenWithCompetingHistory() {
        val current = Chunk("current", "README.md", "Текущее состояние", 1, 2, "[Rules](docs/current.md)", "hash", "CURRENT_STATE")
        val rules = current.copy(chunkId = "rules", path = "docs/current.md", text = "Current rules", status = "PROJECT_DOCUMENT")
        val history = (1..10).map { IndexedChunk(rules.copy(chunkId = "old$it", path = "docs/old$it.md", text = "Boss schedule"), listOf(1.0, 0.0)) }
        val index = IndexRecord("idx", "READY", chunks = history + listOf(IndexedChunk(current, listOf(0.7, 0.7)), IndexedChunk(rules, listOf(0.6, 0.8))))
        val sources = Retriever().search(index, "boss schedule", listOf(1.0, 0.0))
        assertTrue(sources.any { it.chunk.path == "docs/current.md" && it.origin == "README_LINK" })
    }
}
