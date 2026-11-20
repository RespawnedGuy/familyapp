package familyapp.p2p.net

import familyapp.p2p.core.FamilyPeer
import familyapp.p2p.pairing.Confirmation
import familyapp.p2p.pairing.Inviter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Listens for other family devices (LAN or tailnet address, same code). The first frame says
 * what the caller wants: "S" sync, "P" pairing. Binding to the Tailscale interface only
 * (100.x.y.z) keeps the port invisible on the LAN; binding to 0.0.0.0 serves both.
 */
class PeerServer(
    private val peer: FamilyPeer,
    private val inviter: Inviter? = null,
    private val confirmation: Confirmation? = null,
    private val onSession: suspend (Result<String>) -> Unit = {},
) {
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: -1

    fun start(scope: CoroutineScope, bindHost: String = "127.0.0.1", port: Int = 0): Job {
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindHost, port)) }
        server = socket
        return scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(SocketFrameChannel(client)) }
            }
        }
    }

    fun stop() = runCatching { server?.close() }.let { }

    private suspend fun handle(raw: FrameChannel) {
        val result = runCatching {
            when (raw.receive().decodeToString()) {
                MODE_SYNC -> {
                    val channel = SecureChannel.establish(raw, Role.RESPONDER, peer.identity, peer.membership)
                    val stats = SyncSession.run(channel, peer)
                    "sync with ${stats.peerDeviceId}: sent ${stats.sent}, received ${stats.received}"
                }
                MODE_PAIR -> {
                    val inv = inviter ?: error("Pairing not enabled")
                    val endorsement = peer.lock.withLock { inv.accept(raw, confirmation ?: Confirmation { false }) }
                    if (endorsement == null) "pairing declined" else "paired ${endorsement.name} (${endorsement.deviceId})"
                }
                else -> error("Unknown mode")
            }
        }
        raw.close()
        onSession(result)
    }

    companion object {
        const val MODE_SYNC = "S"
        const val MODE_PAIR = "P"
    }
}

object PeerClient {
    suspend fun connect(address: String, timeoutMs: Int = 3000): FrameChannel = withContext(Dispatchers.IO) {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toInt()
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        SocketFrameChannel(socket)
    }

    suspend fun sync(address: String, peer: FamilyPeer): SyncStats {
        val raw = connect(address)
        try {
            raw.send(PeerServer.MODE_SYNC.toByteArray())
            val channel = SecureChannel.establish(raw, Role.INITIATOR, peer.identity, peer.membership)
            return SyncSession.run(channel, peer)
        } finally {
            raw.close()
        }
    }
}
