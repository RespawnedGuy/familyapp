package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.offers.IngredientSuggestion
import io.github.respawnedguy.familyapp.domain.offers.RecipeSuggestion
import io.github.respawnedguy.familyapp.domain.offers.Suggestions
import io.github.respawnedguy.familyapp.domain.recipe.MealPlan
import io.github.respawnedguy.familyapp.domain.recipe.PlannedMeal
import io.github.respawnedguy.familyapp.domain.recipe.RecipeViews
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.FamilySection
import io.github.respawnedguy.familyapp.presentation.PlanSheet
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.common_cancel
import io.github.respawnedguy.familyapp.resources.kitchen_batches_value
import io.github.respawnedguy.familyapp.resources.kitchen_servings_less
import io.github.respawnedguy.familyapp.resources.kitchen_servings_more
import io.github.respawnedguy.familyapp.resources.kitchen_servings_value
import io.github.respawnedguy.familyapp.resources.offers_add_to_list
import io.github.respawnedguy.familyapp.resources.offers_ingredients_title
import io.github.respawnedguy.familyapp.resources.offers_meals_title
import io.github.respawnedguy.familyapp.resources.offers_no_place
import io.github.respawnedguy.familyapp.resources.offers_not_matching
import io.github.respawnedguy.familyapp.resources.offers_not_matching_label
import io.github.respawnedguy.familyapp.resources.offers_plan
import io.github.respawnedguy.familyapp.resources.offers_plan_confirm
import io.github.respawnedguy.familyapp.resources.offers_plan_how_many
import io.github.respawnedguy.familyapp.resources.offers_plan_move
import io.github.respawnedguy.familyapp.resources.offers_plan_no_day
import io.github.respawnedguy.familyapp.resources.offers_plan_no_servings
import io.github.respawnedguy.familyapp.resources.offers_plan_open_recipe
import io.github.respawnedguy.familyapp.resources.offers_plan_remove
import io.github.respawnedguy.familyapp.resources.offers_plan_when
import io.github.respawnedguy.familyapp.resources.offers_set_place
import io.github.respawnedguy.familyapp.resources.offers_suggested_empty
import io.github.respawnedguy.familyapp.resources.offers_suggested_title
import io.github.respawnedguy.familyapp.resources.today_meals_more
import io.github.respawnedguy.familyapp.resources.offers_today_meal
import io.github.respawnedguy.familyapp.resources.offers_upcoming_title
import org.jetbrains.compose.resources.stringResource

/*
 * Offers, season and meal plan (docs/angebote-und-vorschlaege.md): today's dish under "Heute", the
 * suggestions above the shopping list (Familie › Einkauf), the plan under "Demnächst", the sheet
 * "Wann? / Für wie viele?" and Einstellungen › Einkauf. Texts come from the model (reasons, market
 * lines) or from strings.xml; nothing here decides anything.
 */

/**
 * Under Heute: today's planned dishes and one line to the suggestions above the shopping list.
 * Shown only when there is something to show; it never notifies (premise of 26.11.2026).
 */
@Composable
internal fun TodayMealsCard(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    // Offers change the count, so they are read here and the card recomposes when they arrive.
    val cache by model.offers.cache.collectAsState()
    val planned = MealPlan.today(data.meals, model.today())
    val suggestions = model.offers.suggestions.recipes(data, cache).size
    if (planned.isEmpty() && suggestions == 0) return
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(Res.string.offers_meals_title), Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        planned.forEach { meal -> TodayMealRow(meal, data, model) }
        if (suggestions > 0) {
            TextButton(
                onClick = { model.openSection(FamilySection.SHOPPING) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(Res.string.today_meals_more, suggestions)) }
        }
    }
}

/**
 * "Vorgeschlagene Rezepte" on top of Familie › Einkauf: up to five recipe cards with their reasons,
 * then produce in season and on offer while the library is small. When there is nothing to suggest
 * it says where suggestions come from instead of leaving a gap.
 */
@Composable
internal fun MealsCard(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    // Offers change the cards, so they are read here and the card recomposes when they arrive.
    val cache by model.offers.cache.collectAsState()
    val recipes = model.offers.suggestions.recipes(data, cache)
    val produce = model.offers.suggestions.ingredients(data, cache)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.offers_suggested_title), Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        if (recipes.isEmpty() && produce.isEmpty()) {
            Text(stringResource(Res.string.offers_suggested_empty), fontSize = 14.sp, color = c.textSecondary)
        }
        recipes.forEach { RecipeSuggestionRow(it, model) }
        if (produce.isNotEmpty()) {
            Text(stringResource(Res.string.offers_ingredients_title), fontSize = 14.sp, color = c.textSecondary)
            produce.forEach { IngredientRow(it, model) }
        }
        if (data.place == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.offers_no_place), Modifier.weight(1f),
                    fontSize = 12.sp, color = c.textTertiary,
                )
                TextButton(onClick = { model.openSettings() }) { Text(stringResource(Res.string.offers_set_place)) }
            }
        }
    }
}

@Composable
private fun TodayMealRow(meal: PlannedMeal, data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    val title = data.recipes.firstOrNull { it.id == meal.recipeId }?.title ?: return
    Text(
        stringResource(Res.string.offers_today_meal, title, meal.servings),
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable { model.offers.plan.openPlanned(meal) }.padding(vertical = 12.dp),
        fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text,
    )
}

