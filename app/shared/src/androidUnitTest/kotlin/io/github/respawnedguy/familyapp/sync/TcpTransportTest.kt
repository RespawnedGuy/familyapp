package io.github.respawnedguy.familyapp.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a stranger in the same WLAN can do before knowing any secret (threat model NE-3, NE-4,
 * docs/qualitaet/bedrohungsmodell.md): at most small frames, a short wait, a limited number of
 * connections. Real sockets on localhost.
 */
class TcpTransportTest {
    private val transports = mutableListOf<TcpTransport>()
    private val sockets = mutableListOf<Socket>()

    @AfterTest
    fun cleanUp() {
        sockets.forEach { runCatching { it.close() } }
        transports.forEach { it.stopServer() }
    }

    private fun server(transport: TcpTransport, handler: suspend (FrameChannel) -> Unit): Int {
        transports += transport
        return transport.startServer(0, handler)
    }

    private fun rawClient(port: Int): Socket = Socket("127.0.0.1", port).also {
        it.soTimeout = 5_000
        sockets += it
    }

    @Test
    fun aHugeFirstFrameIsRefusedBeforeAnyMemoryIsReserved(): Unit = runBlocking {
        val outcome = CompletableDeferred<Throwable?>()
        val port = server(TcpTransport()) { ch -> outcome.complete(runCatching { ch.receive() }.exceptionOrNull()) }
        DataOutputStream(rawClient(port).getOutputStream()).apply { writeInt(TcpTransport.MAX_FRAME_BYTES); flush() }
        val error = withTimeout(5_000) { outcome.await() }
        assertIs<IOException>(error)
        assertTrue(error.message!!.contains("Nachrichtengröße"))
    }

    @Test
    fun afterTheHandshakeFramesMayBePhotoSized(): Unit = runBlocking {
        val sizes = Channel<Int>(Channel.UNLIMITED)
        val port = server(TcpTransport()) { ch -> repeat(3) { sizes.send(ch.receive().size) } }
        val client = TcpTransport().connect("127.0.0.1:$port", 2_000, 5_000)
        client.send(ByteArray(100))
        client.send(ByteArray(TcpTransport.PRE_AUTH_FRAME_BYTES))
        client.send(ByteArray(1_000_000))
        withTimeout(5_000) { assertEquals(listOf(100, TcpTransport.PRE_AUTH_FRAME_BYTES, 1_000_000), List(3) { sizes.receive() }) }
        client.close()
    }

    @Test
    fun aSilentStrangerIsDroppedQuickly(): Unit = runBlocking {
        val outcome = CompletableDeferred<Throwable?>()
        val port = server(TcpTransport(preAuthTimeoutMs = 300)) { ch -> outcome.complete(runCatching { ch.receive() }.exceptionOrNull()) }
        rawClient(port)
        assertIs<SocketTimeoutException>(withTimeout(5_000) { outcome.await() })
    }

    @Test
    fun connectionsBeyondTheLimitAreClosedAndFreedSlotsAreUsedAgain(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val port = server(TcpTransport(maxConnections = 2)) { started.send(Unit); gate.await() }
        rawClient(port)
        rawClient(port)
        withTimeout(5_000) { repeat(2) { started.receive() } }
        val third = rawClient(port)
        assertEquals(-1, third.getInputStream().read(), "the third connection is closed at once")
        gate.complete(Unit)
        // Slots are released when the handlers end; retry briefly because closing is asynchronous.
        withTimeout(5_000) {
            while (started.tryReceive().isFailure) {
                rawClient(port)
                kotlinx.coroutines.delay(100)
            }
        }
    }
}
