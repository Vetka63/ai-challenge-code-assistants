package dev.aichallenge.workspacemcp.storage

import dev.aichallenge.workspace.contracts.*
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Единственный владелец снимков, подтверждений чтения и артефактов. Игра доступна только на чтение. */
@Repository
class WorkspaceStore(@Value("$"+"{WORKSPACE_SOURCE:/project}") sourcePath: String,
    @Value("$"+"{WORKSPACE_DATA:/app/workspaces}") dataPath: String, private val mapper: ObjectMapper) {
    private val source = Path.of(sourcePath).toRealPath()
    private val data = Path.of(dataPath).toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()
    private val lockChannel = FileChannel.open(data.resolve(".owner.lock"), CREATE, WRITE)
    private val owner = lockChannel.tryLock() ?: error("Workspace storage already has an owner")
    init { require(!data.startsWith(source) && !source.startsWith(data)) { "Source and storage must be separate" } }
    /** Не раскрывает абсолютные пути при ошибке. */
    private fun fail(code: String, message: String, status: Int = 400): Nothing = throw WorkspaceFailure(code, message, status)
    /** Канонические UUID не позволяют подменить имя служебного файла. */
    fun uuid(value: String): String {
        if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value) fail("INVALID_ID", "Некорректный UUID.")
        return value
    }
    /** SHA-256 исходных UTF-8 байтов, включая переводы строк. */
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    /** Allowlist не зависит от указаний внутри документа или желания модели. */
    private fun allowed(path: String): Boolean = path == "README.md" ||
        (path.startsWith("docs/") && path.endsWith(".md")) ||
        (path.startsWith("AsciiArena/Assets/Prototype/") && path.endsWith(".cs"))
    /** Явно помеченная история до следующего заголовка не должна незаметно становиться текущей инструкцией. */
    private fun historicalBlocks(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n")
        val markers = Regex("(?imu)^.*(?:следующие абзацы[^\\n]*истори|ниже сохранено описание первой|ниже[^\\n]*отклонённой визуальной версии)[^\\n]*")
        val headings = Regex("(?m)^#{1,6} ")
        return markers.findAll(normalized).map { marker ->
            val end = headings.find(normalized, marker.range.last + 1)?.range?.first ?: normalized.length
            normalized.substring(marker.range.first, end)
        }.toList()
    }
    /** Отсекает обходы каталогов, скрытые сегменты, Windows ADS и нестандартные разделители. */
    fun checkPath(path: String) {
        if (path.length !in 1..240 || '\\' in path || ':' in path || path.startsWith("/") ||
            path.split('/').any { it.isBlank() || it.startsWith(".") || !Regex("[A-Za-z0-9_.-]+").matches(it) } ||
            !allowed(path)) fail("PATH_DENIED", "Путь не входит в разрешённую область проекта.")
    }
    /** Перечень не следует по symlink/junction и не включает Unity-ассеты или служебные каталоги. */
    private fun sourceFiles(): List<Path> {
        val found = mutableListOf<Path>()
        if (Files.isRegularFile(source.resolve("README.md"), LinkOption.NOFOLLOW_LINKS)) found.add(source.resolve("README.md"))
        listOf("docs", "AsciiArena/Assets/Prototype").forEach { dir ->
            val base = source.resolve(dir)
            if (Files.exists(base)) {
                if (!base.toRealPath().startsWith(source) || Files.isSymbolicLink(base)) fail("SOURCE_LINK", "Недопустимая ссылка в источниках.")
                Files.walk(base, 20).use { stream ->
                    stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach {
                        val path = source.relativize(it).toString().replace('\\', '/')
                        if (allowed(path)) { checkPath(path); found.add(it) }
                        if (found.size > 2000) fail("SOURCE_LIMIT", "В снимке допускается до 2000 текстовых файлов.")
                    }
                }
            }
        }
        return found.sortedBy { source.relativize(it).toString() }
    }
    /** Ограничивает размер и проверяет реальную цель пути. */
    private fun sourceFile(path: Path): SourceFile {
        val relative = source.relativize(path).toString().replace('\\', '/')
        checkPath(relative)
        if (!path.toRealPath().startsWith(source) || Files.isSymbolicLink(path)) fail("SOURCE_LINK", "Ссылка не может быть источником.")
        if (Files.size(path) > 512_000) fail("SOURCE_LIMIT", "Исходный файл превышает 512 КБ.")
        val text = Files.readString(path)
        return SourceFile(relative, hash(text), text)
    }
    /** Atomic rename делает JSON единицей commit; временный файл не считается готовой записью. */
    private fun write(path: Path, value: Any) {
        val bytes = mapper.writeValueAsBytes(value)
        require(bytes.size <= 30_000_000)
        val temp = Files.createTempFile(data, ".pending-", ".json")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    /** Повреждение не маскируется пустым состоянием и не вызывает перегенерацию. */
    private fun <T> read(path: Path, type: Class<T>): T {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) fail("NOT_FOUND", "Запись рабочей области не найдена.", 404)
        if (Files.size(path) > 30_000_000) fail("STORAGE_LIMIT", "Запись хранилища слишком велика.", 500)
        return mapper.readValue(Files.readAllBytes(path), type)
    }
    /** Создаёт стабильный снимок по явной команде; повтор UUID возвращает прежний снимок. */
    @Synchronized fun create(id: String): SnapshotInfo {
        uuid(id)
        val target = data.resolve("snapshot-$id.json")
        if (Files.exists(target)) return snapshot(id).info
        val first = sourceFiles()
        val files = first.map(::sourceFile)
        if (files.isEmpty()) fail("EMPTY_SOURCE", "Нет разрешённых исходников для снимка.")
        if (files.sumOf { it.text.toByteArray().size.toLong() } > 20_000_000) fail("SOURCE_LIMIT", "Снимок превышает 20 МБ.")
        val second = sourceFiles()
        if (first != second || files != second.map(::sourceFile)) fail("SOURCE_CHANGED", "Игра изменилась во время снимка. Повторите создание.", 409)
        val fingerprint = hash(files.joinToString("\n") { it.path + ":" + it.hash })
        val info = SnapshotInfo(id, fingerprint, files.size, Instant.now().toString())
        write(target, WorkspaceSnapshot(info, files))
        return info
    }
    /** Выдаёт метаданные без отправки всего проекта модели. */
    @Synchronized fun list(): List<SnapshotInfo> = Files.list(data).use { paths ->
        paths.filter { it.fileName.toString().startsWith("snapshot-") && it.toString().endsWith(".json") }
            .map { read(it, WorkspaceSnapshot::class.java).info }.toList().sortedByDescending { it.createdAt }
    }
    /** Старые запуски читают сохранённый снимок, не текущую рабочую копию. */
    fun snapshot(id: String): WorkspaceSnapshot = read(data.resolve("snapshot-${uuid(id)}.json"), WorkspaceSnapshot::class.java)
    /** Постраничный список путей, хешей и размеров. */
    fun files(id: String, offset: Int, limit: Int): Map<String, Any> {
        require(offset >= 0 && limit in 1..100)
        val all = snapshot(id).files
        return mapOf("total" to all.size, "offset" to offset, "files" to all.drop(offset).take(limit).map {
            mapOf("path" to it.path, "hash" to it.hash, "lines" to it.text.lines().size)
        }, "hasMore" to (offset + limit < all.size))
    }
    /** Буквальный поиск с явной пагинацией; это не семантический анализ C#. */
    fun search(id: String, query: String, offset: Int, limit: Int): Map<String, Any> {
        require(query.length in 1..120 && offset >= 0 && limit in 1..50)
        val matches = snapshot(id).files.flatMap { file -> file.text.lines().mapIndexedNotNull { index, line ->
            if (line.contains(query, ignoreCase = true)) mapOf("path" to file.path, "line" to index + 1,
                "text" to line.take(400), "hash" to file.hash) else null
        } }
        return mapOf("query" to query, "total" to matches.size, "offset" to offset,
            "matches" to matches.drop(offset).take(limit), "hasMore" to (offset + limit < matches.size))
    }
    /** Читает настоящий фрагмент и записывает подтверждение чтения для будущего артефакта. */
    @Synchronized fun file(id: String, run: String, path: String, start: Int, end: Int): Map<String, Any> {
        uuid(run); checkPath(path)
        val file = snapshot(id).files.find { it.path == path } ?: fail("NOT_FOUND", "Файл отсутствует в снимке.", 404)
        val lines = file.text.lines()
        if (start !in 1..lines.size || end < start || end - start >= 160)
            fail("READ_RANGE", "Укажите существующие строки: start_line от 1 до ${lines.size}, end_line >= start_line, максимум 160 строк за вызов.")
        val actualEnd = minOf(end, lines.size)
        val text = lines.subList(start - 1, actualEnd).joinToString("\n")
        if (text.length > 24_000) fail("READ_LIMIT", "Прочитайте меньший диапазон строк.")
        write(data.resolve("reads-$id-$run.json"), (reads(id, run) + FileEvidence(path, file.hash, start, actualEnd)).distinct())
        return mapOf("path" to path, "hash" to file.hash, "startLine" to start, "endLine" to actualEnd,
            "totalLines" to lines.size, "numberedText" to lines.subList(start - 1, actualEnd)
                .mapIndexed { index, line -> "${start + index}: $line" }.joinToString("\n"),
            "completeFile" to (start == 1 && actualEnd == lines.size),
            "warning" to if (historicalBlocks(file.text).isNotEmpty()) "Документ содержит явно помеченную историю. Эти блоки защищены от перезаписи; не выдавайте их за актуальные правила." else "")
    }
    /** Подтверждения принадлежат паре snapshot/run, а не всем предыдущим диалогам. */
    private fun reads(id: String, run: String): List<FileEvidence> {
        uuid(id); uuid(run)
        val path = data.resolve("reads-$id-$run.json")
        return if (Files.exists(path)) read(path, Array<FileEvidence>::class.java).toList() else emptyList()
    }
    /** Происхождение вычисляет сервер по реальным чтениям, а не по списку, угаданному моделью. */
    fun evidence(id: String, run: String): List<String> = reads(id, run).map { it.path }.distinct()
    /** Воспроизводимый unified diff полной замены с учётом последнего newline. */
    private fun diff(path: String, before: String?, after: String): String {
        fun lines(value: String) = value.removeSuffix("\n").split("\n").let { if (value.isEmpty()) emptyList() else it }
        val old = lines(before ?: ""); val new = lines(after)
        return buildString {
            appendLine("--- " + if (before == null) "/dev/null" else "a/$path")
            appendLine("+++ b/$path")
            appendLine("@@ -${if (old.isEmpty()) 0 else 1},${old.size} +${if (new.isEmpty()) 0 else 1},${new.size} @@")
            old.forEach { appendLine("-$it") }
            if (before != null && before.isNotEmpty() && !before.endsWith("\n")) appendLine("\\ No newline at end of file")
            new.forEach { appendLine("+$it") }
            if (after.isNotEmpty() && !after.endsWith("\n")) appendLine("\\ No newline at end of file")
        }
    }
    /** Сохраняет документ и patch в изолированном хранилище; исходники не перезаписываются. */
    @Synchronized fun propose(id: String, run: String, path: String, content: String,
        baseHash: String?, evidence: List<String>, create: Boolean): WorkspaceArtifact {
        uuid(run); checkPath(path)
        if (!(path == "README.md" || path.startsWith("docs/") && path.endsWith(".md"))) fail("WRITE_DENIED", "Изменять можно только Markdown-документы.")
        if (content.isBlank() || content.length > 40_000) fail("CONTENT_LIMIT", "Документ должен содержать от 1 до 40000 символов.")
        val snapshot = snapshot(id)
        val old = snapshot.files.find { it.path == path }
        if (create && old != null) fail("FILE_EXISTS", "Файл уже существует: используйте предложение изменения.", 409)
        if (!create && (old == null || old.hash != baseHash)) fail("BASE_CONFLICT", "Исходный хеш не совпадает со снимком.", 409)
        val normalized = content.replace("\r\n", "\n")
        val proposed = if (old != null && "\r\n" in old.text && old.text.replace("\r\n", "").indexOf('\n') < 0)
            normalized.replace("\n", "\r\n") else normalized
        if (old != null) {
            val beforeHistory = historicalBlocks(old.text)
            val afterHistory = historicalBlocks(proposed)
            if (beforeHistory != afterHistory) fail("HISTORY_PROTECTED", "Нельзя переписывать явно помеченные исторические блоки. Выберите актуальный документ или сохраните отдельный отчёт.")
        }
        val receipts = reads(id, run)
        if (evidence.distinct().size < 3 || !receipts.map { it.path }.containsAll(evidence))
            fail("EVIDENCE_REQUIRED", "Сначала прочитайте минимум 3 разных исходных файла в этом запуске. Список источников сформирует сервер.")
        if (!create) {
            var covered = 0
            receipts.filter { it.path == path && it.hash == old?.hash }.sortedBy { it.startLine }.forEach {
                if (it.startLine <= covered + 1) covered = maxOf(covered, it.endLine)
            }
            if (covered < old!!.text.lines().size) fail("BASE_NOT_READ", "Перед полной заменой прочитайте все строки исходного документа.")
        }
        if (old?.text == proposed) fail("NO_CHANGE", "Новое содержимое совпадает с исходным.")
        val artifactId = UUID.nameUUIDFromBytes("$id|$run|$path|${hash(proposed)}".toByteArray()).toString()
        val file = data.resolve("artifact-$artifactId.json")
        val artifact = if (Files.exists(file)) read(file, WorkspaceArtifact::class.java) else WorkspaceArtifact(
            artifactId, id, run, path, if (create) "CREATED_DOCUMENT" else "PROPOSED_CHANGE", old?.hash,
            proposed, diff(path, old?.text, proposed), evidence.distinct())
        if (!Files.exists(file)) {
            if (artifacts(id, run).size >= 10) fail("ARTIFACT_LIMIT", "В одном запуске допускается до 10 артефактов.")
            write(file, artifact)
        }
        materialize(data.resolve("$artifactId.md"), artifact.content)
        materialize(data.resolve("$artifactId.patch"), artifact.diff)
        val document = data.resolve("outputs").resolve(artifactId).resolve(path).normalize()
        require(document.startsWith(data.resolve("outputs")))
        Files.createDirectories(document.parent)
        require(document.parent.toRealPath().startsWith(data))
        materialize(document, artifact.content)
        return artifact
    }
    /** Производные файлы восстанавливаются из JSON без нового вызова LLM. */
    private fun materialize(path: Path, content: String) {
        val temp = Files.createTempFile(data, ".export-", ".tmp")
        try { Files.writeString(temp, content); Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temp) }
    }
    /** Возвращает артефакты запуска, в том числе при потерянном подтверждении tool call. */
    @Synchronized fun artifacts(id: String, run: String): List<WorkspaceArtifact> {
        uuid(id); uuid(run)
        return Files.list(data).use { paths -> paths.filter { it.fileName.toString().startsWith("artifact-") && it.toString().endsWith(".json") }
            .map { read(it, WorkspaceArtifact::class.java) }.filter { it.snapshotId == id && it.runId == run }.toList() }
    }
    /** Освобождает владение volume только при остановке процесса. */
    @PreDestroy fun close() { owner.release(); lockChannel.close() }
}
