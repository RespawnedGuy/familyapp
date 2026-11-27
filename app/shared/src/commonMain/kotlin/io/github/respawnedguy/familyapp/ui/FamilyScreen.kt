package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.heading
import io.github.respawnedguy.familyapp.domain.Views
import io.github.respawnedguy.familyapp.resources.family_load_hint
import io.github.respawnedguy.familyapp.resources.family_load_title
import io.github.respawnedguy.familyapp.resources.family_open_count
import io.github.respawnedguy.familyapp.resources.family_tasks_none
import io.github.respawnedguy.familyapp.resources.family_unowned_none
import io.github.respawnedguy.familyapp.resources.family_unowned_some
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.offers.MarketOffers
import io.github.respawnedguy.familyapp.domain.offers.Offer
import io.github.respawnedguy.familyapp.domain.offers.OfferList
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.FamilySection
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.family_member_self
import io.github.respawnedguy.familyapp.resources.family_tab_contacts
import io.github.respawnedguy.familyapp.resources.family_tab_people
import io.github.respawnedguy.familyapp.resources.family_tab_recipes
import io.github.respawnedguy.familyapp.resources.family_tab_shopping
import io.github.respawnedguy.familyapp.resources.family_tab_tasks
import io.github.respawnedguy.familyapp.resources.offers_all_add
import io.github.respawnedguy.familyapp.resources.offers_all_add_label
import io.github.respawnedguy.familyapp.resources.offers_all_no_place
import io.github.respawnedguy.familyapp.resources.offers_all_no_price
import io.github.respawnedguy.familyapp.resources.offers_all_none
import io.github.respawnedguy.familyapp.resources.offers_all_not_loaded
import io.github.respawnedguy.familyapp.resources.offers_all_on_list
import io.github.respawnedguy.familyapp.resources.offers_all_search
import io.github.respawnedguy.familyapp.resources.offers_all_title
import io.github.respawnedguy.familyapp.resources.offers_set_place
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * "Familie" with tabs at the top (Nils, 27.11.2026): Aufgaben (default, load card), Kontakte,
 * Personen, Rezepte, Einkauf. The bottom navigation (D1) stays as it is; the tabs only sort what
 * used to hang below the load card as entry cards. Opening a recipe, contact or portfolio covers
 * the tabs like before, "back" returns to the tab it came from.
 */

/** Transparency has a fixed place: everybody's stack and the load card are the default tab. */
@Composable
fun FamilyScreen(data: FamilyData, model: AppModel, photos: PhotoLoader?, reducedMotion: Boolean) {
    val section by model.section.collectAsState()
    Column(Modifier.fillMaxSize()) {
        FamilyTabs(section) { model.openSection(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                FamilySection.TASKS -> FamilyTasks(data, model, photos, reducedMotion)
                FamilySection.CONTACTS -> ContactListScreen(data, model)
                FamilySection.PEOPLE -> FamilyPeople(data, model)
                FamilySection.RECIPES -> LibraryScreen(data, model, photos)
                FamilySection.SHOPPING -> ShoppingTab(data, model)
            }
        }
    }
}

/**
 * Tab "Aufgaben" under Familie (default): the load card on top, then every person's open tasks.
 * Tapping a bar filters the list; tapping a person opens their portfolio.
 */
@Composable
private fun FamilyTasks(data: FamilyData, model: AppModel, photos: PhotoLoader?, reducedMotion: Boolean) {
    var filter by remember { mutableStateOf<String?>(null) }
    val shown = data.members.filter { filter == null || it.id == filter }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = TasksPadding) {
        item { LoadCard(data, model.today(), filter) { id -> filter = if (filter == id) null else id } }
        shown.forEach { member ->
            val open = Views.openFor(data, member.id)
            item(key = "m-${member.id}") {
                MemberHeader(member, data, open.size) { model.people.openPortfolio(member.id) }
            }
            if (open.isEmpty()) {
                item(key = "n-${member.id}") {
                    val c = LocalFamilyColors.current
                    Text(stringResource(Res.string.family_tasks_none), color = c.textTertiary, fontSize = 14.sp)
                }
            }
            items(open, key = { "f-${it.id}" }) { item ->
                val takeOver = member.id != data.selfId
                TaskRow(item, data, model, photos, reducedMotion, showAssignee = false, takeOver = takeOver)
            }
        }
    }
}

private val TasksPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)

/** Only fixed deadlines and dates; a bar filters the list below to that person. */
@Composable
private fun LoadCard(data: FamilyData, today: LocalDate, filter: String?, onFilter: (String) -> Unit) {
    val c = LocalFamilyColors.current
    val loads = Views.loads(data, today)
    val maxOpen = (loads.maxOfOrNull { it.open } ?: 0).coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.family_load_title), Modifier.semantics { heading() },
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text,
        )
        Text(stringResource(Res.string.family_load_hint), fontSize = 12.sp, color = c.textTertiary)
        loads.forEach { load ->
            LoadRow(load, maxOpen, filter == load.member.id) { onFilter(load.member.id) }
        }
        val unowned = Views.unowned(data)
        Text(
            if (unowned == 0) stringResource(Res.string.family_unowned_none)
            else stringResource(Res.string.family_unowned_some, unowned),
            fontSize = 13.sp, color = c.textSecondary,
        )
    }
}

