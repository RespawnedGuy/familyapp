package io.github.respawnedguy.familyapp.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [RecipeAi] over the Claude Messages API with plain HttpURLConnection. No SDK on purpose: the
 * official Java SDK would add about 40 MB to a 7 MB APK for one optional request (ADR 0007).
 * The key goes only to [ClaudeRecipeRequest.URL]; responses are read up to [MAX_BYTES].
 */
class HttpClaudeRecipeAi internal constructor(
    private val apiKey: String,
    private val url: String,
    private val timeoutMs: Int = TIMEOUT_MS,
) : RecipeAi {

    constructor(apiKey: String) : this(apiKey, ClaudeRecipeRequest.URL)

    override fun extract(text: String): AiOutcome = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", ClaudeRecipeRequest.API_VERSION)
            setRequestProperty("anthropic-beta", ClaudeRecipeRequest.BETA)
        }
        try {
            conn.outputStream.use { it.write(ClaudeRecipeRequest.body(text).encodeToByteArray()) }
            val status = conn.responseCode
            val stream = if (status == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val body = stream?.use { BoundedRead.readAtMost(it, MAX_BYTES).decodeToString() }
            if (status == HttpURLConnection.HTTP_OK && body != null) {
                ClaudeRecipeRequest.parse(body)
            } else {
                ClaudeRecipeRequest.failure(status, body)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        AiOutcome.Failed("Keine Verbindung zur KI.")
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024
        const val CONNECT_TIMEOUT_MS = 10_000

        /** Thinking plus structured output can take a while even at low effort. */
        const val TIMEOUT_MS = 120_000
    }
}
