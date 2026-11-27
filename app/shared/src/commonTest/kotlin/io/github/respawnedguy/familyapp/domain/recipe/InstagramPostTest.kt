package io.github.respawnedguy.familyapp.domain.recipe

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Threat model TE-9/TE-10, ADR 0007. The pages are invented, only their structure follows what
 * Instagram served on 26.11.2026 (embed page with "contextJSON", caption block, Open Graph meta).
 */
class InstagramPostTest {

    private val caption = "Linsensuppe für 4 Personen 🍲\n\nZutaten:\n250 g rote Linsen\n1 Zwiebel\n2 EL Öl\n\n" +
        "Zubereitung:\n1. Zwiebel anbraten.\n2. Linsen 15 Minuten kochen.\n\n#suppe \"lecker\" & schnell"

    /** The embed page of a reel: the media JSON is a string inside the page's own JSON (double encoded). */
    private fun embedWithJson(text: String = caption, isVideo: Boolean = true): String {
        val media = buildJsonObject {
            putJsonObject("context") { put("type", "GraphVideo"); put("shortcode", "Abc_123-x") }
            putJsonObject("gql_data") {
                putJsonObject("shortcode_media") {
                    put("__typename", if (isVideo) "GraphVideo" else "GraphSidecar")
                    put("is_video", isVideo)
                    putJsonObject("owner") { put("username", "koch.beispiel") }
                    putJsonObject("edge_media_to_caption") {
                        putJsonArray("edges") { addJsonObject { putJsonObject("node") { put("text", text) } } }
                    }
                }
            }
        }
        val inner = Json.encodeToString(String.serializer(), media.toString())
        return "<html><script>{\"require\":[[\"PolarisEmbedSimple\",\"init\",[],[{\"isRichEmbed\":true," +
            "\"contextJSON\":$inner}]]]}</script></html>"
    }

    /** The embed page of an image post: no JSON, only the visible caption block with <br>. */
    private val embedImage = """
        <html><script>{"contextJSON":null}</script><div class="EmbeddedMedia GraphImage">
        <div class="Caption"><a class="CaptionUsername" href="https://www.instagram.com/koch.beispiel/?utm_source=ig_embed"
        data-log-event="captionProfileClick" target="_blank">koch.beispiel</a><br><br>Linsensuppe &#127858;<br><br>Zutaten:<br>
        250 g rote Linsen<br>1 Zwiebel<br/>2 EL &Ouml;l &amp; Salz<div class="CaptionComments"><a class="CaptionCommentsExpand"
        href="#">Alle 12 Kommentare ansehen</a></div></div></div></html>
    """.trimIndent()

    private fun postPage(description: String) =
        "<html><head><meta property=\"og:title\" content=\"x\" />" +
            "<meta property=\"og:description\" content=\"$description\" /></head></html>"

    @Test
    fun embedJsonGivesTheWholeCaption() {
        val post = InstagramPage.fromEmbed(embedWithJson())!!
        assertEquals(caption, post.caption)
        assertEquals("koch.beispiel", post.username)
        assertTrue(post.isVideo)
        assertEquals(InstagramPost.Source.EMBED_JSON, post.source)
        // The caption is good enough for the rules: ingredients and steps.
        val draft = RecipeImport.fromText(post.caption)
        assertFalse(draft.needsText)
        assertEquals(3, draft.ingredients.size)
    }

    @Test
    fun imagePostFallsBackToTheCaptionBlock() {
        val post = InstagramPage.fromEmbed(embedImage)!!
        assertEquals(InstagramPost.Source.EMBED_HTML, post.source)
        assertEquals("koch.beispiel", post.username)
        assertFalse(post.isVideo)
        assertTrue(post.caption.startsWith("Linsensuppe 🍲\n\nZutaten:"), post.caption)
        assertFalse(post.caption.contains("Kommentare"), "comment link is not part of the caption")
        assertFalse(post.caption.contains("koch.beispiel"), "user name link is not part of the caption")
        // Unknown named entity stays as it was; &amp; is decoded.
        assertTrue(post.caption.endsWith("2 EL &Ouml;l & Salz"), post.caption)
    }

    @Test
    fun brokenJsonFallsBackToTheCaptionBlock() {
        val html = "<script>{\"contextJSON\":\"{\\\"gql_data\\\": nope\"}</script><div class=\"Caption\">Text<br>zwei</div>"
        val post = InstagramPage.fromEmbed(html)!!
        assertEquals("Text\nzwei", post.caption)
        assertEquals(InstagramPost.Source.EMBED_HTML, post.source)
    }

    @Test
    fun postPageOpenGraphLosesOnlyTheCountsPrefix() {
        val description = "1.234 likes, 56 comments - koch.beispiel am November 17, 2026: &quot;Linsensuppe&#10;" +
            "Zutaten: &quot;rote&quot; Linsen&quot;. "
        val post = InstagramPage.fromPostPage(postPage(description))!!
        assertEquals("Linsensuppe\nZutaten: \"rote\" Linsen", post.caption)
        assertEquals("koch.beispiel", post.username)
        assertEquals(InstagramPost.Source.OPEN_GRAPH, post.source)
    }

    @Test
    fun loginWallAndForeignPagesGiveNothing() {
        assertNull(InstagramPage.fromEmbed("<html><title>Login • Instagram</title></html>"))
        assertNull(InstagramPage.fromPostPage("<html><meta property=\"og:description\" content=\"Instagram\"></html>"))
        assertNull(InstagramPage.fromPostPage("<html></html>"))
        assertNull(InstagramPage.fromEmbed("<div class=\"Caption\">   </div>"))
        // Unterminated JSON string at the end of a cut page.
        assertNull(InstagramPage.fromEmbed("{\"contextJSON\":\"{\\\"gql_data"))
    }

    @Test
    fun overlongCaptionIsRefused() {
        assertNull(InstagramPage.fromEmbed(embedWithJson("x".repeat(InstagramPage.MAX_CAPTION + 1))))
        assertEquals(InstagramPage.MAX_CAPTION, InstagramPage.fromEmbed(embedWithJson("x".repeat(InstagramPage.MAX_CAPTION)))!!.caption.length)
    }

    @Test
    fun shortcodeOnlyFromInstagramPostLinks() {
        assertEquals("DgLx_1-a", InstagramLink.shortcode("https://www.instagram.com/reel/DgLx_1-a/?igsh=abc"))
        assertEquals("C0de", InstagramLink.shortcode("https://instagram.com/koch.beispiel/p/C0de/"))
        assertEquals("C0de", InstagramLink.shortcode("https://m.instagram.com/tv/C0de"))
        assertNull(InstagramLink.shortcode("https://www.instagram.com/koch.beispiel/"))
        assertNull(InstagramLink.shortcode("https://instagram.com.example.org/p/C0de/"))
        assertNull(InstagramLink.shortcode("https://example.org/?u=https://www.instagram.com/p/C0de/"))
        assertNull(InstagramLink.shortcode("https://www.instagram.com/p/../accounts/"))
        assertNull(InstagramLink.shortcode("https://www.instagram.com/p/" + "a".repeat(65)))
    }

    @Test
    fun pathsAreBuiltFromTheShortcodeOnly() {
        assertEquals("/p/C0de/embed/captioned/", InstagramLink.embedPath("C0de"))
        assertEquals("/p/C0de/", InstagramLink.postPath("C0de"))
    }

    @Test
    fun numericEntitiesOutsideUnicodeStay() {
        assertEquals("😀 &#xD800; &#1114112; ' ", HtmlText.decodeEntities("&#128512; &#xD800; &#1114112; &apos;&nbsp;"))
    }
}
