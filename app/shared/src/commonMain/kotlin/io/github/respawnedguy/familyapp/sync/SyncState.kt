package io.github.respawnedguy.familyapp.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Another device of the family, as far as this device knows it. */
@Serializable
data class KnownDevice(
    val deviceId: String,
    val name: String,
    /** The person the device belongs to; null for a house device without a person (ADR 0003, Dauer-Peer). */
    val memberId: String? = null,
    /** "host:port" entries: last LAN address, Tailscale address (100.x). */
    val addresses: List<String> = emptyList(),
    /** When this information was current (wall clock of the device that saw it last). */
    val seenMs: Long = 0,
    /** Last successful sync of THIS device with it. Local only, never taken from others. */
    val lastSyncMs: Long? = null,
)

/**
 * Secrets and device list for sync. Kept in its own file next to the family data and never part of
 * the family data itself, so the family key does not end up in backups or in sync messages.
 */
@Serializable
data class SyncState(
    /** Random id of the family, chosen by the first device that invited someone. */
    val familyId: String? = null,
    /** 32-byte family key, base64. Whoever has it can sync with the family. */
    val familyKey: String? = null,
    val devices: List<KnownDevice> = emptyList(),
    /** Shared folder for sync (Syncthing, Synology Drive, Google Drive …), platform reference; null = none. */
    val folder: String? = null,
    val lastFolderSyncMs: Long? = null,
) {
    val paired: Boolean get() = familyKey != null
}

/** Where [SyncState] lives (a private file on Android, a file next to the CLI's data). */
interface SyncStore {
    fun read(): String?
    fun write(text: String)
}

class InMemorySyncStore(var text: String? = null) : SyncStore {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

internal val syncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

object SyncStateJson {
    fun decode(text: String?): SyncState =
        text?.let { runCatching { syncJson.decodeFromString(SyncState.serializer(), it) }.getOrNull() } ?: SyncState()

    fun encode(state: SyncState): String = syncJson.encodeToString(SyncState.serializer(), state)
}

/** Merges device lists: per device the newest information wins, [KnownDevice.lastSyncMs] stays local. */
fun mergeDevices(mine: List<KnownDevice>, theirs: List<KnownDevice>, selfDeviceId: String): List<KnownDevice> {
    val out = mine.associateByTo(LinkedHashMap()) { it.deviceId }
    theirs.filter { it.deviceId != selfDeviceId }.forEach { d ->
        val old = out[d.deviceId]
        if (old == null || d.seenMs > old.seenMs) out[d.deviceId] = d.copy(lastSyncMs = old?.lastSyncMs)
    }
    return out.values.toList()
}
