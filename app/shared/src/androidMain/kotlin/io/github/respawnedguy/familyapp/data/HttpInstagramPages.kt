package io.github.respawnedguy.familyapp.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [InstagramPages] over plain HttpURLConnection (JDK only, so the quality gate tests it too).
 * No cookies, no login, redirects are not followed (a redirect means the login wall), at most
 * [maxBytes] are read (threat model TE-9, TE-10). [base] is fixed to Instagram; tests pass a local server.
 */
class HttpInstagramPages internal constructor(
    private val base: String,
    private val maxBytes: Int = MAX_BYTES,
    private val timeoutMs: Int = TIMEOUT_MS,
) : InstagramPages {

    constructor() : this(INSTAGRAM)

    override fun get(path: String): String? {
        if (!SAFE_PATH.matches(path)) return null
        return try {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html")
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.5")
            }
            try {
                read(conn)
            } finally {
                conn.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun read(conn: HttpURLConnection): String? {
        val acceptable = conn.responseCode == HttpURLConnection.HTTP_OK &&
            conn.contentType?.startsWith("text/html", ignoreCase = true) == true &&
            conn.contentLengthLong <= maxBytes
        // Throws IOException right after the limit, which get() turns into null.
        if (!acceptable) return null
        return conn.inputStream.use { BoundedRead.readAtMost(it, maxBytes.toLong()) }.decodeToString()
    }

    companion object {
        private const val INSTAGRAM = "https://www.instagram.com"

        /** The embed page is about 250 KB, the post page about 600 KB (26.11.2026). */
        const val MAX_BYTES = 3 * 1024 * 1024
        private const val TIMEOUT_MS = 10_000

        /** Only the two paths InstagramLink builds. */
        private val SAFE_PATH = Regex("""^/p/[A-Za-z0-9_-]{1,64}/(?:embed/captioned/)?$""")

        /** Same header as a phone browser that opens the embed page; not verified that Instagram checks it. */
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
