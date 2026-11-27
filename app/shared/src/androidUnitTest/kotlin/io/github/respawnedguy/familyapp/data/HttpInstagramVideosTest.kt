package io.github.respawnedguy.familyapp.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model TE-12: only allowed addresses, only videos, bounded, no redirects, deleted after use. */
class HttpInstagramVideosTest {

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val dir: File = Files.createTempDirectory("videos").toFile()
    private val requests = mutableListOf<String>()

    @AfterTest
    fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(path: String, status: Int = 200, type: String = "video/mp4", body: ByteArray = ByteArray(0), location: String? = null, chunked: Boolean = false) {
        server.createContext(path) { ex ->
            requests += ex.requestURI.path + "|" + ex.requestHeaders.getFirst("Cookie")
            ex.responseHeaders.add("Content-Type", type)
            location?.let { ex.responseHeaders.add("Location", it) }
            ex.sendResponseHeaders(status, if (chunked) 0 else if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        }
    }

    private fun videos(max: Long = 1_000) = HttpInstagramVideos(dir, { it.startsWith(base) }, max, 2_000)

    @Test
    fun videoIsSavedInTheCacheAndDiscardedAgain() {
        serve("/clip.mp4", type = "video/mp4; codecs=avc1", body = ByteArray(300) { 7 })
        val clip = videos().download("$base/clip.mp4")!!
        assertEquals(300, clip.bytes)
        assertEquals("video/mp4", clip.mimeType)
        assertEquals(dir, File(clip.path).parentFile)
        assertEquals(listOf("/clip.mp4|null"), requests, "no cookies")
        videos().discard(clip)
        assertFalse(File(clip.path).exists())
    }

    @Test
    fun onlyAllowedAddressesAreFetched() {
        serve("/clip.mp4", body = ByteArray(10))
        assertNull(HttpInstagramVideos(dir).download("$base/clip.mp4"), "the real check allows only Instagram's CDN")
        assertTrue(requests.isEmpty())
    }

    @Test
    fun anythingButAVideoIsRefusedAndLeavesNoFile() {
        serve("/page", type = "text/html", body = "<html>".toByteArray())
        serve("/big", body = ByteArray(2_000))
        serve("/chunked", body = ByteArray(2_000), chunked = true)
        serve("/moved", status = 302, location = "$base/clip.mp4")
        serve("/gone", status = 404)
        serve("/empty", body = ByteArray(0), chunked = true)
        listOf("/page", "/big", "/chunked", "/moved", "/gone", "/empty").forEach {
            assertNull(videos().download(base + it), it)
        }
        assertEquals(0, dir.listFiles()!!.size, "nothing half-written stays behind")
    }

    @Test
    fun foreignFilesAreNotDeleted() {
        val outside = File.createTempFile("keep", ".mp4")
        videos().discard(VideoClip(outside.path, 1, "video/mp4"))
        assertTrue(outside.exists())
        outside.delete()
    }

    @Test
    fun noServerMeansNoVideo() {
        val closed = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val port = closed.address.port
        closed.stop(0)
        assertNull(HttpInstagramVideos(dir, { true }, 1_000, 1_000).download("http://127.0.0.1:$port/clip.mp4"))
    }
}
