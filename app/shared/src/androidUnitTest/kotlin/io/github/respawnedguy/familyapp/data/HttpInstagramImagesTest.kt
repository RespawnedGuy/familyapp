package io.github.respawnedguy.familyapp.data

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model TE-13: only allowed addresses, only real JPEG/PNG/WebP files, bounded, no redirects, no cookies. */
class HttpInstagramImagesTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val requests = mutableListOf<String>()

    @AfterTest
    fun stop() = server.stop(0)

    private fun serve(path: String, status: Int = 200, type: String = "image/jpeg", body: ByteArray = jpeg(), location: String? = null) {
        server.createContext(path) { ex ->
            requests += ex.requestURI.path + "|" + ex.requestHeaders.getFirst("Cookie")
            ex.responseHeaders.add("Content-Type", type)
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
    }

    private fun jpeg(size: Int = 64) = ByteArray(size).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    private fun images(max: Long = 1_000) = HttpInstagramImages({ it.startsWith(base) }, max, 2_000)

    @Test
    fun coverIsDownloadedWithoutCookies() {
        val body = jpeg()
        serve("/cover.jpg", type = "image/jpeg; charset=binary", body = body)
        assertContentEquals(body, images().download("$base/cover.jpg"))
        assertEquals(listOf("/cover.jpg|null"), requests)
    }

    @Test
    fun onlyAllowedAddressesAreFetched() {
        serve("/cover.jpg")
        assertNull(HttpInstagramImages().download("$base/cover.jpg"), "the real check allows only Instagram's CDN")
        assertTrue(requests.isEmpty())
    }

    @Test
    fun wrongTypeErrorRedirectAndTooBigAreRefused() {
        serve("/page", type = "text/html")
        serve("/svg", type = "image/svg+xml", body = "<svg/>".encodeToByteArray())
        serve("/gone", status = 404)
        serve("/moved", status = 302, location = "$base/cover.jpg")
        serve("/big", body = jpeg(2_000))
        listOf("/page", "/svg", "/gone", "/moved", "/big").forEach { assertNull(images().download("$base$it"), it) }
        assertFalse(requests.any { it.startsWith("/cover.jpg") }, "redirect not followed")
    }

    @Test
    fun contentMustReallyBeAnImage() {
        serve("/fake.jpg", body = "<html>not a picture</html>".encodeToByteArray())
        assertNull(images().download("$base/fake.jpg"), "header says JPEG, bytes say HTML")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()
        assertTrue(HttpInstagramImages.looksLikeImage(png))
        assertTrue(HttpInstagramImages.looksLikeImage(webp))
        assertTrue(HttpInstagramImages.looksLikeImage(jpeg(3)))
        assertFalse(HttpInstagramImages.looksLikeImage("RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray()))
        assertFalse(HttpInstagramImages.looksLikeImage(ByteArray(2) { 0xFF.toByte() }))
        assertFalse(HttpInstagramImages.looksLikeImage(ByteArray(0)))
    }
}
