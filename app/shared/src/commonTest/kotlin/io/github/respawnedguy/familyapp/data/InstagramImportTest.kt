package io.github.respawnedguy.familyapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ADR 0007: embed page first, post page only when the embed page has no caption. */
class InstagramImportTest {

    private class FakePages(private val pages: Map<String, String>) : InstagramPages {
        val asked = mutableListOf<String>()
        override fun get(path: String): String? {
            asked += path
            return pages[path]
        }
    }

    private val embed = "<div class=\"Caption\">Brot<br>500 g Mehl</div>"
    private val post = "<meta property=\"og:description\" content=\"3 likes - koch.beispiel am 1. Mai: &quot;Brot&quot;.\">"

    @Test
    fun embedPageIsEnough() {
        val pages = FakePages(mapOf("/p/C0de/embed/captioned/" to embed, "/p/C0de/" to post))
        assertEquals("Brot\n500 g Mehl", InstagramImport(pages).fetch("C0de")?.caption)
        assertEquals(listOf("/p/C0de/embed/captioned/"), pages.asked)
    }

    @Test
    fun postPageIsTheSecondChance() {
        val pages = FakePages(mapOf("/p/C0de/embed/captioned/" to "<html>login</html>", "/p/C0de/" to post))
        assertEquals("Brot", InstagramImport(pages).fetch("C0de")?.caption)
        assertEquals(listOf("/p/C0de/embed/captioned/", "/p/C0de/"), pages.asked)
    }

    @Test
    fun nothingReachableGivesNull() {
        assertNull(InstagramImport(FakePages(emptyMap())).fetch("C0de"))
    }

    @Test
    fun browserIsAskedOnlyWhenThePlainFetchGetsNoCaption() {
        val refused = FakePages(mapOf("/p/C0de/embed/captioned/" to "<html>Login</html>"))
        val browser = FakePages(mapOf("/p/C0de/embed/captioned/" to embed))
        assertEquals("Brot\n500 g Mehl", InstagramImport(refused, browser = browser).fetch("C0de")?.caption)
        assertEquals(listOf("/p/C0de/embed/captioned/", "/p/C0de/"), refused.asked)
        assertEquals(listOf("/p/C0de/embed/captioned/"), browser.asked)

        val direct = FakePages(mapOf("/p/C0de/embed/captioned/" to embed))
        val unused = FakePages(emptyMap())
        InstagramImport(direct, browser = unused).fetch("C0de")
        assertEquals(emptyList(), unused.asked, "no browser when the plain fetch works")
        assertNull(InstagramImport(FakePages(emptyMap()), browser = FakePages(emptyMap())).fetch("C0de"))
    }
}
