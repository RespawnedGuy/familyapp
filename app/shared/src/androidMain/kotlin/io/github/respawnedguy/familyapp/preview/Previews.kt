package io.github.respawnedguy.familyapp.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.respawnedguy.familyapp.App
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.CaptureState
import io.github.respawnedguy.familyapp.presentation.FamilySection
import io.github.respawnedguy.familyapp.presentation.Tab
import io.github.respawnedguy.familyapp.ui.CaptureSheet
import io.github.respawnedguy.familyapp.ui.DecisionChip
import io.github.respawnedguy.familyapp.ui.EmptyNote
import io.github.respawnedguy.familyapp.ui.FamilyTheme
import io.github.respawnedguy.familyapp.ui.InboxCard
import io.github.respawnedguy.familyapp.ui.LocalFamilyColors
import io.github.respawnedguy.familyapp.ui.PersonDot
import io.github.respawnedguy.familyapp.ui.SectionHeader
import io.github.respawnedguy.familyapp.ui.TaskRow

/*
 * Android Studio previews. Open this file and switch to "Split" or "Design" (top right of the
 * editor). Each function below is one picture; "Hell"/"Dunkel" show light and dark mode.
 * The previews call the real UI from commonMain, so changes there show up here after a refresh.
 * Sample data: PreviewData.kt. Guide: docs/ui-bearbeiten.md.
 */

/** Phone size, light and dark. */
@Preview(name = "Hell", widthDp = 411, heightDp = 891, showBackground = true)
@Preview(name = "Dunkel", widthDp = 411, heightDp = 891, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class PhonePreviews

/** Single components, light and dark. */
@Preview(name = "Hell", widthDp = 411, showBackground = true)
@Preview(name = "Dunkel", widthDp = 411, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class ComponentPreviews

@Composable
private fun AppPreview(data: FamilyData = PreviewData.family(), setup: AppModel.() -> Unit = {}) {
    val model = rememberPreviewModel(data, setup)
    App(versionName = "0.0.0-vorschau", model = model, photos = null, reducedMotion = true)
}

@Composable
private fun ComponentSurface(content: @Composable ColumnScope.() -> Unit) {
    FamilyTheme {
        Column(
            Modifier.background(LocalFamilyColors.current.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// ---- Whole screens (ui/Screens.kt, ui/SettingsScreen.kt, App.kt) --------------------------------

@PhonePreviews
@Composable
private fun HeutePreview() = AppPreview()

@PhonePreviews
@Composable
private fun HeuteLeerPreview() = AppPreview(PreviewData.justCreated())

@PhonePreviews
@Composable
private fun FamiliePreview() = AppPreview { select(Tab.FAMILY) }

@PhonePreviews
@Composable
private fun FamilieEinkaufPreview() = AppPreview { openSection(FamilySection.SHOPPING) }

@PhonePreviews
@Composable
private fun FamilieRezeptePreview() = AppPreview { openSection(FamilySection.RECIPES) }

@PhonePreviews
@Composable
private fun FamiliePersonenPreview() = AppPreview { openSection(FamilySection.PEOPLE) }

@PhonePreviews
@Composable
private fun DemnaechstPreview() = AppPreview { select(Tab.UPCOMING) }

@PhonePreviews
@Composable
private fun SuchenPreview() = AppPreview { select(Tab.SEARCH) }

@PhonePreviews
@Composable
private fun EinstellungenPreview() = AppPreview { openSettings() }

@PhonePreviews
@Composable
private fun OnboardingPreview() = AppPreview(PreviewData.fresh())

// ---- Capture sheet (ui/CaptureSheet.kt) ---------------------------------------------------------

@Composable
private fun CapturePreview(state: CaptureState) {
    val model = rememberPreviewModel()
    Box(Modifier.fillMaxSize()) {
        App(versionName = "0.0.0-vorschau", model = model, photos = null, reducedMotion = true)
        FamilyTheme { CaptureSheet(state, model, reducedMotion = true) }
    }
}

@PhonePreviews
@Composable
private fun ErfassenSprichPreview() = CapturePreview(CaptureState.Listening(partial = "Hausschuhe für Mia bis Donnerstag", ready = true))

@PhonePreviews
@Composable
private fun ErfassenTippenPreview() = CapturePreview(CaptureState.Typing)

@PhonePreviews
@Composable
private fun ErfassenFehlerPreview() = CapturePreview(CaptureState.Problem("Spracherkennung ist auf diesem Gerät nicht verfügbar."))

// ---- Cards and rows (ui/Cards.kt) ---------------------------------------------------------------

@ComponentPreviews
@Composable
private fun EingangKartenPreview() {
    val model = rememberPreviewModel()
    val data = model.repo.current
    ComponentSurface {
        InboxCard(PreviewData.inboxSuggested, data, model, photos = null, reducedMotion = true)
        InboxCard(PreviewData.inboxEmpty, data, model, photos = null, reducedMotion = true)
    }
}

@ComponentPreviews
@Composable
private fun AufgabenZeilenPreview() {
    val model = rememberPreviewModel()
    val data = model.repo.current
    ComponentSurface {
        TaskRow(PreviewData.overdue, data, model, photos = null, reducedMotion = true, showAssignee = false, takeOver = false)
        TaskRow(PreviewData.soon, data, model, photos = null, reducedMotion = true, showAssignee = true, takeOver = false)
        TaskRow(PreviewData.noDate, data, model, photos = null, reducedMotion = true, showAssignee = true, takeOver = true)
    }
}

// ---- Small building blocks (ui/Components.kt) ---------------------------------------------------

@ComponentPreviews
@Composable
private fun BausteinePreview() = ComponentSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PreviewData.nils, PreviewData.lisa, PreviewData.mia, PreviewData.oma).forEach { PersonDot(it) }
        PersonDot(null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DecisionChip("Wer?", missing = true, suggested = false, onClick = {})
        DecisionChip("Do 26.11.", missing = false, suggested = true, onClick = {})
        DecisionChip("Nils", missing = false, suggested = false, onClick = {}, leading = { PersonDot(PreviewData.nils, size = 20.dp) })
    }
    SectionHeader("Eingang", count = 3)
    EmptyNote("Alles sortiert. Darüber musst du gerade nicht nachdenken.")
}
