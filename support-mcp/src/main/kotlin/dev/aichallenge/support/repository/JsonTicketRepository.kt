package dev.aichallenge.support.repository

import dev.aichallenge.support.contracts.*
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.time.Instant
import java.util.UUID

/** Один процесс-владелец каталога; запись temp+force+atomic move не оставляет половину JSON. */
@Repository
class JsonTicketRepository(@Value("$"+"{SUPPORT_DATA:/app/support-data}") directory: String,
    private val mapper: ObjectMapper) : AutoCloseable {
    private val root = Path.of(directory).toAbsolutePath().normalize()
    private val lockChannel: FileChannel
    private val ownerLock: java.nio.channels.FileLock
    init {
        Files.createDirectories(root.resolve("tickets"))
        lockChannel = FileChannel.open(root.resolve(".owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        ownerLock = try { lockChannel.tryLock() ?: error("JSON directory already has an owner") }
        catch (e: Exception) { lockChannel.close(); throw e }
        try {
            val usersPath = root.resolve("users.json")
            if (!Files.exists(usersPath)) write(usersPath, listOf(
                SupportUser("demo-anna", "Анна · учебный профиль", "WINDOWS"),
                SupportUser("demo-boris", "Борис · учебный профиль", "WINDOWS")))
            require(users().map { it.id }.distinct().size == users().size && users().isNotEmpty())
            recover()
        } catch (e: Exception) { close(); throw e }
    }
    /** Освобождает process-lock при штатной остановке. Несколько реплик не поддерживаются. */
    @PreDestroy override fun close() { if (ownerLock.isValid) ownerLock.release(); lockChannel.close() }
    /** Только явно подготовленные вымышленные профили; повреждённый файл не заменяется seed-данными. */
    @Synchronized fun users(): List<SupportUser> =
        mapper.readValue(read(root.resolve("users.json")), Array<SupportUser>::class.java).toList()
    /** Проверка профиля обязательна на сервере, а не только в выпадающем списке UI. */
    fun user(id: String) = users().firstOrNull { it.id == id }
        ?: throw SupportFailure("DEMO_USER", "Неизвестный учебный профиль.", 403)
    /** UUID не позволяет использовать идентификатор как произвольный файловый путь. */
    private fun path(id: String): Path {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not())
            throw SupportFailure("INVALID_ID", "Нужен UUID обращения или сообщения.")
        return root.resolve("tickets").resolve("$id.json")
    }
    /** Чужое и отсутствующее обращение имеют одинаковую ошибку, без утечки его содержимого. */
    @Synchronized fun ticket(userId: String, id: String): SupportTicket {
        user(userId)
        val file = path(id)
        if (!Files.exists(file)) throw SupportFailure("TICKET_NOT_FOUND", "Обращение недоступно.", 404)
        val ticket = mapper.readValue(read(file), SupportTicket::class.java)
        if (ticket.userId != userId) throw SupportFailure("TICKET_NOT_FOUND", "Обращение недоступно.", 404)
        check(ticket.id == id) { "Invalid persisted ticket ID" }
        return ticket
    }
    /** Список всегда ограничен выбранным профилем; JSON-векторов и чужих сообщений здесь нет. */
    @Synchronized fun list(userId: String): List<TicketSummary> {
        user(userId)
        return Files.list(root.resolve("tickets")).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }.map {
                mapper.readValue(read(it), SupportTicket::class.java)
            }.filter { it.userId == userId }.map {
                TicketSummary(it.id, it.title, it.kind, it.gameVersion, it.platform, it.createdAt, it.updatedAt, it.turns.size)
            }.toList().sortedByDescending { it.updatedAt }
        }
    }
    /** Создаёт обращение идемпотентно; тот же ID с другим содержимым — конфликт, не перезапись. */
    @Synchronized fun create(userId: String, draft: TicketDraft): SupportTicket {
        user(userId)
        require(draft.kind in setOf("ISSUE", "QUESTION"))
        require(draft.title.trim().length in 3..120 && draft.description.trim().length in 10..6000)
        require(draft.gameVersion.trim().length in 1..100 && draft.platform in setOf("WINDOWS", "LINUX", "MAC", "OTHER"))
        val file = path(draft.id)
        val proposed = SupportTicket(draft.id, userId, draft.title.trim(), draft.description.trim(),
            draft.gameVersion.trim(), draft.platform, draft.kind)
        if (Files.exists(file)) {
            val old = ticket(userId, draft.id)
            if (old.copy(createdAt = proposed.createdAt, updatedAt = proposed.updatedAt, version = 0, turns = emptyList()) != proposed)
                throw SupportFailure("ID_CONFLICT", "UUID уже использован для другого обращения.", 409)
            return old
        }
        write(file, proposed)
        return proposed
    }
    /** Вопрос сохраняется до модели. Повтор UUID возвращает прежний ход, не добавляет дубликат. */
    @Synchronized fun addTurn(userId: String, id: String, input: SupportQuestion): SupportTurn {
        path(input.id)
        require(input.question.trim().length in 1..4000 && input.provider in setOf("LOCAL", "DEEPSEEK"))
        val ticket = ticket(userId, id)
        ticket.turns.firstOrNull { it.id == input.id }?.let {
            if (it.question != input.question.trim() || it.provider != input.provider)
                throw SupportFailure("ID_CONFLICT", "UUID уже использован для другого вопроса.", 409)
            return it
        }
        if (ticket.turns.any { it.status == "RUNNING" }) throw SupportFailure("TICKET_BUSY", "Предыдущий вопрос ещё обрабатывается.", 409)
        val turn = SupportTurn(input.id, input.question.trim(), input.provider)
        save(ticket.copy(turns = ticket.turns + turn))
        return turn
    }
    /** CAS не позволяет запоздавшему результату затереть завершённый/прерванный ход. */
    @Synchronized fun update(userId: String, id: String, input: SaveTurn): SupportTurn {
        val ticket = ticket(userId, id)
        val old = ticket.turns.firstOrNull { it.id == input.turn.id }
            ?: throw SupportFailure("TURN_NOT_FOUND", "Сообщение недоступно.", 404)
        if (old.revision != input.expectedRevision || old.status != "RUNNING")
            throw SupportFailure("TURN_CONFLICT", "Сообщение уже изменилось.", 409)
        require(input.turn.question == old.question && input.turn.provider == old.provider && input.turn.createdAt == old.createdAt)
        require(input.turn.status in setOf("RUNNING", "COMPLETED", "FAILED", "UNKNOWN", "TRUNCATED", "INTERRUPTED"))
        val next = input.turn.copy(revision = old.revision + 1)
        save(ticket.copy(turns = ticket.turns.map { if (it.id == next.id) next else it }))
        return next
    }
    /** Restart не повторяет оплаченный запрос: неопределённый результат становится INTERRUPTED. */
    @Synchronized fun recover(): Int {
        var count = 0
        users().forEach { user -> list(user.id).forEach { summary ->
            val ticket = ticket(user.id, summary.id)
            if (ticket.turns.any { it.status == "RUNNING" }) {
                save(ticket.copy(turns = ticket.turns.map {
                    if (it.status == "RUNNING") { count++; it.copy(status = "INTERRUPTED", revision = it.revision + 1,
                        errorCode = "RESTART", error = "Приложение перезапущено. Платный вызов автоматически не повторялся.") } else it
                }))
            }
        } }
        return count
    }
    /** Ограниченный контекст MCP не дублирует источники и метрики всей переписки. */
    fun context(userId: String, id: String): TicketContext {
        val ticket = ticket(userId, id)
        return TicketContext(user(userId), id, ticket.kind, ticket.title, ticket.description, ticket.gameVersion,
            ticket.platform, ticket.version, ticket.turns.filter { it.status == "COMPLETED" }.takeLast(3)
                .map { SupportHistory(it.question, it.answer.take(2000)) })
    }
    private fun save(ticket: SupportTicket) = write(path(ticket.id),
        ticket.copy(version = ticket.version + 1, updatedAt = Instant.now().toString()))
    /** Лимит файла — защита хранилища, не скрытый лимит генерации модели. */
    private fun read(file: Path): String {
        require(!Files.isSymbolicLink(file) && Files.size(file) <= 8_000_000)
        return Files.readString(file)
    }
    private fun write(file: Path, value: Any) {
        val bytes = mapper.writeValueAsBytes(value)
        if (bytes.size > 8_000_000) throw SupportFailure("TICKET_TOO_LARGE", "Обращение превысило безопасный размер JSON-файла.", 413)
        val temp = Files.createTempFile(file.parent, ".write-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}
