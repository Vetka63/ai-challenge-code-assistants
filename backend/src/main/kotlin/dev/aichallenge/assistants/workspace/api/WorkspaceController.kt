package dev.aichallenge.assistants.workspace.api

import dev.aichallenge.assistants.workspace.service.WorkspaceAgent
import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.workspace.contracts.*
import org.springframework.http.*
import org.springframework.web.bind.annotation.*

/** Тонкий API используется одинаково Vue и CLI; файловая политика находится не в клиенте. */
@RestController
@RequestMapping("/api/workspace")
class WorkspaceController(private val agent: WorkspaceAgent) {
    @GetMapping("/snapshots") fun snapshots() = agent.snapshots()
    @PostMapping("/snapshots") fun snapshot(@RequestBody request: SnapshotRequest) = agent.snapshot(request.id)
    @GetMapping("/runs") fun runs() = agent.runs()
    @PostMapping("/runs") fun start(@RequestBody input: WorkspaceGoal) = ResponseEntity.accepted().body(agent.start(input))
    @GetMapping("/runs/{id}") fun run(@PathVariable id: String) = agent.run(id).copy(transcript = emptyList())
    @GetMapping("/runs/{id}/artifacts") fun artifacts(@PathVariable id: String) = agent.artifacts(id)
    @GetMapping("/runs/{id}/sources") fun sources(@PathVariable id: String) = agent.sources(id)
    /** Экспорт не исполняет HTML и не генерирует новый ответ. */
    @GetMapping("/runs/{id}/artifacts/{artifact}/{format}")
    fun artifact(@PathVariable id: String, @PathVariable artifact: String, @PathVariable format: String): ResponseEntity<String> {
        require(format in setOf("md", "patch"))
        val item = agent.artifacts(id).find { it.id == artifact } ?: throw AssistantFailure("NOT_FOUND", "Артефакт не найден.", 404)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=day34-${item.id}.$format")
            .body(if (format == "md") item.content else item.diff)
    }
}
