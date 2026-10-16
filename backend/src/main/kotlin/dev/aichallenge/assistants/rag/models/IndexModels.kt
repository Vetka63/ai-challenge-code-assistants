package dev.aichallenge.assistants.rag.models

import dev.aichallenge.assistants.llm.EmbeddingIdentity
import dev.aichallenge.assistants.project.McpEvent
import dev.aichallenge.assistants.project.ProjectContext
import java.time.Instant

/** Фрагмент сохраняет точные строки, путь и хеш исходного документа. */
data class Chunk(val chunkId: String, val path: String, val section: String,
    val startLine: Int, val endLine: Int, val text: String, val fileHash: String, val status: String)
/** Вектор связан с конкретным чанком, а не с порядком текущей выдачи. */
data class IndexedChunk(val chunk: Chunk, val vector: List<Double>)
/** READY публикуется целиком; предыдущий индекс не удаляется при ошибке. */
data class IndexRecord(val id: String, val status: String, val createdAt: String = Instant.now().toString(),
    val context: ProjectContext? = null, val identity: EmbeddingIdentity? = null,
    val chunks: List<IndexedChunk> = emptyList(), val processed: Int = 0, val total: Int = 0,
    val error: String? = null, val events: List<McpEvent> = emptyList())
/** Источник ответа без embedding-вектора; cosine не является вероятностью. */
data class Source(val id: String, val chunk: Chunk, val cosine: Double, val origin: String = "RETRIEVED")
