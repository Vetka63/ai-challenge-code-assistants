package dev.aichallenge.assistants

import dev.aichallenge.assistants.common.AssistantFailure
import dev.aichallenge.assistants.review.config.ReviewConfig
import dev.aichallenge.assistants.review.github.*
import dev.aichallenge.assistants.review.models.*
import dev.aichallenge.assistants.review.policy.*
import dev.aichallenge.assistants.review.service.reviewMarkdown
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.*
import java.net.URI
import java.io.ByteArrayOutputStream
import java.util.zip.*

/** Проверка реального REST-адаптера на fake transport: ни одного внешнего запроса/комментария. */
class ReviewGitHubTest {
    @TempDir lateinit var dir: Path
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val repo = "Vetka63/ai-challenge-game"
    private val input = ReviewRequest(repo, 7, "a".repeat(40), "b".repeat(40), 42)
    private data class Call(val uri: URI, val method: String, val token: String?, val body: String?)
    private inner class Transport : GitHubTransport {
        val calls = mutableListOf<Call>()
        var handler: (Call) -> GitHubResponse = { error("Unexpected request: " + it.uri.path) }
        override fun send(uri: URI, method: String, token: String?, body: String?, limit: Int): GitHubResponse {
            val call = Call(uri, method, token, body); calls += call; return handler(call)
        }
    }
    private fun api(transport: Transport, hasToken: Boolean = true): GitHubApi {
        val path = dir.resolve("token")
        if (hasToken) Files.writeString(path, "test-placeholder")
        val config = ReviewConfig(repo, "Vetka63", path.toString(), "ai-review.yml")
        return GitHubApi(config, ReviewInputPolicy(config), mapper, transport)
    }
    private fun json(value: Any) = GitHubResponse(200, mapper.writeValueAsBytes(value))
    private fun zip(name: String = "review-request.json", text: String = mapper.writeValueAsString(input)): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry(name)); it.write(text.toByteArray()); it.closeEntry() }
        return bytes.toByteArray()
    }
    private fun pull() = mapOf("number" to 7, "title" to "Test", "body" to "", "state" to "open", "changed_files" to 1,
        "html_url" to "https://github.com/" + repo + "/pull/7", "user" to mapOf("login" to "Vetka63"),
        "base" to mapOf("sha" to input.baseSha, "repo" to mapOf("full_name" to repo)),
        "head" to mapOf("sha" to input.headSha, "repo" to mapOf("full_name" to repo)))
    @Test fun tokenIsNotForwardedToArtifactRedirect() {
        val transport = Transport()
        transport.handler = { call -> if (call.uri.host == "api.github.com")
            GitHubResponse(302, byteArrayOf(), "https://results.blob.core.windows.net/artifact?signature=test")
            else GitHubResponse(200, zip()) }
        assertEquals(input, api(transport).manifest(1))
        assertEquals("test-placeholder", transport.calls[0].token)
        assertTrue(transport.calls[1].token == null)
    }
    @Test fun arbitraryRedirectIsRejectedBeforeSecondRequest() {
        val transport = Transport(); transport.handler = { GitHubResponse(302, byteArrayOf(), "http://127.0.0.1:8080/secret") }
        assertThrows(IllegalArgumentException::class.java) { api(transport).manifest(1) }
        assertEquals(1, transport.calls.size)
    }
    @Test fun zipTraversalIsNeverExtracted() {
        val transport = Transport(); transport.handler = { GitHubResponse(200, zip("../review-request.json")) }
        assertThrows(IllegalArgumentException::class.java) { api(transport).manifest(1) }
        assertFalse(Files.exists(dir.resolve("review-request.json")))
    }
    @Test fun oversizedManifestIsRejected() {
        val transport = Transport(); transport.handler = { GitHubResponse(200, zip(text = "x".repeat(20000))) }
        assertThrows(IllegalArgumentException::class.java) { api(transport).manifest(1) }
    }
    @Test fun missingTokenDoesNotMakeAnHttpRequest() {
        val transport = Transport()
        val failure = assertThrows(AssistantFailure::class.java) { api(transport, false).verifyConnection() }
        assertEquals("GITHUB_TOKEN_MISSING", failure.code); assertTrue(transport.calls.isEmpty())
    }
    @Test fun httpErrorDoesNotExposeProviderBodyOrToken() {
        val transport = Transport(); transport.handler = { GitHubResponse(401, "private response test-placeholder".toByteArray()) }
        val failure = assertThrows(AssistantFailure::class.java) { api(transport).verifyConnection() }
        assertFalse(failure.message!!.contains("test-placeholder")); assertEquals("GITHUB_HTTP_401", failure.code)
    }
    @Test fun successfulWorkflowProducesOneVerifiedDeliveryAndSkipsSeenRun() {
        val run = mapOf("id" to 42, "run_attempt" to 1, "event" to "pull_request", "conclusion" to "success",
            "path" to ".github/workflows/ai-review.yml", "head_repository" to mapOf("full_name" to repo),
            "actor" to mapOf("login" to "Vetka63"), "pull_requests" to listOf(mapOf("number" to 7, "head" to mapOf("sha" to input.headSha))))
        val transport = Transport()
        transport.handler = { call -> when {
            call.uri.path.endsWith("/runs") -> json(mapOf("workflow_runs" to listOf(run)))
            call.uri.path.endsWith("/artifacts") -> json(mapOf("artifacts" to listOf(mapOf("id" to 99, "name" to "ai-review-request", "expired" to false, "size_in_bytes" to 1000))))
            call.uri.path.endsWith("/zip") -> GitHubResponse(200, zip())
            call.uri.path.endsWith("/pulls/7") -> json(pull())
            else -> error("Unexpected")
        } }
        val api = api(transport)
        assertEquals(input, api.discover(emptySet()).deliveries.single().request)
        val before = transport.calls.size
        assertTrue(api.discover(setOf("42:1")).deliveries.isEmpty())
        assertEquals(before + 1, transport.calls.size)
    }
    @Test fun existingCommentMarkerPreventsDuplicatePublication() {
        val job = ReviewJob("job-id", input, "ACTION")
        val transport = Transport()
        transport.handler = { call ->
            if (call.uri.path.endsWith("/pulls/7")) json(pull())
            else json(listOf(mapOf("body" to "<!-- ai-review:job-id -->\nReport", "user" to mapOf("login" to "Vetka63"), "html_url" to "https://github.com/comment")))
        }
        assertEquals("https://github.com/comment", api(transport).publish(job, reviewMarkdown(job)))
        assertTrue(transport.calls.none { it.method == "POST" })
    }
    @Test fun snapshotUsesExactGitTreesAndMarksTruncatedPatch() {
        val path = "AsciiArena/Assets/Prototype/Save.cs"
        val transport = Transport()
        transport.handler = { call -> when {
            call.uri.path.endsWith("/pulls/7") -> json(pull())
            call.uri.path.endsWith("/files") -> json(listOf(mapOf("filename" to path, "status" to "modified",
                "patch" to "@@ -1 +1 @@\n-old\n+new", "additions" to 2, "deletions" to 1)))
            call.uri.path.contains("/git/trees/") -> json(mapOf("truncated" to false, "tree" to listOf(
                mapOf("path" to "README.md", "type" to "blob", "size" to 50, "sha" to "c".repeat(40)),
                mapOf("path" to path, "type" to "blob", "size" to 20, "sha" to "d".repeat(40)))))
            call.uri.path.contains("/git/blobs/") -> json(mapOf("encoding" to "base64",
                "content" to java.util.Base64.getEncoder().encodeToString("Project source rules".toByteArray())))
            else -> error("Unexpected snapshot request")
        } }
        val result = api(transport).snapshot(input)
        assertTrue(result.files.isEmpty())
        assertTrue(result.limitations.any { it.contains("неполный patch") })
        assertTrue(result.documents.any { it.side == "BASE" && it.sha == input.baseSha })
        assertTrue(result.documents.any { it.side == "HEAD" && it.sha == input.headSha })
        assertTrue(transport.calls.none { it.method != "GET" })
    }
    @Test fun staleDeliveryDoesNotBecomeAnAnalysis() {
        val run = mapOf("id" to 42, "run_attempt" to 1, "event" to "pull_request", "conclusion" to "success",
            "path" to ".github/workflows/ai-review.yml", "head_repository" to mapOf("full_name" to repo),
            "actor" to mapOf("login" to "Vetka63"), "pull_requests" to emptyList<Any>())
        val transport = Transport()
        transport.handler = { call -> when {
            call.uri.path.endsWith("/runs") -> json(mapOf("workflow_runs" to listOf(run)))
            call.uri.path.endsWith("/artifacts") -> json(mapOf("artifacts" to listOf(mapOf("id" to 99, "name" to "ai-review-request", "expired" to false, "size_in_bytes" to 1000))))
            call.uri.path.endsWith("/zip") -> GitHubResponse(200, zip(text = mapper.writeValueAsString(input.copy(headSha = "e".repeat(40)))))
            call.uri.path.endsWith("/pulls/7") -> json(pull())
            else -> error("Unexpected")
        } }
        val result = api(transport).discover(emptySet())
        assertTrue(result.deliveries.isEmpty()); assertEquals(listOf("42:1"), result.seen)
        assertTrue(result.warnings.isNotEmpty())
    }
}
