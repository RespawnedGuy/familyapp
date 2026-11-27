package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.recipe.Amounts
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.RecipeViews
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.kitchen_batches_value
import io.github.respawnedguy.familyapp.resources.kitchen_confirm
import io.github.respawnedguy.familyapp.resources.kitchen_cook
import io.github.respawnedguy.familyapp.resources.kitchen_edit
import io.github.respawnedguy.familyapp.resources.kitchen_fill_hint
import io.github.respawnedguy.familyapp.resources.kitchen_ingredients
import io.github.respawnedguy.familyapp.resources.kitchen_minutes
import io.github.respawnedguy.familyapp.resources.kitchen_needs_text_banner
import io.github.respawnedguy.familyapp.resources.kitchen_no_ingredients
import io.github.respawnedguy.familyapp.resources.kitchen_no_steps
import io.github.respawnedguy.familyapp.resources.kitchen_notes
import io.github.respawnedguy.familyapp.resources.kitchen_paste_label
import io.github.respawnedguy.familyapp.resources.kitchen_photo_fetch
import io.github.respawnedguy.familyapp.resources.kitchen_read_text
import io.github.respawnedguy.familyapp.resources.kitchen_servings_less
import io.github.respawnedguy.familyapp.resources.kitchen_servings_more
import io.github.respawnedguy.familyapp.resources.kitchen_servings_value
import io.github.respawnedguy.familyapp.resources.kitchen_source
import io.github.respawnedguy.familyapp.resources.kitchen_source_open
import io.github.respawnedguy.familyapp.resources.kitchen_source_text
import io.github.respawnedguy.familyapp.resources.kitchen_steps
import io.github.respawnedguy.familyapp.resources.kitchen_suggestion_banner
import io.github.respawnedguy.familyapp.resources.kitchen_to_shopping
import org.jetbrains.compose.resources.stringResource

/** One recipe: servings to scale, ingredients, steps, and the ways on (cook, shop, edit). */
@Composable
internal fun RecipeScreen(recipe: Recipe, model: AppModel, photos: PhotoLoader?) {
    val c = LocalFamilyColors.current
    val servings by model.kitchen.servings.collectAsState()
    val factor = RecipeViews.factor(recipe, servings)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = KitchenPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The dish across the full width on top; a tap shows it whole.
        recipe.photoPath?.let { path -> item { PhotoThumb(path, photos, height = 220) } }
        if (recipe.status == RecipeStatus.SUGGESTED) item { SuggestionBanner(recipe, model) }
        if (recipe.ingredients.isEmpty()) item { PasteTextCard(recipe, model, banner = true) }
        item { RecipeFacts(recipe) }
        item { ServingsStepper(recipe, servings, model) }
        item { RecipeActionsColumn(recipe, model) }
        item {
            SectionHeader(stringResource(Res.string.kitchen_ingredients), recipe.ingredients.size)
        }
        if (recipe.ingredients.isEmpty()) {
            item { EmptyNote(stringResource(Res.string.kitchen_no_ingredients)) }
        }
        itemsIndexed(recipe.ingredients, key = { i, _ -> "i$i" }) { _, ingredient ->
            Text(
                Amounts.line(ingredient, factor),
                Modifier.padding(vertical = 2.dp),
                color = c.text, fontSize = 16.sp,
            )
        }
        item { SectionHeader(stringResource(Res.string.kitchen_steps), recipe.steps.size) }
        if (recipe.steps.isEmpty()) item { EmptyNote(stringResource(Res.string.kitchen_no_steps)) }
        itemsIndexed(recipe.steps, key = { i, _ -> "s$i" }) { i, step -> StepRow(i, step) }
        if (recipe.notes.isNotBlank()) {
            item { SectionHeader(stringResource(Res.string.kitchen_notes)) }
            item { Text(recipe.notes, color = c.text, fontSize = 15.sp) }
        }
        recipe.sourceText?.let { original ->
            item { SectionHeader(stringResource(Res.string.kitchen_source_text)) }
            item { Text(original, color = c.textSecondary, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun StepRow(index: Int, step: String) {
    val c = LocalFamilyColors.current
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            "${index + 1}.",
            Modifier.padding(end = 8.dp),
            color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(step, color = c.text, fontSize = 16.sp)
    }
}

@Composable
private fun SuggestionBanner(recipe: Recipe, model: AppModel) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.kitchen_suggestion_banner), color = c.text, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.kitchen.recipes.confirm(recipe) }) {
                Text(stringResource(Res.string.kitchen_confirm))
            }
            OutlinedButton(onClick = { model.kitchen.editRecipe(recipe.id) }) {
                Text(stringResource(Res.string.kitchen_edit))
            }
        }
    }
}

