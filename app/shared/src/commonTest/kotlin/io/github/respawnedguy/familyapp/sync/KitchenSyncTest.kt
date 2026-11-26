package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recipes and the shopping list between phones (ADR 0006 merge rules, kitchen library). */
@OptIn(ExperimentalCoroutinesApi::class)
class KitchenSyncTest {
    private val friday = LocalDate(2026, 11, 20)

    private fun TestScope.device(name: String): Pair<FamilyRepository, () -> Unit> {
        var ms = 1_000_000L
        var ids = 0
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler), { ms }, { friday }, { "$name-${ids++}" },
        )
        return repo to { ms += 1_000 }
    }

    private fun path(name: String) = "/local/$name"

    private fun meet(a: FamilyRepository, b: FamilyRepository) {
        val forB = SyncMerge.newerThan(a.current, SyncMerge.digest(b.current))
        val forA = SyncMerge.newerThan(b.current, SyncMerge.digest(a.current))
        b.applySync(forB, ::path)
        a.applySync(forA, ::path)
    }

    private fun TestScope.pair(): Triple<FamilyRepository, FamilyRepository, Pair<() -> Unit, () -> Unit>> {
        val (a, tickA) = device("a")
        val (b, tickB) = device("b")
        a.createFamily("Nils")
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), null, ::path)
        return Triple(a, b, tickA to tickB)
    }

    private val text = "Salat\nZutaten:\n1 Gurke\n2 Tomaten\n1 EL Öl\nZubereitung:\nSchneiden und mischen."

    @Test
    fun recipeEditAndDeletionReachTheOtherPhone() = runTest {
        val (a, b, ticks) = pair()
        val (recipe, _) = a.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.SUGGESTED)
        meet(a, b)
        assertEquals(recipe, b.current.recipes.single())
        ticks.second()
        b.recipeStore.confirm(recipe.id)
        meet(a, b)
        assertEquals(RecipeStatus.CONFIRMED, a.recipeStore.recipe(recipe.id)?.status)
        ticks.first(); ticks.first()
        a.recipeStore.delete(recipe.id)
        meet(a, b)
        assertTrue(b.current.recipes.single().deleted)
        assertEquals(a.current.recipes, b.current.recipes)
    }

    @Test
    fun twoPeopleTickingInTheSameShopSeeEachOther() = runTest {
        val (a, b, ticks) = pair()
        a.shoppingStore.addEntries(ShoppingList.splitTyped("Milch, Brot"))
        meet(a, b)
        val milk = b.current.shopping.single { it.name == "Milch" }
        val bread = a.current.shopping.single { it.name == "Brot" }
        ticks.first(); a.shoppingStore.setChecked(listOf(bread.id), true)
        ticks.second(); b.shoppingStore.setChecked(listOf(milk.id), true)
        meet(a, b)
        assertTrue(a.current.shopping.all { it.checked })
        assertEquals(a.current.shopping.sortedBy { it.id }, b.current.shopping.sortedBy { it.id })
        // Applying the same again changes nothing (idempotent).
        assertEquals(0, a.applySync(SyncMerge.newerThan(b.current, emptyMap()), ::path))
    }

    @Test
    fun joiningCarriesOwnRecipesOver() = runTest {
        val (a, _) = device("a")
        val (b, _) = device("b")
        a.createFamily("Nils")
        b.createFamily("Lisa")
        b.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED)
        b.shoppingStore.addEntries(ShoppingList.splitTyped("Kaffee"))
        val lisa = a.addMember("Lisa", io.github.respawnedguy.familyapp.domain.MemberKind.ADULT)
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), lisa.id, ::path)
        assertEquals(lisa.id, b.current.recipes.single().createdBy)
        assertEquals(lisa.id, b.current.shopping.single().createdBy)
        meet(a, b)
        assertEquals("Salat", a.current.recipes.single().title)
    }

    /** What an app version before the kitchen library knows of the sync records. */
    @Serializable
    private data class OldRecords(
        val members: List<kotlinx.serialization.json.JsonElement> = emptyList(),
        val items: List<kotlinx.serialization.json.JsonElement> = emptyList(),
        val tombstones: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    )

    @Test
    fun olderAppVersionsStillReadTheRecords() = runTest {
        val (a, _) = pair()
        a.recipeStore.delete(a.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED).first.id)
        a.shoppingStore.addEntries(ShoppingList.splitTyped("Milch"))
        val wire = syncJson.encodeToString(SyncRecords.serializer(), SyncMerge.newerThan(a.current, emptyMap()))
        // Same settings as the old app: unknown fields are skipped; deleting a recipe made no new tombstone kind.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(OldRecords.serializer(), wire)
        assertEquals(a.current.members.size, old.members.size)
        assertTrue(old.tombstones.isEmpty())
        // And the new app reads records from an old one (no recipe fields at all).
        val fromOld = syncJson.decodeFromString(SyncRecords.serializer(), """{"members":[],"items":[],"tombstones":[]}""")
        assertTrue(fromOld.recipes.isEmpty() && fromOld.shopping.isEmpty())
    }
}
