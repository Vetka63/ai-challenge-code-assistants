package dev.aichallenge.assistants.review.repository

import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.storage.RecordStore
import dev.aichallenge.assistants.common.AssistantFailure
import org.springframework.stereotype.Repository
import java.time.Instant

/** Репозиторий домена скрывает ключи хранения и выполняет атомарные смены состояния. */
@Repository
class ReviewRepository(private val store: RecordStore) {
    fun list() = store.list("review-job", ReviewJob::class.java, 500)
    fun get(id: String) = store.get("review-job", id, ReviewJob::class.java)
        ?: throw AssistantFailure("REVIEW_NOT_FOUND", "Ревью не найдено.", 404)
    fun create(request: ReviewRequest, origin: String, publish: Boolean): ReviewJob {
        val record = ReviewJob(reviewId(request), request, origin, publishRequested = publish)
        store.insertIfAbsent("review-job", record.id, record)
        return get(record.id)
    }
    fun update(expected: ReviewJob, transform: (ReviewJob) -> ReviewJob): ReviewJob {
        val next = transform(expected).copy(version = expected.version + 1, updatedAt = Instant.now().toString())
        if (!store.replace("review-job", expected.id, expected, next))
            throw AssistantFailure("REVIEW_CONFLICT", "Задание уже изменилось; обновите список.", 409)
        return next
    }
    fun settings() = store.get("review-settings", "default", ReviewSettings::class.java) ?: ReviewSettings()
    fun settings(value: ReviewSettings) { store.put("review-settings", "default", value) }
    fun poll() = store.get("review-poll", "default", PollState::class.java) ?: PollState()
    fun poll(value: PollState) { store.put("review-poll", "default", value) }
    /** Перезапуск не превращает неопределённый платный вызов в автоматический повтор. */
    fun recover() {
        list().forEach { record ->
            when (record.status) {
                ReviewStatus.INDEXING -> update(record) { it.copy(status = ReviewStatus.QUEUED) }
                ReviewStatus.ANALYZING -> update(record) { it.copy(status = ReviewStatus.INTERRUPTED, errorCode = "RESTART",
                    error = "Вызов модели мог начаться. Автоматический платный повтор отключён.") }
                ReviewStatus.PUBLISHING -> update(record) { it.copy(status = ReviewStatus.REPORT_READY,
                    errorCode = "PUBLISH_INTERRUPTED", error = "Отчёт сохранён; публикацию можно повторить без LLM.") }
                else -> Unit
            }
        }
    }
}
