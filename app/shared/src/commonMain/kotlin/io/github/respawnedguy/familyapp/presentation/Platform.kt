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

    /** Lets the person choose a folder for backups; returns an opaque folder reference. */
    fun pickBackupFolder(onResult: (String?) -> Unit)

    /** Writes the backup into the chosen folder. Returns an error text or null. */
    fun writeBackup(folder: String, text: String): String?

    /** Lets the person choose a backup file and returns its text (null if cancelled or unreadable). */
    fun pickRestoreFile(onResult: (String?) -> Unit)

    /** Human-readable name of a backup folder reference. */
    fun describeFolder(folder: String): String
}
