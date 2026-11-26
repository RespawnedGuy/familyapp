package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.domain.DeviceInfo
import io.github.respawnedguy.familyapp.domain.ResourceUsage

/** Callbacks of on-device speech recognition (Android SpeechRecognizer, later iOS SFSpeechRecognizer). */
interface SpeechListener {
    /** The microphone is open and listening: the moment the 2-second target (D8) measures. */
    fun onReady()

    /** The recognizer noticed that the person stopped speaking: the moment D8 (b) "saved ≤ 1 s later" measures from. */
    fun onEndOfSpeech() = Unit
    fun onPartial(text: String)
    fun onResult(text: String)
    fun onError(message: String)
}

interface SpeechInput {
    /** Asks for the microphone permission if needed, then starts listening. */
    fun start(listener: SpeechListener)
    fun stop()
    fun cancel()
}

/**
 * Everything the shared UI needs from the platform. Android implements it in androidApp;
 * iOS gets its own implementation when the iOS app is built.
 */
interface PlatformServices : DeviceMeasurements {
    /** Null where no recognizer is available; the capture sheet then offers text instead. */
    val speech: SpeechInput?

    /** Monotonic milliseconds for latency measurements (not wall clock). */
    fun elapsedMs(): Long

    /** "Remove animations" / "Reduce motion" is on: no movement, only instant changes. */
    fun reducedMotion(): Boolean

    /** Opens the system camera; returns the stored photo's path or null if cancelled. */
    fun takePhoto(onResult: (String?) -> Unit)

    /** Lets the person choose a folder (for backups or to restore from); returns an opaque folder reference. */
    fun pickBackupFolder(onResult: (String?) -> Unit)

    /**
     * Writes an encrypted backup into the folder: the family data and every photo, each file
     * encrypted with the recovery code (format in docs/qualitaet, P0-3). Returns an error text or null.
     */
    fun writeBackup(folder: String, backup: BackupContent): String?

    /** Reads a backup from a folder; photos are unpacked into private storage. */
    fun readBackup(folder: String, code: String?): RestoreRead

    /** A new random recovery code (about 100 bits, from a secure random source). */
    fun newRecoveryCode(): String

    /** Asks the system for permission to show notifications where that is needed (Android 13+). */
    fun requestNotificationPermission()

    /** Human-readable name of a backup folder reference. */
    fun describeFolder(folder: String): String

}

/** What goes into a backup. [photoPaths] are the private photo files the items point to. */
class BackupContent(val json: String, val photoPaths: List<String>, val code: String)

sealed interface RestoreRead {
    /** [photoPaths] maps a photo's file name to where it was unpacked on this device. */
    class Ok(val json: String, val photoPaths: Map<String, String>) : RestoreRead
    data object NeedsCode : RestoreRead
    data object WrongCode : RestoreRead
    data class Failed(val message: String) : RestoreRead
}

/** What the reference measurement on real phones needs (quality plan P1-10). Defaults where a platform has none. */
interface DeviceMeasurements {
    /** Phone model, OS version and app version for the measurement CSV. */
    fun deviceInfo(): DeviceInfo? = null

    /** Memory of the app process and space its files take, right now. */
    fun resourceUsage(): ResourceUsage? = null

    /** Local date and time (ISO 8601 with offset) of an epoch-milliseconds value, for the CSV. */
    fun formatLocalTime(epochMs: Long): String = epochMs.toString()

    /** Opens the system share sheet with [text]; false where sharing is not available. */
    fun shareText(subject: String, text: String): Boolean = false
}
