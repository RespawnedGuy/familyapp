package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingLine
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingList
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_add
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_add_placeholder
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_ask_line
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_ask_title
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_checked
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_clear
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_empty
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_for
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_need
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_remove
import io.github.respawnedguy.familyapp.resources.kitchen_shopping_title
import org.jetbrains.compose.resources.stringResource

/**
 * The family's shared shopping list (docs/kochbibliothek.md "Einkaufsliste"): typed or from
 * recipes, same ingredients added up, basics as "Hast du noch …?", ticked lines move down.
 * Items of the tab Familie › Einkauf, between the meal suggestions and the offers.
 */
internal fun LazyListScope.shoppingListItems(data: FamilyData, model: AppModel) {
    val view = ShoppingList.view(data.shopping)
    val titles = data.recipes.associate { it.id to it.title }
    val shopping = model.kitchen.shopping
    item {
        SectionHeader(stringResource(Res.string.kitchen_shopping_title), view.open.size)
    }
    item { AddEntryField { shopping.addTyped(it) } }
    if (view.isEmpty) item { EmptyNote(stringResource(Res.string.kitchen_shopping_empty)) }
    items(view.open, key = { "o-${it.key}" }) { line ->
        EntryRow(line, titles, Modifier.padding(top = 6.dp)) { shopping.toggle(line) }
    }
    if (view.ask.isNotEmpty()) {
        item { SectionHeader(stringResource(Res.string.kitchen_shopping_ask_title), view.ask.size) }
    }
    items(view.ask, key = { "a-${it.key}" }) { line ->
        AskRow(line, onNeed = { shopping.needIt(line) }, onRemove = { shopping.remove(line) })
    }
    if (view.checked.isNotEmpty()) {
        item {
            SectionHeader(stringResource(Res.string.kitchen_shopping_checked), view.checked.size)
        }
    }
    items(view.checked, key = { "c-${it.key}" }) { line ->
        EntryRow(line, titles, Modifier.padding(top = 6.dp)) { shopping.toggle(line) }
    }
    if (view.checked.isNotEmpty()) {
        item {
            OutlinedButton(
                onClick = { shopping.clearChecked() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(stringResource(Res.string.kitchen_shopping_clear))
            }
        }
    }
}

@Composable
private fun AddEntryField(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val add = {
        onAdd(text)
        text = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(Res.string.kitchen_shopping_add_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) add() }),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = add, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(Res.string.kitchen_shopping_add))
        }
    }
}

/** Whole row is the tap target (≥ 48 dp), read out as one checkbox. */
@Composable
private fun EntryRow(line: ShoppingLine, recipeTitles: Map<String, String>, modifier: Modifier, onToggle: () -> Unit) {
    val c = LocalFamilyColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
            .toggleable(value = line.checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = line.checked, onCheckedChange = null)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            val text = listOfNotNull(line.amounts, line.name).joinToString(" ")
            Text(
                text,
                color = if (line.checked) c.textTertiary else c.text,
                fontSize = 16.sp,
                textDecoration = if (line.checked) TextDecoration.LineThrough else null,
            )
            val from = line.recipeIds.mapNotNull { recipeTitles[it] }
            if (from.isNotEmpty()) {
                Text(
                    stringResource(Res.string.kitchen_shopping_for, from.joinToString(", ")),
                    color = c.textTertiary, fontSize = 12.sp,
                )
            }
            // Where it is on offer (Empfehlung 12), so the person knows where to get it.
            line.offer?.let { Text(it, color = c.textSecondary, fontSize = 12.sp) }
        }
    }
}

/** Basics from a recipe, greyed: tapping "Brauchen wir" puts them on the list. */
@Composable
private fun AskRow(line: ShoppingLine, onNeed: () -> Unit, onRemove: () -> Unit) {
    val c = LocalFamilyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.kitchen_shopping_ask_line, line.name),
            Modifier.weight(1f),
            color = c.textTertiary, fontSize = 15.sp,
        )
        TextButton(onClick = onNeed) { Text(stringResource(Res.string.kitchen_shopping_need)) }
        TextButton(onClick = onRemove) {
            Text(stringResource(Res.string.kitchen_shopping_remove), color = c.textSecondary)
        }
    }
}
