package dev.aichallenge.assistants.storage

import dev.aichallenge.assistants.config.RuntimeConfig
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Локальный SQLite: независимые типы записей, атомарная замена JSON и WAL. */
@Repository
class RecordStore(config: RuntimeConfig, private val mapper: ObjectMapper) {
    private val url: String
    init {
        val path = Path.of(config.database).toAbsolutePath()
        Files.createDirectories(path.parent)
        url = "jdbc:sqlite:$path"
        connect().use { c -> c.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("CREATE TABLE IF NOT EXISTS records(kind TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,created TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')),PRIMARY KEY(kind,id))")
        } }
    }
    /** Открывает короткое соединение с ожиданием блокировки вместо потери записи. */
    private fun connect() = DriverManager.getConnection(url).also { c ->
        c.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
    }
    /** Обновляет только указанную запись, сохраняя время её создания. */
    @Synchronized fun put(kind: String, id: String, record: Any) {
        connect().use { c -> c.prepareStatement("INSERT INTO records(kind,id,payload) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET payload=excluded.payload").use {
            it.setString(1, kind); it.setString(2, id); it.setString(3, mapper.writeValueAsString(record)); it.executeUpdate()
        } }
    }
    /** Читает DTO конкретного домена, не изменяя его состояние. */
    fun <T> get(kind: String, id: String, type: Class<T>): T? = connect().use { c ->
        c.prepareStatement("SELECT payload FROM records WHERE kind=? AND id=?").use {
            it.setString(1, kind); it.setString(2, id)
            it.executeQuery().use { rs -> if (rs.next()) mapper.readValue(rs.getString(1), type) else null }
        }
    }
    /** Возвращает ограниченный список; newestFirst регулирует порядок истории. */
    fun <T> list(kind: String, type: Class<T>, limit: Int = 100, newestFirst: Boolean = true): List<T> = connect().use { c ->
        c.prepareStatement("SELECT payload FROM records WHERE kind=? ORDER BY created ${if (newestFirst) "DESC" else "ASC"}, rowid ${if (newestFirst) "DESC" else "ASC"} LIMIT ?").use {
            it.setString(1, kind); it.setInt(2, limit.coerceIn(1, 10000))
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(mapper.readValue(rs.getString(1), type)) } }
        }
    }
}
