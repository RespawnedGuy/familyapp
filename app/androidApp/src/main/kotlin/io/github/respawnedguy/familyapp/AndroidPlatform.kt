package io.github.respawnedguy.familyapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowManager
import androidx.core.content.FileProvider
import io.github.respawnedguy.familyapp.data.BackupCrypto
import io.github.respawnedguy.familyapp.data.BackupFolder
import io.github.respawnedguy.familyapp.data.AiProvider
import io.github.respawnedguy.familyapp.data.HttpClaudeRecipeAi
import io.github.respawnedguy.familyapp.data.HttpGeminiRecipeAi
import io.github.respawnedguy.familyapp.data.AndroidTextStore
import io.github.respawnedguy.familyapp.data.HttpInstagramPages
import io.github.respawnedguy.familyapp.data.HttpMarkets
import io.github.respawnedguy.familyapp.data.HttpInstagramVideos
import io.github.respawnedguy.familyapp.data.RecipeAi
import io.github.respawnedguy.familyapp.data.PhotoCleanup
import io.github.respawnedguy.familyapp.data.ShareRejection
import io.github.respawnedguy.familyapp.data.SharedImage
import io.github.respawnedguy.familyapp.data.SharedInput
import io.github.respawnedguy.familyapp.data.StoredPhoto
import io.github.respawnedguy.familyapp.domain.DeviceInfo
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.ResourceUsage
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.PlatformServices
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import io.github.respawnedguy.familyapp.presentation.SpeechInput
import java.io.ByteArrayOutputStream
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

    /** Caption of a shared Instagram post, fetched without login or yt-dlp (ADR 0007). */
    override val instagramPages = HttpInstagramPages()

    /** Invisible WebView without login, if Instagram refuses the plain fetch (ADR 0007, seen 27.11.2026). */
    override val instagramBrowser = WebViewInstagramPages(app)

    /** Video of a shared reel for Gemini, in the cache only while it is read (ADR 0008). */
    override val instagramVideos = HttpInstagramVideos(File(app.cacheDir, "instagram-video"))

    /** Reel cover or video frame as the recipe's picture, shrunk, in the private photo folder (TE-13). */
    override val recipePhotos = AndroidRecipePhotos(File(app.filesDir, "photos"))

    /** Weekly offers: plain, honest requests to the markets (docs/angebote-und-vorschlaege.md). */
    override val marketHttp = HttpMarkets()

    /** Offers read on this phone; a file of its own, not part of the family data or the backup. */
    override val offerStore = AndroidTextStore(File(app.filesDir, "angebote.json"))

    /** WLAN or another connection the system calls unmetered: only then offers are read by themselves. */
    override fun unmeteredNetwork(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    /** Optional, only with the API key from the settings; the key's prefix names the provider (ADR 0007, 0008). */
    override fun recipeAi(apiKey: String): RecipeAi? = when (AiProvider.ofKey(apiKey)) {
        AiProvider.GEMINI -> HttpGeminiRecipeAi(apiKey)
        AiProvider.CLAUDE -> HttpClaudeRecipeAi(apiKey)
        null -> null
    }

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

    // ---- Contacts without address-book permission (docs/kontakte-und-portfolio.md, E1) ---------

    private var contactCallback: ((ContactDraft?) -> Unit)? = null

    override val canPickContacts: Boolean = true

    /**
     * Picks one phone number with the system picker. The returned data URI comes with a temporary
     * read grant for exactly that row, so no READ_CONTACTS is needed (and Android 17 replaces the
     * picker with its own for apps targeting API 37). Mail and address come with "Kontakt teilen".
     */
    override fun pickContact(onResult: (ContactDraft?) -> Unit) {
        val a = activity ?: return onResult(null)
        contactCallback = onResult
        try {
            val pick = Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
            a.pickContact.launch(pick)
        } catch (e: ActivityNotFoundException) {
            onContactPicked(null)
        }
    }

    fun onContactPicked(uri: Uri?) {
        val callback = contactCallback.also { contactCallback = null } ?: return
        if (uri == null) return callback(null)
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val draft = runCatching {
            app.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) ContactDraft(c.getString(0).orEmpty(), listOfNotNull(c.getString(1))) else null
            }
        }.getOrNull()
        callback(draft)
    }

    /** A shared .vcf from another app's provider, at most 1 MB (KT-1); null when refused or unreadable. */
    fun readSharedVCard(uri: Uri): String? {
        if (!SharedInput.vcardAllowed(uri.scheme, isOwnProvider(uri.authority.orEmpty()))) return null
        return runCatching {
            val out = ByteArrayOutputStream()
            val copied = app.contentResolver.openInputStream(uri)?.use { i ->
                SharedInput.copyLimited({ i.read(it) }, { b, n -> out.write(b, 0, n) }, SharedInput.MAX_VCARD_BYTES)
            }
            if (copied == null) null else out.toByteArray().decodeToString()
        }.getOrNull()
    }

    override fun dial(number: String): Boolean = launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    override fun sendSms(number: String): Boolean =
        launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    override fun sendMail(address: String): Boolean =
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(address)))

    override fun showAddress(address: String): Boolean =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))

    override fun openWebLink(url: String): Boolean {
        val web = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
        return web && launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** The contacts app opens with the fields filled in; the person saves there (no WRITE_CONTACTS, KT-5). */
    override fun addToPhoneBook(draft: ContactDraft): Boolean {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, draft.name)
        val phoneKeys = listOf(
            ContactsContract.Intents.Insert.PHONE,
            ContactsContract.Intents.Insert.SECONDARY_PHONE,
            ContactsContract.Intents.Insert.TERTIARY_PHONE,
        )
        draft.phones.zip(phoneKeys).forEach { (phone, key) -> intent.putExtra(key, phone) }
        val mailKeys = listOf(
            ContactsContract.Intents.Insert.EMAIL,
            ContactsContract.Intents.Insert.SECONDARY_EMAIL,
            ContactsContract.Intents.Insert.TERTIARY_EMAIL,
        )
        draft.emails.zip(mailKeys).forEach { (mail, key) -> intent.putExtra(key, mail) }
        draft.address?.let { intent.putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        draft.organization?.let { intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        return launch(intent)
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        val a = activity
        if (a != null) a.startActivity(intent) else app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

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

    /** Cooking mode: the flag belongs to the window, so it ends with the activity at the latest. */
    override fun keepScreenOn(on: Boolean) {
        val window = activity?.window ?: return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
