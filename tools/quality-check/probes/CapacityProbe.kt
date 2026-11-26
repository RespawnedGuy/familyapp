import io.github.respawnedguy.familyapp.data.*
import io.github.respawnedguy.familyapp.domain.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.measureTime

class CapacityProbe {
    @Test fun probe() {
        for (n in listOf(1_000, 5_000, 20_000)) {
            val members = (0 until 4).map { Member("m$it", "Person$it", it, stamp = Stamp(0, 0, "d")) }
            val items = (0 until n).map { i -> Item("id-$i-0000-0000-0000-000000000000", "Mia braucht bis Freitag Turnschuhe Nummer $i", Source.VOICE, i.toLong(), "m0",
                status = ItemStatus.entries[i % 4], assignee = "m${i % 4}", due = LocalDate(2026, 12, 1 + i % 28), reasons = listOf("Name „Mia“ im Text", "„bis Freitag“ → Fr 27.11."), stamp = Stamp(i.toLong(), 0, "d")) }
            val data = FamilyData(deviceId = "d", selfId = "m0", members = members, items = items)
            repeat(3) { FamilyJson.encode(data) }
            var json = ""
            val enc = measureTime { repeat(5) { json = FamilyJson.encode(data) } } / 5
            val dec = measureTime { repeat(5) { FamilyJson.decode(json) } } / 5
            val today = LocalDate(2026, 11, 25)
            val views = measureTime { repeat(5) { Views.inbox(data); Views.loads(data, today); Views.upcoming(data, today); Views.search(data, "mia turnschuhe") } } / 5
            println("CAPACITY n=$n jsonKB=${json.length / 1024} encode=$enc decode=$dec views=$views")
        }
    }
}
