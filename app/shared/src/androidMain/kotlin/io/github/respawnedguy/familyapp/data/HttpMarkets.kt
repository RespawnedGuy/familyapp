package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.data.offers.HttpAnswer
import io.github.respawnedguy.familyapp.data.offers.MarketHttp
import io.github.respawnedguy.familyapp.data.offers.MarketUrls
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [MarketHttp] over plain HttpURLConnection (JDK only, so the quality gate tests it with a local
 * server). One honest request: the app says who it is, sends no cookies, follows no redirects and
 * reads at most the kind's limit (threat model AN-1, AN-2). A refusal is reported, never worked around
 * (docs/angebote-und-vorschlaege.md, "Grundsatz"). [allowed] is fixed to the markets' hosts; tests
 * pass "localhost" and an http base.
 */
class HttpMarkets internal constructor(
    private val allowed: (URL) -> Boolean,
    private val timeoutMs: Int = TIMEOUT_MS,
) : MarketHttp {

    constructor() : this({ it.protocol == "https" && it.host in MarketUrls.ALLOWED_HOSTS })

    override fun get(url: String, kind: MarketHttp.Kind): HttpAnswer {
        val target = runCatching { URL(url) }.getOrNull()
        if (target == null || !allowed(target)) return HttpAnswer.Failed("Adresse nicht erlaubt")
        return try {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", kind.accept)
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
            }
            try {
                read(conn, kind)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            HttpAnswer.Failed(e.message?.take(MAX_REASON) ?: "Netzwerkfehler")
        }
    }

    private fun read(conn: HttpURLConnection, kind: MarketHttp.Kind): HttpAnswer {
        val code = conn.responseCode
        return when {
            code in REFUSING -> HttpAnswer.Refused(code)
            code != HttpURLConnection.HTTP_OK -> HttpAnswer.Failed("HTTP $code")
            conn.contentLengthLong > kind.maxBytes -> HttpAnswer.Failed("Antwort zu groß")
            else -> {
                val text = conn.inputStream.use { BoundedRead.readAtMost(it, kind.maxBytes.toLong()) }.decodeToString()
                if (isChallenge(text)) HttpAnswer.Refused(code) else HttpAnswer.Ok(text)
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val MAX_REASON = 80

        /** "Not for you", "log in first", "too many requests": the market says no. */
        private val REFUSING = setOf(401, 403, 429)

        /**
         * Honest label instead of a browser's (Grundsatz, point 3). A market that refuses this
         * gets switched off; the app never pretends to be a browser to get in.
         */
        const val USER_AGENT = "FamilyApp (Android; private family app reading weekly offers; " +
            "+https://github.com/RespawnedGuy/familyapp)"

        /** Bot-check pages are small; markers of the usual services, looked for at the start only. */
        private const val CHALLENGE_HEAD = 20_000
        private val CHALLENGE_MARKERS = listOf(
            "cf-chl", "challenge-platform", "px-captcha", "_incapsula_resource", "captcha-delivery", "bm-verify",
            "sec-if-cpt",
        )

        fun isChallenge(text: String): Boolean {
            val head = text.take(CHALLENGE_HEAD).lowercase()
            return CHALLENGE_MARKERS.any { it in head }
        }
    }
}
