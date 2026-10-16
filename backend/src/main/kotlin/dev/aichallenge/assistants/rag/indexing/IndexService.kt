package dev.aichallenge.assistants.rag.indexing

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.common.Jobs
import dev.aichallenge.assistants.llm.Embeddings
import dev.aichallenge.assistants.project.ProjectGateway
import dev.aichallenge.assistants.project.McpEvent
import dev.aichallenge.assistants.rag.models.IndexRecord
import dev.aichallenge.assistants.rag.models.IndexedChunk
import dev.aichallenge.assistants.storage.RecordStore
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.util.UUID

/** Жизненный цикл индекса; в фоне выполняются только явно запрошенные работы. */
@Service
class IndexService(private val store: RecordStore, private val project: ProjectGateway,
    private val embeddings: Embeddings, private val chunker: MarkdownChunker, private val jobs: Jobs) {
    /** Незавершённые операции после restart не запускаются повторно. */
    @PostConstruct fun recover() {
        store.list("index", IndexRecord::class.java).filter { it.status == "BUILDING" }.forEach {
            store.put("index", it.id, it.copy(status = "INTERRUPTED", error = "Приложение перезапущено во время индексации"))
        }
    }
    /** Возвращает опубликованный индекс; не выдаёт BUILDING за готовый. */
    fun ready(): IndexRecord = store.list("index", IndexRecord::class.java).firstOrNull { it.status == "READY" }
        ?: throw AssistantFailure("INDEX_MISSING", "Сначала создайте индекс README и docs игры.", 409)
    /** Сводка не передаёт браузеру embedding-вектора. */
    fun status(): Map<String, Any?> {
        val latest = store.list("index", IndexRecord::class.java, 1).firstOrNull()
        val ready = runCatching { ready() }.getOrNull()
        return mapOf("latest" to latest?.let { summary(it) }, "ready" to ready?.let { summary(it) })
    }
    /** Метаданные показывают реальный корпус и прогресс построения. */
    private fun summary(record: IndexRecord) = mapOf("id" to record.id, "status" to record.status,
        "createdAt" to record.createdAt, "context" to record.context, "identity" to record.identity,
        "processed" to record.processed, "total" to record.total, "error" to record.error,
        "events" to record.events, "documents" to record.chunks.map { it.chunk.path }.distinct())
    /** Сохраняет операцию до запуска, чтобы UI не терял долгую работу. */
    fun start(): Map<String, String> {
        jobs.reserve()
        val record = IndexRecord(UUID.randomUUID().toString(), "BUILDING")
        try { store.put("index", record.id, record) } catch (e: Exception) { jobs.release(); throw e }
        jobs.start { build(record) }
        return mapOf("id" to record.id, "status" to record.status)
    }
    /** Проверяет снимок и identity до/после; публикация одной атомарной записью. */
    private fun build(initial: IndexRecord) {
        var record = initial
        val events = mutableListOf<McpEvent>()
        try {
            val snapshot = project.snapshot(events)
            val identity = embeddings.identity()
            val chunks = snapshot.documents.flatMap(chunker::split)
            require(chunks.isNotEmpty()) { "README/docs пусты" }
            record = record.copy(context = snapshot.context, identity = identity, total = chunks.size, events = events.toList())
            store.put("index", record.id, record)
            val indexed = mutableListOf<IndexedChunk>()
            chunks.chunked(8).forEach { batch ->
                val vectors = embeddings.embed(batch.map { "${it.path}\n${it.section}\n${it.text}" })
                require(vectors.size == batch.size)
                indexed += batch.zip(vectors).map { IndexedChunk(it.first, it.second) }
                record = record.copy(processed = indexed.size)
                store.put("index", record.id, record)
            }
            require(indexed.map { it.vector.size }.distinct().size == 1)
            val current = project.context(events)
            if (current.sha != snapshot.context.sha || current.fingerprint != snapshot.context.fingerprint)
                throw AssistantFailure("SOURCE_CHANGED", "Документы изменились во время индексации. Постройте индекс повторно.", 409)
            if (embeddings.identity() != identity) throw AssistantFailure("EMBEDDING_CHANGED", "Веса embedding-модели изменились.", 409)
            store.put("index", record.id, record.copy(status = "READY", chunks = indexed, events = events.toList()))
        } catch (e: Exception) {
            val message = (e as? AssistantFailure)?.message ?: "Индексация не завершена. Проверьте корпус и локальную embedding-модель."
            store.put("index", record.id, record.copy(status = "FAILED", error = message, events = events.toList()))
        }
    }
}
