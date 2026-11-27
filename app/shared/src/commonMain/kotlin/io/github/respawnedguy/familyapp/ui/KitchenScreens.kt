package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.RecipeViews
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.KitchenRoute
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.kitchen_add_recipe
import io.github.respawnedguy.familyapp.resources.kitchen_cooked_on
import io.github.respawnedguy.familyapp.resources.kitchen_edit_title
import io.github.respawnedguy.familyapp.resources.kitchen_empty
import io.github.respawnedguy.familyapp.resources.kitchen_ingredient_count
import io.github.respawnedguy.familyapp.resources.kitchen_minutes
import io.github.respawnedguy.familyapp.resources.kitchen_needs_text
import io.github.respawnedguy.familyapp.resources.kitchen_new_title
import io.github.respawnedguy.familyapp.resources.kitchen_nothing_found
import io.github.respawnedguy.familyapp.resources.kitchen_search_placeholder
import io.github.respawnedguy.familyapp.resources.kitchen_suggestion
import io.github.respawnedguy.familyapp.resources.kitchen_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

internal val KitchenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

/** Title for the top bar while the kitchen is open. */
@Composable
fun kitchenTitle(route: KitchenRoute, data: FamilyData): String {
    val library = stringResource(Res.string.kitchen_title)
    fun titleOf(id: String) = data.recipes.firstOrNull { it.id == id }?.title ?: library
    return when (route) {
        is KitchenRoute.Edit ->
            stringResource(if (route.id == null) Res.string.kitchen_new_title else Res.string.kitchen_edit_title)
        is KitchenRoute.Detail -> titleOf(route.id)
        is KitchenRoute.Cook -> titleOf(route.id)
    }
}

/** Content area of the kitchen; the cooking mode itself is [CookOverlay] on top of everything. */
@Composable
fun KitchenScreen(route: KitchenRoute, data: FamilyData, model: AppModel, photos: PhotoLoader?) {
    when (route) {
        is KitchenRoute.Edit -> {
            val recipe = route.id?.let { id -> data.recipes.firstOrNull { it.id == id && !it.deleted } }
            if (recipe == null) NewRecipeScreen(model) else EditRecipeScreen(recipe, model, photos)
        }
        is KitchenRoute.Detail -> RecipeOrLibrary(route.id, data, model, photos)
        is KitchenRoute.Cook -> RecipeOrLibrary(route.id, data, model, photos)
    }
}

/** A recipe deleted on another phone meanwhile: show the library instead of an empty page. */
@Composable
private fun RecipeOrLibrary(id: String, data: FamilyData, model: AppModel, photos: PhotoLoader?) {
    val recipe = data.recipes.firstOrNull { it.id == id && !it.deleted }
    if (recipe == null) LibraryScreen(data, model, photos) else RecipeScreen(recipe, model, photos)
}

/** Tab "Rezepte" under Familie: search, new recipe, the whole library (suggestions marked). */
@Composable
internal fun LibraryScreen(data: FamilyData, model: AppModel, photos: PhotoLoader?) {
    var query by remember { mutableStateOf("") }
    val all = RecipeViews.library(data.recipes)
    val shown = RecipeViews.library(data.recipes, query)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = KitchenPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(Res.string.kitchen_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = { model.kitchen.newRecipe() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.kitchen_add_recipe))
            }
        }
        if (all.isEmpty()) item { EmptyNote(stringResource(Res.string.kitchen_empty)) }
        if (all.isNotEmpty() && shown.isEmpty()) {
            item { EmptyNote(stringResource(Res.string.kitchen_nothing_found)) }
        }
        items(shown, key = { it.id }) { recipe ->
            RecipeRow(recipe, photos) { model.kitchen.openRecipe(recipe.id) }
        }
    }
}

@Composable
private fun RecipeRow(recipe: Recipe, photos: PhotoLoader?, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).clickable(onClick = onClick)
            .padding(12.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        recipe.photoPath?.let { RecipeThumb(it, photos) }
        RecipeRowText(recipe)
    }
}

/** Square picture of the dish in the library list; nothing (not even a gap) while it loads or without one. */
@Composable
private fun RecipeThumb(path: String, photos: PhotoLoader?) {
    if (photos == null) return
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.Default) { photos.load(path, THUMB_PX) }
    }
    bitmap?.let {
        // Decorative: the title next to it says what the recipe is.
        Image(
            it,
            contentDescription = null,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

/** Decoded size of a list picture: 64 dp at up to 4× density. */
private const val THUMB_PX = 256

@Composable
private fun RecipeRowText(recipe: Recipe) {
    val c = LocalFamilyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(recipe.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        val details = buildList {
            if (recipe.status == RecipeStatus.SUGGESTED) add(stringResource(Res.string.kitchen_suggestion))
            if (recipe.ingredients.isEmpty()) {
                add(stringResource(Res.string.kitchen_needs_text))
            } else {
                add(stringResource(Res.string.kitchen_ingredient_count, recipe.ingredients.size))
            }
            recipe.totalMinutes?.let { add(stringResource(Res.string.kitchen_minutes, it)) }
            recipe.cookedOn?.let { add(stringResource(Res.string.kitchen_cooked_on, DateFormat.short(it))) }
        }
        val suggested = recipe.status == RecipeStatus.SUGGESTED
        Text(details.joinToString(" · "), color = if (suggested) c.accent else c.textTertiary, fontSize = 13.sp)
    }
}
