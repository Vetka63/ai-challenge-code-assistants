package dev.aichallenge.workspacemcp.passport

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Внутренний collector API, не расширяет пять инструментов Workspace MCP произвольным файловым доступом. */
@RestController
@RequestMapping("/internal/passport")
class PassportDataController(private val collector: PassportCollector) {
    @PostMapping("/collect") fun collect() = collector.collect()
    @GetMapping("/images/{id}") fun image(@PathVariable id: String): ResponseEntity<ByteArray> {
        val (metadata, bytes) = collector.image(id)
        return ResponseEntity.ok().header("Content-Type", metadata.mime).header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, max-age=31536000, immutable").body(bytes)
    }
}
