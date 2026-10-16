package dev.aichallenge.assistants

import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.rag.indexing.MarkdownChunker
import dev.aichallenge.assistants.rag.retrieval.Retriever
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.github.*
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.review.policy.*
import dev.aichallenge.assistants.review.rag.ReviewRag
import dev.aichallenge.assistants.review.repository.ReviewRepository
import dev.aichallenge.assistants.review.service.*
import dev.aichallenge.assistants.storage.RecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.*

/** Явно включаемая живая проверка трёх учебных diff: настоящий DeepSeek/Ollama, GitHub без записей. */
@EnabledIfEnvironmentVariable(named = "DAY32_LIVE", matches = "true")
class ReviewLiveTest {
    @TempDir lateinit var dir: Path
    @Test fun threeSyntheticChangesWithRealEmbeddingsAndDeepSeek() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val config = RuntimeConfig(dir.resolve("live.sqlite").toString(), "", "http://ollama:11434", "qwen3-embedding:0.6b", "qwen3:8b",
            System.getenv("DEEPSEEK_API_KEY") ?: error("Key required"), "deepseek-v4-pro")
        val models = ModelGateway(config, mapper); val store = RecordStore(config, mapper)
        val repository = ReviewRepository(store)
        val reviewConfig = ReviewConfig("Vetka63/ai-challenge-game", "Vetka63", "/missing", "ai-review.yml")
        val results = mutableListOf<ReviewJob>()
        val cases = listOf(
            Triple("bug", "return savedCoins;", "return 0;"),
            Triple("architecture", "return savedCoins;", "System.IO.File.WriteAllText(\"save.txt\", savedCoins.ToString()); return savedCoins;"),
            Triple("correct", "return savedCoins;", "return savedCoins; // Preserve earned progress")
        )
        cases.forEachIndexed { i, (label, before, after) ->
            val path = "AsciiArena/Assets/Prototype/ArenaSimulation.cs"
            val pr = PullRequest(i + 1, "Учебная проверка " + label, "Не реальный GitHub PR. Проверить только изменение восстановления монет.",
                "a".repeat(40), (i + 1).toString().repeat(40), reviewConfig.repository, reviewConfig.repository, "Vetka63", "open", 1, "https://example.invalid/synthetic/" + label)
            val request = ReviewRequest(pr.repository, pr.number, pr.baseSha, pr.headSha)
            val patch = "@@ -1,3 +1,3 @@\n public class ArenaSimulation {\n- public int Resume(int savedCoins) { " + before + " }\n+ public int Resume(int savedCoins) { " + after + " }\n }"
            val lines = changedLines(patch)
            val snapshot = ReviewSnapshot(pr, listOf(ChangedFile(path, status = "modified", patch = patch, addedLines = lines.first, removedLines = lines.second)),
                listOf(ReviewDocument("README.md", "# Правила сохранений\nResume обязан возвращать сохранённые earned coins без сброса. ArenaSimulation содержит только правила симуляции и не читает/пишет файлы. Файловое хранилище должно быть во внешнем адаптере.", pr.baseSha, "BASE"),
                    ReviewDocument(path, "public class ArenaSimulation {\n public int Resume(int savedCoins) { " + after + " }\n}", pr.headSha, "HEAD")))
            val github = object : GitHubGateway {
                override fun pull(number: Int) = pr
                override fun snapshot(request: ReviewRequest) = snapshot
                override fun discover(seen: Set<String>) = Discovery(emptyList(), emptyList(), emptyList())
                override fun verifyConnection() = pr.repository
                override fun publish(job: ReviewJob, markdown: String): String = error("Live publication prohibited in synthetic test")
            }
            val useCase = ReviewUseCase(repository, github, ReviewInputPolicy(reviewConfig), ReviewOutputPolicy(mapper),
                ReviewRag(models, store, MarkdownChunker(), Retriever()), models, mapper, reviewConfig)
            val queued = repository.create(request, "SYNTHETIC_LIVE_TEST", false)
            useCase.analyze(repository.update(queued) { it.copy(status = ReviewStatus.INDEXING) })
            val result = repository.get(queued.id); results += result
            val folder = Path.of(System.getenv("DAY32_LIVE_OUTPUT") ?: error("Output folder required"))
            Files.createDirectories(folder)
            Files.writeString(folder.resolve(label + ".json"), mapper.writeValueAsString(result))
            Files.writeString(folder.resolve(label + ".md"), reviewMarkdown(result))
            assertEquals(ReviewStatus.REPORT_READY, result.status, result.error)
            if (label == "bug") assertTrue(result.report!!.findings.any { it.category == ReviewCategory.BUG })
            if (label == "architecture") assertTrue(result.report!!.findings.any { it.category == ReviewCategory.ARCHITECTURE })
            if (label == "correct") assertTrue(result.report!!.findings.none { it.category == ReviewCategory.BUG && it.severity == Severity.HIGH })
        }
        assertEquals(3, results.size)
    }
}
