package familyapp.sync.relay

import familyapp.sync.protocol.StoredEnvelope
import familyapp.sync.protocol.TimerRequest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

data class DueTimer(val id: String, val familyId: String, val recipients: List<String>, val ciphertext: String)

/**
 * SQLite storage of the relay. Holds only public keys, ciphertexts and timing metadata.
 * One connection behind a lock is plenty for a family relay; the schema is created on start
 * (spike: no migrations yet).
 */
class RelayStore(dbPath: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val lock = Any()

    init {
        connection.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=DELETE")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS devices (family_id TEXT NOT NULL, device_id TEXT NOT NULL, public_key TEXT NOT NULL, ack_seq INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (family_id, device_id))")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS envelopes (seq INTEGER PRIMARY KEY AUTOINCREMENT, family_id TEXT NOT NULL, id TEXT NOT NULL, sender_device TEXT NOT NULL, ciphertext TEXT NOT NULL, UNIQUE (family_id, id))")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS timers (id TEXT PRIMARY KEY, family_id TEXT NOT NULL, fire_at_ms INTEGER NOT NULL, recipients TEXT NOT NULL, ciphertext TEXT NOT NULL, cancel_hash TEXT NOT NULL, state TEXT NOT NULL)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS timers_due ON timers (state, fire_at_ms)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS nonces (device_id TEXT NOT NULL, nonce TEXT NOT NULL, expires_at_ms INTEGER NOT NULL, PRIMARY KEY (device_id, nonce))")
        }
    }

    fun familyExists(familyId: String): Boolean = synchronized(lock) {
        query("SELECT 1 FROM devices WHERE family_id = ? LIMIT 1", familyId) { it.next() }
    }

    fun addDevice(familyId: String, deviceId: String, publicKey: String) = synchronized(lock) {
        update("INSERT OR IGNORE INTO devices (family_id, device_id, public_key) VALUES (?, ?, ?)", familyId, deviceId, publicKey)
    }

    fun publicKey(familyId: String, deviceId: String): String? = synchronized(lock) {
        query("SELECT public_key FROM devices WHERE family_id = ? AND device_id = ?", familyId, deviceId) {
            if (it.next()) it.getString(1) else null
        }
    }

    fun deviceIds(familyId: String): Set<String> = synchronized(lock) {
        query("SELECT device_id FROM devices WHERE family_id = ?", familyId) {
            buildSet { while (it.next()) add(it.getString(1)) }
        }
    }

    /** Returns false if the nonce was seen before (replay). */
    fun rememberNonce(deviceId: String, nonce: String, expiresAtMs: Long): Boolean = synchronized(lock) {
        try {
            update("INSERT INTO nonces (device_id, nonce, expires_at_ms) VALUES (?, ?, ?)", deviceId, nonce, expiresAtMs)
            true
        } catch (e: SQLException) {
            false
        }
    }

    /** Idempotent: re-uploading the same envelope id returns the existing seq. */
    fun appendEnvelope(familyId: String, id: String, senderDevice: String, ciphertext: String): Long = synchronized(lock) {
        update("INSERT OR IGNORE INTO envelopes (family_id, id, sender_device, ciphertext) VALUES (?, ?, ?, ?)", familyId, id, senderDevice, ciphertext)
        query("SELECT seq FROM envelopes WHERE family_id = ? AND id = ?", familyId, id) { it.next(); it.getLong(1) }
    }

    fun envelopesAfter(familyId: String, afterSeq: Long, limit: Int = 500): List<StoredEnvelope> = synchronized(lock) {
        query("SELECT seq, id, sender_device, ciphertext FROM envelopes WHERE family_id = ? AND seq > ? ORDER BY seq LIMIT ?", familyId, afterSeq, limit) {
            buildList { while (it.next()) add(StoredEnvelope(it.getLong(1), it.getString(2), it.getString(3), it.getString(4))) }
        }
    }

    fun latestSeq(familyId: String): Long = synchronized(lock) {
        query("SELECT COALESCE(MAX(seq), 0) FROM envelopes WHERE family_id = ?", familyId) { it.next(); it.getLong(1) }
    }

    /** Records the device's cursor and deletes envelopes every device of the family has received. */
    fun acknowledge(familyId: String, deviceId: String, seq: Long) = synchronized(lock) {
        update("UPDATE devices SET ack_seq = MAX(ack_seq, ?) WHERE family_id = ? AND device_id = ?", seq, familyId, deviceId)
        update("DELETE FROM envelopes WHERE family_id = ? AND seq <= (SELECT MIN(ack_seq) FROM devices WHERE family_id = ?)", familyId, familyId)
    }

    fun envelopeCount(familyId: String): Int = synchronized(lock) {
        query("SELECT COUNT(*) FROM envelopes WHERE family_id = ?", familyId) { it.next(); it.getInt(1) }
    }

    fun scheduleTimer(familyId: String, timer: TimerRequest) = synchronized(lock) {
        update(
            "INSERT OR IGNORE INTO timers (id, family_id, fire_at_ms, recipients, ciphertext, cancel_hash, state) VALUES (?, ?, ?, ?, ?, ?, 'pending')",
            timer.id, familyId, timer.fireAtMs, timer.recipients.joinToString(","), timer.ciphertext, timer.cancelHash,
        )
    }

    /** Cancels a pending timer if the hash matches. Returns false otherwise. */
    fun cancelTimer(familyId: String, timerId: String, cancelHash: String): Boolean = synchronized(lock) {
        update(
            "UPDATE timers SET state = 'cancelled', ciphertext = '' WHERE id = ? AND family_id = ? AND cancel_hash = ? AND state = 'pending'",
            timerId, familyId, cancelHash,
        ) == 1
    }

    fun dueTimers(nowMs: Long): List<DueTimer> = synchronized(lock) {
        query("SELECT id, family_id, recipients, ciphertext FROM timers WHERE state = 'pending' AND fire_at_ms <= ? ORDER BY fire_at_ms", nowMs) {
            buildList { while (it.next()) add(DueTimer(it.getString(1), it.getString(2), it.getString(3).split(","), it.getString(4))) }
        }
    }

    fun markFired(timerId: String) = synchronized(lock) {
        update("UPDATE timers SET state = 'fired', ciphertext = '' WHERE id = ?", timerId)
    }

    fun deleteExpiredNonces(nowMs: Long) = synchronized(lock) {
        update("DELETE FROM nonces WHERE expires_at_ms < ?", nowMs)
    }

    override fun close() = connection.close()

    private fun update(sql: String, vararg args: Any): Int =
        connection.prepareStatement(sql).use { st -> bind(st, args); st.executeUpdate() }

    private fun <T> query(sql: String, vararg args: Any, read: (java.sql.ResultSet) -> T): T =
        connection.prepareStatement(sql).use { st -> bind(st, args); st.executeQuery().use(read) }

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any>) =
        args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
}
