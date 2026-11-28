package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.ShareRejection
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.data.usedPhotoPaths
import io.github.respawnedguy.familyapp.domain.CaptureAction
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.DeviceInfo
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.Measurements
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Metric
import io.github.respawnedguy.familyapp.domain.MetricSummary
import io.github.respawnedguy.familyapp.domain.ResourceUsage
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

enum class Tab { TODAY, FAMILY, UPCOMING, SEARCH }

/**
 * The tabs at the top of "Familie" (Nils, 27.11.2026). [TASKS] is the default with the load card;
 * contacts, people (portfolios), recipes and shopping no longer hide behind entry cards.
 */
enum class FamilySection { TASKS, CONTACTS, PEOPLE, RECIPES, SHOPPING }

/** A restore waits for the recovery code; [wrong] means the last code did not open the backup. */
data class RestoreCodeRequest(val folder: String, val wrong: Boolean)

/** Bottom message with optional undo, visible for 6 s (design: undo instead of dialogs). */
data class Feedback(val id: Long, val message: String, val undo: Undo? = null)

sealed interface CaptureState {
    data object Closed : CaptureState
    data class Listening(val partial: String = "", val ready: Boolean = false) : CaptureState
    data object Typing : CaptureState
    data object WaitingForPhoto : CaptureState
    data class Problem(val message: String) : CaptureState
}

/**
 * Presentation logic shared by Android and iOS. Lives as long as the process (held by the
 * Application on Android), so a rotation or a quick-settings tile reuses it.
 */
