package io.github.respawnedguy.familyapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.resources.Res
import io.github.respawnedguy.familyapp.resources.offers_address_label
import io.github.respawnedguy.familyapp.resources.offers_address_note
import io.github.respawnedguy.familyapp.resources.offers_address_save
import io.github.respawnedguy.familyapp.resources.offers_attribution
import io.github.respawnedguy.familyapp.resources.offers_kaufland_find
import io.github.respawnedguy.familyapp.resources.offers_kaufland_other
import io.github.respawnedguy.familyapp.resources.offers_kaufland_store
import io.github.respawnedguy.familyapp.resources.offers_markets
import io.github.respawnedguy.familyapp.resources.offers_refresh
import io.github.respawnedguy.familyapp.resources.offers_rejected_item
import io.github.respawnedguy.familyapp.resources.offers_rejected_remove
import io.github.respawnedguy.familyapp.resources.offers_rejected_title
import io.github.respawnedguy.familyapp.resources.offers_retry
import io.github.respawnedguy.familyapp.resources.offers_rules
import io.github.respawnedguy.familyapp.resources.offers_settings_title
import io.github.respawnedguy.familyapp.resources.offers_waiting_wifi
import org.jetbrains.compose.resources.stringResource

/** Einstellungen › Einkauf: place, markets, Kaufland store, one diagnosis line per market, "Passt nicht". */
@Composable
internal fun ShoppingSettings(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    val place = data.place
    val busy by model.offers.busy.collectAsState()
    val waiting by model.offers.waitingForWifi.collectAsState()
    val cache by model.offers.cache.collectAsState()
    var address by remember(place?.label) { mutableStateOf(place?.label.orEmpty()) }
    SettingsGroup(stringResource(Res.string.offers_settings_title)) {
        OutlinedTextField(
            value = address, onValueChange = { address = it.take(ADDRESS_MAX) },
            label = { Text(stringResource(Res.string.offers_address_label)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (address.isNotBlank() && address.trim() != place?.label) {
            Button(onClick = { model.offers.place.setAddress(address) }) {
                Text(stringResource(Res.string.offers_address_save))
            }
        }
        Text(stringResource(Res.string.offers_address_note), fontSize = 12.sp, color = c.textTertiary)
        if (place == null) return@SettingsGroup

        Text(stringResource(Res.string.offers_markets), fontSize = 14.sp, color = c.textSecondary)
        Chain.entries.forEach { chain ->
            val on = place.has(chain)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = on, role = Role.Checkbox) { model.offers.place.setChain(chain, it) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Text(chain.label, Modifier.padding(start = 12.dp), fontSize = 15.sp, color = c.text)
            }
        }
        if (place.has(Chain.KAUFLAND)) KauflandStore(place.kauflandStoreName, model)

        model.offers.marketLines(cache, busy).forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = if (line.refused) c.overdue else c.textSecondary
                Text(line.text, Modifier.weight(1f), fontSize = 13.sp, color = color)
                if (line.canRetry) {
                    TextButton(onClick = { model.offers.retry(line.chain) }) {
                        Text(stringResource(Res.string.offers_retry))
                    }
                }
            }
        }
        if (waiting) Text(stringResource(Res.string.offers_waiting_wifi), fontSize = 12.sp, color = c.textTertiary)
        OutlinedButton(onClick = { model.offers.refreshNow() }, enabled = busy.isEmpty()) {
            Text(stringResource(Res.string.offers_refresh))
        }
        Text(stringResource(Res.string.offers_rules), fontSize = 12.sp, color = c.textTertiary)
        if (place.rejected.isNotEmpty()) RejectedList(place.rejected, model)
    }
}

@Composable
private fun KauflandStore(storeName: String?, model: AppModel) {
    val c = LocalFamilyColors.current
    var other by remember { mutableStateOf("") }
    storeName?.let { Text(stringResource(Res.string.offers_kaufland_store, it), fontSize = 14.sp, color = c.text) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = other, onValueChange = { v -> other = v.filter(Char::isDigit).take(POSTCODE_LENGTH) },
            label = { Text(stringResource(Res.string.offers_kaufland_other)) },
            singleLine = true, modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { model.offers.place.findKauflandStore(other); other = "" },
            enabled = other.length == POSTCODE_LENGTH,
        ) {
            Text(stringResource(Res.string.offers_kaufland_find))
        }
    }
}

@Composable
private fun RejectedList(rejected: List<String>, model: AppModel) {
    val c = LocalFamilyColors.current
    Text(stringResource(Res.string.offers_rejected_title), fontSize = 14.sp, color = c.textSecondary)
    rejected.forEach { key ->
        val ingredient = key.substringBefore(REJECTION_SEPARATOR)
        val words = key.substringAfter(REJECTION_SEPARATOR)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.offers_rejected_item, words, ingredient), Modifier.weight(1f),
                fontSize = 13.sp, color = c.text,
            )
            TextButton(onClick = { model.offers.place.unreject(key) }) {
                Text(stringResource(Res.string.offers_rejected_remove))
            }
        }
    }
}

/** GeoNames asks for attribution (CC BY 4.0); shown in "Über". */
@Composable
internal fun OffersAttribution() {
    val c = LocalFamilyColors.current
    Text(stringResource(Res.string.offers_attribution), fontSize = 12.sp, color = c.textTertiary)
}

private const val ADDRESS_MAX = 120
private const val POSTCODE_LENGTH = 5
private const val REJECTION_SEPARATOR = '|'
