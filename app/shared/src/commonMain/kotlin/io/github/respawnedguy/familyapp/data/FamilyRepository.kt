package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.CaptureAction
import io.github.respawnedguy.familyapp.domain.CURRENT_SCHEMA
import io.github.respawnedguy.familyapp.domain.CaptureRules
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.HybridClock
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.LatencySample
import io.github.respawnedguy.familyapp.domain.Measurements
import io.github.respawnedguy.familyapp.domain.Metric
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Origin
import io.github.respawnedguy.familyapp.domain.PERSON_COLOR_COUNT
import io.github.respawnedguy.familyapp.domain.RecordKind
import io.github.respawnedguy.familyapp.domain.Settings
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.Stamp
import io.github.respawnedguy.familyapp.domain.Tombstone
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingEntry
import io.github.respawnedguy.familyapp.domain.readyToConfirm
import io.github.respawnedguy.familyapp.sync.SyncMerge
import io.github.respawnedguy.familyapp.sync.SyncRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Reverts exactly the records an action touched, so other changes made meanwhile survive.
 * [wholeBefore] is set only for a restore from backup, which replaces everything.
 */
class Undo internal constructor(
    internal val itemsBefore: Map<String, Item?>,
    internal val membersBefore: Map<String, Member?> = emptyMap(),
    internal val wholeBefore: FamilyData? = null,
    internal val recipesBefore: Map<String, Recipe?> = emptyMap(),
    internal val shoppingBefore: Map<String, ShoppingEntry?> = emptyMap(),
)

/** Result of restoring a backup: an error text, or the undo handle plus a note for the person. */
data class RestoreResult(val error: String? = null, val undo: Undo? = null, val note: String? = null)

/** Result of an inbox action: the undo handle and whether the card left the inbox. */
data class ActionResult(val undo: Undo, val confirmed: Boolean)

/**
 * Single source of truth on this device (data layer). Every change gets a new [io.github.respawnedguy.familyapp.domain.Stamp]
 * and is written to the private app storage in the background (atomic replace, see [TextStore]).
 */
