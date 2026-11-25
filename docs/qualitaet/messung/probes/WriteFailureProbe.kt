import io.github.respawnedguy.familyapp.data.*
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test

class WriteFailureProbe {
    @Test fun probe() {
        var caught: Throwable? = null
        val handler = CoroutineExceptionHandler { _, e -> caught = e }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)
        val store = object : TextStore {
            var fail = true
            var writes = 0
            override fun read(): String? = null
            override fun write(text: String) { writes++; if (fail) throw java.io.IOException("No space left on device") }
            override fun preserveBroken(text: String) {}
        }
        var n = 0
        val repo = FamilyRepository(store, scope, Dispatchers.Unconfined, { 1L }, { LocalDate(2026, 11, 25) }, { "id${n++}" })
        repo.createFamily("Nils")
        println("WRITEFAIL caught=${caught?.message}")
        store.fail = false
        repo.capture("Zweiter Eintrag", io.github.respawnedguy.familyapp.domain.Source.TEXT)
        println("WRITEFAIL writesAfterRecovery=${store.writes} (1 = Speichern endet nach dem ersten Fehler)")
    }
}
