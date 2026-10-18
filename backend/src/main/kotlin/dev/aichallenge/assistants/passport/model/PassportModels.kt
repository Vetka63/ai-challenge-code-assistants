package dev.aichallenge.assistants.passport.model

import dev.aichallenge.assistants.llm.Generation
import dev.aichallenge.workspace.contracts.PassportImage
import java.time.Instant

data class PassportSettings(val enabled: Boolean = false, val intervalSeconds: Long = 600,
    val provider: String = "LOCAL", val dailyLimit: Int = 12)
data class PassportState(val settings: PassportSettings = PassportSettings(), val activeId: String? = null,
    val currentId: String? = null, val nextCheckAt: String? = null, val blockedFingerprint: String? = null)
data class PassportCommand(val id: String, val retryConfirmed: Boolean = false)
data class PassportEvidence(val id: String, val path: String, val hash: String,
    val startLine: Int, val endLine: Int, val text: String)
/** Статус — происхождение вывода, не утверждение о независимом прогоне игры. */
data class PassportClaim(val text: String, val kind: String, val sources: List<String>)
data class PassportLevel(val id: String, val name: String, val kind: String,
    val details: List<PassportClaim>, val imageIds: List<String> = emptyList())
data class PassportDraft(val title: String, val overview: List<PassportClaim>,
    val mechanics: List<PassportClaim>, val levels: List<PassportLevel>, val questions: List<String>)
data class PassportChange(val path: String, val kind: String)
data class PassportEvent(val stage: String, val detail: String, val at: String = Instant.now().toString())
/** Durable check сохраняет исходники и usage отдельно от производного паспорта. */
data class PassportCheck(val id: String, val trigger: String, val provider: String, val retryConfirmed: Boolean,
    val status: String = "CAPTURING", val createdAt: String = Instant.now().toString(), val finishedAt: String? = null,
    val snapshotId: String? = null, val fingerprint: String? = null, val fileHashes: Map<String, String> = emptyMap(),
    val images: List<PassportImage> = emptyList(), val sources: List<PassportEvidence> = emptyList(),
    val changes: List<PassportChange> = emptyList(), val warnings: List<String> = emptyList(),
    val events: List<PassportEvent> = emptyList(), val attemptedAt: String? = null,
    val generation: Generation? = null, val draft: PassportDraft? = null, val errorCode: String? = null, val error: String? = null,
    val validationVersion: Int = 1)
data class PassportOverview(val state: PassportState, val current: PassportCheck?, val history: List<PassportCheck>,
    val attemptsToday: Int, val budgetDay: String)
