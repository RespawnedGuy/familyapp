package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.InstagramPages
import io.github.respawnedguy.familyapp.data.InstagramVideos
import io.github.respawnedguy.familyapp.data.RecipeAi
import io.github.respawnedguy.familyapp.data.RecipePhotos
import io.github.respawnedguy.familyapp.data.TextStore
import io.github.respawnedguy.familyapp.data.offers.MarketHttp
import io.github.respawnedguy.familyapp.domain.DeviceInfo
import io.github.respawnedguy.familyapp.domain.ResourceUsage
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft

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
interface PlatformServices : DeviceMeasurements, ScreenControl, RecipeSources, ContactActions, OfferSources {
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

    /** True when the system blocks this app's notifications, so reminders cannot arrive (FMEA F4). */
    fun notificationsBlocked(): Boolean = false

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

/** Where recipe text comes from besides the person (ADR 0007). Defaults: nothing, the person pastes. */
interface RecipeSources {
    /** Public Instagram pages for the recipe import. Null: link-only recipes wait for pasted text. */
    val instagramPages: InstagramPages? get() = null

    /** The same pages through a browser engine, tried when [instagramPages] gets no caption. */
    val instagramBrowser: InstagramPages? get() = null

    /** Video files of public Instagram posts for a cloud AI that watches them. Null: caption only. */
    val instagramVideos: InstagramVideos? get() = null

    /** Shrinks and stores recipe pictures (cover, video frame, TE-13). Null: recipes stay without picture. */
    val recipePhotos: RecipePhotos? get() = null

    /** Cloud AI for recipe texts with this API key (provider from the key). Null where the platform has none. */
    fun recipeAi(apiKey: String): RecipeAi? = null
}

/**
 * Contacts without address-book permission (docs/kontakte-und-portfolio.md, E1): the system picker
 * hands over one contact, everything else opens another app that the person controls (dialler,
 * mail, maps, contacts). Each call returns false when no app could take it. Defaults: nothing.
 */
interface ContactActions {
    /** True where [pickContact] opens the system's contact picker. */
    val canPickContacts: Boolean get() = false

    /** Lets the person choose one contact; null when cancelled or unreadable. */
    fun pickContact(onResult: (ContactDraft?) -> Unit) = onResult(null)

    /** Opens the dialler with the number filled in; never calls by itself (no CALL_PHONE, KT-2). */
    fun dial(number: String): Boolean = false

    fun sendSms(number: String): Boolean = false

    fun sendMail(address: String): Boolean = false

    fun showAddress(address: String): Boolean = false

    /** Only http(s) links are passed on (TE-8). */
    fun openWebLink(url: String): Boolean = false

    /** Hands the contact to the phone's contacts app, where the person saves it (no WRITE_CONTACTS, KT-5). */
    fun addToPhoneBook(draft: ContactDraft): Boolean = false
}

/**
 * Weekly offers of the markets (docs/angebote-und-vorschlaege.md). Defaults: nothing, so platforms
 * without it still show season suggestions.
 */
interface OfferSources {
    /** Plain requests to the markets; null where the platform cannot fetch. */
    val marketHttp: MarketHttp? get() = null

    /** Where this phone keeps the offers it read (not the family data, not in the backup). */
    val offerStore: TextStore? get() = null

    /** True on WLAN or another connection without data volume: offers are only read automatically then. */
    fun unmeteredNetwork(): Boolean = false
}

/** Display control for the kitchen library. Default: nothing (platforms without it). */
interface ScreenControl {
    /** Keeps the display on while the cooking mode is open; off again afterwards. */
    fun keepScreenOn(on: Boolean) = Unit
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
