package dev.aichallenge.assistants.review.service

import dev.aichallenge.assistants.review.models.*

/** Отчёт рендерится из сохранённого DTO, без повторной генерации и HTML модели. */
fun reviewMarkdown(job: ReviewJob, includeSourceText: Boolean = true): String = buildString {
    fun literal(text: String) {
        val fence = "~".repeat(maxOf(4, Regex("~+").findAll(text).maxOfOrNull { it.value.length + 1 } ?: 4))
        appendLine(fence); appendLine(text); appendLine(fence); appendLine()
    }
    appendLine("# AI-ревью PR " + job.request.pr + "\n")
    appendLine("Репозиторий: " + job.request.repository)
    appendLine("Base: " + job.request.baseSha + "\nHead: " + job.request.headSha)
    appendLine("Результат: " + job.id + "; состояние: " + job.status + "\n")
    val report = job.report
    if (report == null) { literal(job.error ?: "Отчёт ещё не готов."); return@buildString }
    appendLine("## Итог\n"); literal(report.summary)
    appendLine("Покрытие: " + report.coverage + "; модель: " + report.generation.model + "\n")
    if (report.findings.isEmpty()) appendLine("Подтверждённых проверками формата замечаний нет. Это не гарантия отсутствия багов.\n")
    report.findings.forEachIndexed { i, finding ->
        appendLine("## Замечание " + (i + 1) + " " + finding.category + " " + finding.severity + "\n")
        literal(finding.title)
        literal(finding.path + ":" + finding.line + " (" + finding.side + ")\n" +
            finding.explanation + "\nРекомендация: " + finding.recommendation + "\nИсточники: " + finding.sourceIds.joinToString())
    }
    appendLine("## Ограничения\n")
    report.limitations.forEach { literal(it) }
    appendLine("## Источники\n")
    report.sources.forEach { source ->
        appendLine("### " + source.id + "\n")
        literal(source.chunk.path + ":" + source.chunk.startLine + "–" + source.chunk.endLine + " " + source.chunk.section)
        if (includeSourceText) literal(source.chunk.text)
    }
    if (includeSourceText) {
        appendLine("## Анализируемый diff\n")
        job.files.forEach { file -> literal(file.path); literal(file.patch ?: "Patch отсутствует") }
    }
}
