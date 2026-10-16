package dev.aichallenge.assistants.projecthelp.models

import dev.aichallenge.assistants.llm.Generation
import dev.aichallenge.assistants.project.McpEvent
import dev.aichallenge.assistants.project.ProjectContext
import dev.aichallenge.assistants.rag.models.Source
import java.time.Instant

/** Отдельный диалог помощника разработчика; будущие агенты не используют его историю. */
data class HelpChat(val id: String, val title: String = "Новый диалог", val createdAt: String = Instant.now().toString())
/** UUID запроса служит ключом повтора без второго платного вызова. */
data class HelpRequest(val id: String, val message: String, val provider: String = "LOCAL")
/** Неблокирующая диагностика сохраняется вместе с ответом и его источниками. */
data class HelpWarning(val code: String, val message: String)
/** Сохранённый ход вместе с вопросом, источниками, MCP-диагностикой и результатом. */
data class HelpTurn(val id: String, val chatId: String, val question: String, val provider: String,
    val status: String = "RUNNING", val createdAt: String = Instant.now().toString(),
    val answer: String = "", val context: ProjectContext? = null, val indexId: String? = null,
    val sources: List<Source> = emptyList(), val events: List<McpEvent> = emptyList(),
    val generation: Generation? = null, val citationStatus: String = "NOT_CHECKED",
    val errorCode: String? = null, val error: String? = null,
    val indexContext: ProjectContext? = null, val warnings: List<HelpWarning> = emptyList())
