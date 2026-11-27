package io.github.respawnedguy.familyapp.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Threat model TE-11, ADR 0008: a local server stands in for Google's API. */
class HttpGeminiRecipeAiTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val keys: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var generated = ""
    private val recipe = """{"title":"Shakshuka","ingredients":["4 Eier"],"steps":["Braten"]}"""
    private val answer = """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(recipe)}}]},"finishReason":"STOP"}]}"""

    @AfterTest
    fun stop() = server.stop(0)

    private fun HttpExchange.reply(status: Int, body: String, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun generateAnswers(status: Int = 200, body: String = answer) {
        server.createContext(GeminiRecipeRequest.GENERATE_PATH) { ex ->
            calls += "generate"
            keys += ex.requestHeaders.getFirst("x-goog-api-key")
            generated = ex.requestBody.readBytes().decodeToString()
            ex.reply(status, body)
        }
    }

    private fun video(bytes: Int): VideoClip {
        val file = File.createTempFile("clip", ".mp4").apply { writeBytes(ByteArray(bytes) { 1 }); deleteOnExit() }
        return VideoClip(file.path, file.length(), "video/mp4")
    }

    @Test
    fun captionGoesWithTheKeyOnlyInTheHeader() {
        generateAnswers()
        val ok = assertIs<AiOutcome.Ok>(HttpGeminiRecipeAi("AIzaTest", base).extract("Shakshuka für 2"))
        assertEquals("Shakshuka", ok.recipe.title)
        assertEquals(listOf<String?>("AIzaTest"), keys)
        assertTrue(generated.contains("Shakshuka für 2"))
        assertFalse(generated.contains("AIzaTest"), "the key never goes into the body")
        assertTrue(HttpGeminiRecipeAi("k", base).readsVideo)
    }

    @Test
    fun smallVideoGoesInline() {
        generateAnswers()
        val clip = video(1_000)
        assertIs<AiOutcome.Ok>(HttpGeminiRecipeAi("AIzaTest", base).extract("Reel", clip))
        assertTrue(generated.contains("\"inline_data\""))
        assertTrue(generated.contains(Base64.getEncoder().encodeToString(ByteArray(1_000) { 1 })))
        assertEquals(listOf("generate"), calls)
    }

    private fun filesApi(uploadUrl: String = "$base/upload/session-1", states: MutableList<String> = mutableListOf("PROCESSING", "ACTIVE")) {
        var uploaded = 0
        server.createContext(GeminiRecipeRequest.UPLOAD_PATH) { ex ->
            calls += "start:" + ex.requestHeaders.getFirst("X-Goog-Upload-Header-Content-Length")
            keys += ex.requestHeaders.getFirst("x-goog-api-key")
            ex.reply(200, "{}", "X-Goog-Upload-URL" to uploadUrl)
        }
        server.createContext("/upload/session-1") { ex ->
            uploaded = ex.requestBody.readBytes().size
            calls += "upload:$uploaded:" + ex.requestHeaders.getFirst("X-Goog-Upload-Command")
            keys += ex.requestHeaders.getFirst("x-goog-api-key")
            ex.reply(200, """{"file":{"name":"files/abc-1","uri":"$base/v1beta/files/abc-1","state":"PROCESSING"}}""")
        }
        server.createContext("/v1beta/files/abc-1") { ex ->
            calls += ex.requestMethod
            if (ex.requestMethod == "DELETE") {
                ex.reply(200, "{}")
            } else {
                val state = states.removeAt(0)
                ex.reply(200, """{"name":"files/abc-1","uri":"$base/v1beta/files/abc-1","state":"$state"}""")
            }
        }
    }

    @Test
    fun largeVideoIsUploadedAwaitedUsedAndDeleted() {
        generateAnswers()
        filesApi()
        val big = (GeminiRecipeRequest.MAX_INLINE_BYTES + 10).toInt()
        val ai = HttpGeminiRecipeAi("AIzaTest", base, 5_000, 1)
        assertIs<AiOutcome.Ok>(ai.extract("Reel", video(big)))
        assertEquals(listOf("start:$big", "upload:$big:upload, finalize", "GET", "GET", "generate", "DELETE"), calls)
        assertEquals(listOf<String?>("AIzaTest", null, "AIzaTest"), keys.take(3), "no key on Google's upload address")
        assertTrue(generated.contains("\"file_uri\":\"$base/v1beta/files/abc-1\""))
    }

    @Test
    fun failedProcessingIsSaidAndTheFileStillDeleted() {
        generateAnswers()
        filesApi(states = mutableListOf("FAILED"))
        val big = (GeminiRecipeRequest.MAX_INLINE_BYTES + 1).toInt()
        val failed = assertIs<AiOutcome.Failed>(HttpGeminiRecipeAi("AIzaTest", base, 5_000, 1).extract("Reel", video(big)))
        assertEquals("Google konnte das Video nicht verarbeiten.", failed.reason)
        assertEquals("DELETE", calls.last())
        assertFalse("generate" in calls)
    }

    @Test
    fun uploadAddressOnAnotherHostIsRefused() {
        filesApi(uploadUrl = "http://evil.example/upload")
        val big = (GeminiRecipeRequest.MAX_INLINE_BYTES + 1).toInt()
        assertIs<AiOutcome.Failed>(HttpGeminiRecipeAi("AIzaTest", base, 5_000, 1).extract("Reel", video(big)))
        assertEquals(listOf("start:$big"), calls, "the video never leaves for a host Google's answer names")
    }

    @Test
    fun wrongKeyAndNoServerAreSaid() {
        generateAnswers(400, """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""")
        val failed = assertIs<AiOutcome.Failed>(HttpGeminiRecipeAi("AIzaFalsch", base).extract("x"))
        assertTrue(failed.reason.startsWith("API-Schlüssel ungültig"))

        val closed = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = closed.address.port
        closed.stop(0)
        val none = assertIs<AiOutcome.Failed>(HttpGeminiRecipeAi("k", "http://127.0.0.1:$port", 1_000).extract("x"))
        assertEquals("Keine Verbindung zur KI.", none.reason)
    }
}
