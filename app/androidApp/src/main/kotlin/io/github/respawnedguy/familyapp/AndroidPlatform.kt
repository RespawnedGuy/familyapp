package io.github.respawnedguy.familyapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.respawnedguy.familyapp.data.BackupCrypto
import io.github.respawnedguy.familyapp.data.BackupFolder
import io.github.respawnedguy.familyapp.data.PhotoCleanup
import io.github.respawnedguy.familyapp.data.ShareRejection
import io.github.respawnedguy.familyapp.data.SharedImage
import io.github.respawnedguy.familyapp.data.SharedInput
import io.github.respawnedguy.familyapp.data.StoredPhoto
import io.github.respawnedguy.familyapp.domain.DeviceInfo
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.ResourceUsage
import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.PlatformServices
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import io.github.respawnedguy.familyapp.presentation.SpeechInput
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Android side of [PlatformServices]. Lives in the Application; the current [MainActivity]
 * attaches itself so camera, folder picker and permission requests have an activity to run in.
 */
class AndroidPlatform(private val app: FamilyApplication) : PlatformServices {

    var activity: MainActivity? = null
    var allowOnlineSpeech: () -> Boolean = { false }

    override val speech: SpeechInput = AndroidSpeech(app, { activity }, { allowOnlineSpeech() })

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun reducedMotion(): Boolean =
        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    // ---- Camera ------------------------------------------------------------------------------

    private var pendingPhoto: File? = null
    private var photoCallback: ((String?) -> Unit)? = null

    val photoDir: File get() = File(app.filesDir, "photos").apply { mkdirs() }

    override fun takePhoto(onResult: (String?) -> Unit) {
        val a = activity ?: return onResult(null)
        val file = File(photoDir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        pendingPhoto = file
        photoCallback = onResult
        try {
            a.takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            onPhotoResult(false)
        }
    }

    fun onPhotoResult(ok: Boolean) {
        val file = pendingPhoto
        val callback = photoCallback
        pendingPhoto = null
        photoCallback = null
        if (ok && file != null && file.length() > 0) {
            callback?.invoke(file.absolutePath)
        } else {
            file?.delete()
            callback?.invoke(null)
        }
    }

    // ---- Backup folder (Storage Access Framework) ---------------------------------------------

    private var folderCallback: ((String?) -> Unit)? = null

    override fun pickBackupFolder(onResult: (String?) -> Unit) {
        val a = activity ?: return onResult(null)
        folderCallback = onResult
        a.openTree.launch(null)
    }

    fun onFolderPicked(uri: Uri?) {
        val callback = folderCallback.also { folderCallback = null } ?: return
        if (uri == null) return callback(null)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
        callback(uri.toString())
    }

    override fun writeBackup(folder: String, backup: BackupContent): String? = runCatching {
        BackupFolder(SafBackupFiles(app.contentResolver, Uri.parse(folder))).write(backup)
        null
    }.getOrElse { e ->
        when (e) {
            is SecurityException -> "Kein Zugriff mehr auf den Ordner. Bitte neu wählen."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    override fun readBackup(folder: String, code: String?): RestoreRead = runCatching {
        BackupFolder(SafBackupFiles(app.contentResolver, Uri.parse(folder))).read(code, photoDir)
    }.getOrElse { e ->
        RestoreRead.Failed(if (e is SecurityException) "Kein Zugriff auf den Ordner." else e.message ?: e.javaClass.simpleName)
    }

    override fun newRecoveryCode(): String = BackupCrypto.newCode()

    override fun requestNotificationPermission() {
        activity?.requestNotifications()
    }

    /** Deletes photo files no item uses any more (P0-4); runs once at app start. */
    fun cleanOrphanPhotos(data: FamilyData) {
        runCatching {
            val files = photoDir.listFiles().orEmpty().filter { it.isFile }.map { StoredPhoto(it.absolutePath, it.lastModified()) }
            PhotoCleanup.orphans(files, data, System.currentTimeMillis()).forEach { File(it).delete() }
        }
    }

    override fun describeFolder(folder: String): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(folder))
        val path = id.substringAfter(':', id)
        val root = id.substringBefore(':', "")
        when {
            root == "primary" -> "Interner Speicher/${path}"
            path.isNotBlank() -> path
            else -> id
        }
    }.getOrDefault(folder)

    /**
     * Copies an image shared from another app into private storage (quality plan P1-8, threat model
     * TE-1 to TE-4 in docs/qualitaet/bedrohungsmodell.md). Only content:// from a provider of another
     * app: a file:// link or our own FileProvider could point into this app's private files. The
     * copy stops after 20 MB whatever size the sender claims, and the image decoder must read a
     * plausible picture size from the copied bytes, so a renamed APK or HTML file is not kept.
     */
    fun importSharedImage(uri: Uri, declaredType: String?): SharedImage {
        val type = runCatching { app.contentResolver.getType(uri) }.getOrNull() ?: declaredType
        val file = File(photoDir, "${UUID.randomUUID()}.jpg")
        val rejection = SharedInput.precheck(uri.scheme, isOwnProvider(uri.authority.orEmpty()), type)
            ?: runCatching { copyAndCheck(uri, file) }.getOrElse { ShareRejection.UNREADABLE }
        if (rejection == null) return SharedImage.Accepted(file.absolutePath)
        file.delete()
        return SharedImage.Rejected(rejection)
    }

    /** Fails closed: if the package manager cannot answer, the provider counts as our own. */
    private fun isOwnProvider(authority: String): Boolean =
        authority == app.packageName || authority.startsWith("${app.packageName}.") ||
            runCatching { app.packageManager.resolveContentProvider(authority, 0)?.packageName == app.packageName }
                .getOrDefault(true)

    private fun copyAndCheck(uri: Uri, file: File): ShareRejection? {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Kein Inhalt")
        val copied = input.use { i ->
            file.outputStream().use { o -> SharedInput.copyLimited({ i.read(it) }, { b, n -> o.write(b, 0, n) }) }
        }
        if (copied == null) return ShareRejection.TOO_LARGE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return SharedInput.postcheck(bounds.outWidth, bounds.outHeight)
    }

    // ---- Measurements on the real phone (quality plan P1-10) -------------------------------------

    override fun deviceInfo() = DeviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion = BuildConfig.VERSION_NAME,
    )

    /** PSS of this process (what Android itself shows as memory use) and the size of all app files. */
    override fun resourceUsage() = ResourceUsage(
        memoryKb = runCatching { Debug.getPss() }.getOrNull()?.takeIf { it > 0 },
        storageBytes = runCatching { sizeOf(app.filesDir) + sizeOf(app.cacheDir) }.getOrNull(),
    )

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    override fun formatLocalTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS)
            .toOffsetDateTime().toString()

    override fun shareText(subject: String, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, subject)
        val a = activity
        return runCatching {
            if (a != null) {
                a.startActivity(chooser)
            } else {
                app.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.isSuccess
    }
}
