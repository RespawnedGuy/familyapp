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

/**
 * Direct TCP connections (ADR 0003): LAN address at home, Tailscale address (100.x) on the road.
 * Plain java.net, so the app and the house device program share it. Frames are length-prefixed
 * (4 bytes, big endian); the content is already encrypted by [SecureChannel].
 */
class TcpTransport(private val maxFrameBytes: Int = MAX_FRAME_BYTES) : SyncTransport {
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null

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
                client.soTimeout = SERVER_READ_TIMEOUT_MS
                launch { handler(SocketChannel(client, maxFrameBytes)) }
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
        return SocketChannel(socket, maxFrameBytes)
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

    private class SocketChannel(private val socket: Socket, private val maxFrameBytes: Int) : FrameChannel {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())

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
            if (size !in 0..maxFrameBytes) throw IOException("Ungültige Nachrichtengröße $size")
            ByteArray(size).also { input.readFully(it) }
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
