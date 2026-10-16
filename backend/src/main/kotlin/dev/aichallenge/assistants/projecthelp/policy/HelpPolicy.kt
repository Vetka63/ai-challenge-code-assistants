package dev.aichallenge.assistants.projecthelp.policy

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.projecthelp.models.HelpRequest
import dev.aichallenge.assistants.rag.models.Source
import org.springframework.stereotype.Component
import java.util.UUID

/** Входные правила принадлежат помощнику, а не универсальному HTTP/LLM-клиенту. */
@Component
class HelpInputPolicy {
    /** Поддерживает /help и обычный ввод; отсекает пустой/слишком большой запрос. */
    fun question(request: HelpRequest): String {
        try { UUID.fromString(request.id) } catch (_: Exception) { throw AssistantFailure("INVALID_ID", "Нужен UUID запроса") }
        val question = request.message.trim().removePrefix("/help").trim()
        if (question.length !in 2..4000) throw AssistantFailure("QUESTION_SIZE", "После /help задайте вопрос от 2 до 4000 символов.")
        if (request.provider !in setOf("LOCAL", "DEEPSEEK")) throw AssistantFailure("PROVIDER", "Выберите LOCAL или DEEPSEEK.")
        return question
    }
}

/** Проверяет только существование ID; не выдаёт это за проверку истины утверждений. */
@Component
class HelpOutputPolicy {
    /** UNKNOWN без ссылок допустим, но успешный ответ без ссылок явно помечается. */
    fun citations(answer: String, sources: List<Source>): String {
        val ids = Regex("\\[(S\\d+)]").findAll(answer).map { it.groupValues[1] }.toSet()
        return when {
            ids.isEmpty() -> "MISSING"
            ids.any { id -> sources.none { it.id == id } } -> "INVALID"
            else -> "IDS_VALID"
        }
    }
}
