package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.InstagramLink
import io.github.respawnedguy.familyapp.domain.recipe.RecipePhoto
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a post's cover picture from Instagram's CDN (threat model TE-13). Only addresses
 * Instagram's own page named and [InstagramLink.isImageUrl] allows; no cookies, no redirects, only
 * status 200 with an image type, at most [maxBytes], and the bytes must start like a JPEG, PNG or
 * WebP file whatever the header says. The bytes are only ever decoded and re-encoded, never stored.
 */
class HttpInstagramImages internal constructor(
    private val allowed: (String) -> Boolean,
    private val maxBytes: Long = RecipePhoto.MAX_DOWNLOAD_BYTES,
    private val timeoutMs: Int = TIMEOUT_MS,
) {
    constructor() : this(InstagramLink::isImageUrl)

    /** The picture file, or null for anything but a normal JPEG, PNG or WebP within the limits. */
    fun download(url: String): ByteArray? {
        if (!allowed(url)) return null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                setRequestProperty("Accept", "image/jpeg,image/webp,image/png")
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

    private fun read(conn: HttpURLConnection): ByteArray? {
        val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
        val acceptable = conn.responseCode == HttpURLConnection.HTTP_OK &&
            type in IMAGE_TYPES && conn.contentLengthLong <= maxBytes
        if (!acceptable) return null
        val bytes = conn.inputStream.use { BoundedRead.readAtMost(it, maxBytes) }
        return bytes.takeIf(::looksLikeImage)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        private val JPEG = intArrayOf(0xFF, 0xD8, 0xFF)
        private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val RIFF = "RIFF".encodeToByteArray().map { it.toInt() }.toIntArray()
        private val WEBP = "WEBP".encodeToByteArray().map { it.toInt() }.toIntArray()
        private const val WEBP_AT = 8

        /** Magic numbers of JPEG, PNG and WebP (RIFF....WEBP); everything else is refused before decoding. */
        fun looksLikeImage(b: ByteArray): Boolean {
            fun at(i: Int, v: IntArray) = b.size >= i + v.size && v.indices.all { b[i + it] == v[it].toByte() }
            return at(0, JPEG) || at(0, PNG) || (at(0, RIFF) && at(WEBP_AT, WEBP))
        }
    }
}
