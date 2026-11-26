package io.github.respawnedguy.familyapp.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct TCP connections (ADR 0003): LAN address at home, Tailscale address (100.x) on the road.
 * Plain java.net, so the app and the house device program share it. Frames are length-prefixed
 * (4 bytes, big endian); the content is already encrypted by [SecureChannel].
 *
 * Anyone in the same WLAN can connect before any secret is checked (threat model NE-3, NE-4,
 * docs/qualitaet/bedrohungsmodell.md). So until a connection has passed the handshake it may only
 * send small frames, must answer quickly, and only [maxConnections] connections are served at once.
 */
class TcpTransport(
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val preAuthTimeoutMs: Int = PRE_AUTH_TIMEOUT_MS,
) : SyncTransport {
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val open = AtomicInteger()

    @Synchronized
    override fun startServer(port: Int, handler: suspend (FrameChannel) -> Unit): Int {
        server?.let { return it.localPort }
        val socket = try {
            ServerSocket(port)
        } catch (@Suppress("SwallowedException") e: BindException) {
            ServerSocket(0) // the preferred port is taken: any free one, peers learn it in the hello
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = socket
        scope = s
        s.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (open.incrementAndGet() > maxConnections) {
                    open.decrementAndGet()
                    runCatching { client.close() }
                } else {
                    launch {
                        try {
                            handler(SocketChannel(client, maxFrameBytes, SERVER_READ_TIMEOUT_MS, preAuthTimeoutMs))
                        } finally {
                            runCatching { client.close() }
                            open.decrementAndGet()
                        }
                    }
                }
            }
        }
        return socket.localPort
    }

    @Synchronized
    override fun stopServer() {
        runCatching { server?.close() }
        scope?.cancel()
        server = null
        scope = null
    }

    override suspend fun connect(address: String, connectTimeoutMs: Int, readTimeoutMs: Int): FrameChannel =
        withContext(Dispatchers.IO) { open(address, connectTimeoutMs, readTimeoutMs) }

    private fun open(address: String, connectTimeoutMs: Int, readTimeoutMs: Int): FrameChannel {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: throw IOException("Adresse ohne Port: $address")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return SocketChannel(socket, maxFrameBytes, readTimeoutMs, readTimeoutMs)
    }

    override fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sortedBy { if (isTailscale(it)) 1 else 0 } // LAN first: faster at home
    }.getOrDefault(emptyList())

    /**
     * The first [PRE_AUTH_FRAMES] frames a side receives are the handshake hello and the first
     * encrypted message (sync [Hello] or [PairHello]), all far below [PRE_AUTH_FRAME_BYTES]. Only
     * after that the other side has proven the secret, and photo-sized frames are allowed. Without
     * this a stranger could make the app reserve 32 MB per connection with four bytes.
     */
    private class SocketChannel(
        private val socket: Socket,
        private val maxFrameBytes: Int,
        private val readTimeoutMs: Int,
        preAuthTimeoutMs: Int,
    ) : FrameChannel {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        private var received = 0

        init {
            socket.soTimeout = minOf(preAuthTimeoutMs, readTimeoutMs)
        }

        override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }

        override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
            val size = try {
                input.readInt()
            } catch (e: EOFException) {
                throw IOException("Verbindung beendet", e)
            }
            val limit = if (received < PRE_AUTH_FRAMES) PRE_AUTH_FRAME_BYTES else maxFrameBytes
            if (size !in 0..limit) throw IOException("Ungültige Nachrichtengröße $size")
            ByteArray(size).also {
                input.readFully(it)
                if (++received == PRE_AUTH_FRAMES) socket.soTimeout = readTimeoutMs
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** Big enough for one photo (20 MB, base64 in JSON) plus margin. */
        const val MAX_FRAME_BYTES = 32 * 1024 * 1024

        /** Pairing waits for two people to compare digits. */
        const val SERVER_READ_TIMEOUT_MS = 200_000

        /** Handshake and first message come without anyone tapping; a silent stranger is dropped. */
        const val PRE_AUTH_TIMEOUT_MS = 10_000

        /** Frames before the other side proved the secret (see [SocketChannel]). */
        const val PRE_AUTH_FRAMES = 2
        const val PRE_AUTH_FRAME_BYTES = 64 * 1024

        /** A family has a handful of devices; more parallel connections are someone knocking. */
        const val MAX_CONNECTIONS = 8

        private const val IPV4_PARTS = 4
        private const val TAILSCALE_FIRST = 100
        private const val TAILSCALE_SECOND_MIN = 64
        private const val TAILSCALE_SECOND_MAX = 127

        /** Tailscale hands out addresses from 100.64.0.0/10 (carrier-grade NAT range). */
        fun isTailscale(ip: String): Boolean {
            val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == IPV4_PARTS && parts[0] == TAILSCALE_FIRST &&
                parts[1] in TAILSCALE_SECOND_MIN..TAILSCALE_SECOND_MAX
        }
    }
}
