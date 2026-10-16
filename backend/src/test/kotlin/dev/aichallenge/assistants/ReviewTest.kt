package dev.aichallenge.assistants

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.rag.indexing.MarkdownChunker
import dev.aichallenge.assistants.rag.retrieval.Retriever
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.github.*
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.review.policy.*
import dev.aichallenge.assistants.review.rag.*
import dev.aichallenge.assistants.review.repository.ReviewRepository
import dev.aichallenge.assistants.review.service.*
import dev.aichallenge.assistants.storage.RecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.*

/** Полный review use case и настоящая SQLite; сеть/LLM подменяются только опубликованными портами. */
class ReviewTest {
    @TempDir lateinit var dir: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val config = ReviewConfig("Vetka63/ai-challenge-game", "Vetka63", "/missing", "ai-review.yml")
    private val policy = ReviewInputPolicy(config)
    private val request = ReviewRequest(config.repository, 1, "a".repeat(40), "b".repeat(40))
    private val path = "AsciiArena/Assets/Prototype/Save.cs"
    private fun pr() = PullRequest(1, "Fix saves", "Preserve coins", request.baseSha, request.headSha,
        config.repository, config.repository, "Vetka63", "open", 1, "https://github.com/" + config.repository + "/pull/1")
    private fun database() = RecordStore(RuntimeConfig(dir.resolve("review.sqlite").toString(), "", "", "", "", "", ""), mapper)
    private fun snapshot(): ReviewSnapshot {
        val patch = "@@ -1,2 +1,2 @@\n public class Save {\n- public int Coins = 10;\n+ public int Coins = 0;"
        val lines = changedLines(patch)
        return ReviewSnapshot(pr(), listOf(ChangedFile(path, status = "modified", patch = patch, addedLines = lines.first, removedLines = lines.second)),
            listOf(ReviewDocument("README.md", "# Save rules\nPreserve earned coins when resuming the game.", request.baseSha, "BASE"),
                ReviewDocument(path, "public class Save {\n public int Coins = 0;\n}", request.headSha, "HEAD")))
    }
    private class FakeGitHub(var data: ReviewSnapshot) : GitHubGateway {
        var publications = 0; var failPublication = false
        override fun pull(number: Int) = data.pr
        override fun snapshot(request: ReviewRequest) = data
        override fun discover(seen: Set<String>) = Discovery(emptyList(), emptyList(), emptyList())
        override fun verifyConnection() = data.pr.repository
        override fun publish(job: ReviewJob, markdown: String): String {
            publications++
            if (failPublication) throw AssistantFailure("GITHUB_HTTP_503", "Unavailable")
            return data.pr.url + "#comment"
        }
    }
    @Test fun diffTracksAddedRemovedAndContextLines() {
        val result = changedLines("@@ -10,3 +20,3 @@\n unchanged\n-old\n+new\n last")
        assertEquals(setOf(21), result.first); assertEquals(setOf(11), result.second)
        assertTrue(changedLines(null).first.isEmpty())
    }
    @Test fun pathsRejectSecretsTraversalAndUnityYaml() {
        listOf(".env", "docs/../README.md", "/README.md", "docs\\rules.md", "Assets/Scene.unity", "docs/./x.md", "docs//x.md").forEach { assertFalse(reviewPath(it)) }
        assertTrue(reviewPath(path)); assertTrue(reviewPath("docs/rules.md"))
    }
    @Test fun onlyOwnSameRepositoryPrIsAllowed() {
        policy.matches(request, pr())
        assertThrows(AssistantFailure::class.java) { policy.pull(pr().copy(author = "outsider")) }
        assertThrows(AssistantFailure::class.java) { policy.pull(pr().copy(headRepository = "fork/game")) }
        assertThrows(AssistantFailure::class.java) { policy.matches(request.copy(headSha = "c".repeat(40)), pr()) }
        assertThrows(IllegalArgumentException::class.java) { policy.request(request.copy(baseSha = "main")) }
    }
    @Test fun idIgnoresDeliveryAndIncludesVersionAndProvider() {
        assertEquals(reviewId(request), reviewId(request.copy(runId = 99)))
        assertNotEquals(reviewId(request), reviewId(request.copy(headSha = "c".repeat(40))))
        assertNotEquals(reviewId(request), reviewId(request.copy(provider = "LOCAL")))
    }
    @Test fun concurrentRegistrationUsesSqliteUniqueKey() {
        val repo = ReviewRepository(database()); val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..16).map { executor.submit<ReviewJob> { repo.create(request, "ACTION", true) } }.map { it.get() }
            assertEquals(1, results.map { it.id }.distinct().size); assertEquals(1, repo.list().size)
        } finally { executor.shutdownNow() }
    }
    @Test fun compareAndSetPreventsDoubleClaim() {
        val repo = ReviewRepository(database()); val job = repo.create(request, "ACTION", false)
        repo.update(job) { it.copy(status = ReviewStatus.INDEXING) }
        assertThrows(AssistantFailure::class.java) { repo.update(job) { it.copy(status = ReviewStatus.INDEXING) } }
    }
    @Test fun restartDoesNotRepeatAnUncertainGeneration() {
        val repo = ReviewRepository(database()); val job = repo.create(request, "ACTION", false)
        repo.update(job) { it.copy(status = ReviewStatus.ANALYZING, generationAttempts = listOf(Instant.now().toString())) }
        repo.recover()
        assertEquals(ReviewStatus.INTERRUPTED, repo.get(job.id).status)
        assertEquals(1, repo.get(job.id).generationAttempts.size)
    }
    @Test fun indexingCanResumeAfterRestartWithoutCallingLlm() {
        val repo = ReviewRepository(database()); val job = repo.create(request, "ACTION", false)
        repo.update(job) { it.copy(status = ReviewStatus.INDEXING) }; repo.recover()
        assertEquals(ReviewStatus.QUEUED, repo.get(job.id).status)
    }
    @Test fun csharpChunksPreserveAllSourceLines() {
        val doc = ReviewDocument(path, (1..150).joinToString("\n") { "public int Field" + it + " = " + it + ";" }, request.headSha, "HEAD")
        val chunks = codeChunks(doc)
        assertTrue(chunks.size > 1)
        assertEquals(doc.text, chunks.joinToString("\n") { it.text })
        chunks.forEach { chunk ->
            assertEquals(chunk.text, doc.text.split('\n').subList(chunk.startLine - 1, chunk.endLine).joinToString("\n"))
        }
    }
    @Test fun analysisPublishesSavedReportWithoutSecondGeneration() {
        val store = database(); val repo = ReviewRepository(store); val github = FakeGitHub(snapshot())
        var calls = 0
        val embeddings = object : Embeddings {
            override fun identity() = EmbeddingIdentity("fake", "digest")
            override fun embed(texts: List<String>) = texts.map { listOf(1.0, 0.0) }
        }
        val generator = object : Generator {
            override fun generate(provider: String, messages: List<LlmMessage>): Generation {
                calls++
                assertTrue(messages.last().content.contains("BASE_DOCUMENT")); assertTrue(messages.last().content.contains("HEAD_CODE"))
                return Generation("""{"summary":"Coins should be preserved","findings":[{"category":"BUG","severity":"HIGH","path":"$path","line":2,"side":"HEAD","title":"Coins reset","explanation":"Earned progress is lost","recommendation":"Restore saved coins","sourceIds":["S1"]}]}""", "fake", Usage(100, 50), "stop", 1)
            }
        }
        val useCase = ReviewUseCase(repo, github, policy, ReviewOutputPolicy(mapper), ReviewRag(embeddings, store, MarkdownChunker(), Retriever()), generator, mapper, config)
        val queued = useCase.enqueue(1, "DEEPSEEK", true)
        useCase.analyze(repo.update(queued) { it.copy(status = ReviewStatus.INDEXING) })
        val ready = repo.get(queued.id)
        assertEquals(ReviewStatus.REPORT_READY, ready.status); assertEquals(1, ready.report?.findings?.size)
        assertEquals(snapshot().files, ready.files)
        assertEquals(1, calls); assertTrue(reviewMarkdown(ready).contains("Coins reset"))
        github.failPublication = true
        useCase.publish(repo.update(ready) { it.copy(status = ReviewStatus.PUBLISHING) })
        val failedPublish = repo.get(ready.id)
        assertEquals(ReviewStatus.REPORT_READY, failedPublish.status); assertTrue(failedPublish.report != null)
        github.failPublication = false
        useCase.publish(repo.update(failedPublish) { it.copy(status = ReviewStatus.PUBLISHING) })
        assertEquals(ReviewStatus.COMPLETED, repo.get(ready.id).status); assertEquals(1, calls)
    }
    @Test fun invalidOutputRejectsUnchangedLinesAndFakeSources() {
        val chunk = codeChunks(snapshot().documents.last()).first()
        val sources = listOf(dev.aichallenge.assistants.rag.models.Source("S1", chunk, 1.0))
        val valid = """{"summary":"Test","findings":[{"category":"BUG","severity":"LOW","path":"$path","line":2,"side":"HEAD","title":"T","explanation":"E","recommendation":"R","sourceIds":["S1"]}]}"""
        val output = ReviewOutputPolicy(mapper)
        assertEquals(1, output.parse(valid, snapshot().files, sources).second.size)
        assertThrows(AssistantFailure::class.java) { output.parse(valid.replace("\"line\":2", "\"line\":1"), snapshot().files, sources) }
        assertThrows(AssistantFailure::class.java) { output.parse(valid.replace("S1", "S999"), snapshot().files, sources) }
        assertThrows(AssistantFailure::class.java) { output.parse("not json", snapshot().files, sources) }
        assertTrue(output.parse("""{"summary":"No evidence","findings":[]}""", snapshot().files, sources).second.isEmpty())
    }
    @Test fun workerPersistsDeliveryBeforeAcknowledgingAndDeduplicates() {
        val store = database(); val repo = ReviewRepository(store); val github = FakeGitHub(snapshot())
        val embeddings = object : Embeddings {
            override fun identity() = EmbeddingIdentity("fake", "digest")
            override fun embed(texts: List<String>) = texts.map { listOf(1.0, 0.0) }
        }
        var calls = 0
        val generator = object : Generator {
            override fun generate(provider: String, messages: List<LlmMessage>): Generation {
                calls++; return Generation("""{"summary":"No evidence","findings":[]}""", "fake", Usage(1, 1), "stop", 1)
            }
        }
        val delivering = object : GitHubGateway by github {
            override fun discover(seen: Set<String>): Discovery = if ("run:1" in seen) Discovery(emptyList(), emptyList(), emptyList())
                else Discovery(listOf(Delivery("run:1", request)), listOf("run:1"), emptyList())
        }
        val useCase = ReviewUseCase(repo, delivering, policy, ReviewOutputPolicy(mapper), ReviewRag(embeddings, store, MarkdownChunker(), Retriever()), generator, mapper, config)
        val jobs = Jobs()
        try {
            val worker = ReviewWorker(repo, delivering, config, useCase, jobs, store)
            assertEquals(1, worker.sync().discovered); assertEquals(0, worker.sync().discovered)
            assertEquals(1, repo.list().size); assertEquals(0, calls)
            jobs.reserve(); worker.processNext()
            assertEquals(ReviewStatus.QUEUED, repo.list().single().status); jobs.release()
        } finally { jobs.close() }
    }
}
