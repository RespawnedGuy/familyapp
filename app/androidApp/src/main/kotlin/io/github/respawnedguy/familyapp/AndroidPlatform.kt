package io.github.respawnedguy.familyapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.respawnedguy.familyapp.data.BackupCrypto
import io.github.respawnedguy.familyapp.data.BackupFolder
import io.github.respawnedguy.familyapp.data.PhotoCleanup
import io.github.respawnedguy.familyapp.data.StoredPhoto
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.PlatformServices
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import io.github.respawnedguy.familyapp.presentation.SpeechInput
import java.io.File
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

    override fun notificationsBlocked(): Boolean =
        app.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == false

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
     * Copies an image shared from another app into private storage, returns its path. Only
     * content:// from other apps and at most [MAX_SHARED_BYTES]: a file:// link could point into
     * this app's own private files (quality plan P1-8).
     */
    fun importSharedImage(uri: Uri): String? = runCatching {
        if (uri.scheme != "content" || uri.authority == "${app.packageName}.files") return null
        val file = File(photoDir, "${UUID.randomUUID()}.jpg")
        app.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SHARED_BYTES) {
                        out.close()
                        file.delete()
                        return null
                    }
                    out.write(buffer, 0, n)
                }
            }
        } ?: return null
        file.absolutePath
    }.getOrNull()

    companion object {
        const val MAX_SHARED_BYTES = 20L * 1024 * 1024
    }
}
