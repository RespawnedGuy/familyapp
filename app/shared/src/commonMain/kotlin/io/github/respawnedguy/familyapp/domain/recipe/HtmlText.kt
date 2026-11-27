package io.github.respawnedguy.familyapp.domain.recipe

/** Small, regex-light helpers for reading text out of foreign HTML (Instagram pages). */
internal object HtmlText {

    /** The JSON string literal (with its quotes) that follows [key]; a scan instead of a regex, pages are big. */
    fun jsonStringAfter(html: String, key: String): String? {
        val open = html.indexOf(key).takeIf { it >= 0 }?.plus(key.length)
            ?.takeIf { it < html.length && html[it] == '"' }
        var i = (open ?: html.length) + 1
        var end = -1
        while (open != null && end < 0 && i < html.length) {
            when (html[i]) {
                '\\' -> i += 2
                '"' -> end = i
                else -> i++
            }
        }
        return if (open != null && end > 0) html.substring(open, end + 1) else null
    }

    /** content of the first <meta property|name="[property]">, entities decoded. */
    fun metaContent(html: String, property: String): String? =
        META.findAll(html).map { it.value }
            .firstOrNull { attr(it, "property") == property || attr(it, "name") == property }
            ?.let { attr(it, "content") }?.let(::decodeEntities)

    private fun attr(tag: String, name: String): String? =
        Regex("""\s$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)

    fun decodeEntities(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(HEX)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name]
        } ?: m.value
    }

    /** A numeric entity as text; surrogates, zero and values beyond Unicode stay as they were. */
    private fun codePoint(cp: Int): String? = when {
        cp in 1 until SUPPLEMENTARY && cp !in SURROGATES -> cp.toChar().toString()
        cp in SUPPLEMENTARY..MAX_CODE_POINT -> (cp - SUPPLEMENTARY).let {
            charArrayOf((HIGH + (it shr TEN_BITS)).toChar(), (LOW + (it and LOW_MASK)).toChar()).concatToString()
        }
        else -> null
    }

    private const val HEX = 16
    private const val SUPPLEMENTARY = 0x10000
    private const val MAX_CODE_POINT = 0x10FFFF
    private const val HIGH = 0xD800
    private const val SURROGATE_END = 0xDFFF
    private val SURROGATES = HIGH..SURROGATE_END
    private const val LOW = 0xDC00
    private const val TEN_BITS = 10
    private const val LOW_MASK = 0x3FF
    private val META = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
}
