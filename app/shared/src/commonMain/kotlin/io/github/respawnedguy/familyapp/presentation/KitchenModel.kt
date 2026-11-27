package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.AiOutcome
import io.github.respawnedguy.familyapp.data.InstagramImport
import io.github.respawnedguy.familyapp.data.RecipeAi
import io.github.respawnedguy.familyapp.data.RecipeEdit
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.domain.recipe.InstagramLink
import io.github.respawnedguy.familyapp.domain.recipe.InstagramPost
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.RecipeViews
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingLine
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where in the kitchen library the person is. Null in [KitchenModel.route] means: not in the kitchen. */
sealed interface KitchenRoute {
    data object Library : KitchenRoute
    data object Shopping : KitchenRoute
    data class Detail(val id: String) : KitchenRoute

    /** [id] null: a new recipe from pasted or typed text. */
    data class Edit(val id: String?) : KitchenRoute
    data class Cook(val id: String, val servings: Int) : KitchenRoute
}

/**
 * Presentation logic of the kitchen library (docs/kochbibliothek.md): navigation here, recipe
 * actions in [recipes], the shared shopping list in [shopping]. Lives inside [AppModel]; messages go
 * through its feedback bar with undo. No notifications: everything happens while the app is open.
 */
class KitchenModel internal constructor(
    private val repo: FamilyRepository,
    private val platform: PlatformServices,
    background: Background,
    show: (String, Undo?) -> Unit,
) {
    val recipes = RecipeActions(
        repo, this, show, background,
        instagram = platform.instagramPages?.let {
            InstagramImport(it, platform.instagramVideos, platform.instagramBrowser)
        },
        ai = { repo.current.settings.recipeAiKey?.let(platform::recipeAi) },
    )
    val shopping = ShoppingActions(repo, this, show)

    private val _route = MutableStateFlow<KitchenRoute?>(null)
    val route: StateFlow<KitchenRoute?> = _route.asStateFlow()

    /** Servings chosen on the recipe page; the cooking mode and the shopping list use the same number. */
    private val _servings = MutableStateFlow(1)
    val servings: StateFlow<Int> = _servings.asStateFlow()

    internal fun go(route: KitchenRoute?) {
        val wasCooking = _route.value is KitchenRoute.Cook
        _route.value = route
        val cooking = route is KitchenRoute.Cook
        // Cooking mode keeps the screen on (concept "Kochmodus"), only while it is open.
        if (cooking != wasCooking) platform.keepScreenOn(cooking)
    }

    fun openLibrary() = go(KitchenRoute.Library)

    fun openShopping() = go(KitchenRoute.Shopping)

    fun openRecipe(id: String) {
        val recipe = repo.recipeStore.recipe(id) ?: return
        _servings.value = RecipeViews.startServings(recipe)
        go(KitchenRoute.Detail(id))
    }

    fun newRecipe() = go(KitchenRoute.Edit(null))

    fun editRecipe(id: String) = go(KitchenRoute.Edit(id))

    fun startCooking(id: String) = go(KitchenRoute.Cook(id, _servings.value))

    fun close() = go(null)

    /** System back inside the kitchen. False when the kitchen is not open. */
    fun back(): Boolean {
        val next = when (val r = _route.value) {
            null -> return false
            KitchenRoute.Library, KitchenRoute.Shopping -> null
            is KitchenRoute.Detail -> KitchenRoute.Library
            is KitchenRoute.Edit -> r.id?.let { KitchenRoute.Detail(it) } ?: KitchenRoute.Library
            is KitchenRoute.Cook -> KitchenRoute.Detail(r.id)
        }
        go(next)
        return true
    }

    fun setServings(value: Int) {
        _servings.value = value.coerceIn(RecipeViews.MIN_SERVINGS, RecipeViews.MAX_SERVINGS)
    }
}

/** Where slow work runs: [scope] lives as long as the app model, [io] for network and files. */
internal class Background(val scope: CoroutineScope, val io: CoroutineDispatcher)