@Composable
private fun MemberHeader(member: Member, data: FamilyData, open: Int, onClick: () -> Unit) {
    val c = LocalFamilyColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonDot(member, 32.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(memberName(member, data), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.family_open_count, open), color = c.textTertiary, fontSize = 12.sp)
        }
    }
}

/** "Nils (ich)" for the own profile, so everybody finds themselves in the lists. */
@Composable
internal fun memberName(member: Member, data: FamilyData): String =
    if (member.id == data.selfId) stringResource(Res.string.family_member_self, member.name) else member.name

private val FamilySection.label: StringResource
    get() = when (this) {
        FamilySection.TASKS -> Res.string.family_tab_tasks
        FamilySection.CONTACTS -> Res.string.family_tab_contacts
        FamilySection.PEOPLE -> Res.string.family_tab_people
        FamilySection.RECIPES -> Res.string.family_tab_recipes
        FamilySection.SHOPPING -> Res.string.family_tab_shopping
    }

/**
 * Five tabs in one row of equal parts; the labels shrink a little on narrow phones instead of
 * breaking. With large fonts (P1-4) they wrap onto further lines at full size, so no tab is ever
 * hidden behind a sideways scroll. Fixed colours (D6), no vibration (D-Haptik).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilyTabs(selected: FamilySection, onSelect: (FamilySection) -> Unit) {
    val padding = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
    if (largeFont()) {
        FlowRow(
            padding.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FamilySection.entries.forEach { s ->
                FamilyTab(s, s == selected, Modifier, fit = false) { onSelect(s) }
            }
        }
    } else {
        Row(padding.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FamilySection.entries.forEach { s ->
                FamilyTab(s, s == selected, Modifier.weight(1f), fit = true) { onSelect(s) }
            }
        }
    }
}

@Composable
private fun FamilyTab(
    section: FamilySection,
    selected: Boolean,
    modifier: Modifier,
    fit: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalFamilyColors.current
    val label = stringResource(section.label)
    val style = TextStyle(
        color = if (selected) c.onAccent else c.textSecondary,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accent else c.card)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (fit) 4.dp else 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (fit) FitLabel(label, style) else Text(label, style = style)
    }
}

/**
 * Tab "Einkauf": suggested recipes on top (they fill the list), the shopping list in the middle,
 * all regional offers of the chosen markets below.
 */
@Composable
private fun ShoppingTab(data: FamilyData, model: AppModel) {
    val cache by model.offers.cache.collectAsState()
    var query by remember { mutableStateOf("") }
    val today = model.today()
    val all = data.place?.let { cache.offers(it.markets) }.orEmpty()
    val markets = OfferList.byMarket(all, today, query)
    val onList = model.kitchen.shopping.openNames(data.shopping)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = KitchenPadding) {
        item { MealsCard(data, model) }
        shoppingListItems(data, model)
        item {
            SectionHeader(stringResource(Res.string.offers_all_title), markets.sumOf { it.offers.size })
        }
        offersItems(data, model, all, markets, onList, today, query) { query = it }
    }
}

@Suppress("LongParameterList") // one list section, everything it shows comes in from the tab
private fun LazyListScope.offersItems(
    data: FamilyData,
    model: AppModel,
    all: List<Offer>,
    markets: List<MarketOffers>,
    onList: Set<String>,
    today: LocalDate,
    query: String,
    onQuery: (String) -> Unit,
) {
    when {
        data.place == null -> item {
            Column {
                EmptyNote(stringResource(Res.string.offers_all_no_place))
                TextButton(onClick = { model.openSettings() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.offers_set_place))
                }
            }
        }
        all.isEmpty() -> item { EmptyNote(stringResource(Res.string.offers_all_not_loaded)) }
        else -> {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text(stringResource(Res.string.offers_all_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (markets.isEmpty()) item { EmptyNote(stringResource(Res.string.offers_all_none)) }
            markets.forEach { market ->
                item(key = market.chain.ordinal) { SectionHeader(market.chain.label, market.offers.size) }
                items(market.offers, key = { "${it.chain.ordinal}|${it.title}|${it.priceCents}" }) { offer ->
                    OfferRow(offer, OfferList.onList(offer, onList), today) { model.kitchen.shopping.addOffer(offer) }
                }
            }
        }
    }
}

/** One offer: product, price, old price, saving and validity, "Auf die Liste" (or "Auf der Liste"). */
@Composable
private fun OfferRow(offer: Offer, onList: Boolean, today: LocalDate, onAdd: () -> Unit) {
    val c = LocalFamilyColors.current
    val addLabel = stringResource(Res.string.offers_all_add_label, offer.title)
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.card)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(offer.title, color = c.text, fontSize = 15.sp)
            val price = OfferList.price(offer.priceCents)
            Text(
                price ?: stringResource(Res.string.offers_all_no_price),
                color = if (price != null) c.text else c.textSecondary,
                fontSize = if (price != null) 15.sp else 13.sp,
                fontWeight = if (price != null) FontWeight.SemiBold else FontWeight.Normal,
            )
            val details = OfferList.details(offer, today)
            if (details.isNotEmpty()) {
                Text(details, color = c.textTertiary, fontSize = 12.sp)
            }
        }
        if (onList) {
            Text(
                stringResource(Res.string.offers_all_on_list),
                Modifier.padding(horizontal = 12.dp),
                color = c.textTertiary, fontSize = 13.sp,
            )
        } else {
            TextButton(
                onClick = onAdd,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addLabel },
            ) { Text(stringResource(Res.string.offers_all_add)) }
        }
    }
}
