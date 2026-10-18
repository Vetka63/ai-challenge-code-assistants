package dev.aichallenge.support.contracts

import java.time.Instant

/** Учебный профиль, не настоящая учётная запись и не механизм авторизации. */
data class SupportUser(val id: String, val displayName: String, val platform: String)
/** Команда формы. UUID обеспечивает безопасный повтор при потере HTTP-ответа. */
data class TicketDraft(val id: String, val title: String, val description: String,
    val gameVersion: String, val platform: String, val kind: String = "ISSUE")
/** Вопрос пользователя; повтор того же UUID не запускает вторую генерацию. */
data class SupportQuestion(val id: String, val question: String, val provider: String = "LOCAL")
/** Точный фрагмент FAQ, действительно переданный модели. */
data class SupportSource(val id: String, val path: String, val section: String, val startLine: Int,
    val endLine: Int, val chunkId: String, val fileHash: String, val text: String, val cosine: Double)
/** Публичный журнал MCP без содержимого всего JSON-хранилища. */
data class SupportEvent(val method: String, val status: String, val durationMs: Long,
    val details: String = "", val at: String = Instant.now().toString())
/** Метрики одного реального вызова, не локальная оценка токенов. */
data class SupportGeneration(val model: String, val inputTokens: Long?, val outputTokens: Long?,
    val durationMs: Long, val finishReason: String)
/** Ограниченный контекст одного обращения. Старые ответы не служат доказательствами. */
data class SupportHistory(val question: String, val answer: String)
/** Результат инструмента MCP: только выбранный пользователь и принадлежащий ему тикет. */
data class TicketContext(val user: SupportUser, val ticketId: String, val kind: String, val title: String,
    val description: String, val gameVersion: String, val platform: String, val ticketVersion: Int,
    val recentConversation: List<SupportHistory>)
/** Ход хранится вместе с вопросом, ответом, источниками и снимком MCP в JSON тикета. */
data class SupportTurn(val id: String, val question: String, val provider: String,
    val status: String = "RUNNING", val createdAt: String = Instant.now().toString(),
    val answer: String = "", val context: TicketContext? = null,
    val sources: List<SupportSource> = emptyList(), val events: List<SupportEvent> = emptyList(),
    val generation: SupportGeneration? = null, val indexId: String? = null,
    val knowledgeVersion: String? = null, val citationStatus: String = "NOT_CHECKED",
    val errorCode: String? = null, val error: String? = null, val revision: Int = 0,
    val candidateAnswer: String? = null)
/** Агрегат обращения: один файл и одна атомарная граница записи, без внешних вызовов. */
data class SupportTicket(val id: String, val userId: String, val title: String, val description: String,
    val gameVersion: String, val platform: String, val kind: String,
    val createdAt: String = Instant.now().toString(), val updatedAt: String = createdAt,
    val version: Int = 0, val turns: List<SupportTurn> = emptyList())
/** Список не пересылает всю историю каждого обращения при каждом polling. */
data class TicketSummary(val id: String, val title: String, val kind: String, val gameVersion: String,
    val platform: String, val createdAt: String, val updatedAt: String, val turnCount: Int)
/** Compare-and-set результата одного хода, а не слепая перезапись всего тикета. */
data class SaveTurn(val turn: SupportTurn, val expectedRevision: Int)
/** Машинная ошибка домена с безопасным текстом; транспорт сам выбирает HTTP/MCP-конверт. */
class SupportFailure(val code: String, override val message: String, val status: Int = 400) : RuntimeException(message)
