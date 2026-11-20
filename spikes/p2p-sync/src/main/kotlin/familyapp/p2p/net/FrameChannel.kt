package familyapp.p2p.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/** A bidirectional stream of length-delimited frames. The only thing a transport has to provide. */
interface FrameChannel {
    suspend fun send(frame: ByteArray)
    suspend fun receive(): ByteArray
    fun close()
}

class ConnectionLostException(message: String) : IOException(message)

/** Two connected in-memory endpoints, for deterministic simulation of many encounters. */
object InMemoryPipe {
    fun pair(): Pair<FrameChannel, FrameChannel> {
        val aToB = Channel<ByteArray>(Channel.UNLIMITED)
        val bToA = Channel<ByteArray>(Channel.UNLIMITED)
        return Endpoint(bToA, aToB) to Endpoint(aToB, bToA)
    }

    private class Endpoint(private val incoming: Channel<ByteArray>, private val outgoing: Channel<ByteArray>) : FrameChannel {
        override suspend fun send(frame: ByteArray) {
            if (outgoing.trySend(frame).isFailure) throw ConnectionLostException("Peer closed the connection")
        }

        override suspend fun receive(): ByteArray = try {
            incoming.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw ConnectionLostException("Peer closed the connection")
        }

        override fun close() {
            outgoing.close()
            incoming.close()
        }
    }
}

/** Length-prefixed frames over TCP: used for LAN and for Tailscale (a Tailscale IP is just another address). */
class SocketFrameChannel(private val socket: Socket) : FrameChannel {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val writeLock = Mutex()

    override suspend fun send(frame: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
            } catch (e: IOException) {
                throw ConnectionLostException("Send failed: ${e.message}")
            }
        }
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val size = input.readInt()
            if (size !in 0..MAX_FRAME_BYTES) throw IOException("Frame too large: $size")
            ByteArray(size).also { input.readFully(it) }
        } catch (e: EOFException) {
            throw ConnectionLostException("Peer closed the connection")
        } catch (e: IOException) {
            if (e is ConnectionLostException) throw e
            throw ConnectionLostException("Receive failed: ${e.message}")
        }
    }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
