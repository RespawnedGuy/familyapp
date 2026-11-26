package io.github.respawnedguy.familyapp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanOptions
import io.github.respawnedguy.familyapp.sync.PairingPlatform
import io.github.respawnedguy.familyapp.sync.PeerDiscovery
import io.github.respawnedguy.familyapp.sync.QrMatrix
import io.github.respawnedguy.familyapp.sync.SyncFolderFiles
import java.net.Inet4Address
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** QR codes with ZXing (offline, no Google Play services), folder choice through the system picker. */
class AndroidPairing(private val platform: AndroidPlatform) : PairingPlatform {
    private var scanCallback: ((String?) -> Unit)? = null

    override fun qrCode(text: String): QrMatrix? = runCatching {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bits.width
        QrMatrix(size, BooleanArray(size * size) { bits.get(it % size, it / size) })
    }.getOrNull()

    override fun scanQr(onResult: (String?) -> Unit) {
        val activity = platform.activity ?: return onResult(null)
        scanCallback = onResult
        activity.scanQr.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("QR-Code auf dem anderen Handy scannen")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    fun onScanned(contents: String?) {
        scanCallback?.invoke(contents)
        scanCallback = null
    }

    override fun pickFolder(onResult: (String?) -> Unit) = platform.pickBackupFolder(onResult)

    override fun describeFolder(reference: String): String = platform.describeFolder(reference)
}

/** The shared sync folder through the Storage Access Framework (Syncthing, Synology Drive, Google Drive …). */
class SafSyncFolder(resolver: ContentResolver, tree: Uri) : SyncFolderFiles {
    private val files = SafBackupFiles(resolver, tree)
    override fun list(sub: String): List<String> = files.list(sub)
    override fun read(sub: String, name: String): ByteArray? = files.read(sub, name)
    override fun write(sub: String, name: String, data: ByteArray) = files.write(sub, name, data)
}

/**
 * Finds other FamilyApp devices in the same WLAN with DNS-SD (`_familyapp._tcp`). The entry says
 * nothing about the family: who belongs to whom shows only in the encrypted handshake (ADR 0003).
 * Tailscale does not forward multicast, there the remembered addresses are used.
 */
class NsdDiscovery(context: Context) : PeerDiscovery {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val multicast = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createMulticastLock("familyapp-discovery")?.apply { setReferenceCounted(false) }
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    override fun start(port: Int, onFound: (String) -> Unit) {
        if (nsd == null || registration != null) return
        runCatching { multicast?.acquire() }
        val info = NsdServiceInfo().apply {
            serviceName = "FamilyApp"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }.also { runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) } }
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info, onFound)
        }.also { runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) } }
    }

    /** Before Android 14 only one resolve may run at a time, so they are queued. */
    @Synchronized
    private fun enqueue(info: NsdServiceInfo, onFound: (String) -> Unit) {
        pending.add(info)
        if (!resolving) resolveNext(onFound)
    }

    @Synchronized
    @Suppress("DEPRECATION") // resolveService still works on Android 14+, the replacement needs API 34
    private fun resolveNext(onFound: (String) -> Unit) {
        val next = pending.poll()
        resolving = next != null
        if (next == null || nsd == null) return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext(onFound)
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host
                if (host is Inet4Address) host.hostAddress?.let { onFound("$it:${info.port}") }
                resolveNext(onFound)
            }
        })
    }

    @Synchronized
    override fun stop() {
        registration?.let { runCatching { nsd?.unregisterService(it) } }
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        pending.clear()
        resolving = false
        runCatching { multicast?.release() }
    }

    private companion object {
        const val SERVICE_TYPE = "_familyapp._tcp"
    }
}

/**
 * Sync every 15 minutes in the background (the shortest WorkManager allows): reads and writes the
 * shared folder and reaches an always-on house device. Two phones in the background cannot reach
 * each other directly; that is what the folder and the house device are for (ADR 0003).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as FamilyApplication).sync.syncAll()
        return Result.success()
    }

    companion object {
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("familyapp-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
