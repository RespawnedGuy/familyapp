package io.github.respawnedguy.familyapp.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.sp
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.MeasurementText
import io.github.respawnedguy.familyapp.domain.Metric
import io.github.respawnedguy.familyapp.domain.ResourceUsage
import io.github.respawnedguy.familyapp.presentation.AppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PERCENT = 100
private const val RECENT_SAMPLES = 3

/**
 * Local measurements for the reference measurement on real phones (quality plan P1-10,
 * docs/qualitaet/leistung-messen.md): medians against the D8 targets, memory and storage,
 * and a CSV to share. Nothing leaves the phone unless the person shares it.
 */
@Composable
fun MeasurementsGroup(data: FamilyData, model: AppModel) {
    val c = LocalFamilyColors.current
    val samples = data.settings.latency
    // Walking the photo folder can take a moment: off the main thread, refreshed with every new sample.
    val usage by produceState<ResourceUsage?>(null, samples.size) {
        value = withContext(Dispatchers.Default) { model.resourceUsage() }
    }
    SettingsGroup("Messwerte (nur lokal)") {
        val summaries = model.measurementSummary()
        if (summaries.isEmpty()) {
            Text(
                "Noch keine Messung. Öffne die App ein paar Mal neu und sprich einmal per Knopf, " +
                    "Kachel oder Verknüpfung.",
                fontSize = 14.sp, color = c.text,
            )
        }
        summaries.forEach { summary ->
            Text(MeasurementText.line(summary), fontSize = 14.sp, color = c.text)
            if (summary.metric == Metric.CAPTURE_READY) {
                model.measurementsByTrigger(Metric.CAPTURE_READY).forEach {
                    Text(MeasurementText.triggerLine(it), fontSize = 12.sp, color = c.textTertiary)
                }
            }
        }
        usage?.let(MeasurementText::usage)?.let { Text(it, fontSize = 14.sp, color = c.text) }
        val (one, all) = model.oneTapShare()
        if (all > 0) {
            Text(
                "Karten mit einem Tap erledigt: $one von $all (${one * PERCENT / all} %, Ziel ≥ 70 %).",
                fontSize = 14.sp, color = c.text,
            )
        }
        samples.takeLast(RECENT_SAMPLES).reversed().forEach {
            val took = MeasurementText.duration(it.ms)
            Text(
                "${MeasurementText.title(it.metric)}, ${it.trigger}: $took · ${ago(it.atMs, model.repo.now())}",
                fontSize = 12.sp, color = c.textTertiary,
            )
        }
        // One below the other: side by side they would not fit at 200 % font size.
        OutlinedButton(onClick = { model.shareMeasurements() }) { Text("Messwerte teilen") }
        if (samples.isNotEmpty()) TextButton(onClick = { model.clearMeasurements() }) { Text("Messreihe neu beginnen") }
        Text(
            "Teilen schickt eine Tabelle (CSV) nur mit Zeiten, Handymodell und App-Version, ohne Einträge oder Namen.",
            fontSize = 12.sp, color = c.textTertiary,
        )
    }
}
