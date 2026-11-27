package io.github.respawnedguy.familyapp.data

import com.sun.net.httpserver.HttpServer
import io.github.respawnedguy.familyapp.data.offers.HttpAnswer
import io.github.respawnedguy.familyapp.data.offers.MarketHttp
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model AN-1 (size, redirects), AN-5 (honest request, refusal reported). A local server stands in for a market. */
class HttpMarketsTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val http = HttpMarkets({ it.host == "127.0.0.1" }, timeoutMs = 5_000)
    private val requests = mutableListOf<String>()
    private var headers: Map<String, String?> = emptyMap()

    private fun serve(path: String, status: Int = 200, body: ByteArray, location: String? = null) {
        server.createContext(path) { ex ->
            requests += ex.requestURI.path
            headers = mapOf(
                "User-Agent" to ex.requestHeaders.getFirst("User-Agent"),
                "Cookie" to ex.requestHeaders.getFirst("Cookie"),
                "Accept" to ex.requestHeaders.getFirst("Accept"),
            )
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun an5_normalAnswerWithAnHonestLabelAndNoCookies() {
        serve("/angebote", body = "{\"data\":[]}".toByteArray())
        assertEquals(HttpAnswer.Ok("{\"data\":[]}"), http.get("$base/angebote", MarketHttp.Kind.JSON))
        assertEquals(HttpMarkets.USER_AGENT, headers["User-Agent"])
        assertTrue(headers["User-Agent"]!!.startsWith("FamilyApp"))
        assertNull(headers["Cookie"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun an5_refusalsAreReportedNotWorkedAround() {
        listOf(401, 403, 429).forEach { code ->
            serve("/nein$code", status = code, body = "no".toByteArray())
            assertEquals(HttpAnswer.Refused(code), http.get("$base/nein$code", MarketHttp.Kind.HTML))
        }
        assertEquals(3, requests.size) // one request each, no second attempt
    }

    @Test
    fun an5_challengePageCountsAsRefusal() {
        serve("/check", body = "<html><script src=\"/cdn-cgi/challenge-platform/x.js\"></script></html>".toByteArray())
        assertEquals(HttpAnswer.Refused(200), http.get("$base/check", MarketHttp.Kind.HTML))
        assertTrue(HttpMarkets.isChallenge("<div id=\"px-captcha\"></div>"))
        assertTrue(!HttpMarkets.isChallenge("<html>Grünkohl 1,49 €</html>"))
    }

    @Test
    fun an1_redirectsAreNotFollowed() {
        serve("/weiter", status = 302, body = ByteArray(0), location = "$base/ziel")
        serve("/ziel", body = "ziel".toByteArray())
        assertEquals(HttpAnswer.Failed("HTTP 302"), http.get("$base/weiter", MarketHttp.Kind.HTML))
        assertEquals(listOf("/weiter"), requests)
    }

    @Test
    fun an1_tooLargeAnswersAreDropped() {
        serve("/gross", body = ByteArray(MarketHttp.Kind.JSON.maxBytes + 1) { 'a'.code.toByte() })
        assertIs<HttpAnswer.Failed>(http.get("$base/gross", MarketHttp.Kind.JSON))
    }

    @Test
    fun serverErrorsAndForeignHostsAreFailures() {
        serve("/kaputt", status = 503, body = "x".toByteArray())
        assertEquals(HttpAnswer.Failed("HTTP 503"), http.get("$base/kaputt", MarketHttp.Kind.JSON))
        assertEquals(HttpAnswer.Failed("Adresse nicht erlaubt"), HttpMarkets().get("$base/kaputt", MarketHttp.Kind.JSON))
        assertEquals(HttpAnswer.Failed("Adresse nicht erlaubt"), HttpMarkets().get("http://www.lidl.de/", MarketHttp.Kind.HTML))
        assertEquals(HttpAnswer.Failed("Adresse nicht erlaubt"), http.get("kein url", MarketHttp.Kind.HTML))
    }
}
