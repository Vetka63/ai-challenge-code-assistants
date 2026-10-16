package dev.aichallenge.assistants.review.rag

import dev.aichallenge.assistants.llm.Embeddings
import dev.aichallenge.assistants.project.ProjectDocument
import dev.aichallenge.assistants.rag.indexing.MarkdownChunker
import dev.aichallenge.assistants.rag.models.*
import dev.aichallenge.assistants.rag.retrieval.Retriever
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.storage.RecordStore
import org.springframework.stereotype.Component
import java.security.MessageDigest

/** Текстовый chunking C# сохраняет строки и начинает фрагменты у объявлений. Не AST-компилятор. */
fun codeChunks(document: ReviewDocument): List<Chunk> {
    val lines = document.text.split('\n'); val result = mutableListOf<Chunk>()
    var start = 0; var size = 0
    fun flush(end: Int) {
        if (end <= start) return
        val text = lines.subList(start, end).joinToString("\n")
        if (text.isNotBlank()) result += Chunk(hash(document.path + document.side + document.sha + start + end),
            document.path, "[" + document.side + "] " + document.sha.take(10) + " C#",
            start + 1, end, text, hash(document.text), document.side + "_CODE")
        start = end; size = 0
    }
    lines.forEachIndexed { i, line ->
        require(line.length <= 12000) { "Слишком длинная строка C#" }
        val declaration = Regex("^\\s*(public|private|internal|protected)\\s+.*[({].*").matches(line)
        if (i > start && (size + line.length > 2400 || (declaration && size > 1200))) flush(i)
        size += line.length + 1
    }
    flush(lines.size)
    return result
}
private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Отдельный индекс точного снимка PR; база /help не изменяется и не используется как fallback. */
@Component
class ReviewRag(private val embeddings: Embeddings, private val store: RecordStore,
    private val markdown: MarkdownChunker, private val retriever: Retriever) {
    fun retrieve(snapshot: ReviewSnapshot): List<Source> {
        val identity = embeddings.identity()
        val fingerprint = snapshot.documents.joinToString("\n") { it.path + ":" + it.side + ":" + it.sha + ":" + hash(it.text) }
        val key = hash("review-v1:" + fingerprint + identity.toString())
        val index = store.get("review-index", key, IndexRecord::class.java) ?: run {
            val chunks = snapshot.documents.flatMap { doc ->
                if (doc.path.endsWith(".cs")) codeChunks(doc) else markdown.split(ProjectDocument(doc.path, hash(doc.text), doc.text, doc.side + "_DOCUMENT"))
                    .map { it.copy(chunkId = hash(doc.side + doc.sha + it.chunkId), section = "[" + doc.side + " " + doc.sha.take(10) + "] " + it.section,
                        status = doc.side + "_DOCUMENT") }
            }
            require(chunks.isNotEmpty() && chunks.size <= 1500) { "Неподдерживаемый размер корпуса ревью" }
            val indexed = chunks.chunked(8).flatMap { batch ->
                val vectors = embeddings.embed(batch.map { it.path + "\n" + it.section + "\n" + it.text })
                require(vectors.size == batch.size)
                batch.zip(vectors).map { IndexedChunk(it.first, it.second) }
            }
            require(embeddings.identity() == identity) { "Embedding-модель изменилась" }
            IndexRecord(key, "READY", identity = identity, chunks = indexed, total = chunks.size, processed = chunks.size).also {
                store.put("review-index", key, it)
            }
        }
        val question = snapshot.pr.title + "\n" + snapshot.pr.body.take(2000) + "\n" +
            snapshot.files.joinToString("\n") { it.path + "\n" + (it.patch ?: "").take(3000) }.take(14000)
        val vector = embeddings.embed(listOf("Instruct: Retrieve project rules and code relevant to reviewing this diff.\nQuery: " + question)).single()
        return retriever.search(index, question, vector, 8)
    }
}