class FamilyRepository(
    private val store: TextStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    val now: () -> Long,
    private val today: () -> LocalDate,
    private val newId: () -> String,
    private val rules: CaptureRules = CaptureRules(),
) {
    /** Set when the stored file could not be read; the broken file is kept next to it. */
    var loadProblem: String? = null
        private set

    private val _saveProblem = MutableStateFlow<String?>(null)

    /**
     * Set while the last write failed (disk full, I/O error). The data stays in memory, every later
     * change and [retrySave] try again, and the UI shows a lasting hint (P0-1 of the quality plan).
     */
    val saveProblem: StateFlow<String?> = _saveProblem.asStateFlow()


    private val _data: MutableStateFlow<FamilyData>
    val data: StateFlow<FamilyData>
    private val clock: HybridClock

    init {
        val loaded = store.read()?.let { text ->
            runCatching { FamilyJson.decode(text) }.getOrElse { e ->
                store.preserveBroken(text)
                loadProblem = "Gespeicherte Daten waren nicht lesbar und wurden beiseitegelegt (${e.message?.take(80)})."
                null
            }
        }
        val initial = loaded ?: FamilyData(deviceId = newId())
        clock = HybridClock(initial.deviceId, now)
        allStamps(initial).forEach(clock::observe)
        _data = MutableStateFlow(initial)
        data = _data.asStateFlow()
        scope.launch(ioDispatcher) {
            // StateFlow conflates: under bursts only the newest state is written.
            _data.drop(1).collect { persist(it) }
        }
    }

    /** Never throws: a failed write must neither crash the app nor stop later writes (M6). */
    @Suppress("TooGenericExceptionCaught") // any failure of the store must end up as a visible hint
    private fun persist(data: FamilyData) {
        try {
            store.write(FamilyJson.encode(data))
            _saveProblem.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _saveProblem.value = "Speichern fehlgeschlagen (${e.message?.take(80) ?: e::class.simpleName}). " +
                "Ist der Speicher voll? Deine Einträge bleiben erhalten, solange die App offen ist."
        }
    }

    /** Writes the current state again, e.g. after the person freed some space. */
    fun retrySave() {
        scope.launch(ioDispatcher) { persist(_data.value) }
    }

    val current: FamilyData get() = _data.value

    private fun update(change: (FamilyData) -> FamilyData) {
        _data.value = change(_data.value)
    }

    /** The kitchen library: recipes and the shopping list, on the same state and clock. */
    val recipeStore: RecipeRepository by lazy { RecipeRepository(this) }
    val shoppingStore: ShoppingRepository by lazy { ShoppingRepository(this) }

    internal fun edit(change: (FamilyData) -> FamilyData) = update(change)

    internal fun tick(): Stamp = clock.tick()

    internal fun nextId(): String = newId()

    internal fun day(): LocalDate = today()

    // ---- Family and members ------------------------------------------------------------------

    fun createFamily(selfName: String) {
        val name = selfName.trim()
        require(name.isNotEmpty()) { "Name fehlt" }
        if (current.selfId != null) return
        val me = Member(newId(), name, colorIndex = 0, kind = MemberKind.ADULT, hasDevice = true, stamp = clock.tick())
        update { it.copy(selfId = me.id, members = it.members + me) }
    }

    fun addMember(name: String, kind: MemberKind): Member {
        val used = current.members.map { it.colorIndex }.toSet()
        val color = (0 until PERSON_COLOR_COUNT).firstOrNull { it !in used } ?: (current.members.size % PERSON_COLOR_COUNT)
        val m = Member(newId(), name.trim(), color, kind, hasDevice = false, stamp = clock.tick())
        update { it.copy(members = it.members + m) }
        return m
    }

    fun updateMember(id: String, name: String, colorIndex: Int, kind: MemberKind) {
        update { d ->
            d.copy(members = d.members.map {
                if (it.id == id) it.copy(name = name.trim(), colorIndex = colorIndex, kind = kind, stamp = clock.tick()) else it
            })
        }
    }

    /** Removes a member; their open items go back to the inbox so nothing silently disappears. */
    fun removeMember(id: String): Undo? {
        if (id == current.selfId) return null
        val before = current
        val touched = before.items.filter { it.assignee == id || it.concerns == id }
        update { d ->
            d.copy(
                members = d.members.filterNot { it.id == id },
                tombstones = d.tombstones.filterNot { it.id == id } + Tombstone(id, RecordKind.MEMBER, clock.tick(), d.selfId),
                items = d.items.map { item ->
                    if (item !in touched) item else item.copy(
                        assignee = item.assignee.takeUnless { it == id },
                        assigneeOrigin = item.assigneeOrigin.takeUnless { item.assignee == id },
                        concerns = item.concerns.takeUnless { it == id },
                        concernsOrigin = item.concernsOrigin.takeUnless { item.concerns == id },
                        status = if (item.assignee == id && item.status == ItemStatus.OPEN) ItemStatus.INBOX else item.status,
                        changedBy = d.selfId,
                        stamp = clock.tick(),
                    )
                },
            )
        }
        return Undo(touched.associate { it.id to it }, mapOf(id to before.member(id)))
    }

    // ---- Capture -------------------------------------------------------------------------------

    fun capture(text: String, source: Source, photoPath: String? = null): Item {
        val title = text.trim().replaceFirstChar { it.uppercaseChar() }.ifEmpty { defaultTitle(source) }
        val s = rules.analyze(text, current.members, today())
        val item = Item(
            id = newId(),
            title = title,
            source = source,
            createdMs = now(),
            createdBy = current.selfId ?: "",
            photoPath = photoPath,
            changedBy = current.selfId,
            concerns = s.concerns,
            concernsOrigin = s.concerns?.let { Origin.RULE },
            due = s.due,
            dueOrigin = s.due?.let { Origin.RULE },
            reasons = s.reasons,
            stamp = clock.tick(),
        )
        update { it.copy(items = it.items + item) }
        return item
    }

    private fun defaultTitle(source: Source) = when (source) {
        Source.PHOTO -> "Foto"
        Source.SHARE -> "Geteilt"
        else -> "Ohne Titel"
    }

    // ---- Inbox decisions (design: one tap per card, undo instead of dialogs) --------------------

    private fun change(id: String, block: (Item) -> Item): Undo {
        val before = current.items.firstOrNull { it.id == id }
        update { d ->
            d.copy(items = d.items.map { if (it.id == id) block(it).copy(changedBy = d.selfId, stamp = clock.tick()) else it })
        }
        return Undo(mapOf(id to before))
    }

    private fun item(id: String): Item? = current.items.firstOrNull { it.id == id }

    private fun Item.confirmedIfReady(): Item =
        if (status == ItemStatus.INBOX && readyToConfirm) copy(status = ItemStatus.OPEN, dueDecided = true) else this

    /** Chip "kümmert sich": the choice confirms the card once a date is settled too (D4). */
    fun setAssignee(id: String, memberId: String): ActionResult {
        val undo = change(id) {
            it.copy(assignee = memberId, assigneeOrigin = Origin.PERSON, inboxTaps = it.inboxTapsPlus(2)).confirmedIfReady()
        }
        return ActionResult(undo, confirmed = item(id)?.status == ItemStatus.OPEN && undo.wasInbox(id))
    }

    /** Chip "betrifft". Does not confirm: it is optional context, not a decision. */
    fun setConcerns(id: String, memberId: String?): Undo =
        change(id) { it.copy(concerns = memberId, concernsOrigin = memberId?.let { Origin.PERSON }, inboxTaps = it.inboxTapsPlus(2)) }

    /** Chip "bis wann": a date or null for "Kein Datum". Confirms once a person is set (D4). */
    fun setDue(id: String, date: LocalDate?): ActionResult {
        val undo = change(id) {
            it.copy(due = date, dueDecided = true, dueOrigin = Origin.PERSON, inboxTaps = it.inboxTapsPlus(2)).confirmedIfReady()
        }
        return ActionResult(undo, confirmed = item(id)?.status == ItemStatus.OPEN && undo.wasInbox(id))
    }

    /**
     * "Passt" (assignee known) or "Mach ich" (nobody yet: the person who taps takes it).
     * A suggested date is accepted as it is; no date stays "Kein Datum".
     */
    fun confirm(id: String): Undo = change(id) {
        it.copy(
            status = ItemStatus.OPEN,
            assignee = it.assignee ?: current.selfId,
            assigneeOrigin = it.assigneeOrigin ?: Origin.PERSON,
            dueDecided = true,
            inboxTaps = it.inboxTapsPlus(1),
        )
    }

    fun markIrrelevant(id: String): Undo = change(id) { it.copy(status = ItemStatus.IRRELEVANT, inboxTaps = it.inboxTapsPlus(1)) }

    fun complete(id: String): Undo =
        change(id) { it.copy(status = ItemStatus.DONE, doneMs = now(), doneOn = today(), doneBy = current.selfId) }

    /** "Übernehme ich": visible to everyone, there is no hidden doing (KONZEPT, Phase 5). */
    fun takeOver(id: String): Undo = change(id) {
        it.copy(assignee = current.selfId, assigneeOrigin = Origin.PERSON, status = if (it.status == ItemStatus.INBOX) it.status else ItemStatus.OPEN)
            .confirmedIfReady()
    }

    fun rename(id: String, title: String): Undo = change(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    /** Puts a finished or dismissed item back to the inbox. */
    fun reopen(id: String): Undo = change(id) { it.copy(status = ItemStatus.INBOX, doneMs = null, doneOn = null, doneBy = null) }

    /** Removes the item and leaves a tombstone (no content), so a later sync cannot bring it back. */
    fun delete(id: String): Undo {
        val before = item(id) ?: return Undo(emptyMap())
        update { d ->
            d.copy(
                items = d.items.filterNot { it.id == id },
                tombstones = d.tombstones.filterNot { it.id == id } + Tombstone(id, RecordKind.ITEM, clock.tick(), d.selfId),
            )
        }
        return Undo(mapOf(id to before))
    }

    fun undo(undo: Undo) {
        undo.wholeBefore?.let { before ->
            restoreWhole(before)
            saveSnapshot(null)
            return
        }
        update { d ->
            var items = d.items
            undo.itemsBefore.forEach { (id, old) ->
                items = if (old == null) items.filterNot { it.id == id }
                else if (items.any { it.id == id }) items.map { if (it.id == id) old.copy(stamp = clock.tick()) else it }
                else items + old.copy(stamp = clock.tick())
            }
            var members = d.members
            undo.membersBefore.forEach { (id, old) ->
                members = if (old == null) members.filterNot { it.id == id }
                else if (members.any { it.id == id }) members.map { if (it.id == id) old.copy(stamp = clock.tick()) else it }
                else members + old.copy(stamp = clock.tick())
            }
            // A record that comes back must not stay marked as deleted.
            val revived = undo.itemsBefore.filterValues { it != null }.keys + undo.membersBefore.filterValues { it != null }.keys
            d.copy(
                items = items,
                members = members,
                tombstones = d.tombstones.filterNot { it.id in revived },
                recipes = recipeStore.revert(d.recipes, undo.recipesBefore),
                shopping = shoppingStore.revert(d.shopping, undo.shoppingBefore),
            )
        }
    }

    /**
     * Puts back a whole state (backup or the state before a restore). Records keep their stamps, so a
     * later sync still decides by the real age of each change and an old backup cannot win over newer
     * edits on other devices. The clock has seen all stamps, so the next local change is newer.
     */
    private fun restoreWhole(before: FamilyData) {
        allStamps(before).forEach(clock::observe)
        update {
            before.copy(
                deviceId = it.deviceId,
                schema = CURRENT_SCHEMA,
                settings = before.settings.copy(backupFolder = it.settings.backupFolder, latency = it.settings.latency),
            )
        }
    }

    private fun Item.inboxTapsPlus(n: Int) = if (status == ItemStatus.INBOX) inboxTaps + n else inboxTaps

    private fun Undo.wasInbox(id: String) = itemsBefore[id]?.status == ItemStatus.INBOX

    // ---- Settings, metrics, backup -------------------------------------------------------------

    private fun settings(block: (Settings) -> Settings) = update { it.copy(settings = block(it.settings)) }

    fun setFirstCaptureAction(action: CaptureAction) = settings { it.copy(firstCaptureAction = action) }

    fun setAllowOnlineSpeech(allowed: Boolean) = settings { it.copy(allowOnlineSpeech = allowed) }

    fun setBackupFolder(folder: String?) = settings { it.copy(backupFolder = folder, lastBackupError = null) }

    fun setBackupCode(code: String) = settings { it.copy(backupCode = code) }

    fun setRemindersOn(on: Boolean) = settings { it.copy(remindersOn = on) }

    fun recordRemindersChecked(ms: Long) = settings { it.copy(remindersCheckedMs = ms) }

    fun recordNotificationsAsked() = settings { it.copy(notificationsAsked = true) }

    fun dismissLocalOnlyHint() = settings { it.copy(localOnlyHintDismissedAt = current.items.size) }

    fun recordBackup(error: String?) = settings {
        if (error == null) it.copy(lastBackupMs = now(), lastBackupError = null) else it.copy(lastBackupError = error)
    }

    fun recordLatency(trigger: String, ms: Long, metric: Metric = Metric.CAPTURE_READY) = settings {
        it.copy(latency = Measurements.add(it.latency, LatencySample(trigger, ms, now(), metric)))
    }

    /** Starts a fresh measurement series, e.g. before a reference measurement on a new phone. */
    fun clearMeasurements() = settings { it.copy(latency = emptyList()) }

    fun rename(selfName: String) {
        val me = current.self ?: return
        updateMember(me.id, selfName, me.colorIndex, me.kind)
    }

    /** Text of a readable backup (pretty-printed JSON with all members, items and settings). */
    fun exportBackup(): String = FamilyJson.encodePretty(current)

    /**
     * Replaces everything with a backup. This device keeps its own device id so its future changes
     * stay distinguishable. The state before is kept as a snapshot on disk and as an undo handle, so
     * a wrong or old backup never destroys newer data for good (P0-2).
     * [photoPaths] maps photo file names in the backup to where the platform unpacked them.
     */
    fun restoreBackup(text: String, photoPaths: Map<String, String> = emptyMap()): RestoreResult {
        val restored = runCatching { FamilyJson.decode(text) }
            .getOrElse { return RestoreResult(error = "Datei ist keine FamilyApp-Sicherung (${it.message?.take(80)})") }
        if (restored.selfId == null) return RestoreResult(error = "Die Sicherung enthält keine Familie.")
        val before = current
        val snapshotSaved = saveSnapshot(FamilyJson.encode(before))
        val note = olderNote(restored, before)
        restoreWhole(
            restored.copy(
                items = restored.items.map { item ->
                    item.photoPath?.let { path -> photoPaths[photoName(path)]?.let { item.copy(photoPath = it) } } ?: item
                },
            ),
        )
        val extra = if (snapshotSaved) null else "Der vorige Stand ließ sich nicht sichern, nur 6 s Rückgängig."
        return RestoreResult(undo = Undo(emptyMap(), wholeBefore = before), note = listOfNotNull(note, extra).joinToString(" ").ifEmpty { null })
    }

    /** Cached, because the settings screen asks on every recomposition; null = not read yet. */
    private var snapshotPresent: Boolean? = null

    /** True if a state from before the last restore is kept on disk. */
    fun hasRestoreSnapshot(): Boolean =
        snapshotPresent ?: runCatching { store.readSnapshot() != null }.getOrDefault(false).also { snapshotPresent = it }

    private fun saveSnapshot(text: String?): Boolean =
        runCatching { store.writeSnapshot(text) }.isSuccess.also { ok -> snapshotPresent = if (ok) text != null else null }

    /** Brings back the state from before the last restore (settings: "Stand vor der Wiederherstellung"). */
    fun restoreSnapshot(): String? {
        val text = runCatching { store.readSnapshot() }.getOrNull() ?: return "Kein früherer Stand gespeichert."
        val before = runCatching { FamilyJson.decode(text) }.getOrElse { return "Früherer Stand ist nicht lesbar." }
        restoreWhole(before)
        saveSnapshot(null)
        return null
    }

    private fun olderNote(restored: FamilyData, before: FamilyData): String? {
        val newestBackup = newestStamp(restored) ?: return null
        val newestHere = newestStamp(before) ?: return null
        return if (newestBackup < newestHere) "Achtung: Die Sicherung ist älter als die Daten auf diesem Handy." else null
    }

    private fun newestStamp(d: FamilyData): Long? = allStamps(d).maxOfOrNull { it.wallMs }

    private fun allStamps(d: FamilyData): List<Stamp> =
        d.members.map { it.stamp } + d.items.map { it.stamp } + d.tombstones.map { it.stamp } +
            d.recipes.map { it.stamp } + d.shopping.map { it.stamp }

    // ---- Sync between family devices (ADR 0003, ADR 0006) ---------------------------------------

    /**
     * Applies records another device sent (newest stamp per record wins, see [SyncMerge]).
     * [photoPath] maps a photo file name to its path on this device. Returns how many records changed.
     */
    fun applySync(records: SyncRecords, photoPath: (String) -> String): Int {
        observeAll(records)
        val applied = SyncMerge.apply(current, records, photoPath)
        if (applied.changed > 0) update { SyncMerge.apply(it, records, photoPath).data }
        return applied.changed
    }

    /** This device joined a family by pairing: take over its data, keep own entries (see [SyncMerge.adopt]). */
    fun joinFamily(records: SyncRecords, selfMemberId: String?, photoPath: (String) -> String) {
        observeAll(records)
        val family = records.copy(
            items = records.items.map { it.copy(photoPath = it.photoPath?.let { path -> photoPath(photoName(path)) }) },
        )
        update { SyncMerge.adopt(it, family, selfMemberId, clock::tick) }
    }

    /** A person got their own paired phone ("eigenes Handy" in the family list). */
    fun markHasDevice(id: String) {
        update { d ->
            val members = d.members.map { m ->
                if (m.id == id && !m.hasDevice) m.copy(hasDevice = true, stamp = clock.tick()) else m
            }
            d.copy(members = members)
        }
    }

    private fun observeAll(records: SyncRecords) {
        records.members.forEach { clock.observe(it.stamp) }
        records.items.forEach { clock.observe(it.stamp) }
        records.tombstones.forEach { clock.observe(it.stamp) }
        records.recipes.forEach { clock.observe(it.stamp) }
        records.shopping.forEach { clock.observe(it.stamp) }
    }

    companion object {
        /** File name of a stored photo, the key that survives moving photos between devices. */
        fun photoName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    }
}
