package dev.aichallenge.assistants.review.api

import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.github.GitHubGateway
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.review.repository.ReviewRepository
import dev.aichallenge.assistants.review.service.*
import org.springframework.http.*
import org.springframework.web.bind.annotation.*

data class StartReview(val pr: Int, val provider: String = "DEEPSEEK", val publish: Boolean = false)
data class RetryReview(val confirmed: Boolean = false)
/** HTTP-адаптер не получает токен из браузера и не выполняет анализ самостоятельно. */
@RestController
@RequestMapping("/api/reviews")
class ReviewController(private val repository: ReviewRepository, private val useCase: ReviewUseCase,
    private val worker: ReviewWorker, private val github: GitHubGateway, private val config: ReviewConfig) {
    @GetMapping fun list() = repository.list()
    @GetMapping("/{id}") fun get(@PathVariable id: String) = repository.get(id)
    @GetMapping("/settings") fun settings() = mapOf("repository" to config.repository, "author" to config.author,
        "workflow" to config.workflow, "tokenConfigured" to config.token().isNotBlank(), "enabled" to repository.settings().enabled,
        "poll" to repository.poll(), "dailyAttemptLimit" to 20)
    @PostMapping("/settings") fun settings(@RequestBody input: ReviewSettings): Map<String, Any> {
        if (input.enabled) github.verifyConnection()
        repository.settings(input); return settings()
    }
    @PostMapping("/connection") fun connection() = mapOf("repository" to github.verifyConnection(), "readAccess" to true,
        "message" to "Чтение доступно. Право записи проверяется при публикации комментария.")
    @PostMapping("/sync") fun sync() = worker.sync()
    @PostMapping fun create(@RequestBody input: StartReview) = ResponseEntity.accepted().body(useCase.enqueue(input.pr, input.provider, input.publish))
    @PostMapping("/{id}/retry-analysis") fun retry(@PathVariable id: String, @RequestBody input: RetryReview) =
        ResponseEntity.accepted().body(useCase.retry(id, input.confirmed))
    @PostMapping("/{id}/publish") fun publish(@PathVariable id: String) = ResponseEntity.accepted().body(useCase.requestPublication(id))
    @GetMapping("/{id}/report") fun report(@PathVariable id: String): ResponseEntity<String> = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=day32-" + id + ".md")
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).body(reviewMarkdown(repository.get(id)))
}
