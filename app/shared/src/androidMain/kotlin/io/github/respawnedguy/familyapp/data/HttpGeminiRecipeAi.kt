package io.github.respawnedguy.familyapp.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * [RecipeAi] over the Gemini API with plain HttpURLConnection, no SDK (ADR 0008). Reads the caption
 * and, if there is one, the video: small videos inline, larger ones through the Files API (upload,
 * wait until Google has processed it, ask, delete). The key goes only as a header to [base], which is
 * Google's API host; the upload address Google hands back must be on the same host (threat model TE-11).
 */
class HttpGeminiRecipeAi internal constructor(
    private val apiKey: String,
    private val base: String,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val pollMs: Long = POLL_MS,
) : RecipeAi {

    constructor(apiKey: String) : this(apiKey, GeminiRecipeRequest.BASE)

    override val readsVideo = true

    override fun extract(text: String): AiOutcome = guarded { generate(GeminiRecipeRequest.body(text)) }

    override fun extract(text: String, video: VideoClip): AiOutcome = guarded {
        val file = File(video.path)
        if (video.bytes <= GeminiRecipeRequest.MAX_INLINE_BYTES) {
            val bytes = BoundedRead.readAtMost(file, GeminiRecipeRequest.MAX_INLINE_BYTES)
            val base64 = Base64.getEncoder().encodeToString(bytes)
            generate(GeminiRecipeRequest.body(text, GeminiRecipeRequest.Media.Inline(video.mimeType, base64)))
        } else {
            withUpload(file, video.mimeType) { uri ->
                generate(GeminiRecipeRequest.body(text, GeminiRecipeRequest.Media.Uploaded(video.mimeType, uri)))
            }
        }
    }

    private fun generate(body: String): AiOutcome {
        val (status, answer) = call("POST", base + GeminiRecipeRequest.GENERATE_PATH, body.encodeToByteArray())
        return if (status == HttpURLConnection.HTTP_OK && answer != null) {
            GeminiRecipeRequest.parse(answer)
        } else {
            GeminiRecipeRequest.failure(status, answer)
        }
    }

    /** Files API: start, upload in one go, wait for ACTIVE, use it, delete it even if asking failed. */
    private fun withUpload(file: File, mimeType: String, use: (String) -> AiOutcome): AiOutcome {
        val start = open("POST", base + GeminiRecipeRequest.UPLOAD_PATH).apply {
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
            setRequestProperty("Content-Type", "application/json")
        }
        val started = exchange(start, GeminiRecipeRequest.uploadStart().encodeToByteArray(), "X-Goog-Upload-URL")
        val uploadUrl = started.header
        return when {
            started.status != HttpURLConnection.HTTP_OK || uploadUrl == null ->
                GeminiRecipeRequest.failure(started.status, started.body)
            // Google's own address for the bytes; refused if it points anywhere but the API host.
            !uploadUrl.startsWith("$base/") -> AiOutcome.Failed(ClaudeRecipeRequest.UNREADABLE)
            else -> afterUpload(sendBytes(uploadUrl, file), use)
        }
    }

    /** The upload address already identifies the upload: no key goes there. */
    private fun sendBytes(uploadUrl: String, file: File): Response {
        val upload = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = timeoutMs
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        }
        return try {
            upload.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            Response(upload.responseCode, readBody(upload))
        } finally {
            upload.disconnect()
        }
    }

    private fun afterUpload(sent: Response, use: (String) -> AiOutcome): AiOutcome {
        val uploaded = sent.body?.let(GeminiRecipeRequest::parseFile)
            ?: return GeminiRecipeRequest.failure(sent.status, sent.body)
        return try {
            if (waitUntilActive(uploaded)) use(uploaded.uri) else AiOutcome.Failed(NOT_PROCESSED)
        } finally {
            runCatching { call("DELETE", "$base/v1beta/${uploaded.name}", null) }
        }
    }

    private fun waitUntilActive(file: GeminiRecipeRequest.UploadedFile): Boolean {
        var state = file.state
        var waited = 0L
        while (state != "ACTIVE" && state != "FAILED" && waited < MAX_WAIT_MS) {
            Thread.sleep(pollMs)
            waited += pollMs
            val (status, body) = call("GET", "$base/v1beta/${file.name}", null)
            val ok = status == HttpURLConnection.HTTP_OK
            state = if (ok) body?.let(GeminiRecipeRequest::parseFile)?.state else "FAILED"
        }
        return state == "ACTIVE"
    }

    private fun open(method: String, url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        instanceFollowRedirects = false
        useCaches = false
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = timeoutMs
        setRequestProperty("x-goog-api-key", apiKey)
    }

    private fun call(method: String, url: String, body: ByteArray?): Response {
        val conn = open(method, url)
        if (body != null) conn.setRequestProperty("Content-Type", "application/json")
        return exchange(conn, body)
    }

    /** Status, body and one response header, read before the connection closes. */
    private data class Response(val status: Int, val body: String?, val header: String? = null)

    private fun exchange(conn: HttpURLConnection, body: ByteArray?, header: String? = null): Response = try {
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        Response(conn.responseCode, readBody(conn), header?.let(conn::getHeaderField))
    } finally {
        conn.disconnect()
    }


    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** Watching a video and answering in a schema can take a while. */
        const val TIMEOUT_MS = 180_000
        const val POLL_MS = 2_000L

        /** Google processes a short reel in seconds; after this the person gets a clear message. */
        const val MAX_WAIT_MS = 90_000L
        const val NOT_PROCESSED = "Google konnte das Video nicht verarbeiten."
    }
}

private inline fun guarded(block: () -> AiOutcome): AiOutcome = try {
    block()
} catch (_: IOException) {
    AiOutcome.Failed("Keine Verbindung zur KI.")
}

private const val MAX_RESPONSE_BYTES = 1024L * 1024
private val OK_RANGE = HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE

/** Body of a success or an error response, read up to [MAX_RESPONSE_BYTES]. */
private fun readBody(conn: HttpURLConnection): String? {
    val stream = if (conn.responseCode in OK_RANGE) conn.inputStream else conn.errorStream
    return stream?.use { BoundedRead.readAtMost(it, MAX_RESPONSE_BYTES).decodeToString() }
}