/** For a link that came without text, or to read a recipe again from new text. */
@Composable
internal fun PasteTextCard(recipe: Recipe, model: AppModel, banner: Boolean) {
    val c = LocalFamilyColors.current
    var text by remember(recipe.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val hint = if (banner) Res.string.kitchen_needs_text_banner else Res.string.kitchen_fill_hint
        Text(stringResource(hint), color = c.text, fontSize = 15.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.kitchen_paste_label)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { model.kitchen.recipes.fillFromText(recipe.id, text) }, enabled = text.isNotBlank()) {
            Text(stringResource(Res.string.kitchen_read_text))
        }
    }
}

@Composable
private fun RecipeFacts(recipe: Recipe) {
    val c = LocalFamilyColors.current
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recipe.totalMinutes?.let {
            Text(stringResource(Res.string.kitchen_minutes, it), color = c.textSecondary, fontSize = 14.sp)
        }
        recipe.sourceUrl?.let { url ->
            val label = stringResource(Res.string.kitchen_source) + ": " + RecipeImport.host(url)
            if (RecipeImport.isWebLink(url)) {
                val open = stringResource(Res.string.kitchen_source_open)
                TextButton(
                    onClick = { runCatching { uri.openUri(url) } },
                    modifier = Modifier.semantics { contentDescription = "$open, ${RecipeImport.host(url)}" },
                ) { Text(label) }
            } else {
                Text(label, color = c.textSecondary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ServingsStepper(recipe: Recipe, servings: Int, model: AppModel) {
    val c = LocalFamilyColors.current
    val label = if (recipe.servings == null) {
        stringResource(Res.string.kitchen_batches_value, servings)
    } else {
        stringResource(Res.string.kitchen_servings_value, servings)
    }
    val less = stringResource(Res.string.kitchen_servings_less)
    val more = stringResource(Res.string.kitchen_servings_more)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { model.kitchen.setServings(servings - 1) },
            enabled = servings > RecipeViews.MIN_SERVINGS,
            modifier = Modifier.size(56.dp, 48.dp).semantics { contentDescription = less },
        ) { Text("−", fontSize = 20.sp) }
        Text(label, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { model.kitchen.setServings(servings + 1) },
            enabled = servings < RecipeViews.MAX_SERVINGS,
            modifier = Modifier.size(56.dp, 48.dp).semantics { contentDescription = more },
        ) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
private fun RecipeActionsColumn(recipe: Recipe, model: AppModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = { model.kitchen.startCooking(recipe.id) },
            enabled = recipe.steps.isNotEmpty() || recipe.ingredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(stringResource(Res.string.kitchen_cook), fontSize = 16.sp) }
        OutlinedButton(
            onClick = { model.kitchen.shopping.addRecipe(recipe) },
            enabled = recipe.ingredients.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(Res.string.kitchen_to_shopping)) }
        TextButton(onClick = { model.kitchen.editRecipe(recipe.id) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.kitchen_edit))
        }
        // Recipes imported before pictures existed, or when the cover could not be loaded then.
        if (model.kitchen.pictures.canFetch(recipe)) {
            TextButton(onClick = { model.kitchen.pictures.fetch(recipe) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.kitchen_photo_fetch))
            }
        }
    }
}
