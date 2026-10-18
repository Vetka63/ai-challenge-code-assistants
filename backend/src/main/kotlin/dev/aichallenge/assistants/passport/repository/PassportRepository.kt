package dev.aichallenge.assistants.passport.repository

import dev.aichallenge.assistants.config.RuntimeConfig
import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.passport.model.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.*
import java.sql.*
import java.time.Instant

/** Владелец таблиц паспорта. BEGIN IMMEDIATE защищает claim и публикацию; сеть вне транзакции. */
@Repository
class PassportRepository(config: RuntimeConfig, private val mapper: ObjectMapper) {
    private val url = "jdbc:sqlite:" + Path.of(config.database).toAbsolutePath().also { Files.createDirectories(it.parent) }
    init { connection().use { c -> c.createStatement().use {
        it.execute("PRAGMA journal_mode=WAL")
        it.execute("CREATE TABLE IF NOT EXISTS passport_state(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
        it.execute("CREATE TABLE IF NOT EXISTS passport_checks(id TEXT PRIMARY KEY, created TEXT NOT NULL, payload TEXT NOT NULL)")
        c.prepareStatement("INSERT OR IGNORE INTO passport_state VALUES(1,?)").use { s -> s.setString(1, mapper.writeValueAsString(PassportState())); s.executeUpdate() }
    } } }
    private fun connection() = DriverManager.getConnection(url).also { c -> c.createStatement().use { it.execute("PRAGMA busy_timeout=10000") } }
    private fun <T> transaction(block: (Connection) -> T): T = connection().use { c ->
        c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try { val result = block(c); c.createStatement().use { it.execute("COMMIT") }; result }
        catch (e: Throwable) { c.createStatement().use { it.execute("ROLLBACK") }; throw e }
    }
    private fun state(c: Connection): PassportState = c.createStatement().use { s -> s.executeQuery("SELECT payload FROM passport_state WHERE id=1").use {
        check(it.next()); mapper.readValue(it.getString(1), PassportState::class.java)
    } }
    private fun save(c: Connection, state: PassportState) { c.prepareStatement("UPDATE passport_state SET payload=? WHERE id=1").use {
        it.setString(1, mapper.writeValueAsString(state)); check(it.executeUpdate() == 1)
    } }
    private fun get(c: Connection, id: String): PassportCheck? = c.prepareStatement("SELECT payload FROM passport_checks WHERE id=?").use {
        it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) mapper.readValue(rs.getString(1), PassportCheck::class.java) else null }
    }
    private fun put(c: Connection, record: PassportCheck) { c.prepareStatement("INSERT INTO passport_checks VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload").use {
        it.setString(1, record.id); it.setString(2, record.createdAt); it.setString(3, mapper.writeValueAsString(record)); it.executeUpdate()
    } }
    fun state() = connection().use(::state)
    fun get(id: String) = connection().use { get(it, id) }
    fun history(limit: Int = 100): List<PassportCheck> = connection().use { c -> c.prepareStatement("SELECT payload FROM passport_checks ORDER BY created DESC,rowid DESC LIMIT ?").use {
        it.setInt(1, limit.coerceIn(1, 200)); it.executeQuery().use { rs -> buildList { while (rs.next()) add(mapper.readValue(rs.getString(1), PassportCheck::class.java)) } }
    } }
    private fun attempts(c: Connection, day: String): Int = c.prepareStatement("SELECT count(*) FROM passport_checks WHERE substr(json_extract(payload,'$.attemptedAt'),1,10)=?").use {
        it.setString(1, day); it.executeQuery().use { r -> r.next(); r.getInt(1) }
    }
    fun attempts(day: String) = connection().use { attempts(it, day) }
    fun settings(settings: PassportSettings, now: Instant): PassportState = transaction { c ->
        val previous = state(c)
        // Смена провайдера/лимита не создаёт немедленный второй платный запуск.
        previous.copy(settings = settings, nextCheckAt = if (!settings.enabled) null else now.plusSeconds(settings.intervalSeconds).toString()).also { save(c, it) }
    }
    /** UUID неизменяем: повтор не перезапускает завершённое или неопределённое задание. */
    fun claim(record: PassportCheck, expectedState: PassportState? = null): PassportCheck = transaction { c ->
        get(c, record.id)?.let { return@transaction it }
        val state = state(c)
        if (expectedState != null && state != expectedState)
            throw AssistantFailure("PASSPORT_SETTINGS_CHANGED", "Настройки изменились перед запуском. Повторите проверку.", 409)
        if (state.activeId != null) throw AssistantFailure("PASSPORT_BUSY", "Проверка паспорта уже выполняется.", 409)
        put(c, record); save(c, state.copy(activeId = record.id)); record
    }
    fun update(record: PassportCheck) = transaction { c ->
        check(state(c).activeId == record.id); put(c, record)
    }
    /** Маркер возможного расхода пишется до HTTP; квота проверяется в той же транзакции. */
    fun attempt(record: PassportCheck, now: Instant): PassportCheck = transaction { c ->
        val state = state(c); check(state.activeId == record.id)
        if (attempts(c, now.toString().take(10)) >= state.settings.dailyLimit)
            throw AssistantFailure("PASSPORT_DAILY_LIMIT", "Достигнут дневной лимит генераций (UTC).", 409)
        record.copy(status = "ANALYZING", attemptedAt = now.toString()).also { put(c, it) }
    }
    /** Результат и указатель версии публикуются одним commit; ошибка не заменяет успешный паспорт. */
    fun finish(record: PassportCheck, now: Instant): PassportCheck = transaction { c ->
        val state = state(c); check(state.activeId == record.id)
        val result = record.copy(finishedAt = now.toString())
        put(c, result)
        save(c, state.copy(activeId = null, currentId = if (record.status == "COMPLETED") record.id else state.currentId,
            blockedFingerprint = if (record.status == "COMPLETED") null else if (record.attemptedAt != null && record.status in setOf("FAILED", "INTERRUPTED")) record.fingerprint else state.blockedFingerprint,
            nextCheckAt = if (state.settings.enabled) now.plusSeconds(state.settings.intervalSeconds).toString() else null))
        result
    }
    /** Один backend владеет volume. После crash платный вызов с неизвестным итогом не повторяется. */
    fun recover(now: Instant) = transaction { c ->
        val state = state(c)
        state.activeId?.let { id ->
            val record = get(c, id) ?: error("Missing active passport check")
            put(c, record.copy(status = "INTERRUPTED", finishedAt = now.toString(), errorCode = "RESTART",
                error = "Процесс перезапущен. Неопределённая генерация автоматически не повторяется."))
            save(c, state.copy(activeId = null, blockedFingerprint = if (record.attemptedAt != null) record.fingerprint else state.blockedFingerprint,
                nextCheckAt = if (state.settings.enabled) now.plusSeconds(state.settings.intervalSeconds).toString() else null))
        }
    }
}
