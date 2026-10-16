package dev.aichallenge.assistants.review.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/** Конфигурация интеграции; токен не сериализуется в публичный DTO и перечитывается с диска. */
@Component
class ReviewConfig(
    @Value("$" + "{REVIEW_REPOSITORY:Vetka63/ai-challenge-game}") val repository: String,
    @Value("$" + "{REVIEW_AUTHOR:Vetka63}") val author: String,
    @Value("$" + "{REVIEW_TOKEN_FILE:/run/day32/github-token}") private val tokenFile: String,
    @Value("$" + "{REVIEW_WORKFLOW:ai-review.yml}") val workflow: String
) {
    init {
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository))
        require(Regex("[A-Za-z0-9_-]+").matches(author))
        require(Regex("[A-Za-z0-9_.-]+\\.yml").matches(workflow))
    }
    /** Локальный секрет доступен только HTTP-адаптеру GitHub. */
    fun token(): String = runCatching { Files.readString(Path.of(tokenFile)).trim() }.getOrDefault("")
}
