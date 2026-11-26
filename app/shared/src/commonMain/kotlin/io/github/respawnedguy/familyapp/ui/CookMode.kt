package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.recipe.Amounts
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeViews
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.KitchenRoute
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.kitchen_cook_back
import io.github.respawnedguy.familyapp.resources.kitchen_cook_close
import io.github.respawnedguy.familyapp.resources.kitchen_cook_cooked
import io.github.respawnedguy.familyapp.resources.kitchen_cook_done_question
import io.github.respawnedguy.familyapp.resources.kitchen_cook_next
import io.github.respawnedguy.familyapp.resources.kitchen_cook_not_cooked
import io.github.respawnedguy.familyapp.resources.kitchen_cook_prepare
import io.github.respawnedguy.familyapp.resources.kitchen_cook_screen_on
import io.github.respawnedguy.familyapp.resources.kitchen_cook_step
import org.jetbrains.compose.resources.stringResource

/** Swipe distance in px that turns the page; small enough for a wet thumb. */
private const val SWIPE_PX = 80f

/**
 * Cooking mode (concept "Kochmodus"): made for greasy fingers and a glance from a metre away.
 * Page 0 "Alles bereitlegen" to tick off, then one step per page with its ingredients, big text,
 * large Weiter/Zurück areas at the bottom and swiping, and a last page "Gekocht?". The screen stays
 * on while this is open ([io.github.respawnedguy.familyapp.presentation.KitchenModel]).
 */
@Composable
fun CookOverlay(route: KitchenRoute.Cook, data: FamilyData, model: AppModel) {
    val recipe = data.recipes.firstOrNull { it.id == route.id && !it.deleted } ?: return
    val c = LocalFamilyColors.current
    val factor = RecipeViews.factor(recipe, route.servings)
    val pages = recipe.steps.size + 2
    var page by remember(route) { mutableStateOf(0) }
    val ticked = remember(route) { mutableStateListOf<Int>() }
    fun turn(by: Int) {
        page = (page + by).coerceIn(0, pages - 1)
    }
    Column(
        Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()
            .pointerInput(route) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -SWIPE_PX) turn(1) else if (dragged > SWIPE_PX) turn(-1) },
                ) { _, amount -> dragged += amount }
            },
    ) {
        CookHeader(recipe, page, pages, model)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            when (page) {
                0 -> PreparePage(recipe, factor, ticked)
                pages - 1 -> DonePage(recipe, model)
                else -> StepPage(recipe, page - 1, factor)
            }
        }
        if (page < pages - 1) CookNavigation(page, onBack = { turn(-1) }, onNext = { turn(1) })
    }
}

@Composable
private fun CookHeader(recipe: Recipe, page: Int, pages: Int, model: AppModel) {
    val c = LocalFamilyColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(recipe.title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val where = when (page) {
                0 -> stringResource(Res.string.kitchen_cook_prepare)
                pages - 1 -> stringResource(Res.string.kitchen_cook_done_question)
                else -> stringResource(Res.string.kitchen_cook_step, page, recipe.steps.size)
            }
            Text(
                where,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = c.textSecondary, fontSize = 15.sp,
            )
        }
        TextButton(onClick = { model.kitchen.back() }) { Text(stringResource(Res.string.kitchen_cook_close)) }
    }
}

@Composable
private fun PreparePage(recipe: Recipe, factor: Double, ticked: MutableList<Int>) {
    val c = LocalFamilyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.kitchen_cook_prepare), Modifier.semantics { heading() },
            color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
        )
        recipe.ingredients.forEachIndexed { i, ingredient ->
            val done = i in ticked
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = done, role = Role.Checkbox) { if (done) ticked.remove(i) else ticked.add(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = done, onCheckedChange = null)
                Text(
                    Amounts.line(ingredient, factor),
                    Modifier.padding(start = 12.dp),
                    color = if (done) c.textTertiary else c.text, fontSize = 22.sp,
                )
            }
        }
        Text(
            stringResource(Res.string.kitchen_cook_screen_on),
            Modifier.padding(top = 16.dp),
            color = c.textTertiary, fontSize = 14.sp,
        )
    }
}

@Composable
private fun StepPage(recipe: Recipe, index: Int, factor: Double) {
    val c = LocalFamilyColors.current
    val step = recipe.steps[index]
    val ingredients = RecipeViews.ingredientsOfStep(recipe, step)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (ingredients.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ingredients.forEach {
                    Text(Amounts.line(it, factor), color = c.accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Text(step, color = c.text, fontSize = 28.sp, lineHeight = 38.sp)
    }
}

@Composable
private fun DonePage(recipe: Recipe, model: AppModel) {
    val c = LocalFamilyColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(Res.string.kitchen_cook_done_question),
            color = c.text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
        )
        Button(
            onClick = { model.kitchen.recipes.cooked(recipe.id) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text(stringResource(Res.string.kitchen_cook_cooked), fontSize = 20.sp)
        }
        OutlinedButton(onClick = { model.kitchen.back() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(Res.string.kitchen_cook_not_cooked), fontSize = 18.sp)
        }
    }
}

/** Large areas at the bottom: reachable with a knuckle. */
@Composable
private fun CookNavigation(page: Int, onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, enabled = page > 0, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) {
            Text(stringResource(Res.string.kitchen_cook_back), fontSize = 20.sp)
        }
        Button(onClick = onNext, modifier = Modifier.weight(2f).heightIn(min = 72.dp)) {
            Text(stringResource(Res.string.kitchen_cook_next), fontSize = 20.sp)
        }
    }
}