/** One recipe card: title, why (market and saving, season), "Passt nicht" per counted offer, tap plans it. */
@Composable
private fun RecipeSuggestionRow(s: RecipeSuggestion, model: AppModel) {
    val c = LocalFamilyColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background)
            .clickable { model.offers.plan.open(s) }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.recipe.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text)
            Text(stringResource(Res.string.offers_plan), fontSize = 14.sp, color = c.accent)
        }
        s.offers.take(Suggestions.MAX_REASONS).forEach { hit ->
            val label = stringResource(Res.string.offers_not_matching_label, hit.offer.title, hit.ingredient.name)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Suggestions.offerReason(hit.ingredient.name, hit.offer),
                        fontSize = 13.sp, color = c.textSecondary,
                    )
                    Text(hit.offer.title, fontSize = 12.sp, color = c.textTertiary)
                }
                TextButton(
                    onClick = { model.offers.suggestions.reject(hit) },
                    modifier = Modifier.semantics { contentDescription = label },
                ) { Text(stringResource(Res.string.offers_not_matching), fontSize = 12.sp) }
            }
        }
        val seasonRoom = (Suggestions.MAX_REASONS - s.offers.size).coerceAtLeast(0)
        s.season.take(seasonRoom).forEach { hit ->
            Text(Suggestions.seasonReason(hit.ingredient.name, hit.level), fontSize = 13.sp, color = c.textSecondary)
        }
    }
}

@Composable
private fun IngredientRow(s: IngredientSuggestion, model: AppModel) {
    val c = LocalFamilyColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.reason, Modifier.weight(1f), fontSize = 14.sp, color = c.text)
        TextButton(onClick = { model.offers.suggestions.addIngredient(s) }) {
            Text(stringResource(Res.string.offers_add_to_list))
        }
    }
}

/** "Essensplan" under Demnächst: every open dish from today on, those without a day at the end. */
@Composable
internal fun UpcomingMeals(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    val today = model.today()
    val meals = MealPlan.upcoming(data.meals, today)
    if (meals.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(Res.string.offers_upcoming_title), meals.size)
        meals.forEach { meal ->
            val title = data.recipes.firstOrNull { it.id == meal.recipeId }?.title ?: return@forEach
            val day = meal.day?.let { DateFormat.chip(it, today) } ?: stringResource(Res.string.offers_plan_no_day)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
                    .clickable { model.offers.plan.openPlanned(meal) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    day, Modifier.padding(end = 12.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary,
                )
                Text(title, Modifier.weight(1f), fontSize = 15.sp, color = c.text)
                Text(meal.servings.toString(), fontSize = 14.sp, color = c.textSecondary)
            }
        }
    }
}

/** The sheet after a tap: "Wann?" as chips, "Für wie viele?" with − and +, one button to the list. */
@Composable
internal fun PlanSheetDialog(sheet: PlanSheet, model: AppModel) {
    val c = LocalFamilyColors.current
    val today = model.today()
    val planned = sheet.mealId != null
    AlertDialog(
        onDismissRequest = { model.offers.plan.close() },
        title = { Text(sheet.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Question(stringResource(Res.string.offers_plan_when))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sheet.days.forEach { day ->
                        DayChip(DateFormat.chip(day, today), sheet.day == day) { model.offers.plan.setDay(day) }
                    }
                    DayChip(stringResource(Res.string.offers_plan_no_day), sheet.day == null) {
                        model.offers.plan.setDay(null)
                    }
                }
                if (!planned) ServingsChooser(sheet, model)
            }
        },
        confirmButton = {
            TextButton(onClick = { model.offers.plan.confirm() }) {
                Text(stringResource(if (planned) Res.string.offers_plan_move else Res.string.offers_plan_confirm))
            }
        },
        dismissButton = {
            if (planned) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { model.offers.plan.openRecipe() }) {
                        Text(stringResource(Res.string.offers_plan_open_recipe))
                    }
                    TextButton(onClick = { model.offers.plan.removePlanned() }) {
                        Text(stringResource(Res.string.offers_plan_remove))
                    }
                }
            } else {
                TextButton(onClick = { model.offers.plan.close() }) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun Question(text: String) {
    val c = LocalFamilyColors.current
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

@Composable
private fun ServingsChooser(sheet: PlanSheet, model: AppModel) {
    val c = LocalFamilyColors.current
    Question(stringResource(Res.string.offers_plan_how_many))
    val label = if (sheet.hasServings) {
        stringResource(Res.string.kitchen_servings_value, sheet.servings)
    } else {
        stringResource(Res.string.kitchen_batches_value, sheet.servings)
    }
    val less = stringResource(Res.string.kitchen_servings_less)
    val more = stringResource(Res.string.kitchen_servings_more)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { model.offers.plan.setServings(sheet.servings - 1) },
            enabled = sheet.servings > RecipeViews.MIN_SERVINGS,
            modifier = Modifier.size(56.dp, 48.dp).semantics { contentDescription = less },
        ) { Text("−", fontSize = 20.sp) }
        Text(label, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { model.offers.plan.setServings(sheet.servings + 1) },
            enabled = sheet.servings < RecipeViews.MAX_SERVINGS,
            modifier = Modifier.size(56.dp, 48.dp).semantics { contentDescription = more },
        ) { Text("+", fontSize = 20.sp) }
    }
    if (!sheet.hasServings) {
        Text(stringResource(Res.string.offers_plan_no_servings), fontSize = 12.sp, color = c.textTertiary)
    }
}
