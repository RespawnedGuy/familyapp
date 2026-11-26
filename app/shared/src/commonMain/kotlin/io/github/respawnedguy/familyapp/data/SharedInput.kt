package io.github.respawnedguy.familyapp.data

/**
 * Rules for what another app may hand over with "Teilen" (quality plan P1-8, threat model
 * docs/qualitaet/bedrohungsmodell.md, TE-1 to TE-4). The share target is exported on purpose,
 * so every app on the phone can send anything here. Platform code does the I/O, these rules
 * decide; they live here so the quality gate tests them without Android.
 */
object SharedInput {
    /** Same limit as a photo in the sync (SyncController.MAX_PHOTO_BYTES). */
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    /** An 8K photo has 33 megapixels; anything above this is a decompression bomb, not a letter. */
    const val MAX_IMAGE_PIXELS = 100_000_000L

    /** A long school mail fits easily; a shared 50 MB text would bloat every backup and sync. */
    const val MAX_TEXT_CHARS = 20_000

    /**
     * Checks before anything is opened. [ownProvider] is true when the content provider belongs to
     * this app: another app could otherwise make us copy our own private files (e.g. the family
     * data) into a photo. file:// is refused for the same reason.
     */
    fun precheck(scheme: String?, ownProvider: Boolean, mimeType: String?): ShareRejection? = when {
        scheme?.lowercase() != "content" || ownProvider -> ShareRejection.NOT_FROM_OTHER_APP
        mimeType == null || !mimeType.trim().lowercase().startsWith("image/") -> ShareRejection.NOT_AN_IMAGE
        else -> null
    }

    /** Checks after the copy, with the size the image decoder read from the file header. */
    fun postcheck(width: Int, height: Int): ShareRejection? = when {
        width <= 0 || height <= 0 -> ShareRejection.NOT_AN_IMAGE
        width.toLong() * height.toLong() > MAX_IMAGE_PIXELS -> ShareRejection.TOO_MANY_PIXELS
        else -> null
    }

    /**
     * Copies from [read] to [write] in chunks and stops as soon as more than [max] bytes came
     * (the sender's size claim is not trusted). Returns the number of bytes, or null if too large.
     */
    fun copyLimited(
        read: (ByteArray) -> Int,
        write: (ByteArray, Int) -> Unit,
        max: Long = MAX_IMAGE_BYTES,
    ): Long? {
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) return total
            total += n
            if (total > max) return null
            if (n > 0) write(buffer, n)
        }
    }

    /** Subject and body of a share as one capture text, cut to [MAX_TEXT_CHARS]. */
    fun text(subject: String?, body: String?): String {
        val joined = listOfNotNull(subject?.takeIf { it.isNotBlank() }, body?.takeIf { it.isNotBlank() })
            .joinToString(" – ")
        return if (joined.length <= MAX_TEXT_CHARS) joined else joined.take(MAX_TEXT_CHARS - 1) + "…"
    }

    private const val CHUNK_BYTES = 64 * 1024
}

/** Why a shared image was not taken over. [reason] is shown to the person, so nothing fails silently. */
enum class ShareRejection(val reason: String) {
    NOT_FROM_OTHER_APP("Es werden nur Bilder angenommen, die eine andere App teilt."),
    NOT_AN_IMAGE("Die geteilte Datei ist kein Bild."),
    TOO_LARGE("Das Bild ist größer als 20 MB."),
    TOO_MANY_PIXELS("Das Bild hat zu viele Bildpunkte."),
    UNREADABLE("Das Bild ließ sich nicht lesen."),
}

/** Result of taking over a shared image. */
sealed interface SharedImage {
    data class Accepted(val path: String) : SharedImage
    data class Rejected(val why: ShareRejection) : SharedImage
}
