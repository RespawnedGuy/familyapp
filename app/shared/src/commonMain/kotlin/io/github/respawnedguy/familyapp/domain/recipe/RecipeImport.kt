package io.github.respawnedguy.familyapp.domain.recipe

/** A recipe as the rules built it from a text, before it gets an id and a stamp. */
data class RecipeDraft(
    val title: String,
    val servings: Double?,
    val totalMinutes: Int?,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
    val sourceUrl: String?,
    val sourceText: String?,
    /** Nothing usable found: the recipe stays a suggestion and says what is missing. */
    val needsText: Boolean,
)

/**
 * Builds recipes from what reaches the app as text: a pasted or typed recipe, a shared caption,
 * or only a link (docs/kochbibliothek.md "Erfassen"). Rules only; nothing goes over the network here.
 */
object RecipeImport {

    private val URL = Regex("""https?://[^\s<>"']+""")
    private val INSTAGRAM = Regex("""^https?://(?:www\.|m\.)?instagram\.com/(?:[\w.]+/)?(?:reel|reels|p|tv)/[\w-]+""")
    private val TRACKING = setOf("igsh", "igshid", "fbclid", "gclid", "si", "mc_cid", "mc_eid", "ref", "ref_src")

    /** Text left beside a link that still counts as "only a link" (e.g. the share subject). */
    private const val LINK_ONLY_REST = 40

    /** The title a link-only recipe gets until somebody adds the text. */
    const val INSTAGRAM_TITLE = "Rezept von Instagram"
    const val UNTITLED = "Rezept"

    fun fromText(text: String): RecipeDraft {
        val url = firstUrl(text)
        val withoutUrls = text.replace(URL, " ").trim()
        val parsed = RecipeTextParser.parse(withoutUrls)
        val instagram = url != null && INSTAGRAM.containsMatchIn(url)
        val needsText = parsed.status == ParsedRecipe.Status.NO_INGREDIENTS
        val title = when {
            instagram && needsText -> INSTAGRAM_TITLE
            else -> parsed.title?.trim(' ', '–', '-', ':')?.takeIf { it.length >= 2 } ?: UNTITLED
        }
        return RecipeDraft(
            title = title,
            servings = parsed.servings,
            totalMinutes = parsed.totalMinutes,
            ingredients = parsed.ingredients,
            steps = parsed.steps,
            sourceUrl = url,
            sourceText = withoutUrls.takeIf { it.isNotBlank() },
            needsText = needsText,
        )
    }

    /**
     * Should shared text become a recipe suggestion instead of an inbox card? Only when the rules
     * are sure: a list of ingredients and steps, or an Instagram post link with hardly any text
     * (Instagram hands over nothing but the link). Everything else stays a normal capture.
     */
    fun isRecipe(text: String): Boolean {
        val url = firstUrl(text)
        val rest = text.replace(URL, " ").replace("–", " ").trim()
        if (url != null && INSTAGRAM.containsMatchIn(url) && rest.length <= LINK_ONLY_REST) return true
        val parsed = RecipeTextParser.parse(rest)
        return parsed.status == ParsedRecipe.Status.COMPLETE && parsed.ingredients.size >= 2
    }

    /** First http(s) link, without tracking parameters (igsh, utm_…), so the same post is the same link. */
    fun firstUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')?.let(::cleanUrl)

    internal fun cleanUrl(url: String): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        val fragment = query.substringAfter('#', "")
        val kept = query.substringBefore('#').split('&').filter { part ->
            val name = part.substringBefore('=').lowercase()
            part.isNotEmpty() && name !in TRACKING && !name.startsWith("utm_")
        }
        val withQuery = if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        return if (fragment.isEmpty()) withQuery else "$withQuery#$fragment"
    }

    /** "instagram.com" for the source line under a recipe. */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    /** Only web links are opened; anything else typed into the source field stays plain text (TE-1). */
    fun isWebLink(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** Lines as typed in the edit form become ingredients again (same rules as the import). */
    fun ingredientsFrom(lines: String): List<Ingredient> =
        lines.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(IngredientParser::parse)
            .filter { it.name.isNotBlank() }

    /** One step per paragraph or line; numbering ("1.", "2)") is dropped, the list numbers itself. */
    fun stepsFrom(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            RecipeTextParser.NUMBERED_STEP.find(line)?.groupValues?.get(2)?.trim() ?: line
        }
}
