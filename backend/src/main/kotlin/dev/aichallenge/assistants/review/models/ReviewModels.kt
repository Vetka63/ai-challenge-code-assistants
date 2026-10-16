package dev.aichallenge.assistants.review.models

import dev.aichallenge.assistants.llm.Generation
import dev.aichallenge.assistants.rag.models.Source
import java.time.Instant
import java.security.MessageDigest

/** Состояние анализа не смешивается со статусом GitHub Actions, создавшего задание. */
enum class ReviewStatus { QUEUED, INDEXING, ANALYZING, REPORT_READY, PUBLISHING, COMPLETED, FAILED, INTERRUPTED, SUPERSEDED }
enum class ReviewCategory { BUG, ARCHITECTURE, RECOMMENDATION }
enum class Severity { HIGH, MEDIUM, LOW }
/** Минимальный контракт задания; секретов, команд shell и произвольных URL здесь нет. */
data class ReviewRequest(val repository: String, val pr: Int, val baseSha: String, val headSha: String,
    val runId: Long? = null, val provider: String = "DEEPSEEK")
/** Контекст PR перепроверяется у GitHub, а не берётся на веру из artifact. */
data class PullRequest(val number: Int, val title: String, val body: String, val baseSha: String,
    val headSha: String, val repository: String, val headRepository: String, val author: String,
    val state: String, val changedFiles: Int, val url: String)
/** Строки diff отдельно для каждой стороны позволяют проверять удалённые строки. */
data class ChangedFile(val path: String, val previousPath: String? = null, val status: String,
    val patch: String?, val addedLines: Set<Int> = emptySet(), val removedLines: Set<Int> = emptySet(),
    val completePatch: Boolean = true)
/** Версия источника всегда явная; документы PR не заменяют принятые правила базы. */
data class ReviewDocument(val path: String, val text: String, val sha: String, val side: String)
data class ReviewSnapshot(val pr: PullRequest, val files: List<ChangedFile>,
    val documents: List<ReviewDocument>, val limitations: List<String> = emptyList())
/** Замечание привязано к изменённой строке, а доказательства — к конкретным RAG-источникам. */
data class Finding(val category: ReviewCategory, val severity: Severity, val path: String, val line: Int,
    val side: String = "HEAD", val title: String, val explanation: String,
    val recommendation: String, val sourceIds: List<String>)
data class ReviewReport(val summary: String, val findings: List<Finding>, val limitations: List<String>,
    val generation: Generation, val sources: List<Source>, val coverage: String)
/** Сохранённое задание — источник истины для повторов, UI и восстановления. */
data class ReviewJob(val id: String, val request: ReviewRequest, val origin: String,
    val status: ReviewStatus = ReviewStatus.QUEUED, val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString(), val publishRequested: Boolean = false,
    val pr: PullRequest? = null, val report: ReviewReport? = null, val sources: List<Source> = emptyList(),
    val limitations: List<String> = emptyList(), val errorCode: String? = null, val error: String? = null,
    val commentUrl: String? = null, val version: Int = 0, val generation: Generation? = null,
    val generationAttempts: List<String> = emptyList(), val files: List<ChangedFile> = emptyList())
data class ReviewSettings(val enabled: Boolean = false)
data class PollState(val checkedAt: String? = null, val error: String? = null, val discovered: Int = 0)
/** Ключ одинаков для ручного и автоматического обнаружения одной версии PR. */
fun reviewId(request: ReviewRequest): String {
    val key = listOf("day32-v1", request.repository.lowercase(), request.pr, request.baseSha, request.headSha, request.provider).joinToString(":")
    return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
