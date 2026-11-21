package io.github.respawnedguy.familyapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.respawnedguy.familyapp.presentation.PlatformServices
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
    private var restoreCallback: ((String?) -> Unit)? = null

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

    override fun writeBackup(folder: String, text: String): String? = runCatching {
        val tree = Uri.parse(folder)
        val resolver = app.contentResolver
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        var target: Uri? = null
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == BACKUP_NAME) {
                    target = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    break
                }
            }
        }
        val uri = target ?: DocumentsContract.createDocument(resolver, dir, "application/json", BACKUP_NAME)
            ?: error("Datei ließ sich nicht anlegen")
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Datei ließ sich nicht schreiben")
        null
    }.getOrElse { e ->
        when (e) {
            is SecurityException -> "Kein Zugriff mehr auf den Ordner. Bitte neu wählen."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    override fun pickRestoreFile(onResult: (String?) -> Unit) {
        val a = activity ?: return onResult(null)
        restoreCallback = onResult
        a.openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    fun onRestorePicked(uri: Uri?) {
        val callback = restoreCallback.also { restoreCallback = null } ?: return
        val text = uri?.let { runCatching { app.contentResolver.openInputStream(it)?.use { s -> s.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
        callback(text)
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

    /** Copies an image shared from another app into private storage, returns its path. */
    fun importSharedImage(uri: Uri): String? = runCatching {
        val file = File(photoDir, "${UUID.randomUUID()}.jpg")
        app.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        file.absolutePath
    }.getOrNull()

    companion object {
        const val BACKUP_NAME = "familyapp-sicherung.json"
    }
}
