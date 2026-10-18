package dev.aichallenge.assistants

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.github.GitHubGateway
import dev.aichallenge.assistants.review.repository.ReviewRepository
import dev.aichallenge.assistants.review.service.*
import dev.aichallenge.assistants.storage.RecordStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

/** Даже сохранённая очередь не запускается при эксплуатационном выключателе дня 32. */
class ReviewPauseTest {
    @Test fun pausedWorkerDoesNotPollClaimGenerateOrPublish() {
        val repo = mock(ReviewRepository::class.java)
        val github = mock(GitHubGateway::class.java)
        val useCase = mock(ReviewUseCase::class.java)
        val jobs = mock(Jobs::class.java)
        val store = mock(RecordStore::class.java)
        val worker = ReviewWorker(repo, github, ReviewConfig("Vetka63/ai-challenge-game", "Vetka63", "/missing", "ai-review.yml", false), useCase, jobs, store)
        worker.discover(); worker.processNext()
        assertEquals("REVIEW_PAUSED", assertThrows(AssistantFailure::class.java) { worker.sync() }.code)
        verifyNoInteractions(repo, github, useCase, jobs, store)
    }
}
