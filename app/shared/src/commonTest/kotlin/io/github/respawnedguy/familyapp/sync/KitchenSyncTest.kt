package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.ShoppingPlace
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
    fun plannedDishReachesTheOtherPhoneAndRemovalToo() = runTest {
        val (a, b, ticks) = pair()
        val (recipe, _) = a.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.CONFIRMED)
        a.mealStore.plan(recipe.id, friday, 4)
        meet(a, b)
        val meal = b.current.meals.single()
        assertEquals(friday to 4, meal.day to meal.servings)
        ticks.second()
        b.mealStore.remove(meal.id)
        meet(a, b)
        assertTrue(a.current.meals.single().deleted)
        assertEquals(0, SyncMerge.newerThan(a.current, SyncMerge.digest(b.current)).size)
    }

    @Test
    fun shoppingPlaceAndPasstNichtReachTheOtherPhone() = runTest {
        val (a, b, ticks) = pair()
        a.placeStore.setAddress("10115 Berlin")
        meet(a, b)
        assertEquals("10115", b.current.place?.postcode)
        ticks.second()
        b.placeStore.reject("grünkohl|grünkohl chips")
        b.placeStore.setChain(Chain.LIDL, false)
        meet(a, b)
        assertEquals(listOf("grünkohl|grünkohl chips"), a.current.place?.rejected)
        assertEquals(listOf(Chain.ALDI_NORD, Chain.KAUFLAND, Chain.PENNY), a.current.place?.markets)
        assertEquals(0, SyncMerge.newerThan(a.current, SyncMerge.digest(b.current)).size)
    }

    @Test
    fun an6_marketUnknownToThisVersionIsSkippedNotFatal() = runTest {
        val (a, _) = pair()
        a.placeStore.setAddress("10115")
        val wire = syncJson.encodeToString(SyncRecords.serializer(), SyncMerge.newerThan(a.current, emptyMap()))
            .replace("\"LIDL\"", "\"TEGUT\"")
        val records = syncJson.decodeFromString(SyncRecords.serializer(), wire)
        assertEquals(listOf(Chain.ALDI_NORD, Chain.KAUFLAND, Chain.PENNY), records.places.single().markets)
    }

    @Test
    fun an6_passtNichtListStaysBounded() = runTest {
        val (a, _) = pair()
        a.placeStore.setAddress("10115")
        repeat(ShoppingPlace.MAX_REJECTED + 3) { a.placeStore.reject("k$it|wort") }
        val rejected = a.current.place!!.rejected
        assertEquals(ShoppingPlace.MAX_REJECTED, rejected.size)
        assertEquals("k3|wort", rejected.first()) // the oldest went first
    }

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

    @Test
    fun recipePictureTravelsByFileName() = runTest {
        val (a, b, ticks) = pair()
        val (recipe, _) = a.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.SUGGESTED)
        a.recipeStore.attachPhoto(recipe.id, "/phone-a/photos/dish-1.jpg")
        meet(a, b)
        assertEquals("/local/dish-1.jpg", b.current.recipes.single().photoPath, "this phone's path for the same file")
        assertEquals(setOf("dish-1.jpg"), SyncMerge.photoNames(b.current), "b asks a for the file")
        assertEquals(setOf("dish-1.jpg"), SyncMerge.photoNames(a.current), "a offers it")
        // A picture is never replaced by attaching: the first one stays on every phone.
        ticks.second()
        assertEquals(null, b.recipeStore.attachPhoto(recipe.id, "/local/other.jpg"))
        ticks.first(); ticks.first()
        a.recipeStore.delete(recipe.id)
        meet(a, b)
        assertEquals(null, b.current.recipes.single().photoPath)
        assertTrue(SyncMerge.photoNames(b.current).isEmpty(), "a deleted recipe's picture is not fetched any more")
    }

    @Test
    fun joiningPhoneGetsItsOwnPathsForRecipePictures() = runTest {
        val (a, _) = device("a")
        a.createFamily("Nils")
        val (recipe, _) = a.recipeStore.add(RecipeImport.fromText(text), RecipeStatus.SUGGESTED)
        a.recipeStore.attachPhoto(recipe.id, "/phone-a/photos/dish-2.jpg")
        val (b, _) = device("b")
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), null, ::path)
        assertEquals("/local/dish-2.jpg", b.current.recipes.single().photoPath)
    }
}
