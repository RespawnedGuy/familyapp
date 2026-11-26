package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.Undo
import io.github.respawnedguy.familyapp.domain.CaptureAction
import io.github.respawnedguy.familyapp.domain.DateFormat
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Source
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

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback: StateFlow<Feedback?> = _feedback.asStateFlow()

    private val _capture = MutableStateFlow<CaptureState>(CaptureState.Closed)
    val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    /** Pending latency measurement: which trigger, started when (monotonic ms). */
    private var latencyStart: Pair<String, Long>? = null

    init {
        startAutoBackup()
    }

    fun elapsedMs(): Long = platform.elapsedMs()

    // ---- Navigation --------------------------------------------------------------------------

    fun select(tab: Tab) {
        _settingsOpen.value = false
        _tab.value = tab
    }

    fun openSettings() {
        _settingsOpen.value = true
    }

    /** System back: sheet, then settings, then back to "Heute". False means: leave the app. */
    fun handleBack(): Boolean = when {
        _capture.value != CaptureState.Closed -> { dismissCapture(); true }
        _settingsOpen.value -> { _settingsOpen.value = false; true }
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
    }

    fun irrelevant(item: Item) = show("Als irrelevant markiert.", repo.markIrrelevant(item.id))

    fun complete(item: Item) = show("Erledigt.", repo.complete(item.id))

    fun takeOver(item: Item) =
        show("Übernommen. Alle sehen, dass du das machst.", repo.takeOver(item.id))

    fun setAssignee(item: Item, memberId: String) {
        val r = repo.setAssignee(item.id, memberId)
        if (r.confirmed) show("Bestätigt: ${name(memberId)} kümmert sich.", r.undo)
    }

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
        speech.start(object : SpeechListener {
            override fun onReady() {
                latencyStart?.let { (trigger, start) -> repo.recordLatency(trigger, platform.elapsedMs() - start) }
                latencyStart = null
                (_capture.value as? CaptureState.Listening)?.let { _capture.value = it.copy(ready = true) }
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
                }
            }

            override fun onError(message: String) {
                latencyStart = null
                if (_capture.value is CaptureState.Listening) _capture.value = CaptureState.Problem(message)
            }
        })
    }

    /** Ends listening early ("Fertig"); the recognizer then delivers the final result. */
    fun stopListening() = platform.speech?.stop()

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

    /** Text or image shared from another app (mail, gallery, browser). Saved without a further tap. */
    fun captureShared(text: String?, photoPath: String?) {
        if (repo.current.selfId == null) return
        if (text.isNullOrBlank() && photoPath == null) return
        save(text.orEmpty(), Source.SHARE, photoPath)
    }

    private fun save(text: String, source: Source, photoPath: String? = null) {
        val item = repo.capture(text, source, photoPath)
        _capture.value = CaptureState.Closed
        _tab.value = Tab.TODAY
        show("Gespeichert. Liegt oben im Eingang.", Undo(mapOf(item.id to null)))
    }

    fun dismissCapture() {
        platform.speech?.cancel()
        latencyStart = null
        _capture.value = CaptureState.Closed
    }

    // ---- Settings and backup -----------------------------------------------------------------

    fun setFirstCaptureAction(action: CaptureAction) = repo.setFirstCaptureAction(action)

    fun setAllowOnlineSpeech(allowed: Boolean) = repo.setAllowOnlineSpeech(allowed)

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
        return BackupContent(repo.exportBackup(), repo.current.items.mapNotNull { it.photoPath }, code)
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
            repo.data.map { Triple(it.selfId, it.members, it.items) }
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

    /** Median of the last latency samples, or null without samples. */
    fun latencyMedianMs(): Long? {
        val ms = repo.current.settings.latency.map { it.ms }.sorted()
        if (ms.isEmpty()) return null
        return if (ms.size % 2 == 1) ms[ms.size / 2] else (ms[ms.size / 2 - 1] + ms[ms.size / 2]) / 2
    }

    /** Share of cards that left the inbox with exactly one tap, among those decided in the app. */
    fun oneTapShare(): Pair<Int, Int> {
        val decided = repo.current.items.filter { it.status != ItemStatus.INBOX && it.inboxTaps > 0 }
        return decided.count { it.inboxTaps == 1 } to decided.size
    }

    companion object {
        const val AUTO_BACKUP_DELAY_MS = 3_000L
    }
}
