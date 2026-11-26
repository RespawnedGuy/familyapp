package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.data.RecipeEdit
import io.github.respawnedguy.familyapp.domain.recipe.Amounts
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.kitchen_delete
import io.github.respawnedguy.familyapp.resources.kitchen_field_ingredients
import io.github.respawnedguy.familyapp.resources.kitchen_field_minutes
import io.github.respawnedguy.familyapp.resources.kitchen_field_notes
import io.github.respawnedguy.familyapp.resources.kitchen_field_servings
import io.github.respawnedguy.familyapp.resources.kitchen_field_source
import io.github.respawnedguy.familyapp.resources.kitchen_field_steps
import io.github.respawnedguy.familyapp.resources.kitchen_field_title
import io.github.respawnedguy.familyapp.resources.kitchen_fill_title
import io.github.respawnedguy.familyapp.resources.kitchen_new_create
import io.github.respawnedguy.familyapp.resources.kitchen_new_hint
import io.github.respawnedguy.familyapp.resources.kitchen_paste_label
import io.github.respawnedguy.familyapp.resources.kitchen_save
import org.jetbrains.compose.resources.stringResource

/** One text field instead of a form with twelve fields (concept "Weg 3: von Hand"). */
@Composable
internal fun NewRecipeScreen(model: AppModel) {
    val c = LocalFamilyColors.current
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KitchenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.kitchen_new_hint), color = c.textSecondary, fontSize = 14.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.kitchen_paste_label)) },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { model.kitchen.recipes.createFromText(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(stringResource(Res.string.kitchen_new_create), fontSize = 16.sp) }
    }
}

/** Editing keeps the structure as text: ingredients one per line, steps one per line. */
@Composable
internal fun EditRecipeScreen(recipe: Recipe, model: AppModel) {
    val c = LocalFamilyColors.current
    var title by remember(recipe.id) { mutableStateOf(recipe.title) }
    var servings by remember(recipe.id) { mutableStateOf(recipe.servings?.let { Amounts.decimal(it) }.orEmpty()) }
    var minutes by remember(recipe.id) { mutableStateOf(recipe.totalMinutes?.toString().orEmpty()) }
    var ingredients by remember(recipe.id) { mutableStateOf(recipe.ingredients.joinToString("\n") { it.text }) }
    var steps by remember(recipe.id) { mutableStateOf(recipe.steps.joinToString("\n")) }
    var source by remember(recipe.id) { mutableStateOf(recipe.sourceUrl.orEmpty()) }
    var notes by remember(recipe.id) { mutableStateOf(recipe.notes) }
    val save = {
        model.kitchen.recipes.save(
            recipe.id,
            RecipeEdit(
                title = title,
                servings = servings.replace(',', '.').toDoubleOrNull(),
                totalMinutes = minutes.trim().toIntOrNull(),
                ingredientLines = ingredients,
                stepLines = steps,
                sourceUrl = source,
                notes = notes,
            ),
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(KitchenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(title, { title = it }, stringResource(Res.string.kitchen_field_title), single = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val servingsLabel = stringResource(Res.string.kitchen_field_servings)
            val minutesLabel = stringResource(Res.string.kitchen_field_minutes)
            NumberField(servings, { servings = it }, servingsLabel, Modifier.weight(1f))
            NumberField(minutes, { minutes = it }, minutesLabel, Modifier.weight(1f))
        }
        Field(ingredients, { ingredients = it }, stringResource(Res.string.kitchen_field_ingredients))
        Field(steps, { steps = it }, stringResource(Res.string.kitchen_field_steps))
        Field(source, { source = it }, stringResource(Res.string.kitchen_field_source), single = true)
        Field(notes, { notes = it }, stringResource(Res.string.kitchen_field_notes))
        Button(onClick = save, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(Res.string.kitchen_save), fontSize = 16.sp)
        }
        SectionHeader(stringResource(Res.string.kitchen_fill_title))
        PasteTextCard(recipe, model, banner = false)
        TextButton(onClick = { model.kitchen.recipes.delete(recipe) }) {
            Text(stringResource(Res.string.kitchen_delete), color = c.overdue)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, single: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = single,
        minLines = if (single) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
