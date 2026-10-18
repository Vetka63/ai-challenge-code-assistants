package dev.aichallenge.assistants.review.service

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.github.GitHubGateway
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.review.repository.ReviewRepository
import dev.aichallenge.assistants.storage.RecordStore
import jakarta.annotation.PostConstruct
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** Polling читает задания GitHub; генерация запускается отдельно и только по сохранённой очереди. */
@Component
class ReviewWorker(private val repository: ReviewRepository, private val github: GitHubGateway,
    private val config: ReviewConfig, private val useCase: ReviewUseCase, private val jobs: Jobs,
    private val store: RecordStore) {
    @PostConstruct fun recover() = repository.recover()
    /** Работа без токена не создаёт шумных сетевых ошибок и не тратит API-квоты. */
    @Scheduled(fixedDelayString = "$" + "{REVIEW_POLL_MS:30000}", initialDelay = 10000)
    fun discover() {
        if (!config.runtimeEnabled || !repository.settings().enabled || config.token().isBlank()) return
        sync()
    }
    /** Ручная синхронизация не вызывает LLM; найденные задания сохраняются атомарно. */
    @Synchronized fun sync(): PollState {
        config.requireActive()
        return try {
            val seen = store.list("review-delivery", DeliveryReceipt::class.java, 10000).map { it.id }.toSet()
            val batch = github.discover(seen)
            batch.deliveries.forEach { repository.create(it.request, "ACTION", true) }
            batch.seen.forEach { store.insertIfAbsent("review-delivery", it, DeliveryReceipt(it)) }
            PollState(Instant.now().toString(), batch.warnings.takeIf { it.isNotEmpty() }?.joinToString("; "), batch.deliveries.size)
                .also { repository.poll(it) }
        } catch (e: Exception) {
            PollState(Instant.now().toString(), (e as? AssistantFailure)?.message ?: "Не удалось проверить задания GitHub.", 0)
                .also { repository.poll(it) }
        }
    }
    /** Один общий ресурсный слот с /help; занятость не теряет QUEUED задания. */
    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    fun processNext() {
        if (!config.runtimeEnabled) return
        val job = repository.list().lastOrNull {
            it.status == ReviewStatus.QUEUED || (it.status == ReviewStatus.REPORT_READY && it.publishRequested && it.errorCode == null)
        } ?: return
        try { jobs.reserve() } catch (_: AssistantFailure) { return }
        val claimed = try {
            repository.update(job) { it.copy(status = if (job.status == ReviewStatus.QUEUED) ReviewStatus.INDEXING else ReviewStatus.PUBLISHING) }
        } catch (_: Exception) { jobs.release(); return }
        jobs.start { if (claimed.status == ReviewStatus.INDEXING) useCase.analyze(claimed) else useCase.publish(claimed) }
    }
    data class DeliveryReceipt(val id: String)
}
