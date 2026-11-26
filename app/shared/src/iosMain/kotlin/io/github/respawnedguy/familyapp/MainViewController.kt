package io.github.respawnedguy.familyapp

import androidx.compose.ui.window.ComposeUIViewController
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.TextStore
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import io.github.respawnedguy.familyapp.presentation.PlatformServices
import io.github.respawnedguy.familyapp.presentation.SpeechInput
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.MainScope
import kotlinx.datetime.LocalDate
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIViewController
import platform.posix.arc4random_uniform

/**
 * Entry point for the future iOS app (Xcode project in iosApp/, built on macOS only, not in CI).
 * Speech, camera and backup folder come with the iOS step; until then capture works by typing.
 */
fun MainViewController(): UIViewController {
    val scope = MainScope()
    val repo = FamilyRepository(
        store = IosTextStore(documentsPath() + "/familyapp.json"),
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        today = ::iosToday,
        newId = { NSUUID().UUIDString },
    )
    val model = AppModel(repo, IosPlatform, scope, ::iosToday, Dispatchers.IO)
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    return ComposeUIViewController { App(versionName = version ?: "", model = model, photos = null, reducedMotion = false) }
}

private fun documentsPath(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

private fun iosToday(): LocalDate {
    val c = NSCalendar.currentCalendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, NSDate())
    return LocalDate(c.year.toInt(), c.month.toInt(), c.day.toInt())
}

@OptIn(ExperimentalForeignApi::class)
private class IosTextStore(private val path: String) : TextStore {
    override fun read(): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    // atomically = true: Foundation writes a temp file and renames it. False means it failed.
    override fun write(text: String) {
        check(NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)) { "Schreiben fehlgeschlagen" }
    }

    override fun preserveBroken(text: String) {
        NSString.create(string = text).writeToFile("$path.unlesbar", true, NSUTF8StringEncoding, null)
    }

    private val snapshotPath = "$path.vor-wiederherstellung"

    override fun readSnapshot(): String? = NSString.stringWithContentsOfFile(snapshotPath, NSUTF8StringEncoding, null)

    override fun writeSnapshot(text: String?) {
        if (text == null) {
            NSFileManager.defaultManager.removeItemAtPath(snapshotPath, null)
        } else {
            check(NSString.create(string = text).writeToFile(snapshotPath, true, NSUTF8StringEncoding, null)) { "Schreiben fehlgeschlagen" }
        }
    }
}

private object IosPlatform : PlatformServices {
    override val speech: SpeechInput? = null
    override fun elapsedMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
    override fun reducedMotion(): Boolean = false
    override fun takePhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun writeBackup(folder: String, backup: BackupContent): String? = "Auf iOS noch nicht verfügbar"
    override fun readBackup(folder: String, code: String?): RestoreRead = RestoreRead.Failed("Auf iOS noch nicht verfügbar")

    // Same alphabet and length as BackupCrypto on Android; secure random from the system.
    override fun newRecoveryCode(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        return (1..20).map { alphabet[(arc4random_uniform(alphabet.length.toUInt())).toInt()] }
            .chunked(4).joinToString("-") { it.joinToString("") }
    }
    // Local notifications on iOS come with the iOS port; nothing to ask for yet.
    override fun requestNotificationPermission() = Unit
    override fun describeFolder(folder: String): String = folder
}
