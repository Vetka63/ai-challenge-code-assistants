package dev.aichallenge.projectmcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Контекст checkout; fingerprint включает README/docs, в том числе untracked. */
data class ProjectContext(val project: String, val branch: String?, val sha: String,
    val dirty: Boolean, val fingerprint: String, val fileCount: Int)
/** Точный текст документа и его хеш для неизменяемого входа индексации. */
data class ProjectDocument(val path: String, val hash: String, val text: String, val status: String)
/** Согласованный снимок документов и Git-контекста. */
data class ProjectSnapshot(val context: ProjectContext, val documents: List<ProjectDocument>)

/** Граница файловой системы: фиксированный корень, allowlist и ограничения объёма. */
@Service
class ProjectFiles(@Value("$" + "{PROJECT_ROOT:.}") rootPath: String) {
    private val root = Path.of(rootPath).toRealPath()

    /** Вычисляет идентификатор версии содержимого. */
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Разрешает только документацию и C# прототипа, а не произвольные файлы. */
    private fun allowed(path: String) = path == "README.md" ||
        (path.startsWith("docs/") && path.endsWith(".md")) ||
        (path.startsWith("AsciiArena/Assets/Prototype/") && path.endsWith(".cs"))

    /** Проверяет нормализацию и реальную цель symlink/junction. */
    fun resolve(path: String): Path {
        require(path.length <= 240 && '\\' !in path && !Path.of(path).isAbsolute && allowed(path)) { "Путь не разрешён" }
        require(Path.of(path).normalize().toString().replace('\\', '/') == path && ".." !in Path.of(path).map { it.toString() }) { "Путь содержит обход каталога" }
        val result = root.resolve(path).normalize()
        require(result.startsWith(root) && Files.isRegularFile(result)) { "Файл не найден или не разрешён" }
        val actual = result.toRealPath()
        require(actual.startsWith(root) && allowed(root.relativize(actual).toString().replace('\\', '/'))) { "Ссылка выходит за разрешённую область" }
        require(Files.size(result) <= 512_000) { "Файл превышает 512 КБ" }
        return result
    }

    /** Читает проверенный текстовый файл без исполнения его содержимого. */
    fun read(path: String): ProjectDocument {
        val text = Files.readString(resolve(path))
        return ProjectDocument(path, hash(text), text, when {
            path.startsWith("docs/research/") -> "PROPOSAL_OR_RESEARCH"
            path == "README.md" || path.endsWith("project-context.md") || path.endsWith("four-blocks-prototype.md") -> "MIXED_HISTORY"
            else -> "PROJECT_DOCUMENT"
        })
    }

    /** Обходит только allowlist-каталоги, не следуя по символическим каталогам. */
    fun files(includeCode: Boolean = false): List<String> {
        val result = mutableListOf<String>()
        if (Files.isRegularFile(root.resolve("README.md"))) result += "README.md"
        val dirs = listOf("docs") + if (includeCode) listOf("AsciiArena/Assets/Prototype") else emptyList()
        dirs.forEach { dir ->
            val directory = root.resolve(dir)
            if (Files.isDirectory(directory) && directory.toRealPath().startsWith(root)) {
                Files.walk(directory, 12).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.limit(5000).forEach {
                        val name = root.relativize(it).toString().replace('\\', '/')
                        if (allowed(name)) { resolve(name); result += name }
                    }
                }
            }
        }
        require(result.size <= 500) { "Превышен лимит 500 файлов" }
        return result.distinct().sorted()
    }

    /** Вызывает только внутренние константные read-only команды Git. */
    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
            "-c", "safe.directory=$root", "-C", root.toString()) + args).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val output = executor.submit<String> {
                val bytes = process.inputStream.readNBytes(1_000_001)
                require(bytes.size <= 1_000_000) { "Слишком большой ответ Git" }
                bytes.toString(Charsets.UTF_8)
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw IllegalStateException("Git не ответил за 10 секунд")
            val text = output.get(1, TimeUnit.SECONDS)
            require(process.exitValue() == 0) { "Не удалось прочитать Git-контекст" }
            return text.trim()
        } finally { process.destroyForcibly(); executor.shutdownNow() }
    }

    /** Повторная проверка защищает от смешивания версий при параллельной разработке. */
    fun snapshot(): ProjectSnapshot {
        repeat(2) {
            val sha = git("rev-parse", "HEAD")
            val branch = git("rev-parse", "--abbrev-ref", "HEAD").takeUnless { it == "HEAD" }
            val documents = files().map(::read)
            require(documents.sumOf { it.text.length } <= 2_000_000) { "Корпус превышает 2 млн символов" }
            val fingerprint = hash(documents.joinToString("\n") { "${it.path}:${it.hash}" })
            val check = files().map(::read)
            if (git("rev-parse", "HEAD") == sha && hash(check.joinToString("\n") { "${it.path}:${it.hash}" }) == fingerprint) {
                return ProjectSnapshot(ProjectContext("ai-challenge-game", branch, sha,
                    git("status", "--porcelain=v1", "--untracked-files=no").isNotBlank(), fingerprint, documents.size), documents)
            }
        }
        throw IllegalStateException("Документы меняются во время чтения; повторите запрос")
    }

    /** Читает актуальную ветку и хеши документов тем же механизмом снимка. */
    fun context() = snapshot().context

    /** Литеральный поиск с лимитом результатов, без пользовательского regex. */
    fun search(query: String): List<Map<String, Any>> {
        require(query.length in 2..120) { "Поиск: от 2 до 120 символов" }
        return files(true).flatMap { path ->
            read(path).text.lines().mapIndexedNotNull { index, line ->
                if (line.contains(query, ignoreCase = true)) mapOf("path" to path, "line" to index + 1, "text" to line.take(500)) else null
            }.take(20)
        }.take(60)
    }
}
