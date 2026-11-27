package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.InstagramLink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * [InstagramVideos] over plain HttpURLConnection (threat model TE-12). Only addresses Instagram's
 * own page named and [InstagramLink.isVideoUrl] allows; no cookies, no redirects, only status 200
 * with a video type, at most [maxBytes], streamed into [dir] (the app's cache), never kept.
 */
class HttpInstagramVideos internal constructor(
    private val dir: File,
    private val allowed: (String) -> Boolean,
    private val maxBytes: Long = MAX_BYTES,
    private val timeoutMs: Int = TIMEOUT_MS,
) : InstagramVideos {

    constructor(dir: File) : this(dir, InstagramLink::isVideoUrl)

    override fun download(url: String): VideoClip? {
        if (!allowed(url)) return null
        val target = File(dir.apply { mkdirs() }, "video-${System.nanoTime()}.mp4")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
            }
            try {
                save(conn, target)
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        }.also { if (it == null) target.delete() }
    }

    private fun save(conn: HttpURLConnection, target: File): VideoClip? {
        val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
        val acceptable = conn.responseCode == HttpURLConnection.HTTP_OK &&
            type != null && VIDEO_TYPE.matches(type) && conn.contentLengthLong <= maxBytes
        if (!acceptable) return null
        val total = conn.inputStream.use { input -> target.outputStream().use { copyAtMost(input, it) } }
        return if (total > 0) VideoClip(target.path, total, type) else null
    }

    /** Copies the stream, or throws [IOException] as soon as it is longer than [maxBytes]. */
    private fun copyAtMost(input: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > maxBytes) throw IOException("Video größer als erlaubt")
            out.write(buffer, 0, n)
        }
    }

    override fun discard(clip: VideoClip) {
        File(clip.path).takeIf { it.parentFile == dir }?.delete()
    }

    companion object {
        /** A recipe reel is a few minutes at most; 50 MB leaves room without filling the phone. */
        const val MAX_BYTES = 50L * 1024 * 1024
        private const val TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val CHUNK_BYTES = 64 * 1024
        private val VIDEO_TYPE = Regex("""^video/[a-z0-9.+-]{1,32}$""")
    }
}
