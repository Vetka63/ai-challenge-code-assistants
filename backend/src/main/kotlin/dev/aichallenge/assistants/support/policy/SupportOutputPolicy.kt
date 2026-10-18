package dev.aichallenge.assistants.support.policy

import dev.aichallenge.support.contracts.SupportSource
import org.springframework.stereotype.Component

/** Проверяет минимальные наблюдаемые гарантии, не притворяется семантическим судьёй. */
@Component
class SupportOutputPolicy {
    /** Несуществующая ссылка и выдуманный файловый путь не становятся рекомендацией игроку. */
    fun check(answer: String, sources: List<SupportSource>): String? {
        val ids = Regex("\\[S(\\d+)\\]").findAll(answer).map { "S" + it.groupValues[1] }.toSet()
        if (ids.isEmpty()) return "В ответе нет ссылок на переданные источники FAQ."
        if (ids.any { id -> sources.none { it.id == id } }) return "В ответе есть ссылка на отсутствующий источник."
        val paths = Regex("""(?i)(?:[a-z]:[\\/]|AppData[\\/]|/home/|/Users/)[\p{L}\p{N}_./\\-]+""").findAll(answer).map { it.value }
        if (paths.any { path -> sources.none { path in it.text } }) return "Модель предложила файловый путь, которого нет в документации."
        return null
    }
}
