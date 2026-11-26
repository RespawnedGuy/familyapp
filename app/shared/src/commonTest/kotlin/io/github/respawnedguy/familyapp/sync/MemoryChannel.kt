package io.github.respawnedguy.familyapp.sync

import kotlinx.coroutines.channels.Channel

/** Two connected in-memory [FrameChannel]s; [tamper] may change frames on the way (attack tests). */
class MemoryChannel private constructor(
    private val inbox: Channel<ByteArray>,
    private val outbox: Channel<ByteArray>,
    private val tamper: (Int, ByteArray) -> ByteArray,
) : FrameChannel {
    private var sent = 0

    override suspend fun send(frame: ByteArray) = outbox.send(tamper(sent++, frame.copyOf()))
    override suspend fun receive(): ByteArray = inbox.receive()
    override fun close() {
        outbox.close()
    }

    companion object {
        fun pair(tamper: (Int, ByteArray) -> ByteArray = { _, f -> f }): Pair<MemoryChannel, MemoryChannel> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            return MemoryChannel(ba, ab, tamper) to MemoryChannel(ab, ba) { _, f -> f }
        }
    }
}
