package dev.aichallenge.assistants.passport.usecase

import dev.aichallenge.assistants.common.*
import dev.aichallenge.assistants.llm.JsonGenerator
import dev.aichallenge.assistants.passport.model.*
import dev.aichallenge.assistants.passport.repository.PassportRepository
import dev.aichallenge.assistants.passport.policy.PassportPolicy
import dev.aichallenge.assistants.passport.source.*
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Один сценарий для кнопки и scheduler. Сеть только между короткими сохранениями состояния. */
@Service
class RefreshPassport(private val repository: PassportRepository, private val sources: PassportSources,
    private val reader: PassportEvidenceReader, private val generator: JsonGenerator, private val policy: PassportPolicy,
    private val jobs: Jobs) {
    @PostConstruct fun recover() { repository.recover(Instant.now()) }
    fun settings(settings: PassportSettings): PassportState {
        if (settings.intervalSeconds !in setOf(30L, 600L) || settings.provider !in setOf("LOCAL", "DEEPSEEK") || settings.dailyLimit !in 1..500)
            throw AssistantFailure("PASSPORT_SETTINGS", "Выберите интервал 600 с (или явный демо 30 с), модель и лимит 1–500.", 400)
        return repository.settings(settings, Instant.now())
    }
    @Synchronized fun start(command: PassportCommand, trigger: String = "MANUAL"): PassportCheck {
        if (runCatching { UUID.fromString(command.id).toString() }.getOrNull() != command.id)
            throw AssistantFailure("INVALID_ID", "Некорректный UUID проверки.", 400)
        repository.get(command.id)?.let { return it }
        val state = repository.state()
        if (trigger == "SCHEDULED" && (!state.settings.enabled || state.activeId != null || state.nextCheckAt == null || Instant.parse(state.nextCheckAt).isAfter(Instant.now())))
            throw AssistantFailure("PASSPORT_NOT_DUE", "Фоновая проверка ещё не требуется.", 409)
        jobs.reserve()
        try {
            val check = repository.claim(PassportCheck(command.id, trigger, state.settings.provider, command.retryConfirmed, validationVersion = 2), state)
            jobs.start { execute(check) }
            return check
        } catch (e: Exception) { jobs.release(); throw e }
    }
    /** Не повторяет неопределённый платный HTTP после рестарта и не накапливает просроченные тики. */
    private fun execute(initial: PassportCheck) {
        var check = initial
        try {
            val collection = sources.collect()
            val state = repository.state()
            val previous = state.currentId?.let(repository::get)
            val hashes = collection.snapshot.files.associate { it.path to it.hash }
            val changes = (hashes.keys + (previous?.fileHashes?.keys ?: emptySet())).sorted().mapNotNull { path ->
                val old = previous?.fileHashes?.get(path); val new = hashes[path]
                if (old == new) null else PassportChange(path, if (old == null) "ADDED" else if (new == null) "DELETED" else "MODIFIED")
            }
            check = check.copy(snapshotId = collection.snapshot.info.id, fingerprint = collection.fingerprint,
                fileHashes = hashes, images = collection.images, warnings = collection.warnings, changes = changes,
                events = listOf(PassportEvent("COLLECTOR REST", "${hashes.size} текстов; ${collection.images.size} растров. Игра только для чтения.")))
            repository.update(check)
            if (state.blockedFingerprint == collection.fingerprint && !check.retryConfirmed) {
                repository.finish(check.copy(status = "BLOCKED", errorCode = "RETRY_CONFIRMATION", error = "Эта версия имеет неудачный/неопределённый вызов. Для новой попытки нужно явное подтверждение."), Instant.now()); return
            }
            if (previous?.fingerprint == collection.fingerprint && !check.retryConfirmed) {
                repository.finish(check.copy(status = "NO_CHANGES", events = check.events + PassportEvent("SKIP LLM", "Содержимое не изменилось. Модель не вызывалась.")), Instant.now()); return
            }
            val events = mutableListOf<dev.aichallenge.workspace.contracts.WorkspaceEvent>()
            val evidence = reader.read(collection, check.id, check.provider, events)
            check = check.copy(sources = evidence,
                warnings = check.warnings + listOf("Прочитаны ${evidence.size} фрагментов из ${hashes.size} разрешённых файлов. Запуск игры, тесты и vision-анализ не выполнялись.",
                    "Поля C#-настроек могут быть значениями по умолчанию, не фактическими значениями текущей сцены. Сцена в BuildSettings не доказывает работоспособность сборки."),
                events = check.events + events.map { PassportEvent(it.stage, it.details, it.at) })
            repository.update(check)
            check = repository.attempt(check, Instant.now())
            val generation = generator.generateJson(check.provider, policy.messages(check))
            check = check.copy(generation = generation, events = check.events + PassportEvent("LLM", "${generation.model}, ${generation.durationMs} мс"))
            repository.update(check) // Сохраняем оплаченный raw до parsing/validation.
            if (generation.finishReason !in setOf("stop", "eos")) throw AssistantFailure("TRUNCATED_PASSPORT", "Ответ модели не завершён; сохранён для диагностики.", 502)
            check = check.copy(draft = policy.validate(generation.text, check), status = "COMPLETED")
            repository.finish(check, Instant.now())
        } catch (e: Exception) {
            val failure = e as? AssistantFailure
            val limited = failure?.code == "PASSPORT_DAILY_LIMIT"
            repository.finish(check.copy(status = if (limited) "BLOCKED" else "FAILED", errorCode = failure?.code ?: "PASSPORT_FAILED",
                error = failure?.message ?: "Паспорт не обновлён. Источники игры не изменялись; проверьте журнал."), Instant.now())
        }
    }
    fun overview(): PassportOverview {
        val state = repository.state(); val day = Instant.now().toString().take(10)
        // Список не возвращает весь raw/исходники каждой исторической версии.
        return PassportOverview(state, state.currentId?.let(repository::get), repository.history().map { it.copy(sources = emptyList(), fileHashes = emptyMap(), images = emptyList(), draft = null, generation = it.generation?.copy(text = "")) }, repository.attempts(day), day)
    }
    fun get(id: String) = repository.get(id) ?: throw AssistantFailure("NOT_FOUND", "Проверка не найдена.", 404)
    fun image(checkId: String, imageId: String): Pair<String, ByteArray> {
        if (get(checkId).images.none { it.id == imageId }) throw AssistantFailure("NOT_FOUND", "Изображение не относится к этой проверке.", 404)
        return sources.image(imageId)
    }
}
