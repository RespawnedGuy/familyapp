package io.github.respawnedguy.familyapp.data

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Threat model TE-11: the transport sends the key only as a header, reads bounded, maps errors. */
class HttpClaudeRecipeAiTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val url = "http://127.0.0.1:${server.address.port}/v1/messages"

    @AfterTest
    fun stop() = server.stop(0)

    private fun answer(status: Int, body: String, seen: MutableMap<String, String?> = mutableMapOf()) {
        server.createContext("/v1/messages") { ex ->
            seen["key"] = ex.requestHeaders.getFirst("x-api-key")
            seen["version"] = ex.requestHeaders.getFirst("anthropic-version")
            seen["beta"] = ex.requestHeaders.getFirst("anthropic-beta")
            seen["body"] = ex.requestBody.readBytes().decodeToString()
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun recipeComesBackWithTheRightHeaders() {
        val seen = mutableMapOf<String, String?>()
        val text = """{\"title\":\"Brot\",\"ingredients\":[\"500 g Mehl\"],\"steps\":[\"Backen\"]}"""
        answer(200, """{"stop_reason":"end_turn","content":[{"type":"text","text":"$text"}]}""", seen)
        val outcome = assertIs<AiOutcome.Ok>(HttpClaudeRecipeAi("test-key", url).extract("Brot backen"))
        assertEquals("Brot", outcome.recipe.title)
        assertEquals("test-key", seen["key"])
        assertEquals(ClaudeRecipeRequest.API_VERSION, seen["version"])
        assertEquals(ClaudeRecipeRequest.BETA, seen["beta"])
        assertTrue(seen["body"]!!.contains("Brot backen"))
        assertTrue(!seen["body"]!!.contains("test-key"), "the key never goes into the body")
    }

    @Test
    fun wrongKeyIsSaidPlainly() {
        answer(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        val failed = assertIs<AiOutcome.Failed>(HttpClaudeRecipeAi("falsch", url).extract("x"))
        assertTrue(failed.reason.startsWith("API-Schlüssel ungültig"))
    }

    @Test
    fun noServerMeansNoConnection() {
        val closed = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = closed.address.port
        closed.stop(0)
        val failed = assertIs<AiOutcome.Failed>(HttpClaudeRecipeAi("k", "http://127.0.0.1:$port/v1/messages", 2_000).extract("x"))
        assertEquals("Keine Verbindung zur KI.", failed.reason)
    }
}
