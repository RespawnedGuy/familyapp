package io.github.respawnedguy.familyapp.domain.recipe

/** What to add to the shopping list for one ingredient. */
data class NewEntry(val name: String, val amount: Double?, val unit: String?, val ask: Boolean)

/**
 * One line as the list shows it: all live entries with the same key and state together, amounts of
 * the same kind added up (500 g + 0,5 kg = 1 kg; EL and g stay side by side, docs/kochbibliothek.md).
 */
data class ShoppingLine(
    val key: String,
    val name: String,
    val amounts: String?,
    val entryIds: List<String>,
    val recipeIds: Set<String>,
    val checked: Boolean,
    val ask: Boolean,
    /** Where it is on offer ("bei Aldi Süd, 1,69 €"), if it came from an offer suggestion. */
    val offer: String? = null,
)

/** The list split the way the screen shows it. */
data class ShoppingView(val open: List<ShoppingLine>, val ask: List<ShoppingLine>, val checked: List<ShoppingLine>) {
    val isEmpty: Boolean get() = open.isEmpty() && ask.isEmpty() && checked.isEmpty()
}

object ShoppingList {

    /**
     * Basics most kitchens have. From a recipe they come as "Hast du noch …?" instead of a normal
     * line (keys as [IngredientKey] makes them).
     */
    val PANTRY = setOf(
        "salz", "pfeffer", "öl", "zucker", "mehl", "essig", "backpulver", "natron", "paprikapulver",
        "zimt", "muskat", "muskatnuss", "oregano", "gewürz", "senf", "brühe", "gemüsebrühe", "speisestärke",
    ).map(IngredientKey::of).toSet()

    /** Never on a shopping list. */
    private val NEVER = setOf("wasser", "eiswasser").map(IngredientKey::of).toSet()

    /** Entries for a recipe at [factor] times its amounts. */
    fun fromRecipe(recipe: Recipe, factor: Double = 1.0): List<NewEntry> =
        recipe.ingredients.filter { it.key !in NEVER && it.name.isNotBlank() }.map { i ->
            NewEntry(i.name, i.amount?.let { it * factor }, i.unit, ask = i.key in PANTRY)
        }

    /** A typed line ("2 l Milch", "Klopapier") as an entry; the person wrote it, so it is never a question. */
    fun fromText(text: String): NewEntry? {
        val parsed = IngredientParser.parse(text)
        val name = parsed.name.ifBlank { text.trim() }
        if (name.isBlank()) return null
        return NewEntry(name.replaceFirstChar { it.uppercaseChar() }, parsed.amount, parsed.unit, ask = false)
    }

    /** Several entries typed or spoken in one go: "Milch und Klopapier", "Eier, Butter". */
    fun splitTyped(text: String): List<NewEntry> =
        text.split(TYPED_SEPARATOR).mapNotNull { part -> part.takeIf { it.isNotBlank() }?.let(::fromText) }

    private val TYPED_SEPARATOR = Regex("""\s*(?:[,;\n]|\s+und\s+)\s*""")

    fun view(entries: List<ShoppingEntry>): ShoppingView {
        val live = entries.filterNot { it.deleted }
        fun lines(of: List<ShoppingEntry>) = of.groupBy { it.key }.map { (key, group) -> line(key, group) }
            .sortedBy { it.name.lowercase() }
        return ShoppingView(
            open = lines(live.filter { !it.checked && !it.ask }),
            ask = lines(live.filter { !it.checked && it.ask }),
            checked = lines(live.filter { it.checked }),
        )
    }

    private fun line(key: String, group: List<ShoppingEntry>): ShoppingLine {
        val first = group.minBy { it.createdMs }
        return ShoppingLine(
            key = key,
            name = first.name,
            amounts = amounts(group),
            entryIds = group.map { it.id },
            recipeIds = group.mapNotNull { it.recipeId }.toSet(),
            checked = first.checked,
            ask = first.ask,
            offer = group.sortedByDescending { it.createdMs }.firstNotNullOfOrNull { it.offer },
        )
    }

    /** Sum per kind of unit: mass in g, volume in ml, every other unit (or none) on its own. */
    internal fun amounts(group: List<ShoppingEntry>): String? {
        val withAmount = group.filter { it.amount != null }
        if (withAmount.isEmpty()) return null
        val sums = LinkedHashMap<String?, Double>()
        withAmount.forEach { e ->
            val (unit, value) = Amounts.toBase(e.amount!!, e.unit) ?: (e.unit to e.amount)
            sums[unit] = (sums[unit] ?: 0.0) + value
        }
        return sums.entries.joinToString(" + ") { (unit, value) -> Amounts.format(value, unit = unit) }
    }
}