/** Creating, editing, confirming and cooking recipes. */
class RecipeActions internal constructor(
    private val repo: FamilyRepository,
    private val nav: KitchenModel,
    private val show: (String, Undo?) -> Unit,
    private val background: Background,
    /** Null where the platform cannot fetch pages: link-only recipes then wait for pasted text. */
    private val instagram: InstagramImport?,
    /** Cloud AI if switched on in the settings (API key entered), else null. Asked each time. */
    private val ai: () -> RecipeAi?,
) {
    private val store get() = repo.recipeStore

    /** Text typed or pasted in "Neues Rezept": the rules build it, the person sees the result at once. */
    fun createFromText(text: String) {
        if (text.isBlank()) return
        val draft = RecipeImport.fromText(text)
        val (recipe, undo) = store.add(draft, RecipeStatus.CONFIRMED)
        nav.openRecipe(recipe.id)
        show(if (draft.needsText) NO_INGREDIENTS_FOUND else RECIPE_SAVED, undo)
    }

    /**
     * Shared from another app (Instagram, browser, WhatsApp): saved at once as a suggestion, without
     * a dialog ("schnell rein, komplett später"). Somebody confirms it later in the library.
     */
    fun importShared(text: String) {
        val draft = RecipeImport.fromText(text)
        val (recipe, undo) = store.add(draft, RecipeStatus.SUGGESTED)
        val shortcode = draft.sourceUrl?.let(InstagramLink::shortcode)
        val fetcher = instagram
        if (!draft.needsText || shortcode == null || fetcher == null) {
            show(if (draft.needsText) SHARED_LINK_ONLY else SHARED_RECIPE, undo)
            return
        }
        // Instagram hands over only the link: fetch the caption on the phone (ADR 0007).
        show(FETCHING_CAPTION, undo)
        background.scope.launch {
            val post = withContext(background.io) { runCatching { fetcher.fetch(shortcode) }.getOrNull() }
            fillFromInstagram(recipe, post)
        }
    }

    /** Puts the fetched caption into the suggestion, unless somebody touched it in the meantime. */
    private fun fillFromInstagram(added: Recipe, post: InstagramPost?) {
        val untouched = store.recipe(added.id)?.stamp == added.stamp
        when {
            !untouched -> Unit
            post == null -> show(CAPTION_UNAVAILABLE, null)
            else -> fillCaption(added.id, post)
        }
    }

    private fun fillCaption(id: String, post: InstagramPost) {
        val draft = RecipeImport.fromText(post.caption)
        val undo = store.fillSuggestion(id, draft)
        val cloud = if (draft.needsText) ai() else null
        val filled = store.recipe(id)
        if (cloud == null || filled == null) {
            show(
                when {
                    !draft.needsText -> CAPTION_FETCHED
                    post.isVideo -> CAPTION_WITHOUT_INGREDIENTS_VIDEO
                    else -> CAPTION_WITHOUT_INGREDIENTS
                },
                undo,
            )
        } else {
            // The rules found no ingredients; the person switched the cloud AI on (ADR 0007, 0008).
            val fetcher = instagram ?: return
            val video = fetcher.videoFor(post, cloud)
            show(if (video != null) ASKING_AI_VIDEO else ASKING_AI, undo)
            background.scope.launch {
                val (outcome, sawVideo) = withContext(background.io) {
                    runCatching { fetcher.ask(cloud, post.caption, video) }
                        .getOrElse { AiOutcome.Failed(AI_NO_ANSWER) to false }
                }
                fillFromAi(filled, post, outcome, sawVideo)
            }
        }
    }

    /** The AI's reading goes into the suggestion like the rules' would; a person still confirms. */
    private fun fillFromAi(filled: Recipe, post: InstagramPost, outcome: AiOutcome, sawVideo: Boolean) {
        val current = store.recipe(filled.id) ?: return
        if (current.stamp != filled.stamp) return
        val draft = (outcome as? AiOutcome.Ok)?.recipe?.toDraft(post.caption)
        when {
            outcome is AiOutcome.Failed -> show("$AI_FAILED ${outcome.reason}", null)
            draft == null || draft.needsText -> show(
                when {
                    sawVideo -> AI_FOUND_NOTHING_IN_VIDEO
                    post.isVideo -> AI_FOUND_NOTHING_VIDEO
                    else -> AI_FOUND_NOTHING
                },
                null,
            )
            else -> show(AI_FILLED, store.fillSuggestion(filled.id, draft))
        }
    }

    fun save(id: String, edit: RecipeEdit) {
        val undo = store.update(id, edit)
        nav.go(KitchenRoute.Detail(id))
        show(RECIPE_SAVED, undo)
    }

    /** Text pasted into an existing recipe (e.g. the caption of an Instagram post that came as a link). */
    fun fillFromText(id: String, text: String) {
        val recipe = store.recipe(id) ?: return
        if (text.isBlank()) return
        val draft = RecipeImport.fromText(text)
        val keepTitle = draft.title == RecipeImport.UNTITLED || draft.title == RecipeImport.INSTAGRAM_TITLE
        save(
            id,
            RecipeEdit(
                title = if (keepTitle) recipe.title else draft.title,
                servings = draft.servings ?: recipe.servings,
                totalMinutes = draft.totalMinutes ?: recipe.totalMinutes,
                ingredientLines = draft.ingredients.joinToString("\n") { it.text },
                stepLines = draft.steps.joinToString("\n"),
                sourceUrl = recipe.sourceUrl ?: draft.sourceUrl,
                notes = recipe.notes,
            ),
        )
    }

    fun confirm(recipe: Recipe) = show(CONFIRMED, store.confirm(recipe.id))

    fun delete(recipe: Recipe) {
        val undo = store.delete(recipe.id)
        nav.go(KitchenRoute.Library)
        show(DELETED, undo)
    }

    /** "Gekocht" at the end of the cooking mode. */
    fun cooked(id: String) {
        val undo = store.markCooked(id)
        nav.go(KitchenRoute.Detail(id))
        show(COOKED, undo)
    }

    companion object {
        const val RECIPE_SAVED = "Rezept gespeichert."
        const val NO_INGREDIENTS_FOUND = "Gespeichert. Keine Zutatenliste erkannt, bitte unter „Bearbeiten“ ergänzen."
        const val SHARED_RECIPE = "Als Rezeptvorschlag gespeichert. Liegt unter Familie › Kochbibliothek."
        const val SHARED_LINK_ONLY = "Link als Rezeptvorschlag gespeichert. Text fehlt noch, siehe Kochbibliothek."
        const val FETCHING_CAPTION = "Link als Rezeptvorschlag gespeichert. Hole den Text von Instagram …"
        const val CAPTION_FETCHED = "Rezepttext von Instagram geholt. Vorschlag liegt in der Kochbibliothek."
        const val CAPTION_WITHOUT_INGREDIENTS =
            "Text von Instagram geholt, aber keine Zutatenliste darin. Bitte im Vorschlag ergänzen."
        const val CAPTION_WITHOUT_INGREDIENTS_VIDEO =
            "Text von Instagram geholt, aber keine Zutatenliste darin. Steht das Rezept nur im Video? Bitte ergänzen."
        const val ASKING_AI = "Text von Instagram geholt. Die Regeln finden keine Zutaten, frage die Cloud-KI …"
        const val ASKING_AI_VIDEO =
            "Text von Instagram geholt, aber ohne Zutaten. Die Cloud-KI sieht sich das Video an …"
        const val AI_FILLED = "Rezept mit Cloud-KI ausgelesen. Bitte im Vorschlag prüfen."
        const val AI_FAILED = "Cloud-KI hat nicht geholfen."
        const val AI_NO_ANSWER = "Keine Antwort."
        const val AI_FOUND_NOTHING = "Auch die Cloud-KI findet im Text kein Rezept. Bitte im Vorschlag ergänzen."
        const val AI_FOUND_NOTHING_VIDEO =
            "Auch die Cloud-KI findet im Text kein Rezept. Steht es nur im Video? Bitte ergänzen."
        const val AI_FOUND_NOTHING_IN_VIDEO =
            "Auch im Video findet die Cloud-KI kein Rezept. Bitte im Vorschlag ergänzen."
        const val CAPTION_UNAVAILABLE =
            "Text von Instagram nicht abrufbar. Beschreibung in Instagram kopieren und im Vorschlag einfügen."
        const val CONFIRMED = "Rezept bestätigt."
        const val DELETED = "Rezept gelöscht."
        const val COOKED = "Als gekocht vermerkt."
    }
}

