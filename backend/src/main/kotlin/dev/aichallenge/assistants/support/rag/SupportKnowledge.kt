package dev.aichallenge.assistants.support.rag

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.llm.*
import dev.aichallenge.assistants.project.ProjectDocument
import dev.aichallenge.assistants.rag.indexing.MarkdownChunker
import dev.aichallenge.assistants.rag.models.*
import dev.aichallenge.assistants.rag.retrieval.Retriever
import dev.aichallenge.assistants.storage.RecordStore
import org.springframework.stereotype.Service
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest
import java.util.UUID

/** Версионированная учебная база поддержки. Тикеты и профили здесь отсутствуют. */
data class SupportIndex(val record: IndexRecord, val fingerprint: String, val documents: List<String>,
    val knowledgeVersion: String = SupportKnowledge.VERSION)
/** Отдельный индекс support; общие embeddings/chunking не смешивают корпуса агентов. */
@Service
class SupportKnowledge(private val store: RecordStore, private val embeddings: Embeddings,
    private val chunker: MarkdownChunker, private val retriever: Retriever, private val jobs: Jobs) {
    private val names = listOf("overview.md", "controls.md", "saves.md", "troubleshooting.md")
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    /** Загружает только явный allowlist ресурсов приложения, не произвольные файлы игры. */
    fun documents() = names.map { name ->
        val text = ClassPathResource("support-knowledge/$name").inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
        ProjectDocument("support-faq/$name", hash(text), text, "SUPPORT_DOCUMENT")
    }
    private fun fingerprint() = hash(documents().joinToString("\n") { it.path + ":" + it.hash })
    /** Статус без embeddings: UI видит версию и реальные документы. */
    fun status(): Map<String, Any?> {
        val all = store.list("support-index", SupportIndex::class.java, 100)
        val latest = all.firstOrNull()
        val ready = all.firstOrNull { it.record.status == "READY" && it.fingerprint == fingerprint() }
        fun view(index: SupportIndex?) = index?.let { mapOf("id" to it.record.id, "status" to it.record.status,
            "processed" to it.record.processed, "total" to it.record.total, "error" to it.record.error,
            "documents" to it.documents, "knowledgeVersion" to it.knowledgeVersion, "fingerprint" to it.fingerprint) }
        return mapOf("latest" to view(latest), "ready" to view(ready), "knowledgeVersion" to VERSION)
    }
    /** Построение только по кнопке, без расхода GPU/API после старта. */
    @Synchronized fun start(): Map<String, Any?> {
        jobs.reserve()
        val docs = documents()
        var index = SupportIndex(IndexRecord(UUID.randomUUID().toString(), "BUILDING"), fingerprint(), docs.map { it.path })
        try { store.put("support-index", index.record.id, index) } catch (e: Exception) { jobs.release(); throw e }
        jobs.start {
            try {
                val identity = embeddings.identity()
                val chunks = docs.flatMap { chunker.split(it) }
                index = index.copy(record = index.record.copy(identity = identity, total = chunks.size))
                store.put("support-index", index.record.id, index)
                val vectors = mutableListOf<IndexedChunk>()
                chunks.chunked(8).forEach { batch ->
                    val embedded = embeddings.embed(batch.map { it.text })
                    require(embedded.size == batch.size)
                    vectors += batch.zip(embedded).map { IndexedChunk(it.first, it.second) }
                    index = index.copy(record = index.record.copy(processed = vectors.size))
                    store.put("support-index", index.record.id, index)
                }
                require(identity == embeddings.identity())
                index = index.copy(record = index.record.copy(status = "READY", chunks = vectors))
            } catch (e: Exception) {
                index = index.copy(record = index.record.copy(status = "FAILED",
                    error = (e as? AssistantFailure)?.message ?: "Индекс не построен; прежняя готовая версия сохранена."))
            }
            store.put("support-index", index.record.id, index)
        }
        return status()
    }
    /** Обстоятельства помогают retrieval, но сами не включаются в индекс. */
    fun search(question: String): Pair<SupportIndex, List<Source>> {
        val index = store.list("support-index", SupportIndex::class.java, 100)
            .firstOrNull { it.record.status == "READY" && it.fingerprint == fingerprint() }
            ?: throw AssistantFailure("SUPPORT_INDEX_MISSING", "Создайте базу знаний поддержки кнопкой «Создать индекс FAQ».", 409)
        if (index.record.identity != embeddings.identity())
            throw AssistantFailure("EMBEDDING_CHANGED", "Embedding-модель изменилась. Обновите индекс FAQ.", 409)
        val vector = embeddings.embed(listOf("Instruct: Retrieve game support documentation relevant to the player question.\nQuery: $question")).single()
        return index to retriever.search(index.record, question, vector, 5)
    }
    /** Прерванное построение не повторяет внешние вызовы без кнопки. */
    fun recover() {
        store.list("support-index", SupportIndex::class.java, 100).filter { it.record.status == "BUILDING" }.forEach {
            store.put("support-index", it.record.id, it.copy(record = it.record.copy(status = "INTERRUPTED",
                error = "Построение прервано перезапуском. Запустите его явно.")))
        }
    }
    companion object { const val VERSION = "Учебный снимок HeroAnimations · 16.10.2026" }
}
