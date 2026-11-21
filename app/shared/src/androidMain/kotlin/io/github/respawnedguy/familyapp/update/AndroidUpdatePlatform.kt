package io.github.respawnedguy.familyapp.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Android data layer of the updater: plain HTTPS download, SHA-256 and signature check,
 * installation through the system PackageInstaller (the user always confirms).
 */
class AndroidUpdatePlatform(
    context: Context,
    override val installedVersionCode: Int,
    private val versionName: String,
    override val manifestUrl: String,
    override val allowedApkUrlPrefix: String,
    /** Activity that receives the installer status (needs launchMode singleTop and [handleInstallStatus]). */
    private val statusActivity: Class<out Activity>,
) : UpdatePlatform {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val updateDir = File(app.cacheDir, "updates")

    override val deviceSdk: Int get() = Build.VERSION.SDK_INT

    override var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO, value).apply() }

    override var lastCheckMs: Long?
        get() = if (prefs.contains(KEY_LAST)) prefs.getLong(KEY_LAST, 0) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_LAST) else putLong(KEY_LAST, value) }.apply()
        }

    override fun nowMs(): Long = System.currentTimeMillis()

    override suspend fun fetchManifest(): String? = withContext(Dispatchers.IO) {
        val conn = open(manifestUrl)
        try {
            val code = conn.responseCode
            // GitHub answers 404 while the releases repo has no release yet: nothing to update to.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (code != HttpURLConnection.HTTP_OK) throw UpdateException("Update-Server antwortet mit $code")
            val bytes = conn.inputStream.use { input ->
                val buffer = input.readNBytesCompat(MAX_MANIFEST_BYTES + 1)
                if (buffer.size > MAX_MANIFEST_BYTES) throw UpdateException("update.json ist zu groß")
                buffer
            }
            bytes.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw UpdateException("Keine Verbindung zum Update-Server")
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun download(manifest: UpdateManifest, onProgress: (Float) -> Unit): VerifiedDownload =
        withContext(Dispatchers.IO) {
            updateDir.mkdirs()
            updateDir.listFiles()?.forEach { it.delete() }
            val target = File(updateDir, "familyapp-${manifest.versionCode}.apk")
            val conn = open(manifest.apkUrl)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateException("Download fehlgeschlagen (${conn.responseCode})")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > manifest.sizeBytes) throw UpdateException("Download größer als angekündigt")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            onProgress(total.toFloat() / manifest.sizeBytes)
                        }
                    }
                }
                if (total != manifest.sizeBytes) throw UpdateException("Download unvollständig")
                val actual = digest.digest().toHex()
                if (!UpdatePolicy.checksumMatches(manifest.sha256, actual)) {
                    throw UpdateException("Prüfsumme stimmt nicht, Update verworfen")
                }
                VerifiedDownload(target.absolutePath, manifest)
            } catch (e: UpdateException) {
                target.delete(); throw e
            } catch (e: IOException) {
                target.delete(); throw UpdateException("Download abgebrochen")
            } finally {
                conn.disconnect()
            }
        }

    override fun verifyPackage(download: VerifiedDownload): String? {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(download.path, signatureFlags())
            ?: return reject(download, "Update-Datei ist keine gültige App")
        if (archive.packageName != app.packageName) return reject(download, "Update gehört zu einer anderen App")
        if (archive.versionCodeCompat() != download.manifest.versionCode.toLong()) {
            return reject(download, "Versionsnummer passt nicht zur Update-Info")
        }
        val installed = pm.getPackageInfo(app.packageName, signatureFlags())
        val expected = certDigests(installed)
        val actual = certDigests(archive)
        if (expected.isEmpty() || actual != expected) {
            return reject(download, "Signatur passt nicht, Update verworfen")
        }
        return null
    }

    override fun canInstallPackages(): Boolean = app.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    override suspend fun install(download: VerifiedDownload): InstallOutcome = withContext(Dispatchers.IO) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
        }
        try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                val file = File(download.path)
                file.inputStream().use { input ->
                    session.openWrite("familyapp.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, statusActivity).setAction(ACTION_INSTALL_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getActivity(app, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            InstallOutcome.Started
        } catch (e: IOException) {
            InstallOutcome.Failed("Installation konnte nicht vorbereitet werden")
        } catch (e: SecurityException) {
            InstallOutcome.Failed("Installation nicht erlaubt")
        }
    }

    /**
     * Call from the status activity's onCreate/onNewIntent. Returns true if the intent was an
     * installer status. Opens the system confirmation dialog when the installer asks for it.
     */
    fun handleInstallStatus(activity: Activity, intent: Intent?, onResult: (Boolean, String?) -> Unit): Boolean {
        if (intent?.action != ACTION_INSTALL_STATUS) return false
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) activity.startActivity(confirm) else onResult(false, "Bestätigung nicht möglich")
            }
            PackageInstaller.STATUS_SUCCESS -> onResult(true, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> onResult(false, "Installation abgebrochen")
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> onResult(false, "Update passt nicht zur installierten App")
            PackageInstaller.STATUS_FAILURE_STORAGE -> onResult(false, "Zu wenig Speicherplatz")
            else -> onResult(false, "Installation fehlgeschlagen ($status)")
        }
        return true
    }

    private fun reject(download: VerifiedDownload, reason: String): String {
        File(download.path).delete()
        return reason
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "FamilyApp/$versionName (Android)")
        }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.respawnedguy.familyapp.INSTALL_STATUS"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST = "last_check_ms"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
