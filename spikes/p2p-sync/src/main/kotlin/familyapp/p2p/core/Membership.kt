package familyapp.p2p.core

import familyapp.p2p.crypto.DeviceIdentity
import kotlinx.serialization.Serializable

/**
 * Admission of one device into the family. The founder's entry has no endorser: the family id
 * IS the founder's device id, so the root of trust is self-certifying. Every later device is
 * endorsed (signed) by a device that was already a member.
 */
@Serializable
data class Endorsement(
    val publicKey: String,
    val name: String,
    val endorsedBy: String? = null,
    val signature: String? = null,
) {
    val deviceId: String get() = DeviceIdentity.deviceIdOf(publicKey)

    companion object {
        fun payload(familyId: String, publicKey: String, name: String): ByteArray =
            "endorse\n$familyId\n$publicKey\n$name".toByteArray()
    }
}

/**
 * Grow-only set of verified endorsements (a G-Set CRDT). Peers exchange their sets in every
 * handshake, so a device paired at B is known to C before C ever meets it.
 * Removal and key rotation are out of scope for the spike.
 */
class Membership(val familyId: String) {
    private val members = LinkedHashMap<String, Endorsement>()

    fun publicKeyOf(deviceId: String): String? = members[deviceId]?.publicKey
    fun nameOf(deviceId: String): String? = members[deviceId]?.name
    fun contains(deviceId: String): Boolean = deviceId in members
    fun all(): List<Endorsement> = members.values.toList()

    /** Adds every endorsement whose chain leads back to the founder. Returns the ids that were new. */
    fun merge(endorsements: Collection<Endorsement>): Set<String> {
        val added = mutableSetOf<String>()
        var pending = endorsements.filter { it.deviceId !in members }
        while (true) {
            val (valid, rest) = pending.partition { isValid(it) }
            if (valid.isEmpty()) break
            valid.forEach { members[it.deviceId] = it; added += it.deviceId }
            pending = rest.filter { it.deviceId !in members }
        }
        return added
    }

    fun copy(): Membership = Membership(familyId).also { it.members.putAll(members) }

    private fun isValid(e: Endorsement): Boolean {
        if (e.endorsedBy == null) return e.deviceId == familyId
        val endorserKey = members[e.endorsedBy]?.publicKey ?: return false
        val signature = e.signature ?: return false
        return DeviceIdentity.verify(endorserKey, Endorsement.payload(familyId, e.publicKey, e.name), signature)
    }

    companion object {
        fun found(founder: DeviceIdentity, name: String): Membership =
            Membership(founder.deviceId).also { it.merge(listOf(Endorsement(founder.publicKeyEncoded, name))) }

        fun endorse(familyId: String, endorser: DeviceIdentity, newPublicKey: String, newName: String) = Endorsement(
            publicKey = newPublicKey,
            name = newName,
            endorsedBy = endorser.deviceId,
            signature = endorser.sign(Endorsement.payload(familyId, newPublicKey, newName)),
        )
    }
}