/** The shared shopping list: from recipes, typed, ticked, cleared after shopping. */
class ShoppingActions internal constructor(
    private val repo: FamilyRepository,
    private val nav: KitchenModel,
    private val show: (String, Undo?) -> Unit,
) {
    private val store get() = repo.shoppingStore

    /** All ingredients at the chosen servings; basics (salt, oil) come as "Hast du noch …?". */
    fun addRecipe(recipe: Recipe) {
        val entries = ShoppingList.fromRecipe(recipe, RecipeViews.factor(recipe, nav.servings.value))
        if (entries.isEmpty()) {
            show(NOTHING_TO_BUY, null)
            return
        }
        val undo = store.addEntries(entries, recipe.id)
        val asks = entries.count { it.ask }
        val message = if (asks == 0) {
            "${entries.size} Zutaten auf der Einkaufsliste."
        } else {
            "${entries.size - asks} Zutaten auf der Einkaufsliste, $asks als Frage „Hast du noch …?“."
        }
        show(message, undo)
    }

    /** "Milch und Klopapier" or "2 l Milch, Eier": every part becomes its own line. */
    fun addTyped(text: String) {
        val entries = ShoppingList.splitTyped(text)
        if (entries.isNotEmpty()) store.addEntries(entries)
    }

    fun toggle(line: ShoppingLine) {
        store.setChecked(line.entryIds, !line.checked)
    }

    fun needIt(line: ShoppingLine) {
        store.needIt(line.entryIds)
    }

    fun remove(line: ShoppingLine) = show("${line.name} entfernt.", store.remove(line.entryIds))

    fun clearChecked() = show(CLEARED, store.clearChecked())

    companion object {
        const val NOTHING_TO_BUY = "Dieses Rezept hat keine Zutaten zum Einkaufen."
        const val CLEARED = "Abgehakte Einträge entfernt."
    }
}
