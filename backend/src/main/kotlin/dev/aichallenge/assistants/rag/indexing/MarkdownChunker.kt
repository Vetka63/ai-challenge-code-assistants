package dev.aichallenge.assistants.rag.indexing

import dev.aichallenge.assistants.project.ProjectDocument
import dev.aichallenge.assistants.rag.models.Chunk
import org.springframework.stereotype.Component
import java.security.MessageDigest

/** Разбиение Markdown по заголовкам и группам точных исходных строк. */
@Component
class MarkdownChunker {
    /** Сохраняет длинную строку целиком; её лимит проверяется явно. */
    fun split(document: ProjectDocument): List<Chunk> {
        val lines = document.text.split("\n")
        require(lines.all { it.length <= 12_000 }) { "Одна строка документа слишком длинная" }
        val result = mutableListOf<Chunk>()
        var section = document.path
        var start = 0
        var size = 0
        var inFence = false
        fun flush(end: Int) {
            if (end <= start) return
            val text = lines.subList(start, end).joinToString("\n")
            if (text.isNotBlank()) {
                val key = "${document.path}:${document.hash}:$start:$end"
                val id = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
                val status = if (document.path == "README.md" && section.lowercase() in setOf("текущее состояние", "current state", "current status"))
                    "CURRENT_STATE" else document.status
                result += Chunk(id, document.path, section, start + 1, end, text, document.hash, status)
            }
            start = end; size = 0
        }
        lines.forEachIndexed { index, line ->
            val heading = !inFence && Regex("^#{1,6}\\s+.+").matches(line.trimEnd('\r'))
            if (heading) { flush(index); section = line.trimEnd('\r').trimStart('#', ' ') }
            if (size + line.length > 1800 && index > start) flush(index)
            size += line.length + 1
            if (line.trimStart().startsWith("~~~") || line.trimStart().startsWith(96.toChar().toString().repeat(3))) inFence = !inFence
        }
        flush(lines.size)
        return result
    }
}
