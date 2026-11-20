package familyapp.sync.relay

import org.slf4j.LoggerFactory

/**
 * Hands a wake-up to the push gateway. The real gateway (APNs/FCM, run only by us) is out of
 * scope for this spike; the ciphertext would travel as the push payload and be decrypted
 * by the iOS Notification Service Extension.
 */
fun interface PushSender {
    fun push(deviceId: String, timerId: String, ciphertext: String)
}

class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger("push")

    override fun push(deviceId: String, timerId: String, ciphertext: String) {
        log.info("push device={} timer={} bytes={}", deviceId, timerId, ciphertext.length)
    }
}
