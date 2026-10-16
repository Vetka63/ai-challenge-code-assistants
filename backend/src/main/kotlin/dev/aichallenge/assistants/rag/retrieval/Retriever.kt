package dev.aichallenge.assistants.rag.retrieval

import dev.aichallenge.assistants.llm.cosine
import dev.aichallenge.assistants.rag.models.IndexRecord
import dev.aichallenge.assistants.rag.models.Source
import org.springframework.stereotype.Component

/** Локальный dense + lexical RRF; документы не превращаются в инструкции. */
@Component
class Retriever {
    /** Токенизация поиска не является подсчётом LLM-токенов. */
    private fun tokens(text: String) = Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()
    /** Объединяет cosine и совпадения слов, ограничивает общий объём источников. */
    fun search(index: IndexRecord, question: String, vector: List<Double>, topK: Int = 7): List<Source> {
        val words = tokens(question)
        val dense = index.chunks.map { it to cosine(it.vector, vector) }.sortedByDescending { it.second }
        val lexical = index.chunks.map { item ->
            val text = tokens(item.chunk.path + " " + item.chunk.section + " " + item.chunk.text)
            item.chunk.chunkId to words.count { it in text }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.mapIndexed { i, pair -> pair.first to i + 1 }.toMap()
        val ranked = dense.mapIndexed { i, pair ->
            val id = pair.first.chunk.chunkId
            val rank = 1.0 / (60 + i + 1) + (lexical[id]?.let { 1.5 / (60 + it) } ?: 0.0)
            Triple(pair.first.chunk, pair.second, rank)
        }.filter { it.second >= 0.20 }.sortedByDescending { it.third }
        if (ranked.isEmpty()) return emptyList()
        // Явный current-state раздел README — контекст версии, не скрытый эталон ответа.
        val current = dense.filter { it.first.chunk.status == "CURRENT_STATE" }.minByOrNull { it.first.chunk.startLine }
        // Readme-ссылки дают проверяемый переход к документации текущей сборки,
        // а не жёстко заданное имя игрового файла.
        val currentText = index.chunks.filter { it.chunk.status == "CURRENT_STATE" }
            .sortedBy { it.chunk.startLine }.joinToString("\n") { it.chunk.text }
        val overview = dense.firstOrNull { it.first.chunk.path == "README.md" && it.first.chunk.startLine == 1 }
        val linkedPaths = currentText.takeIf { it.isNotEmpty() }?.let { text ->
            Regex("\\]\\((docs/[^)#]+\\.md)(?:#[^)]*)?\\)").findAll(text).map { it.groupValues[1] }.distinct().take(3).toList()
        } ?: emptyList()
        // Внутри уже выбранной страницы нужен смысл вопроса, а не совпадение
        // общего слова «акт/проект» с её вводным заголовком.
        val linked = linkedPaths.mapNotNull { path ->
            dense.firstOrNull { it.first.chunk.path == path && it.second >= 0.20 }
                ?.let { Triple(it.first.chunk, it.second, 1.0) }
        }
        val selected = if (current == null) ranked.take(topK) else {
            val anchor = Triple(current.first.chunk, current.second, 1.0)
            val overviewContext = overview?.let { listOf(Triple(it.first.chunk, it.second, 1.0)) } ?: emptyList()
            val promoted = (listOf(anchor) + overviewContext + linked).distinctBy { it.first.chunkId }.take(topK)
            val used = promoted.map { it.first.chunkId }.toSet()
            promoted + ranked.filter { it.first.chunkId !in used }.take(topK - promoted.size)
        }
        var budget = 0
        return selected.mapNotNull { item ->
            if (budget + item.first.text.length > 14_000) null else {
                budget += item.first.text.length
                Source("", item.first, item.second, when {
                    item.first.chunkId == current?.first?.chunk?.chunkId -> "CURRENT_STATE"
                    item.first.chunkId == overview?.first?.chunk?.chunkId -> "PROJECT_OVERVIEW"
                    linked.any { it.first.chunkId == item.first.chunkId } -> "README_LINK"
                    else -> "RETRIEVED"
                })
            }
        }.mapIndexed { i, source -> source.copy(id = "S${i + 1}") }
    }
}
