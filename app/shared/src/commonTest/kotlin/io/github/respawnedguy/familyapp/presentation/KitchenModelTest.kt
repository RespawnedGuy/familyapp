package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class KitchenPlatform : PlatformServices {
    val screenOn = mutableListOf<Boolean>()
    override val speech: SpeechInput? = null
    override fun elapsedMs() = 0L
    override fun reducedMotion() = false
    override fun takePhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun writeBackup(folder: String, backup: BackupContent): String? = null
    override fun readBackup(folder: String, code: String?): RestoreRead = RestoreRead.Failed("leer")
    override fun newRecoveryCode() = "ABCD-EFGH-JKMN-PQRS-TVWX"
    override fun requestNotificationPermission() = Unit
    override fun describeFolder(folder: String) = folder
    override fun keepScreenOn(on: Boolean) { screenOn += on }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KitchenModelTest {
    private val friday = LocalDate(2026, 11, 20)
    private var ids = 0

    private fun TestScope.model(platform: KitchenPlatform = KitchenPlatform()): AppModel {
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            { testScheduler.currentTime }, { friday }, { "id${ids++}" },
        )
        return AppModel(repo, platform, backgroundScope, { friday }, UnconfinedTestDispatcher(testScheduler))
            .apply { createFamily("Nils") }
    }

    private val caption = "Ofengemüse für 2 Personen\nZutaten:\n500 g Kartoffeln\n2 Paprika\n2 EL Öl\n" +
        "Zubereitung:\n1. Gemüse schneiden.\n2. 30 Minuten backen."

    @Test
    fun sharedRecipeBecomesSuggestionNotInboxCard() = runTest {
        val m = model()
        m.captureShared(caption, null)
        assertTrue(m.repo.current.items.isEmpty(), "no inbox card for a recipe")
        val recipe = m.repo.current.recipes.single()
        assertEquals(RecipeStatus.SUGGESTED, recipe.status)
        assertEquals(RecipeActions.SHARED_RECIPE, m.feedback.value?.message)
        // Undo on the feedback bar takes it back.
        m.undo(m.feedback.value!!)
        assertTrue(m.repo.current.recipes.single().deleted)
    }

    @Test
    fun sharedInstagramLinkWaitsForText() = runTest {
        val m = model()
        m.captureShared("https://www.instagram.com/reel/XYZ/?igsh=abc", null)
        val recipe = m.repo.current.recipes.single()
        assertTrue(recipe.ingredients.isEmpty())
        assertEquals(RecipeActions.SHARED_LINK_ONLY, m.feedback.value?.message)
        m.kitchen.recipes.fillFromText(recipe.id, caption)
        val filled = m.repo.recipeStore.recipe(recipe.id)!!
        assertEquals("Ofengemüse für 2 Personen", filled.title)
        assertEquals(3, filled.ingredients.size)
        assertEquals("https://www.instagram.com/reel/XYZ/", filled.sourceUrl)
        assertEquals(RecipeStatus.CONFIRMED, filled.status)
    }

    @Test
    fun otherSharedTextStaysACapture() = runTest {
        val m = model()
        m.captureShared("Elternabend am Donnerstag", null)
        assertEquals(1, m.repo.current.items.size)
        assertTrue(m.repo.current.recipes.isEmpty())
    }

    @Test
    fun recipeTextWithPhotoStaysACapture() = runTest {
        val m = model()
        m.captureShared(caption, "/photos/x.jpg")
        assertEquals(1, m.repo.current.items.size)
    }

    @Test
    fun cookingKeepsScreenOnOnlyWhileOpen() = runTest {
        val platform = KitchenPlatform()
        val m = model(platform)
        m.kitchen.recipes.createFromText(caption)
        val id = m.repo.current.recipes.single().id
        assertEquals(KitchenRoute.Detail(id), m.kitchen.route.value)
        m.kitchen.setServings(4)
        m.kitchen.startCooking(id)
        assertEquals(KitchenRoute.Cook(id, 4), m.kitchen.route.value)
        assertTrue(m.handleBack())
        assertEquals(KitchenRoute.Detail(id), m.kitchen.route.value)
        m.kitchen.startCooking(id)
        m.kitchen.recipes.cooked(id)
        assertEquals(listOf(true, false, true, false), platform.screenOn)
        assertEquals(friday, m.repo.recipeStore.recipe(id)?.cookedOn)
    }

    @Test
    fun backWalksOutOfTheKitchenAndTabsClose() = runTest {
        val m = model()
        m.select(Tab.FAMILY)
        m.kitchen.openLibrary()
        m.kitchen.newRecipe()
        assertTrue(m.handleBack())
        assertEquals(KitchenRoute.Library, m.kitchen.route.value)
        assertTrue(m.handleBack())
        assertNull(m.kitchen.route.value)
        assertEquals(Tab.FAMILY, m.tab.value)
        m.kitchen.openShopping()
        m.select(Tab.TODAY)
        assertNull(m.kitchen.route.value)
        m.kitchen.openShopping()
        m.openSettings()
        assertNull(m.kitchen.route.value)
    }

    @Test
    fun shoppingFromRecipeUsesChosenServingsAndAsksForBasics() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(caption)
        val recipe = m.repo.current.recipes.single()
        m.kitchen.setServings(4)
        m.kitchen.shopping.addRecipe(recipe)
        val view = ShoppingList.view(m.repo.current.shopping)
        assertEquals("1 kg", view.open.single { it.name == "Kartoffeln" }.amounts)
        assertEquals(listOf("Öl"), view.ask.map { it.name })
        assertEquals("2 Zutaten auf der Einkaufsliste, 1 als Frage „Hast du noch …?“.", m.feedback.value?.message)
        m.kitchen.shopping.toggle(view.open.first())
        assertEquals(1, ShoppingList.view(m.repo.current.shopping).checked.size)
        m.kitchen.shopping.clearChecked()
        assertTrue(ShoppingList.view(m.repo.current.shopping).checked.isEmpty())
    }

    @Test
    fun servingsStayInBounds() = runTest {
        val m = model()
        m.kitchen.setServings(0)
        assertEquals(1, m.kitchen.servings.value)
        m.kitchen.setServings(1000)
        assertEquals(40, m.kitchen.servings.value)
    }

    @Test
    fun emptyTextCreatesNothingAndUnknownRecipeIsIgnored() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText("   ")
        assertTrue(m.repo.current.recipes.isEmpty())
        m.kitchen.openRecipe("nope")
        assertNull(m.kitchen.route.value)
        assertFalse(m.kitchen.back())
        m.kitchen.recipes.createFromText("Nur ein Gedanke ohne Zutaten")
        assertEquals(RecipeActions.NO_INGREDIENTS_FOUND, m.feedback.value?.message)
        val r = m.repo.current.recipes.single()
        m.kitchen.recipes.delete(r)
        assertEquals(KitchenRoute.Library, m.kitchen.route.value)
        assertIs<String>(m.feedback.value?.message)
    }
}
