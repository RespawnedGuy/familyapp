package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KitchenRepositoryTest {
    private val friday = LocalDate(2026, 11, 20)
    private var clockMs = 1_000L
    private var ids = 0

    private fun TestScope.repo(store: TextStore = InMemoryTextStore()) = FamilyRepository(
        store = store,
        scope = backgroundScope,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        now = { clockMs++ },
        today = { friday },
        newId = { "id${ids++}" },
    ).apply { createFamily("Nils") }

    private val text = "Pfannkuchen\nZutaten:\n200 g Mehl\n2 Eier\n300 ml Milch\n1 Prise Salz\nZubereitung:\nAlles verrühren.\nAusbacken."

    @Test
    fun addedRecipeCarriesWhoAndIsStoredAndReloaded() = runTest {
        val store = InMemoryTextStore()
        val r = repo(store)
        val (recipe, _) = r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.SUGGESTED)
        assertEquals(r.current.selfId, recipe.createdBy)
        assertEquals(4, recipe.ingredients.size)
        assertEquals(text.replace("\n", "\n"), recipe.sourceText)
        val reloaded = FamilyRepository(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { clockMs++ }, { friday }, { "x${ids++}" })
        assertEquals(recipe, reloaded.current.recipes.single())
    }

    @Test
    fun editConfirmsAndReparsesLines() = runTest {
        val r = repo()
        val (recipe, _) = r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.SUGGESTED)
        r.recipeStore.update(recipe.id, RecipeEdit("Pfannkuchen", 4.0, 20, "250 g Mehl\n3 Eier", "Rühren\nBacken", " https://example.org ", "doppelt"))
        val after = assertNotNull(r.recipeStore.recipe(recipe.id))
        assertEquals(RecipeStatus.CONFIRMED, after.status)
        assertEquals(listOf(250.0, 3.0), after.ingredients.map { it.amount })
        assertEquals(listOf("Rühren", "Backen"), after.steps)
        assertEquals("https://example.org", after.sourceUrl)
        assertEquals("doppelt", after.notes)
        assertTrue(after.stamp > recipe.stamp)
    }

    @Test
    fun deleteEmptiesTheRecordAndUndoBringsItBackWithNewerStamp() = runTest {
        val r = repo()
        val (recipe, _) = r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED)
        val undo = r.recipeStore.delete(recipe.id)
        val deleted = r.current.recipes.single()
        assertTrue(deleted.deleted)
        assertTrue(deleted.ingredients.isEmpty() && deleted.steps.isEmpty() && deleted.sourceText == null)
        assertNull(r.recipeStore.recipe(recipe.id))
        r.undo(undo)
        val back = r.current.recipes.single()
        assertFalse(back.deleted)
        assertEquals(recipe.ingredients, back.ingredients)
        assertTrue(back.stamp > deleted.stamp, "undo must win over the deletion on other phones")
    }

    @Test
    fun undoOfCreationDeletesForEverybody() = runTest {
        val r = repo()
        val (recipe, undo) = r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.SUGGESTED)
        r.undo(undo)
        val gone = r.current.recipes.single()
        assertTrue(gone.deleted)
        assertTrue(gone.stamp > recipe.stamp)
    }

    @Test
    fun cookedSetsDateAndCount() = runTest {
        val r = repo()
        val (recipe, _) = r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED)
        r.recipeStore.markCooked(recipe.id)
        r.recipeStore.markCooked(recipe.id)
        val after = assertNotNull(r.recipeStore.recipe(recipe.id))
        assertEquals(friday, after.cookedOn)
        assertEquals(2, after.cookedCount)
    }

    @Test
    fun shoppingTicksShowWhoAndClearAfterShopping() = runTest {
        val r = repo()
        val (recipe, _) = r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED)
        r.shoppingStore.addEntries(ShoppingList.fromRecipe(recipe), recipe.id)
        r.shoppingStore.addEntries(ShoppingList.splitTyped("Milch"))
        var view = ShoppingList.view(r.current.shopping)
        // Flour and salt are basics: asked, not put on the list.
        assertEquals(listOf("Eier", "Milch"), view.open.map { it.name })
        assertEquals("300 ml", view.open.single { it.name == "Milch" }.amounts)
        assertEquals(listOf("Mehl", "Salz"), view.ask.map { it.name })

        r.shoppingStore.needIt(view.ask.single { it.name == "Mehl" }.entryIds)
        r.shoppingStore.setChecked(view.open.single { it.name == "Milch" }.entryIds, true)
        view = ShoppingList.view(r.current.shopping)
        assertEquals(listOf("Salz"), view.ask.map { it.name })
        assertEquals(listOf("Eier", "Mehl"), view.open.map { it.name })
        assertEquals(listOf("Milch"), view.checked.map { it.name })
        assertTrue(r.current.shopping.filter { it.checked }.all { it.checkedBy == r.current.selfId })

        val undo = r.shoppingStore.clearChecked()
        assertTrue(ShoppingList.view(r.current.shopping).checked.isEmpty())
        r.undo(undo)
        assertEquals(listOf("Milch"), ShoppingList.view(r.current.shopping).checked.map { it.name })
    }

    @Test
    fun undoOfAddingEntriesRemovesThem() = runTest {
        val r = repo()
        val undo = r.shoppingStore.addEntries(ShoppingList.splitTyped("Brot, Käse"))
        assertEquals(2, ShoppingList.view(r.current.shopping).open.size)
        r.undo(undo)
        assertTrue(ShoppingList.view(r.current.shopping).isEmpty)
    }

    @Test
    fun backupRoundTripKeepsRecipesAndShopping() = runTest {
        val r = repo()
        r.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED)
        r.shoppingStore.addEntries(ShoppingList.splitTyped("Brot"))
        val decoded = FamilyJson.decode(r.exportBackup())
        assertEquals(r.current.recipes, decoded.recipes)
        assertEquals(r.current.shopping, decoded.shopping)
    }
}
