package dev.aichallenge.assistants.review.policy

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.rag.models.Source
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Граница доверия: разрешён один repo, собственный PR и точные SHA. */
@Component
class ReviewInputPolicy(private val config: ReviewConfig) {
    fun request(input: ReviewRequest) {
        require(input.repository == config.repository && input.pr > 0 && input.pr <= 10_000_000) { "Не разрешён репозиторий/PR" }
        require(listOf(input.baseSha, input.headSha).all { Regex("[0-9a-f]{40}").matches(it) }) { "Нужен полный Git SHA" }
        require(input.provider in setOf("LOCAL", "DEEPSEEK"))
        require(input.runId == null || input.runId > 0)
    }
    fun pull(pr: PullRequest) {
        if (pr.repository != config.repository || pr.headRepository != config.repository || pr.author != config.author)
            throw AssistantFailure("UNTRUSTED_PR", "Автоматически разрешены только собственные PR внутри выбранного репозитория.", 403)
        if (pr.state != "open") throw AssistantFailure("PR_CLOSED", "PR уже закрыт.", 409)
    }
    fun matches(input: ReviewRequest, pr: PullRequest) {
        request(input); pull(pr)
        if (pr.number != input.pr || pr.baseSha != input.baseSha || pr.headSha != input.headSha)
            throw AssistantFailure("PR_CHANGED", "Версия PR изменилась; старое задание не анализируется.", 409)
    }
}

/** Allowlist исключает конфигурацию секретов, Unity YAML, бинарники и зависимости. */
fun reviewPath(path: String): Boolean = path.length in 1..240 && '\\' !in path && !path.startsWith('/') &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
    (path == "README.md" || (path.startsWith("docs/") && path.endsWith(".md")) ||
        (path.startsWith("AsciiArena/Assets/Prototype/") && path.endsWith(".cs")))

/** Unified diff разбирается как текст, никогда не применяется и не исполняется. */
fun changedLines(patch: String?): Pair<Set<Int>, Set<Int>> {
    val added = mutableSetOf<Int>(); val removed = mutableSetOf<Int>()
    var before = 0; var after = 0; var inHunk = false
    patch?.lineSequence()?.forEach { line ->
        val match = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*").matchEntire(line)
        if (match != null) { before = match.groupValues[1].toInt(); after = match.groupValues[2].toInt(); inHunk = true }
        else if (inHunk) when (line.firstOrNull()) {
            '+' -> { added += after; after++ }
            '-' -> { removed += before; before++ }
            ' ' -> { before++; after++ }
        }
    }
    return added to removed
}

/** Structured output проверяется по diff и RAG; не является семантическим judge. */
@Component
class ReviewOutputPolicy(private val mapper: ObjectMapper) {
    fun parse(text: String, files: List<ChangedFile>, sources: List<Source>): Pair<String, List<Finding>> {
        try {
            val clean = text.trim().let { value ->
                if (value.startsWith(96.toChar().toString().repeat(3))) value.lineSequence().drop(1).toList().dropLast(1).joinToString("\n") else value
            }
            val root = mapper.readTree(clean)
            require(root.isObject && root["summary"]?.isTextual == true && root["findings"]?.isArray == true)
            val summary = root["summary"].asText(); require(summary.length in 1..3000)
            require(root["findings"].size() <= 20)
            val allowed = sources.map { it.id }.toSet()
            val findings = root["findings"].toList().map { node ->
                val finding = mapper.treeToValue(node, Finding::class.java)
                require(finding.side in setOf("BASE", "HEAD"))
                val file = files.singleOrNull {
                    if (finding.side == "BASE") (it.previousPath ?: it.path) == finding.path else it.path == finding.path
                } ?: error("Path is not in diff")
                val lines = if (finding.side == "BASE") file.removedLines else file.addedLines
                require(finding.line in lines && finding.line > 0)
                require(finding.title.length in 1..200 && finding.explanation.length in 1..4000 && finding.recommendation.length in 1..2000)
                require(finding.sourceIds.isNotEmpty() && finding.sourceIds.size <= 8 && finding.sourceIds.all { it in allowed })
                finding
            }
            return summary to findings
        } catch (_: Exception) {
            throw AssistantFailure("INVALID_REVIEW", "Модель вернула ревью с некорректной схемой, строками или источниками. Ответ сохранён; повтор не выполнялся.")
        }
    }
}
