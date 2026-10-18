package dev.aichallenge.workspace.contracts

import java.time.Instant

/** Неизменяемый текст исходного файла. Хеш вычисляется по UTF-8 без нормализации перевода строк. */
data class SourceFile(val path: String, val hash: String, val text: String)
/** Публичная версия рабочей области без содержимого всех исходников. */
data class SnapshotInfo(val id: String, val fingerprint: String, val fileCount: Int,
    val createdAt: String, val origin: String = "READ_ONLY_WORKING_TREE")
/** Сохранённый снимок, которым владеет только Workspace MCP. */
data class WorkspaceSnapshot(val info: SnapshotInfo, val files: List<SourceFile>)
/** Идемпотентная команда создания снимка, без произвольного filesystem root. */
data class SnapshotRequest(val id: String)
/** Реальный документ и автоматически построенный patch; исходники не перезаписываются. */
data class WorkspaceArtifact(val id: String, val snapshotId: String, val runId: String,
    val path: String, val kind: String, val baseHash: String?, val content: String, val diff: String,
    val evidence: List<String>, val createdAt: String = Instant.now().toString())
/** Узкая машинная ошибка без абсолютных путей, ключей и stack trace. */
class WorkspaceFailure(val code: String, override val message: String, val status: Int = 400) : RuntimeException(message)
/** Параметры сохранённого запуска; повтор UUID не создаёт повторную генерацию. */
data class WorkspaceGoal(val id: String, val snapshotId: String, val goal: String,
    val provider: String = "LOCAL", val parentId: String? = null)
/** Одна наблюдаемая операция, а не скрытые рассуждения модели. */
data class WorkspaceEvent(val stage: String, val status: String, val details: String,
    val at: String = Instant.now().toString(), val durationMs: Long = 0)
/** Фрагмент, действительно прочитанный через MCP. */
data class FileEvidence(val path: String, val hash: String, val startLine: Int, val endLine: Int)
/** Фактические usage каждого LLM-вызова, включая tool-only ответы. */
data class WorkspaceUsage(val model: String, val inputTokens: Long?, val outputTokens: Long?,
    val durationMs: Long, val finishReason: String)
/** Состояние агента хранится отдельно от файлов; transcript нужен для продолжения после завершённого хода. */
data class WorkspaceRun(val id: String, val snapshotId: String, val goal: String, val provider: String,
    val parentId: String? = null, val status: String = "RUNNING", val answer: String = "",
    val events: List<WorkspaceEvent> = emptyList(), val files: List<FileEvidence> = emptyList(),
    val artifactIds: List<String> = emptyList(), val usage: List<WorkspaceUsage> = emptyList(),
    val transcript: List<Map<String, Any?>> = emptyList(), val errorCode: String? = null,
    val error: String? = null, val createdAt: String = Instant.now().toString())
