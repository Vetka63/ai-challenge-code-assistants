package dev.aichallenge.assistants.passport.api

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.passport.model.*
import dev.aichallenge.assistants.passport.usecase.RefreshPassport
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/passport")
class PassportController(private val refresh: RefreshPassport) {
    @GetMapping fun overview() = refresh.overview()
    @PostMapping("/settings") fun settings(@RequestBody value: PassportSettings) = refresh.settings(value)
    @PostMapping("/checks") fun start(@RequestBody command: PassportCommand) = refresh.start(command)
    @GetMapping("/checks/{id}") fun get(@PathVariable id: String) = refresh.get(id)
    @GetMapping("/checks/{id}/images/{image}") fun image(@PathVariable id: String, @PathVariable image: String): ResponseEntity<ByteArray> {
        val (mime, bytes) = refresh.image(id, image)
        return ResponseEntity.ok().header("Content-Type", mime).header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, max-age=31536000, immutable").body(bytes)
    }
    @GetMapping("/checks/{id}/report") fun report(@PathVariable id: String): ResponseEntity<String> =
        ResponseEntity.ok().header("Content-Type", "text/markdown; charset=UTF-8")
            .header("Content-Disposition", "attachment; filename=passport-${UUID.fromString(id)}.md")
            .body(passportMarkdown(refresh.get(id)))
}

/** Poll сам по себе не вызывает LLM. Срок и единственность проверяет тот же сценарий, что у кнопки. */
@Component
class PassportScheduler(private val refresh: RefreshPassport) {
    @Scheduled(fixedDelay = 5000) fun tick() {
        try { refresh.start(PassportCommand(UUID.randomUUID().toString()), "SCHEDULED") }
        catch (e: AssistantFailure) { if (e.code !in setOf("PASSPORT_NOT_DUE", "PASSPORT_BUSY", "PASSPORT_SETTINGS_CHANGED", "BUSY")) throw e }
    }
}

/** Экспорт сохранённой версии не генерирует ответ заново и не читает текущую игру. */
fun passportMarkdown(check: PassportCheck): String = buildString {
    appendLine("# ${check.draft?.title ?: "Проверка паспорта"}\n")
    appendLine("${check.status} · ${check.createdAt} · ${check.trigger} · ${check.provider}\n")
    appendLine("Снимок: ${check.snapshotId}; fingerprint: ${check.fingerprint}.\n")
    fun claims(values: List<PassportClaim>) { values.forEach { appendLine("- ${it.text} (${it.kind}) ${it.sources.joinToString { s -> "[$s]" }}") }; appendLine() }
    check.draft?.let { draft ->
        appendLine("## Обзор\n"); claims(draft.overview)
        appendLine("## Механики\n"); claims(draft.mechanics)
        appendLine("## Сцены и режимы\n"); draft.levels.forEach { appendLine("### ${it.name} (${it.kind})\n"); claims(it.details) }
        appendLine("## Уточнения\n"); draft.questions.forEach { appendLine("- $it") }; appendLine()
    }
    appendLine("## Изменения относительно последней успешной версии\n")
    check.changes.forEach { appendLine("- ${it.kind}: ${it.path}") }
    appendLine("\n## Источники\n"); check.sources.forEach { appendLine("### [${it.id}] ${it.path}:${it.startLine}–${it.endLine}\n\nSHA-256: ${it.hash}\n\n````text\n${it.text}\n````\n") }
    appendLine("## Ограничения\n\nСсылки проверены структурно, не являются доказательством истинности. Unity и тесты не запускались. Изображения модель не видела.\n")
    check.warnings.forEach { appendLine("- $it") }
    check.images.forEach { appendLine("- Растр ${it.path}, SHA-256 ${it.id}, импорт ${it.importedAt}. Дата съёмки и соответствие текущей сборке не подтверждены.") }
    check.generation?.let { appendLine("\nМодель ${it.model}; ${it.durationMs} мс; API input/output ${it.usage.inputTokens}/${it.usage.outputTokens}; ${it.finishReason}.") }
    check.error?.let { appendLine("\nОшибка ${check.errorCode}: $it") }
}
