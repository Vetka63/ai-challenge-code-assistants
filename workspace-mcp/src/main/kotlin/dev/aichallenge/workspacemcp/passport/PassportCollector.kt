package dev.aichallenge.workspacemcp.passport

import dev.aichallenge.workspace.contracts.*
import dev.aichallenge.workspacemcp.storage.WorkspaceStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import javax.imageio.ImageIO

/** Единственная файловая граница паспорта. Читает allowlist, ничего не исполняет в игре. */
@Component
class PassportCollector(private val store: WorkspaceStore, private val mapper: ObjectMapper,
    @Value("$"+"{WORKSPACE_SOURCE:/project}") sourcePath: String,
    @Value("$"+"{WORKSPACE_DATA:/app/workspaces}") dataPath: String) {
    private val source = Path.of(sourcePath).toRealPath()
    private val media = Path.of(dataPath).resolve("passport-media").also { Files.createDirectories(it) }.toRealPath()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safe(path: Path): Boolean {
        if (!path.normalize().startsWith(source)) return false
        var current = source
        for (part in source.relativize(path.normalize())) {
            if (part.toString().startsWith(".")) return false
            current = current.resolve(part)
            if (Files.isSymbolicLink(current) || !current.toRealPath().startsWith(source)) return false
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    }
    /** Только локальные Markdown-ссылки в art/playtests и PNG/JPEG из Assets/Screenshots. */
    private fun candidates(snapshot: WorkspaceSnapshot): Map<Path, List<String>> {
        val found = linkedMapOf<Path, MutableList<String>>()
        val readme = snapshot.files.find { it.path == "README.md" }
        val currentLinks = Regex("\\]\\((docs/[^) #]+\\.md)").findAll(readme?.text?.lines()?.take(100)?.joinToString("\n") ?: "")
            .map { it.groupValues[1] }.filterNot { it.contains("/research/") }.toList()
        val documents = (listOfNotNull(readme) + currentLinks.mapNotNull { path -> snapshot.files.find { it.path == path } } +
            snapshot.files.filter { it.path.endsWith(".md") }).distinctBy { it.path }
        documents.forEach { file ->
            Regex("!?\\[[^]]*]\\(([^)\\s]+)(?:\\s+[^)]*)?\\)").findAll(file.text).forEach { match ->
                val link = match.groupValues[1]
                if (':' !in link && '\\' !in link && !link.startsWith("/")) {
                    val path = source.resolve(file.path).parent.resolve(link).normalize()
                    val relative = runCatching { source.relativize(path).toString().replace('\\', '/') }.getOrDefault("")
                    if (relative.startsWith("art/playtests/") || relative.startsWith("AsciiArena/Assets/Screenshots/"))
                        found.getOrPut(path) { mutableListOf() }.add(file.path)
                }
            }
        }
        val directory = source.resolve("AsciiArena/Assets/Screenshots")
        if (Files.exists(directory) && !Files.isSymbolicLink(directory) && directory.toRealPath().startsWith(source))
            Files.list(directory).use { files -> files.sorted().forEach { found.getOrPut(it) { mutableListOf() } } }
        return found.filterKeys { it.fileName.toString().lowercase().let { n -> n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") } }
    }
    /** Проверяет заголовок и размеры без декодирования огромного изображения в память. */
    private fun inspect(bytes: ByteArray): Triple<String, Int, Int> {
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                reader.input = input
                val format = reader.formatName.lowercase()
                require(format in setOf("png", "jpeg", "jpg"))
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width in 1..8000 && height in 1..8000 && width.toLong() * height <= 24_000_000)
                return Triple(if (format == "png") "image/png" else "image/jpeg", width, height)
            } finally { reader.dispose() }
        }
    }
    private fun persist(path: Path, bytes: ByteArray) {
        if (Files.exists(path)) return
        val temp = Files.createTempFile(media, ".pending-", ".tmp")
        try { Files.write(temp, bytes); Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE) }
        finally { Files.deleteIfExists(temp) }
    }
    /** Двойное чтение исключает публикацию смеси версий во время разработки. */
    @Synchronized fun collect(): PassportCollection {
        val snapshot = store.capture()
        val warnings = mutableListOf<String>()
        val images = mutableListOf<PassportImage>()
        val observed = mutableListOf<Pair<Path, String>>()
        val candidates = candidates(snapshot)
        var total = 0L
        candidates.entries.take(12).forEach { (path, linked) ->
            try {
                require(safe(path) && Files.size(path) in 1..8_000_000)
                val bytes = Files.readAllBytes(path)
                require(bytes.size <= 8_000_000 && total + bytes.size <= 32_000_000)
                val (mime, width, height) = inspect(bytes)
                val id = hash(bytes)
                val image = PassportImage(id, source.relativize(path).toString().replace('\\', '/'), mime,
                    width, height, bytes.size.toLong(), linked.distinct(), Instant.now().toString())
                persist(media.resolve("$id.bin"), bytes)
                val metadata = media.resolve("$id.json")
                persist(metadata, mapper.writeValueAsBytes(image))
                // Первое время импорта неизменно, даже если файл встречается в новых версиях.
                val first = mapper.readValue(Files.readAllBytes(metadata), PassportImage::class.java)
                images += image.copy(importedAt = first.importedAt)
                observed += path to id
                total += bytes.size
            } catch (_: Exception) { warnings += "Растр пропущен (путь, формат или лимит): ${source.relativize(path).toString().take(240)}" }
        }
        if (candidates.size > 12) warnings += "Галерея ограничена первыми 12 разрешёнными изображениями."
        if (store.capture().info.fingerprint != snapshot.info.fingerprint || candidates(store.snapshot(snapshot.info.id)) != candidates ||
            observed.any { (path, id) -> !safe(path) || Files.size(path) > 8_000_000 || hash(Files.readAllBytes(path)) != id })
            throw WorkspaceFailure("SOURCE_CHANGED", "Игра изменилась во время сбора. Модель не вызывалась.", 409)
        val fingerprint = hash((snapshot.info.fingerprint + "\n" + images.joinToString("\n") { "${it.path}:${it.id}:${it.linkedFrom}" }).toByteArray())
        return PassportCollection(snapshot, fingerprint, images, warnings, Instant.now().toString())
    }
    /** Только сохранённый content-addressed растр, без path-параметра исходной игры. */
    fun image(id: String): Pair<PassportImage, ByteArray> {
        if (!Regex("[a-f0-9]{64}").matches(id)) throw WorkspaceFailure("INVALID_IMAGE", "Некорректный ID изображения.")
        val metadata = media.resolve("$id.json")
        if (!Files.isRegularFile(metadata)) throw WorkspaceFailure("NOT_FOUND", "Изображение не найдено.", 404)
        val image = mapper.readValue(Files.readAllBytes(metadata), PassportImage::class.java)
        return image to Files.readAllBytes(media.resolve("$id.bin")).also { require(hash(it) == id) }
    }
}
