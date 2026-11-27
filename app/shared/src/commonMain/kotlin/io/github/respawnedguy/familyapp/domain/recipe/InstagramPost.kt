package io.github.respawnedguy.familyapp.domain.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What the app reads from a public Instagram post: the caption, who posted it, whether it is a video. */
data class InstagramPost(
    val caption: String,
    val username: String?,
    val isVideo: Boolean,
    val source: Source,
) {
    /** Which part of Instagram's pages the caption came from (for the threat model and debugging). */
    enum class Source { EMBED_JSON, EMBED_HTML, OPEN_GRAPH }
}

/**
 * Addresses of a post, built only from its shortcode, so a shared link can never make the app fetch
 * any other host or path (threat model TE-9). Embed page first: it is Instagram's public page for
 * showing a post on other web sites and carries the whole caption without a login (checked on
 * 26.11.2026, docs/entscheidungen/0007-instagram-text-ohne-yt-dlp.md).
 */
object InstagramLink {
    private val POST =
        Regex("""^https?://(?:www\.|m\.)?instagram\.com/(?:[\w.]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""")

    /** Private posts have the 11 characters plus 28 more; anything longer is not a shortcode. */
    private const val MAX_SHORTCODE = 64

    fun shortcode(url: String): String? =
        POST.find(url)?.groupValues?.get(1)?.takeIf { it.length <= MAX_SHORTCODE }

    fun embedPath(shortcode: String) = "/p/$shortcode/embed/captioned/"

    fun postPath(shortcode: String) = "/p/$shortcode/"
}

/**
 * Reads the caption out of Instagram's HTML. Pure text work, no network; the pages come from
 * [io.github.respawnedguy.familyapp.data.InstagramPages]. Instagram changes its pages without notice,
 * so there are three independent places to look, and when all fail the person pastes the text.
 */
object InstagramPage {

    /** A caption is at most 2,200 characters on Instagram; anything far longer is not a caption. */
    const val MAX_CAPTION = 5_000

    private val json = Json { ignoreUnknownKeys = true }

    /** The embed page: the JSON in "contextJSON" (videos, carousels), else the visible caption block (images). */
    fun fromEmbed(html: String): InstagramPost? = fromContextJson(html) ?: fromCaptionBlock(html)

    /** The normal post page: Open Graph description "… - user am 17. November 2026: "caption"." */
    fun fromPostPage(html: String): InstagramPost? {
        val content = HtmlText.metaContent(html, "og:description").orEmpty()
        val start = content.indexOf(OG_QUOTE).takeIf { it in 0..OG_PREFIX_MAX } ?: return null
        val caption = content.substring(start + OG_QUOTE.length)
            .trimEnd().removeSuffix(".").trimEnd().removeSuffix("\"")
        val username = content.substring(0, start).substringAfter(" - ", "").substringBefore(' ')
        val isVideo = HtmlText.metaContent(html, "og:video") != null
        return post(caption, username, isVideo, InstagramPost.Source.OPEN_GRAPH)
    }

    private fun fromContextJson(html: String): InstagramPost? {
        val media = HtmlText.jsonStringAfter(html, "\"contextJSON\":")?.let { raw ->
            runCatching {
                json.parseToJsonElement(json.decodeFromString<String>(raw)).obj("gql_data")?.obj("shortcode_media")
            }.getOrNull()
        } ?: return null
        val caption = (media.obj("edge_media_to_caption")?.get("edges") as? JsonArray)?.firstOrNull()
            ?.obj("node")?.string("text")
        val isVideo = (media["is_video"] as? JsonPrimitive)?.booleanOrNull == true
        val username = media.obj("owner")?.string("username")
        return caption?.let { post(it, username, isVideo, InstagramPost.Source.EMBED_JSON) }
    }

    private fun fromCaptionBlock(html: String): InstagramPost? {
        val open = html.indexOf("class=\"Caption\"").takeIf { it >= 0 }?.let { html.indexOf('>', it) } ?: -1
        val end = listOf(html.indexOf("<div class=\"CaptionComments\"", open), html.indexOf("</div>", open))
            .filter { it >= 0 }.minOrNull()
        if (open < 0 || end == null) return null
        var block = html.substring(open + 1, end)
        val user = USERNAME_LINK.find(block)
        if (user != null) block = block.removeRange(user.range)
        val text = HtmlText.decodeEntities(block.replace(BR, "\n").replace(TAG, ""))
        val username = user?.groupValues?.get(1)?.let(HtmlText::decodeEntities)?.trim()
        return post(text, username, html.contains("GraphVideo"), InstagramPost.Source.EMBED_HTML)
    }

    private fun post(
        caption: String,
        username: String?,
        isVideo: Boolean,
        source: InstagramPost.Source,
    ): InstagramPost? {
        val text = caption.replace("\r\n", "\n").trim()
        return if (text.isEmpty() || text.length > MAX_CAPTION) {
            null
        } else {
            InstagramPost(text, username?.takeIf { USERNAME.matches(it) }, isVideo, source)
        }
    }

    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val OG_QUOTE = ": \""
    private const val OG_PREFIX_MAX = 200
    private val USERNAME = Regex("""[A-Za-z0-9._]{1,30}""")
    private val USERNAME_LINK = Regex("""<a[^>]*class="CaptionUsername"[^>]*>([^<]*)</a>""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
}
