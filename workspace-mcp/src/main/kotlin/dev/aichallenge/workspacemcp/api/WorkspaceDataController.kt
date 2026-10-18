package dev.aichallenge.workspacemcp.api

import dev.aichallenge.workspace.contracts.*
import dev.aichallenge.workspacemcp.storage.WorkspaceStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Внутренний API управления снимками и экспорта; порт не публикуется на хост. */
@RestController
@RequestMapping("/internal/workspaces")
class WorkspaceDataController(private val store: WorkspaceStore) {
    /** Создание снимка — явное действие человека, не произвольный tool модели. */
    @PostMapping fun create(@RequestBody request: SnapshotRequest) = store.create(request.id)
    /** Отдаёт только метаданные. */
    @GetMapping fun list() = store.list()
    /** Подтверждает существование выбранного неизменяемого снимка. */
    @GetMapping("/{id}") fun get(@PathVariable id: String) = store.snapshot(id).info
    /** Восстанавливает сведения об уже записанных артефактах без повторного tool/LLM-вызова. */
    @GetMapping("/{id}/runs/{run}/artifacts") fun artifacts(@PathVariable id: String, @PathVariable run: String) = store.artifacts(id, run)
}

/** Безопасные ошибки файловой границы, без путей ОС или сырого содержимого. */
@RestControllerAdvice
class WorkspaceDataErrors {
    @ExceptionHandler(WorkspaceFailure::class) fun known(e: WorkspaceFailure) =
        ResponseEntity.status(e.status).body(mapOf("code" to e.code, "message" to e.message))
    @ExceptionHandler(IllegalArgumentException::class) fun invalid(e: Exception) =
        ResponseEntity.badRequest().body(mapOf("code" to "INVALID_REQUEST", "message" to "Проверьте параметры рабочей области."))
    @ExceptionHandler(Exception::class) fun unexpected(e: Exception) =
        ResponseEntity.internalServerError().body(mapOf("code" to "WORKSPACE_STORAGE", "message" to "Хранилище рабочей области недоступно; исходники не изменены."))
}
