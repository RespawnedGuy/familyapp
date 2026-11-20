package familyapp.p2p

import familyapp.p2p.core.FamilyPeer
import familyapp.p2p.core.Membership
import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.FamilyCipher
import familyapp.p2p.net.ConnectionLostException
import familyapp.p2p.net.FrameChannel
import familyapp.p2p.net.InMemoryPipe
import familyapp.p2p.net.Role
import familyapp.p2p.net.SecureChannel
import familyapp.p2p.net.SyncSession
import familyapp.p2p.net.SyncStats
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Founder plus endorsed members, as pairing would leave them (pairing itself is tested separately). */
class TestFamily(names: List<String>, clockOf: (Int) -> () -> Long = { { 1_000_000L } }) {
    val key = FamilyCipher.generateKey()
    val identities = names.map { DeviceIdentity.generate() }
    val familyId: String = identities.first().deviceId
    val peers: List<FamilyPeer>

    init {
        val founder = identities.first()
        val membership = Membership.found(founder, names.first())
        identities.drop(1).forEachIndexed { i, id ->
            membership.merge(listOf(Membership.endorse(familyId, founder, id.publicKeyEncoded, names[i + 1])))
        }
        // Every device starts with only the founder plus itself: the rest must arrive via handshakes.
        peers = identities.mapIndexed { i, id ->
            val own = Membership.found(founder, names.first())
            own.merge(membership.all().filter { it.deviceId == id.deviceId })
            FamilyPeer(id, key, own, clockOf(i))
        }
    }
}

/** Fails after [framesBeforeDrop] sent frames, like a phone leaving the WLAN mid-sync. */
class DroppingChannel(private val inner: FrameChannel, private var framesBeforeDrop: Int) : FrameChannel {
    override suspend fun send(frame: ByteArray) {
        if (framesBeforeDrop-- <= 0) {
            inner.close()
            throw ConnectionLostException("dropped")
        }
        inner.send(frame)
    }

    override suspend fun receive(): ByteArray = inner.receive()
    override fun close() = inner.close()
}

/** Flips one bit in the n-th sent frame. */
class TamperingChannel(private val inner: FrameChannel, private val frameIndex: Int) : FrameChannel {
    private var sent = 0
    override suspend fun send(frame: ByteArray) {
        val out = if (sent++ == frameIndex) frame.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() } else frame
        inner.send(out)
    }

    override suspend fun receive(): ByteArray = inner.receive()
    override fun close() = inner.close()
}

/** One in-memory encounter. Returns both sides' results; failures are returned, not thrown. */
suspend fun encounter(
    a: FamilyPeer,
    b: FamilyPeer,
    wrapA: (FrameChannel) -> FrameChannel = { it },
): Pair<Result<SyncStats>, Result<SyncStats>> = coroutineScope {
    val (rawA, rawB) = InMemoryPipe.pair()
    val left = async {
        runCatching {
            val ch = wrapA(rawA)
            try { SyncSession.run(SecureChannel.establish(ch, Role.INITIATOR, a.identity, a.membership), a) } finally { ch.close() }
        }
    }
    val right = async {
        runCatching {
            try { SyncSession.run(SecureChannel.establish(rawB, Role.RESPONDER, b.identity, b.membership), b) } finally { rawB.close() }
        }
    }
    left.await() to right.await()
}
