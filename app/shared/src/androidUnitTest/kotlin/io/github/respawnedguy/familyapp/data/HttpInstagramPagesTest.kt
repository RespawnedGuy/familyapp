package io.github.respawnedguy.familyapp.data

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model TE-9 (fixed host and path), TE-10 (size, redirects, type). A local server stands in for Instagram. */
class HttpInstagramPagesTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val requests = mutableListOf<String>()

    private fun serve(path: String, status: Int = 200, type: String = "text/html; charset=utf-8", body: ByteArray, location: String? = null, chunked: Boolean = false) {
        server.createContext(path) { ex ->
            requests += ex.requestURI.path
            ex.responseHeaders.add("Content-Type", type)
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(status, if (chunked) 0 else if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun normalPageIsReadWithoutCookies() {
        var cookie: String? = "unset"
        server.createContext("/p/C0de/embed/captioned/") { ex ->
            cookie = ex.requestHeaders.getFirst("Cookie")
            val body = "<div class=\"Caption\">Brot äöü</div>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        assertEquals("<div class=\"Caption\">Brot äöü</div>", HttpInstagramPages(base).get("/p/C0de/embed/captioned/"))
        assertNull(cookie)
    }

    @Test
    fun loginRedirectIsNotFollowed() {
        serve("/p/C0de/", status = 302, body = ByteArray(0), location = "$base/accounts/login/")
        serve("/accounts/login/", body = "<html>login</html>".toByteArray())
        assertNull(HttpInstagramPages(base).get("/p/C0de/"))
        assertEquals(listOf("/p/C0de/"), requests)
    }

    @Test
    fun onlyHtmlAndOnlyStatus200() {
        serve("/p/Json/", type = "application/json", body = "{}".toByteArray())
        serve("/p/Gone/", status = 404, body = "<html></html>".toByteArray())
        assertNull(HttpInstagramPages(base).get("/p/Json/"))
        assertNull(HttpInstagramPages(base).get("/p/Gone/"))
    }

    @Test
    fun pagesOverTheLimitAreDropped() {
        serve("/p/Big/", body = ByteArray(2_000) { 'a'.code.toByte() })
        serve("/p/Chunked/", body = ByteArray(2_000) { 'a'.code.toByte() }, chunked = true)
        serve("/p/Fits/", body = ByteArray(1_000) { 'a'.code.toByte() }, chunked = true)
        val pages = HttpInstagramPages(base, maxBytes = 1_000)
        assertNull(pages.get("/p/Big/"), "announced length over the limit")
        assertNull(pages.get("/p/Chunked/"), "no length announced, stops right after the limit")
        assertEquals(1_000, pages.get("/p/Fits/")?.length)
    }

    @Test
    fun onlyThePathsInstagramLinkBuildsAreRequested() {
        serve("/", body = "<html></html>".toByteArray())
        val pages = HttpInstagramPages(base)
        listOf("/accounts/login/", "/p/C0de/../../x", "/p/C0de/?__a=1", "//evil.example/p/C0de/", "/p/C0de")
            .forEach { assertNull(pages.get(it), it) }
        assertTrue(requests.isEmpty(), "nothing was sent: $requests")
    }

    @Test
    fun unreachableServerGivesNull() {
        val closed = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = closed.address.port
        closed.stop(0)
        assertNull(HttpInstagramPages("http://127.0.0.1:$port", timeoutMs = 2_000).get("/p/C0de/"))
    }
}
