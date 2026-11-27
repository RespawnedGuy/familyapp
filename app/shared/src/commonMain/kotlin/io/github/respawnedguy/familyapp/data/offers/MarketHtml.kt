package io.github.respawnedguy.familyapp.data.offers

/**
 * Reading server-rendered pages of the markets (threat model AN-2): only visible text is taken, tags,
 * comments and scripts are dropped, entities decoded. Regular expressions over bounded input (pages are
 * capped by [MarketHttp.Kind]), no HTML engine.
 */
internal object MarketHtml {
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<[^>]*>""")
    private val WHITESPACE = Regex("""\s+""")
    private val NUMERIC = Regex("""&#(x?)([0-9a-fA-F]{1,6});""")
    private val NAMED = mapOf(
        "&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ",
        "&minus;" to "-", "&percnt;" to "%", "&euro;" to "€", "&shy;" to "",
    )
    private const val HEX = 16
    private const val DECIMAL = 10

    /** Visible text of an HTML fragment, whitespace collapsed. */
    fun text(fragment: String): String =
        unescape(fragment.replace(COMMENT, "").replace(TAG, " ")).replace(WHITESPACE, " ").trim()

    fun unescape(s: String): String {
        var out = NUMERIC.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) DECIMAL else HEX)
            code?.takeIf { it in 1..Char.MAX_VALUE.code }?.toChar()?.toString() ?: ""
        }
        NAMED.forEach { (k, v) -> out = out.replace(k, v) }
        return out
    }

    /** Content of the first element of [tag] after [from] in [html], or null. */
    fun first(html: String, tag: String, from: Int = 0): String? {
        val open = Regex("""<$tag\b[^>]*>""").find(html, from) ?: return null
        val close = html.indexOf("</$tag>", open.range.last + 1)
        return if (close < 0) null else html.substring(open.range.last + 1, close)
    }

    /** Value of the first attribute [name] in [html], unescaped. */
    fun attribute(html: String, name: String): String? =
        Regex("""\b$name="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(::unescape)
}