class AppModel(
    val repo: FamilyRepository,
    private val platform: PlatformServices,
    private val scope: CoroutineScope,
    val today: () -> LocalDate,
    /** For backups into the chosen folder, which may be a slow document provider. */
    private val io: CoroutineDispatcher,
) {
    private var feedbackCounter = 0L

    private val _tab = MutableStateFlow(Tab.TODAY)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _section = MutableStateFlow(FamilySection.TASKS)
    val section: StateFlow<FamilySection> = _section.asStateFlow()

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback: StateFlow<Feedback?> = _feedback.asStateFlow()

    private val _capture = MutableStateFlow<CaptureState>(CaptureState.Closed)
    val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    /** Kitchen library: recipes, cooking mode, shopping list (docs/kochbibliothek.md). */
    val kitchen = KitchenModel(repo, platform, Background(scope, io), { openSection(it) }) { message, undo ->
        show(message, undo)
    }

    /** Offers, season and meal suggestions (docs/angebote-und-vorschlaege.md). */
    val offers = OffersModel(repo, platform, Background(scope, io), kitchen) { message, undo -> show(message, undo) }

    /** Shared contacts and the portfolio per person (docs/kontakte-und-portfolio.md). */
    val people = PeopleModel(repo, platform, { message, undo -> show(message, undo) }, { openSection(it) }) {
        _settingsOpen.value = false
        kitchen.close()
    }

    /** Pending latency measurement: which trigger, started when (monotonic ms). */
    private var latencyStart: Pair<String, Long>? = null

    /** When the person stopped speaking (monotonic ms) and whether a tap on "Fertig" ended it, for D8 (b). */
    private var speechEnd: Pair<String, Long>? = null

    init {
        startAutoBackup()
    }

    fun elapsedMs(): Long = platform.elapsedMs()

    // ---- Navigation --------------------------------------------------------------------------

    fun select(tab: Tab) {
        // "Familie" tapped again while its list is showing: back to the default tab "Aufgaben".
        val again = tab == Tab.FAMILY && _tab.value == Tab.FAMILY && !_settingsOpen.value &&
            kitchen.route.value == null && people.route.value == null
        if (again) _section.value = FamilySection.TASKS
        _settingsOpen.value = false
        kitchen.close()
        people.close()
        _tab.value = tab
    }

    /** One of the tabs under "Familie", from anywhere (a card under Heute, a saved contact list …). */
    fun openSection(section: FamilySection) {
        _settingsOpen.value = false
        kitchen.close()
        people.close()
        _tab.value = Tab.FAMILY
        _section.value = section
    }

    fun openSettings() {
        kitchen.close()
        people.close()
        _settingsOpen.value = true
    }

    /**
     * System back: sheet, settings, contacts and portfolios, the kitchen, then the default tab of
     * "Familie", then "Heute". False means: leave the app.
     */
    fun handleBack(): Boolean = when {
        _capture.value != CaptureState.Closed -> { dismissCapture(); true }
        offers.plan.sheet.value != null -> { offers.plan.close(); true }
        _settingsOpen.value -> { _settingsOpen.value = false; true }
        people.back() -> true
        kitchen.back() -> true
        _tab.value == Tab.FAMILY && _section.value != FamilySection.TASKS -> {
            _section.value = FamilySection.TASKS
            true
        }
        _tab.value != Tab.TODAY -> { _tab.value = Tab.TODAY; true }
        else -> false
    }

    fun show(message: String, undo: Undo? = null) {
        _feedback.value = Feedback(++feedbackCounter, message, undo)
    }

    fun dismissFeedback(id: Long) {
        if (_feedback.value?.id == id) _feedback.value = null
    }

    fun undo(feedback: Feedback) {
        feedback.undo?.let(repo::undo)
        _feedback.value = null
    }

    // ---- Onboarding and members --------------------------------------------------------------

    fun createFamily(name: String) {
        if (name.isBlank()) return
        repo.createFamily(name)
    }

    fun addMember(name: String, kind: MemberKind) {
        if (name.isBlank()) return
        val m = repo.addMember(name, kind)
        show("${m.name} gehört jetzt zur Familie.")
    }

    fun updateMember(id: String, name: String, colorIndex: Int, kind: MemberKind) {
        if (name.isBlank()) return
        repo.updateMember(id, name, colorIndex, kind)
    }

    fun removeMember(id: String) {
        val name = repo.current.member(id)?.name ?: return
        val undo = repo.removeMember(id) ?: return
        show("$name entfernt. Offene Aufgaben liegen wieder im Eingang.", undo)
    }

    // ---- Inbox and tasks ----------------------------------------------------------------------

    private fun name(id: String?): String = repo.current.member(id)?.name ?: "niemand"

    /** "Passt" or, with nobody assigned yet, "Mach ich". */
    fun confirm(item: Item) {
        val undo = repo.confirm(item.id)
        val who = repo.current.items.firstOrNull { it.id == item.id }?.assignee
        show(if (who == repo.current.selfId) "Gehört jetzt dir." else "Passt. ${name(who)} kümmert sich.", undo)
        askForNotificationsOnce(item.id)
    }


    fun complete(item: Item) = show("Erledigt.", repo.complete(item.id))

    fun takeOver(item: Item) =
        show("Übernommen. Alle sehen, dass du das machst.", repo.takeOver(item.id))

    fun setAssignee(item: Item, memberId: String) {
        val r = repo.setAssignee(item.id, memberId)
        if (r.confirmed) show("Bestätigt: ${name(memberId)} kümmert sich.", r.undo)
        askForNotificationsOnce(item.id)
    }

    /**
     * The first time a deadline is confirmed, the app asks for the notification permission: the
     * moment the person sees why reminders matter (instead of a prompt at the first start).
     */
    private fun askForNotificationsOnce(itemId: String) {
        val s = repo.current.settings
        val item = repo.current.items.firstOrNull { it.id == itemId }
        val hasDeadline = item?.status == ItemStatus.OPEN && item.due != null
        if (s.notificationsAsked || !s.remindersOn || !hasDeadline) return
        repo.recordNotificationsAsked()
        platform.requestNotificationPermission()
    }

    fun setRemindersOn(on: Boolean) = repo.setRemindersOn(on)

    fun notificationsBlocked(): Boolean = platform.notificationsBlocked()

    fun dismissLocalOnlyHint() = repo.dismissLocalOnlyHint()

    fun setConcerns(item: Item, memberId: String?) {
        repo.setConcerns(item.id, memberId)
    }

    fun setDue(item: Item, date: LocalDate?) {
        val r = repo.setDue(item.id, date)
        if (r.confirmed) {
            val now = repo.current.items.firstOrNull { it.id == item.id }
            val whenText = date?.let { "bis " + DateFormat.short(it) } ?: "ohne Datum"
            show("Bestätigt: ${name(now?.assignee)}, $whenText.", r.undo)
        }
        askForNotificationsOnce(item.id)
    }

    fun rename(item: Item, title: String) {
        repo.rename(item.id, title)
    }

    fun reopen(item: Item) = show("Liegt wieder im Eingang.", repo.reopen(item.id))

    fun delete(item: Item) = show("Gelöscht.", repo.delete(item.id))

    // ---- Capture (design: first tap starts speech at once, photo at the same spot) ------------

    /**
     * Opens capture. [trigger] names where it came from (Knopf, Kachel, Verknüpfung) and
     * [startedAtMs] is when that trigger fired (monotonic), for the "≤ 2 s until recording" metric.
     */
    fun startCapture(trigger: String, startedAtMs: Long, action: CaptureAction = repo.current.settings.firstCaptureAction) {
        if (repo.current.selfId == null) return
        _settingsOpen.value = false
        when (action) {
            CaptureAction.VOICE -> {
                latencyStart = trigger to startedAtMs
                startListening()
            }
            CaptureAction.PHOTO -> {
                platform.speech?.cancel()
                _capture.value = CaptureState.WaitingForPhoto
                platform.takePhoto { path -> onPhoto(path) }
            }
            CaptureAction.TEXT -> {
                platform.speech?.cancel()
                _capture.value = CaptureState.Typing
            }
        }
    }

    fun switchCapture(action: CaptureAction) = startCapture("Wechsel", platform.elapsedMs(), action)

    private fun startListening() {
        val speech = platform.speech
        if (speech == null) {
            latencyStart = null
            _capture.value = CaptureState.Problem("Spracherkennung ist auf diesem Gerät nicht verfügbar. Tippen geht immer.")
            return
        }
        _capture.value = CaptureState.Listening()
        speechEnd = null
        speech.start(object : SpeechListener {
            override fun onReady() {
                latencyStart?.let { (trigger, start) -> repo.recordLatency(trigger, platform.elapsedMs() - start) }
                latencyStart = null
                (_capture.value as? CaptureState.Listening)?.let { _capture.value = it.copy(ready = true) }
            }

            override fun onEndOfSpeech() {
                if (speechEnd == null) speechEnd = SPEECH_END_AUTO to platform.elapsedMs()
            }

            override fun onPartial(text: String) {
                (_capture.value as? CaptureState.Listening)?.let { _capture.value = it.copy(partial = text) }
            }

            override fun onResult(text: String) {
                if (_capture.value !is CaptureState.Listening) return
                if (text.isBlank()) {
                    _capture.value = CaptureState.Problem("Nichts verstanden. Nochmal sprechen oder tippen.")
                } else {
                    save(text, Source.VOICE)
                    speechEnd?.let { (how, at) ->
                        repo.recordLatency(how, platform.elapsedMs() - at, Metric.SAVE_AFTER_SPEECH)
                    }
                }
                speechEnd = null
            }

            override fun onError(message: String) {
                latencyStart = null
                if (_capture.value is CaptureState.Listening) _capture.value = CaptureState.Problem(message)
            }
        })
    }

    /** Ends listening early ("Fertig"); the recognizer then delivers the final result. */
    fun stopListening() {
        if (_capture.value is CaptureState.Listening && speechEnd == null) {
            speechEnd = SPEECH_END_TAP to platform.elapsedMs()
        }
        platform.speech?.stop()
    }

    fun saveText(text: String) {
        if (text.isBlank()) return
        save(text, Source.TEXT)
    }

    private fun onPhoto(path: String?) {
        if (_capture.value != CaptureState.WaitingForPhoto) return
        if (path == null) {
            _capture.value = CaptureState.Closed
            return
        }
        save("", Source.PHOTO, path)
    }

    /**
     * Text or image shared from another app (mail, gallery, browser). Saved without a further tap.
     * A refused image ([rejected], quality plan P1-8) is said out loud instead of vanishing.
     */
    fun captureShared(text: String?, photoPath: String?, rejected: ShareRejection? = null) {
        if (repo.current.selfId == null) return
        when {
            text.isNullOrBlank() && photoPath == null -> rejected?.let { show("Bild nicht übernommen. ${it.reason}") }
            // A recipe (caption with ingredients and steps, or an Instagram post link) goes to the
            // kitchen library as a suggestion instead of the inbox; undo is on the feedback bar.
            text != null && isSharedRecipe(text, photoPath, rejected) -> kitchen.recipes.importShared(text)
            else -> {
                val message = rejected?.let { "Gespeichert ohne Bild. ${it.reason}" } ?: SAVED
                save(text.orEmpty(), Source.SHARE, photoPath, message)
            }
        }
    }

    /**
     * A contact shared from the phone's contacts app (vCard, threat model KT-1). Null: the file was
     * refused or unreadable, which is said out loud instead of failing silently.
     */
    fun captureSharedContact(vcard: String?) {
        if (repo.current.selfId == null) return
        openSection(FamilySection.CONTACTS)
        if (vcard == null) show("Kontakt nicht übernommen: Die Datei ließ sich nicht lesen oder ist größer als 1 MB.")
        else people.contacts.importVCard(vcard)
    }

    /** Only plain text can be a recipe; a shared image always stays a capture with its photo. */
    private fun isSharedRecipe(text: String, photoPath: String?, rejected: ShareRejection?): Boolean =
        photoPath == null && rejected == null && RecipeImport.isRecipe(text)

    private fun save(text: String, source: Source, photoPath: String? = null, message: String = SAVED) {
        val item = repo.capture(text, source, photoPath)
        _capture.value = CaptureState.Closed
        _tab.value = Tab.TODAY
        show(message, Undo(mapOf(item.id to null)))
    }

    fun dismissCapture() {
        platform.speech?.cancel()
        latencyStart = null
        speechEnd = null
        _capture.value = CaptureState.Closed
    }

    // ---- Settings and backup -----------------------------------------------------------------

    fun setFirstCaptureAction(action: CaptureAction) = repo.setFirstCaptureAction(action)

    fun setAllowOnlineSpeech(allowed: Boolean) = repo.setAllowOnlineSpeech(allowed)

    fun setRecipeAiKey(key: String?) = repo.setRecipeAiKey(key)

    fun renameSelf(name: String) {
        if (name.isNotBlank()) repo.rename(name)
    }

    private val _restoreCode = MutableStateFlow<RestoreCodeRequest?>(null)

    /** Set while a restore waits for the recovery code (new phone, or code typed wrong). */
    val restoreCode: StateFlow<RestoreCodeRequest?> = _restoreCode.asStateFlow()

    /** Choosing a folder also creates the recovery code if there is none yet (P0-3). */
    fun chooseBackupFolder() {
        platform.pickBackupFolder { folder ->
            if (folder != null) {
                if (repo.current.settings.backupCode == null) repo.setBackupCode(platform.newRecoveryCode())
                repo.setBackupFolder(folder)
                backupNow()
            }
        }
    }

    fun stopBackups() = repo.setBackupFolder(null)

    private fun backupContent(): BackupContent? {
        val code = repo.current.settings.backupCode ?: return null
        return BackupContent(repo.exportBackup(), usedPhotoPaths(repo.current), code)
    }

    fun backupNow() {
        val folder = repo.current.settings.backupFolder ?: return
        val content = backupContent() ?: return
        scope.launch {
            val error = withContext(io) { platform.writeBackup(folder, content) }
            repo.recordBackup(error)
            show(error?.let { "Sicherung fehlgeschlagen: $it" } ?: "Gesichert.")
        }
    }

    /** Restore: pick the folder that holds the backup, then open it with the code on this phone. */
    fun restoreFromFolder() {
        platform.pickBackupFolder { folder ->
            if (folder != null) openBackup(folder, repo.current.settings.backupCode)
        }
    }

    /** The person typed the recovery code in the dialog. */
    fun restoreWithCode(code: String) {
        val folder = _restoreCode.value?.folder ?: return
        openBackup(folder, code)
    }

    fun cancelRestore() {
        _restoreCode.value = null
    }

    private fun openBackup(folder: String, code: String?) {
        scope.launch {
            when (val read = withContext(io) { platform.readBackup(folder, code) }) {
                is RestoreRead.Ok -> {
                    _restoreCode.value = null
                    val result = repo.restoreBackup(read.json, read.photoPaths)
                    if (result.error != null) {
                        show(result.error)
                    } else {
                        // The code that opened the backup is the one that protects it from now on.
                        if (code != null) repo.setBackupCode(code)
                        show(listOfNotNull("Sicherung wiederhergestellt.", result.note).joinToString(" "), result.undo)
                    }
                }
                RestoreRead.NeedsCode -> _restoreCode.value = RestoreCodeRequest(folder, wrong = false)
                RestoreRead.WrongCode -> _restoreCode.value = RestoreCodeRequest(folder, wrong = code != null)
                is RestoreRead.Failed -> {
                    _restoreCode.value = null
                    show("Wiederherstellen fehlgeschlagen: ${read.message}")
                }
            }
        }
    }

    fun hasRestoreSnapshot(): Boolean = repo.hasRestoreSnapshot()

    fun restoreSnapshot() = show(repo.restoreSnapshot() ?: "Stand vor der Wiederherstellung ist zurück.")

    fun retrySave() = repo.retrySave()

    fun describeFolder(folder: String) = platform.describeFolder(folder)

    @OptIn(FlowPreview::class)
    private fun startAutoBackup() {
        scope.launch {
            // Only real family data triggers a backup; recording the backup itself must not loop.
            repo.data.map { listOf(it.selfId, it.members, it.items, it.recipes, it.shopping) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(AUTO_BACKUP_DELAY_MS)
                .collect {
                    val folder = repo.current.settings.backupFolder ?: return@collect
                    val content = backupContent() ?: return@collect
                    repo.recordBackup(withContext(io) { platform.writeBackup(folder, content) })
                }
        }
    }

    // ---- Metrics shown in settings ("Prüfung" 4 of the design doc) ----------------------------

    /** Median of the capture measurements (trigger until the microphone listens), or null without samples. */
    fun latencyMedianMs(): Long? =
        Measurements.median(repo.current.settings.latency.filter { it.metric == Metric.CAPTURE_READY }.map { it.ms })

    /**
     * A cold start reached its first frame [ms] after the process started. Ignored when implausibly
     * long: then the process had been running in the background (alarm, sync) before the app opened.
     */
    fun recordColdStart(trigger: String, ms: Long) {
        if (ms in 0..Measurements.COLD_START_PLAUSIBLE_MS) repo.recordLatency(trigger, ms, Metric.COLD_START)
    }

    fun measurementSummary(): List<MetricSummary> = Measurements.summarize(repo.current.settings.latency)

    fun measurementsByTrigger(metric: Metric): List<MetricSummary> =
        Measurements.byTrigger(repo.current.settings.latency, metric)

    fun resourceUsage(): ResourceUsage? = platform.resourceUsage()

    /** All local measurements as CSV, without any family data (P1-10). */
    fun measurementsCsv(): String = Measurements.csv(
        samples = repo.current.settings.latency,
        device = platform.deviceInfo() ?: DeviceInfo("unbekannt", "unbekannt", "unbekannt"),
        usage = platform.resourceUsage(),
        formatTime = platform::formatLocalTime,
    )

    fun shareMeasurements() {
        if (repo.current.settings.latency.isEmpty()) {
            show("Noch keine Messwerte zum Teilen.")
            return
        }
        if (!platform.shareText("FamilyApp Messwerte", measurementsCsv())) show("Teilen geht auf diesem Gerät nicht.")
    }

    fun clearMeasurements() {
        val before = repo.current.settings.latency
        if (before.isEmpty()) return
        repo.clearMeasurements()
        show("Messwerte gelöscht. Die nächste Messreihe beginnt leer.")
    }

    /** Share of cards that left the inbox with exactly one tap, among those decided in the app. */
    fun oneTapShare(): Pair<Int, Int> {
        val decided = repo.current.items.filter { it.status != ItemStatus.INBOX && it.inboxTaps > 0 }
        return decided.count { it.inboxTaps == 1 } to decided.size
    }

    companion object {
        const val AUTO_BACKUP_DELAY_MS = 3_000L
        private const val SAVED = "Gespeichert. Liegt oben im Eingang."

        /** Triggers of [Metric.SAVE_AFTER_SPEECH]: the recognizer noticed the pause, or "Fertig" was tapped. */
        const val SPEECH_END_AUTO = "Sprechpause"
        const val SPEECH_END_TAP = "Fertig getippt"
    }
}
