package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.domain.CURRENT_SCHEMA
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64

/** Who a newly paired device belongs to; the inviter picks it while confirming. */
sealed interface JoinAs {
    val label: String

    /** Another device of the inviting person (tablet, second phone). */
    data class Myself(val name: String) : JoinAs {
        override val label get() = "$name (ich, weiteres Gerät)"
    }

    /** A person already in the family who had no phone in the app yet. */
    data class Existing(val memberId: String, val name: String) : JoinAs {
        override val label get() = name
    }

    data class NewPerson(val name: String) : JoinAs {
        override val label get() = "$name (neu)"
    }

    /** Device without a person: Surface, Synology or Raspi in the tailnet that is always reachable. */
    data object HouseDevice : JoinAs {
        override val label get() = "Hausgerät (keine Person)"
    }
}

sealed interface PairingState {
    data object Idle : PairingState

    /** Inviter: the QR code is shown. */
    data class Inviting(val code: String, val expiresAtMs: Long) : PairingState

    /** Both screens: compare the digits. [choices] only on the inviting device. */
    data class Confirm(
        val sas: String,
        val other: PairHello,
        val inviting: Boolean,
        val choices: List<JoinAs> = emptyList(),
        val suggested: JoinAs? = null,
    ) : PairingState

    data class Working(val text: String) : PairingState
    data class Done(val message: String, val firstPairing: Boolean) : PairingState
    data class Failed(val message: String) : PairingState
}

/** What the settings show about sync. */
data class SyncStatus(
    val paired: Boolean = false,
    val folder: String? = null,
    val lastFolderSyncMs: Long? = null,
    val devices: List<KnownDevice> = emptyList(),
    val running: Boolean = false,
    val syncing: Boolean = false,
    val lastResult: String? = null,
    val problem: String? = null,
)

/**
 * Pairing and sync on this device (ADR 0003, ADR 0006). Shared by the Android app and the house
 * device program. Repository access runs on [repoDispatcher] (the thread that owns the repository),
 * network and files on [io].
 */
