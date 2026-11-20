package familyapp.sync

import familyapp.sync.client.FamilyDevice
import familyapp.sync.client.RelayClient
import familyapp.sync.protocol.DeviceIdentity
import familyapp.sync.protocol.FamilyCipher
import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path

/** Test clock shared by relay and devices. */
class TestClock(var nowMs: Long = 1_795_000_000_000) {
    fun advance(ms: Long) { nowMs += ms }
}

/** A family of simulated devices registered at the relay under test. */
class TestFamily(private val http: HttpClient, val clock: TestClock, size: Int) {
    val familyId = "fam-" + java.util.UUID.randomUUID()
    private val key = FamilyCipher.generateKey()
    val devices: List<FamilyDevice> = List(size) { FamilyDevice(DeviceIdentity.generate(), familyId, key, clock::nowMs) }
    private val clients = devices.associate { it.deviceId to RelayClient(http, familyId, it.identity, clock::nowMs) }

    fun client(device: FamilyDevice): RelayClient = clients.getValue(device.deviceId)

    suspend fun register() {
        val first = client(devices.first())
        first.createFamily()
        devices.drop(1).forEach { first.addDevice(it.identity.publicKeyEncoded) }
    }

    suspend fun sync(device: FamilyDevice) = device.sync(client(device))
}

fun tempDb(): Path = Files.createTempFile("relay-test", ".db")
