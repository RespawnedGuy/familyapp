package familyapp.sync.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Relay entry point. Configuration only via environment variables (see .env.example). */
fun main() {
    val port = System.getenv("RELAY_PORT")?.toInt() ?: 8080
    val dbPath = System.getenv("RELAY_DB_PATH") ?: "relay.db"
    val intervalMs = (System.getenv("RELAY_TIMER_INTERVAL_SECONDS")?.toLong() ?: 30) * 1000

    val store = RelayStore(dbPath)
    val scheduler = TimerScheduler(store, LoggingPushSender(), System::currentTimeMillis)

    embeddedServer(Netty, port = port) {
        relayModule(store)
        launch {
            while (true) {
                scheduler.tick()
                delay(intervalMs)
            }
        }
    }.start(wait = true)
}