@Suppress("TooManyFunctions", "LongParameterList") // one place for the whole sync lifecycle
class SyncController(
    private val repo: FamilyRepository,
    private val store: SyncStore,
    private val transport: SyncTransport,
    private val crypto: SyncCrypto,
    private val photos: PhotoFiles,
    private val scope: CoroutineScope,
    private val repoDispatcher: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val deviceName: String,
    private val discovery: PeerDiscovery? = null,
    /** Opens a shared folder by its platform reference (see [SyncState.folder]). */
    private val folders: (String) -> SyncFolderFiles? = { null },
    private val now: () -> Long = repo.now,
    private val preferredPort: Int = DEFAULT_PORT,
) : SyncParticipant {

    private var state: SyncState = SyncStateJson.decode(store.read())
    private val _status = MutableStateFlow(statusOf(state, SyncStatus()))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    private var port: Int? = null
    private var loops: List<Job> = emptyList()
    private val syncLock = Mutex()
    private val stateLock = Mutex()
    private val discovered = mutableSetOf<String>()

    private var invitation: Invitation? = null
    private var pairingAnswer: CompletableDeferred<JoinAs?>? = null
    private var joinAnswer: CompletableDeferred<Boolean>? = null

    val listeningPort: Int? get() = port

    // ---- Lifecycle -------------------------------------------------------------------------------

    /** App in the foreground (or house device running): listen, find peers, sync now and then. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (port != null) return
        port = runCatching { transport.startServer(preferredPort) { handle(it) } }.getOrElse {
            _status.value = _status.value.copy(problem = "Abgleich kann nicht starten: ${it.message}")
            return
        }
        _status.value = _status.value.copy(running = true, problem = null)
        discovery?.start(port!!) { address -> onDiscovered(address) }
        loops = listOf(
            scope.launch {
                while (true) {
                    syncAll()
                    delay(PERIODIC_MS)
                }
            },
            scope.launch {
                // Own changes go out shortly after they happen, while the other device is still open.
                repo.data.map { Triple(it.members, it.items, it.tombstones) }.distinctUntilChanged().drop(1)
                    .debounce(CHANGE_DELAY_MS).collect { syncAll() }
            },
        )
    }

    fun stop() {
        loops.forEach { it.cancel() }
        loops = emptyList()
        discovery?.stop()
        transport.stopServer()
        port = null
        _status.value = _status.value.copy(running = false, syncing = false)
    }

    fun syncNow() {
        scope.launch { syncAll(manual = true) }
    }

    private fun onDiscovered(address: String) {
        val own = port?.let { p -> transport.localAddresses().map { "$it:$p" } }.orEmpty()
        if (address in discovered || address in own) return
        discovered += address
        scope.launch { syncAll() }
    }

    // ---- Sync as the connecting side -------------------------------------------------------------

    /** Syncs through the shared folder and with every known or discovered device. Never throws. */
    suspend fun syncAll(manual: Boolean = false) {
        if (!state.paired) return
        syncLock.lock() // a sync already running finishes first; then this one sees its result
        try {
            _status.value = _status.value.copy(syncing = true)
            val folderText = state.folder?.let { syncFolder(it) }
            val known = state.devices.flatMap { it.addresses }.toSet()
            val found = (discovered - known).map { listOf(it) }
            val targets = (state.devices.map { it.addresses } + found).filter { it.isNotEmpty() }
            val results = targets.map { syncWithAny(it) }
            val ok = results.filterNotNull()
            val failed = results.size - ok.size
            val direct = when {
                targets.isEmpty() -> null
                ok.isEmpty() -> "Direkt kein Gerät erreichbar (andere App offen, selbes WLAN oder Tailnet?)."
                else -> summary(ok) + if (failed > 0) " $failed nicht erreichbar." else ""
            }
            val text = listOfNotNull(folderText, direct).joinToString(" ").ifEmpty { NO_DEVICE }
            if (manual || ok.isNotEmpty() || folderText != null) _status.value = _status.value.copy(lastResult = text)
        } finally {
            _status.value = _status.value.copy(syncing = false)
            syncLock.unlock()
        }
    }

    private var folderSync: Pair<String, FolderSync>? = null

    private suspend fun syncFolder(reference: String): String {
        val files = folders(reference)
        val sync = folderSyncFor(state.familyKey.orEmpty())
        val outcome = files?.let { f ->
            runSession { withContext(io) { sync.round(f, this@SyncController) { snapshot() } } }
        }
        if (outcome != null) {
            updateState { it.copy(lastFolderSyncMs = now()) }
            outcome.problem?.let { _status.value = _status.value.copy(problem = it) }
        }
        return when {
            files == null -> "Abgleichordner nicht erreichbar."
            outcome == null -> "Abgleichordner: Lesen oder Schreiben fehlgeschlagen."
            outcome.others.isEmpty() -> "Ordner: noch keine Daten anderer Geräte."
            else -> "Ordner (${outcome.others.joinToString(", ")}): ${outcome.received} empfangen."
        }
    }

    private fun folderSyncFor(familyKey: String): FolderSync =
        folderSync?.takeIf { it.first == familyKey }?.second
            ?: FolderSync(crypto, FolderSync.key(crypto, familyKey, state.familyId.orEmpty()))
                .also { folderSync = familyKey to it }

    private suspend fun snapshot(): FolderSnapshot = withContext(repoDispatcher) {
        val all = SyncMerge.newerThan(repo.current, emptyMap())
        FolderSnapshot(SYNC_PROTOCOL, CURRENT_SCHEMA, myDevice(), state.devices, all)
    }

    /** The first device that invites someone or picks a folder creates the family key. */
    private suspend fun foundFamilyIfNeeded() {
        if (state.paired) return
        val id = hex(crypto.randomBytes(ID_BYTES))
        val key = Base64.encode(crypto.randomBytes(KEY_BYTES))
        updateState { it.copy(familyId = id, familyKey = key) }
    }

    /** Uses a shared folder for sync from now on; null stops it. */
    fun useFolder(reference: String?) {
        scope.launch {
            folderSync = null
            if (reference != null) foundFamilyIfNeeded()
            updateState { it.copy(folder = reference) }
            if (reference != null) syncAll(manual = true)
        }
    }

    private fun summary(ok: List<SyncOutcome>): String {
        val names = ok.joinToString(", ") { it.other.name }
        val received = ok.sumOf { it.received }
        val sent = ok.sumOf { it.sent }
        return "Abgeglichen mit $names: $received empfangen, $sent gesendet."
    }

    private suspend fun syncWithAny(addresses: List<String>): SyncOutcome? {
        for (address in addresses) {
            val outcome = runSession { connectAndSync(address) }
            if (outcome != null) {
                if (address in discovered) discovered -= address
                return outcome
            }
        }
        return null
    }

    private suspend fun connectAndSync(address: String): SyncOutcome = withContext(io) {
        val raw = transport.connect(address, CONNECT_TIMEOUT_MS, SYNC_READ_TIMEOUT_MS)
        try {
            val channel = Handshake.initiate(raw, Purpose.SYNC, syncSecret(), crypto)
            SyncSession(channel, this@SyncController).initiate().also { remember(it, address) }
        } finally {
            raw.close()
        }
    }

    /** Runs one session; failures end as status text, never as a crash. */
    @Suppress("TooGenericExceptionCaught") // network and peers can fail in any way; the app must go on
    private suspend fun <T> runSession(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncRefusedException) {
        _status.value = _status.value.copy(problem = e.message)
        null
    } catch (e: SyncAuthException) {
        _status.value = _status.value.copy(problem = "Verbindung abgelehnt: ${e.message}")
        null
    } catch (@Suppress("SwallowedException") e: Exception) {
        null // unreachable or broken connection: the next address or the next round tries again
    }

    // ---- Server side --------------------------------------------------------------------------

    private suspend fun handle(raw: FrameChannel) {
        runSession {
            try {
                val pending = Handshake.readPurpose(raw)
                when (pending.purpose) {
                    Purpose.SYNC -> {
                        if (!state.paired) return@runSession
                        val channel = Handshake.respond(pending, syncSecret(), crypto)
                        val outcome = SyncSession(channel, this).respond()
                        remember(outcome, null)
                        _status.value = _status.value.copy(lastResult = summary(listOf(outcome)), problem = null)
                    }
                    Purpose.PAIR -> answerPairing(pending)
                }
            } finally {
                raw.close()
            }
        }
    }

    private suspend fun remember(outcome: SyncOutcome, address: String?) = updateState { s ->
        val other = outcome.other
        if (other.deviceId == repo.current.deviceId) return@updateState s // found itself in the WLAN
        val known = s.devices.firstOrNull { it.deviceId == other.deviceId }
        val addresses = (listOfNotNull(address) + other.addresses + known?.addresses.orEmpty())
            .distinct().take(MAX_ADDRESSES)
        val updated = (known ?: other).copy(
            name = other.name, memberId = other.memberId, addresses = addresses, seenMs = now(), lastSyncMs = now(),
        )
        s.copy(devices = s.devices.filterNot { it.deviceId == other.deviceId } + updated)
    }

    private suspend fun updateState(block: (SyncState) -> SyncState) = stateLock.withLock {
        state = block(state)
        withContext(io) { store.write(SyncStateJson.encode(state)) }
        _status.value = statusOf(state, _status.value)
    }

    private fun statusOf(s: SyncState, old: SyncStatus) =
        old.copy(paired = s.paired, devices = s.devices, folder = s.folder, lastFolderSyncMs = s.lastFolderSyncMs)

    private fun syncSecret(): ByteArray {
        val key = Base64.decode(state.familyKey ?: throw SyncAuthException("Nicht gekoppelt"))
        val info = state.familyId.orEmpty().encodeToByteArray()
        return crypto.hkdf(key, "familyapp-sync-psk".encodeToByteArray(), info, KEY_BYTES)
    }

    // ---- SyncParticipant -------------------------------------------------------------------------

    private fun myDevice(): KnownDevice {
        val p = port
        return KnownDevice(
            deviceId = repo.current.deviceId,
            name = deviceName,
            memberId = repo.current.selfId,
            addresses = if (p == null) emptyList() else transport.localAddresses().map { "$it:$p" },
            seenMs = now(),
        )
    }

    override suspend fun hello(): Hello = withContext(repoDispatcher) {
        Hello(SYNC_PROTOCOL, CURRENT_SCHEMA, myDevice(), state.devices)
    }

    override suspend fun digest(): Map<String, Stamp> = withContext(repoDispatcher) { SyncMerge.digest(repo.current) }

    override suspend fun recordsFor(theirs: Map<String, Stamp>): SyncRecords =
        withContext(repoDispatcher) { SyncMerge.newerThan(repo.current, theirs) }

    override suspend fun apply(records: SyncRecords): Int =
        withContext(repoDispatcher) { repo.applySync(records, photos::pathFor) }

    override suspend fun missingPhotos(): List<String> {
        val names = withContext(repoDispatcher) { SyncMerge.photoNames(repo.current) }
        return withContext(io) { names.filter { PhotoFiles.isSafeName(it) && !photos.exists(it) } }
    }

    override suspend fun ownPhotoNames(): List<String> {
        val names = withContext(repoDispatcher) { SyncMerge.photoNames(repo.current) }
        return withContext(io) { names.filter { PhotoFiles.isSafeName(it) && photos.exists(it) } }
    }

    override suspend fun readPhoto(name: String): ByteArray? {
        // Only photos the family data points to, never other files.
        val known = withContext(repoDispatcher) { SyncMerge.photoNames(repo.current) }
        if (name !in known || !PhotoFiles.isSafeName(name)) return null
        return withContext(io) { photos.read(name)?.takeIf { it.size <= MAX_PHOTO_BYTES } }
    }

    override suspend fun writePhoto(name: String, bytes: ByteArray) {
        if (!PhotoFiles.isSafeName(name) || bytes.size > MAX_PHOTO_BYTES) return
        withContext(io) { photos.write(name, bytes) }
    }

    override suspend fun met(other: Hello) = updateState { s ->
        s.copy(devices = mergeDevices(s.devices, other.devices + other.device, repo.current.deviceId))
    }

    // ---- Pairing: inviting device ------------------------------------------------------------

    /** Shows a QR code (10 minutes, one use). Founds the family on the first invitation. */
    fun invite() {
        scope.launch {
            start()
            val p = port ?: return@launch fail("Abgleich läuft nicht.")
            val addresses = transport.localAddresses().map { "$it:$p" }
            if (addresses.isEmpty()) return@launch fail("Kein Netzwerk. Bitte mit dem WLAN oder Tailscale verbinden.")
            foundFamilyIfNeeded()
            val self = withContext(repoDispatcher) { repo.current.self?.name ?: deviceName }
            val inv = Invitation(
                inviterName = self,
                inviterDeviceId = repo.current.deviceId,
                secret = Base64.encode(crypto.randomBytes(KEY_BYTES)),
                addresses = addresses,
                expiresAtMs = now() + Invitation.VALIDITY_MS,
            )
            invitation = inv
            _pairing.value = PairingState.Inviting(inv.encode(), inv.expiresAtMs)
        }
    }

    /** Closes the pairing screen; an open invitation becomes invalid. */
    fun cancelPairing() {
        invitation = null
        pairingAnswer?.complete(null)
        joinAnswer?.complete(false)
        _pairing.value = PairingState.Idle
    }

    /** The person compared the digits. On the inviting device [joinAs] says who the new device belongs to. */
    fun answer(accept: Boolean, joinAs: JoinAs? = null) {
        // State first: the protocol may resume on another thread at once and set Done or Failed,
        // which must not be overwritten by "waiting".
        if (accept) _pairing.value = PairingState.Working("Warte auf das andere Gerät …")
        pairingAnswer?.complete(if (accept) joinAs else null)
        joinAnswer?.complete(accept)
    }

    private suspend fun answerPairing(pending: Handshake.Pending) {
        val inv = invitation?.takeIf { now() <= it.expiresAtMs } ?: return
        val channel = Handshake.respond(pending, inv.secretBytes(), crypto)
        val mine = withContext(repoDispatcher) { ownPairHello(repo.current.self?.name ?: deviceName) }
        val firstPairing = state.devices.isEmpty()
        val joined = try {
            PairingProtocol.invite(channel, crypto, mine) { prompt ->
                // The secret was proven: the invitation is used up, a second scan gets nothing.
                invitation = null
                askInviter(prompt)?.let { joinAs -> suspend { welcome(joinAs, prompt.other) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncAuthException) {
            // Before the QR secret was proven (wrong code, port scanner) the QR code stays valid.
            if (invitation == null) fail("Kopplung fehlgeschlagen: ${e.message}")
            return
        }
        _pairing.value = if (joined == null) {
            PairingState.Failed(CANCELLED)
        } else {
            PairingState.Done("${joined.personName} (${joined.deviceName}) ist gekoppelt.", firstPairing)
        }
    }

    private suspend fun askInviter(prompt: PairingPrompt): JoinAs? {
        val (choices, suggested) = withContext(repoDispatcher) { choicesFor(prompt.other) }
        val answer = CompletableDeferred<JoinAs?>()
        pairingAnswer = answer
        _pairing.value = PairingState.Confirm(prompt.code, prompt.other, inviting = true, choices, suggested)
        return try {
            withTimeout(DECISION_TIMEOUT_MS) { answer.await() }
        } finally {
            pairingAnswer = null
        }
    }

    /** Possible owners of the new device; a person with the same name is suggested (the inviter confirms). */
    private fun choicesFor(other: PairHello): Pair<List<JoinAs>, JoinAs> {
        val d = repo.current
        val self = d.self
        val name = other.personName.trim()
        val existing = d.members.filter { it.id != d.selfId && !it.hasDevice }.map { JoinAs.Existing(it.id, it.name) }
        val myself = JoinAs.Myself(self?.name ?: deviceName)
        val new = JoinAs.NewPerson(name.ifEmpty { "Neue Person" })
        val choices = existing + listOfNotNull(new.takeIf { name.isNotEmpty() }, myself, JoinAs.HouseDevice)
        val suggested = JoinAs.HouseDevice.takeIf { other.houseDevice }
            ?: existing.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: myself.takeIf { self != null && self.name.equals(name, ignoreCase = true) }
            ?: choices.first()
        return choices to suggested
    }

    private suspend fun welcome(joinAs: JoinAs, other: PairHello): Welcome {
        val memberId = withContext(repoDispatcher) {
            when (joinAs) {
                is JoinAs.Myself -> repo.current.selfId
                is JoinAs.Existing -> joinAs.memberId.also(repo::markHasDevice)
                is JoinAs.NewPerson -> repo.addMember(joinAs.name, MemberKind.ADULT).id.also(repo::markHasDevice)
                JoinAs.HouseDevice -> null
            }
        }
        updateState { s ->
            val device = KnownDevice(other.deviceId, other.deviceName, memberId, emptyList(), now())
            s.copy(devices = s.devices.filterNot { it.deviceId == other.deviceId } + device)
        }
        val records = withContext(repoDispatcher) { SyncMerge.newerThan(repo.current, emptyMap()) }
        val me = withContext(repoDispatcher) { myDevice() }
        return Welcome(state.familyId!!, state.familyKey!!, memberId, records, state.devices + me)
    }

    // ---- Pairing: new device -----------------------------------------------------------------

    /** Scanned or pasted code. [personName] is shown to the inviter ("Lisa möchte beitreten"). */
    fun join(code: String, personName: String, houseDevice: Boolean = false) {
        scope.launch {
            val inv = Invitation.decode(code) ?: return@launch fail("Das ist kein FamilyApp-Code.")
            if (now() > inv.expiresAtMs) return@launch fail(EXPIRED)
            _pairing.value = PairingState.Working("Verbinde mit ${inv.inviterName} …")
            val result = runCatchingPairing { joinVia(inv, personName.trim(), houseDevice) }
            if (result == null && _pairing.value is PairingState.Working) {
                fail("${inv.inviterName}s Gerät war nicht erreichbar. $NETWORK_HINT")
            }
        }
    }

    /** Returns null if no address of the invitation was reachable. */
    private suspend fun joinVia(inv: Invitation, personName: String, houseDevice: Boolean): Unit? {
        val raw = inv.addresses.firstNotNullOfOrNull { address ->
            runCatchingPairing {
                withContext(io) { transport.connect(address, CONNECT_TIMEOUT_MS, PAIR_READ_TIMEOUT_MS) }
            }
        } ?: return null
        try {
            val channel = withContext(io) { Handshake.initiate(raw, Purpose.PAIR, inv.secretBytes(), crypto) }
            val mine = withContext(repoDispatcher) { ownPairHello(personName).copy(houseDevice = houseDevice) }
            val welcome = withContext(io) { PairingProtocol.join(channel, crypto, inv, mine) { askJoiner(it) } }
            if (welcome == null) fail(CANCELLED) else adopt(welcome)
        } catch (e: PairingException) {
            fail(e.message ?: "Kopplung fehlgeschlagen.")
        } catch (e: SyncAuthException) {
            fail("Kopplung fehlgeschlagen: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Reached, but the connection broke or timed out: not "unreachable".
            fail("Die Verbindung zu ${inv.inviterName}s Gerät ist abgebrochen. Bitte neu koppeln.")
        } finally {
            raw.close()
        }
        return Unit
    }

    private fun ownPairHello(personName: String) = PairHello(repo.current.deviceId, deviceName, personName)

    private suspend fun askJoiner(prompt: PairingPrompt): Boolean {
        val answer = CompletableDeferred<Boolean>()
        joinAnswer = answer
        _pairing.value = PairingState.Confirm(prompt.code, prompt.other, inviting = false)
        return try {
            withTimeout(DECISION_TIMEOUT_MS) { answer.await() }
        } finally {
            joinAnswer = null
        }
    }

    private suspend fun adopt(welcome: Welcome) {
        withContext(repoDispatcher) { repo.joinFamily(welcome.records, welcome.memberId, photos::pathFor) }
        updateState {
            it.copy(
                familyId = welcome.familyId,
                familyKey = welcome.familyKey,
                devices = mergeDevices(emptyList(), welcome.devices, repo.current.deviceId),
            )
        }
        folderSync = null
        _pairing.value = PairingState.Done(JOINED, firstPairing = true)
        start()
        syncAll()
    }

    @Suppress("TooGenericExceptionCaught") // a failed connection attempt only means: try the next address
    private suspend fun <T> runCatchingPairing(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("SwallowedException") e: Exception) {
        null
    }

    private fun fail(message: String) {
        _pairing.value = PairingState.Failed(message)
    }

    companion object {
        const val DEFAULT_PORT = 47420
        const val PERIODIC_MS = 60_000L
        const val CHANGE_DELAY_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 3_000
        const val SYNC_READ_TIMEOUT_MS = 30_000
        const val PAIR_READ_TIMEOUT_MS = 200_000
        const val DECISION_TIMEOUT_MS = 180_000L
        const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
        private const val MAX_ADDRESSES = 6
        private const val KEY_BYTES = 32
        private const val ID_BYTES = 16

        private const val CANCELLED = "Kopplung abgebrochen. Nichts wurde übertragen."
        private const val NO_DEVICE = "Noch kein anderes Gerät bekannt."
        private const val EXPIRED = "Der Code ist abgelaufen. Bitte einen neuen QR-Code zeigen lassen."
        private const val NETWORK_HINT = "Beide im selben WLAN oder Tailnet? QR-Code noch offen?"
        private const val JOINED = "Gekoppelt. Fotos und Änderungen kommen jetzt automatisch."
        private const val HEX = 16
        private const val BYTE_MASK = 0xff

        private fun hex(bytes: ByteArray) =
            bytes.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
    }
}
