package io.github.respawnedguy.familyapp.sync

/** Direct connections between family devices (TCP on the LAN or the tailnet, ADR 0003). */
interface SyncTransport {
    /** Starts listening; tries [port] first, any free port if it is taken. Returns the port in use. */
    fun startServer(port: Int, handler: suspend (FrameChannel) -> Unit): Int
    fun stopServer()

    /** Connects to "host:port". [readTimeoutMs] bounds every wait for the next frame. */
    suspend fun connect(address: String, connectTimeoutMs: Int, readTimeoutMs: Int): FrameChannel

    /** IPv4 addresses of this device that others may reach (WLAN, Tailscale), without port. */
    fun localAddresses(): List<String>
}

/** Finds other FamilyApp devices in the same WLAN (Android NSD / Bonjour). Optional. */
interface PeerDiscovery {
    fun start(port: Int, onFound: (String) -> Unit)
    fun stop()
}

/** Photo files in this device's private photo folder, addressed by file name. */
interface PhotoFiles {
    fun pathFor(name: String): String
    fun exists(name: String): Boolean
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)

    companion object {
        /** Photo names are random ids with an extension; anything else (paths, "..") is refused. */
        private val SAFE = Regex("[A-Za-z0-9_-]{1,80}(\\.[A-Za-z0-9]{1,8})?")

        fun isSafeName(name: String): Boolean = SAFE.matches(name)
    }
}

/** A QR code as a square of dark (true) and light modules, quiet zone not included. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** QR code drawing and scanning, which the platform provides (ZXing on Android). */
interface PairingPlatform {
    fun qrCode(text: String): QrMatrix?

    /** Opens the camera scanner; returns the scanned text, or null if cancelled. */
    fun scanQr(onResult: (String?) -> Unit)
}
