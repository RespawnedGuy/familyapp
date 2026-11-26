package io.github.respawnedguy.familyapp.presentation

/** Callbacks of on-device speech recognition (Android SpeechRecognizer, later iOS SFSpeechRecognizer). */
interface SpeechListener {
    /** The microphone is open and listening: the moment the 2-second target (D8) measures. */
    fun onReady()
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
interface PlatformServices {
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
