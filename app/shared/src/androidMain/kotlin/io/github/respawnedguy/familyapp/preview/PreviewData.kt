package io.github.respawnedguy.familyapp.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.respawnedguy.familyapp.data.FamilyJson
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.LatencySample
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Origin
import io.github.respawnedguy.familyapp.domain.Settings
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.Stamp
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.PlatformServices
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import io.github.respawnedguy.familyapp.presentation.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate

/**
 * Sample family for the Android Studio previews (Design/Split view). Only used by @Preview
 * functions, never by the app. Change names, titles or dates here to see other cases.
 *
 * "Today" is fixed to Monday 23.11.2026 so overdue/soon labels look the same on every machine.
 */
object PreviewData {
    val today: LocalDate = LocalDate(2026, 11, 23)

    /** 23.11.2026 08:00 UTC; "vor 10 Min." etc. are computed against this. */
    const val NOW_MS: Long = 1_795_420_800_000L

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private val stamp = Stamp(NOW_MS, 0, "preview")

    val nils = Member("m-nils", "Nils", colorIndex = 0, kind = MemberKind.ADULT, hasDevice = true, stamp = stamp)
    val lisa = Member("m-lisa", "Lisa", colorIndex = 1, kind = MemberKind.ADULT, hasDevice = true, stamp = stamp)
    val mia = Member("m-mia", "Mia", colorIndex = 2, kind = MemberKind.CHILD, stamp = stamp)
    val oma = Member("m-oma", "Oma Gerda", colorIndex = 3, kind = MemberKind.ADULT, stamp = stamp)

    /** Inbox card with rule suggestions (dashed chips): date and child recognised, nobody assigned yet. */
    val inboxSuggested = Item(
        id = "i-elternabend",
        title = "Elternabend Klasse 2b am Donnerstag 19 Uhr",
        source = Source.VOICE,
        createdMs = NOW_MS - 10 * MIN,
        createdBy = nils.id,
        concerns = mia.id,
        concernsOrigin = Origin.RULE,
        due = LocalDate(2026, 11, 26),
        dueOrigin = Origin.RULE,
        reasons = listOf("Datum erkannt: „Donnerstag“", "Name erkannt: Mia"),
        stamp = stamp,
    )

    /** Inbox card without any suggestion: everything still open. */
    val inboxEmpty = Item(
        id = "i-laterne",
        title = "Laterne für den Umzug basteln",
        source = Source.TEXT,
        createdMs = NOW_MS - 2 * HOUR,
        createdBy = lisa.id,
        stamp = stamp,
    )

    val inboxShared = Item(
        id = "i-krankenkasse",
        title = "Brief von der Krankenkasse: Bonusheft einreichen",
        source = Source.SHARE,
        createdMs = NOW_MS - DAY,
        createdBy = nils.id,
        stamp = stamp,
    )

    private fun open(id: String, title: String, who: Member, due: LocalDate?, concerns: Member? = null, ageMs: Long = 3 * DAY) = Item(
        id = id,
        title = title,
        source = Source.VOICE,
        createdMs = NOW_MS - ageMs,
        createdBy = nils.id,
        status = ItemStatus.OPEN,
        concerns = concerns?.id,
        concernsOrigin = concerns?.let { Origin.PERSON },
        assignee = who.id,
        assigneeOrigin = Origin.PERSON,
        due = due,
        dueDecided = true,
        dueOrigin = Origin.PERSON,
        stamp = stamp,
    )

    val overdue = open("i-hausschuhe", "Hausschuhe für Mia kaufen", nils, LocalDate(2026, 11, 20), concerns = mia)
    val noDate = open("i-zahnarzt", "Zahnarzttermin vereinbaren", nils, null)
    val soon = open("i-geschenk", "Geschenk für Pauls Geburtstag", nils, LocalDate(2026, 11, 28), concerns = mia)

    fun family(): FamilyData = FamilyData(
        deviceId = "preview",
        selfId = nils.id,
        members = listOf(nils, lisa, mia, oma),
        items = listOf(
            inboxSuggested, inboxEmpty, inboxShared,
            overdue, noDate, soon,
            open("i-kfz", "Kfz-Versicherung kündigen", lisa, LocalDate(2026, 11, 30)),
            open("i-steuer", "Unterlagen für die Steuererklärung sammeln", lisa, LocalDate(2026, 12, 15)),
            open("i-tabletten", "Tabletten für Oma abholen", lisa, LocalDate(2026, 11, 24), concerns = oma),
            open("i-turnbeutel", "Turnbeutel waschen", nils, today, concerns = mia).copy(
                status = ItemStatus.DONE, doneMs = NOW_MS - HOUR, doneOn = today,
            ),
            inboxEmpty.copy(id = "i-pizza", title = "Flyer Pizzadienst", status = ItemStatus.IRRELEVANT),
        ),
        settings = Settings(
            lastBackupMs = NOW_MS - 5 * HOUR,
            latency = listOf(
                LatencySample("Kachel", 840, NOW_MS - DAY),
                LatencySample("Knopf", 410, NOW_MS - 3 * HOUR),
            ),
        ),
    )

    /** Family that was just created: only the own profile, nothing captured yet. */
    fun justCreated(): FamilyData = FamilyData(deviceId = "preview", selfId = nils.id, members = listOf(nils))

    /** Fresh install: no profile yet, the app shows onboarding. */
    fun fresh(): FamilyData = FamilyData(deviceId = "preview")
}

/** Platform stub: no microphone, no camera, no folders. Buttons that need them simply do nothing. */
private object PreviewPlatform : PlatformServices {
    override val speech: SpeechInput? = null
    override fun elapsedMs(): Long = 0
    override fun reducedMotion(): Boolean = true
    override fun takePhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun writeBackup(folder: String, backup: BackupContent): String? = null
    override fun readBackup(folder: String, code: String?): RestoreRead = RestoreRead.Failed("Vorschau")
    override fun newRecoveryCode(): String = "7K3M-Q9TB-X2WD-H5NC-R8PF"
    override fun describeFolder(folder: String): String = folder
}

/**
 * A real [AppModel] over in-memory sample data. In the interactive preview (▶ in the preview
 * pane) taps really change the data, e.g. swiping a card or picking a person.
 */
@Composable
fun rememberPreviewModel(data: FamilyData = PreviewData.family(), setup: AppModel.() -> Unit = {}): AppModel {
    val scope = rememberCoroutineScope()
    return remember {
        var id = 0
        val repo = FamilyRepository(
            store = InMemoryTextStore(FamilyJson.encode(data)),
            scope = scope,
            ioDispatcher = Dispatchers.Unconfined,
            now = { PreviewData.NOW_MS },
            today = { PreviewData.today },
            newId = { "preview-${id++}" },
        )
        AppModel(repo, PreviewPlatform, scope, { PreviewData.today }, Dispatchers.Unconfined).apply(setup)
    }
}
