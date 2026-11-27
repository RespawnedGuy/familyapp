package io.github.respawnedguy.familyapp.domain.offers

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * German postcodes with the centre of their area, from a table shipped with the app
 * (docs/angebote-und-vorschlaege.md "Adresse und Händlersuche"). The typed address never leaves the
 * phone: only the postcode is used, and at most the centre of its area goes out (Kaufland's store search).
 */
object Postcodes {

    /** Centre of a postcode area, rounded to 0.01° (about 1 km): precise enough to find the nearest store. */
    data class Point(val lat: Double, val lon: Double) {
        /** "52.52" with a dot and two decimals, as store searches expect it. */
        val latText: String get() = fixed(lat)
        val lonText: String get() = fixed(lon)

        /** Great-circle distance to [lat]/[lon] in km (haversine), for "ca. 3 km" next to a store. */
        fun distanceKm(lat: Double, lon: Double): Double {
            val dLat = radians(lat - this.lat)
            val dLon = radians(lon - this.lon)
            val a = sin(dLat / 2).pow(2) + cos(radians(this.lat)) * cos(radians(lat)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }

    private const val EARTH_KM = 6371.0
    private fun radians(deg: Double) = deg * PI / HALF_TURN
    private const val HALF_TURN = 180.0

    private const val RECORD = 13
    private const val CODE = 5
    private const val COORD = 4
    private const val HUNDRED = 100.0

    private val ANY_CODE = Regex("""(?<!\d)(\d{5})(?!\d)""")

    /** The centre of [postcode], or null if there is no such German postcode. */
    fun find(postcode: String): Point? {
        val at = postcode.takeIf { it.length == CODE && it.all(Char::isDigit) }?.let(::offsetOf) ?: return null
        val data = PostcodeData.RECORDS
        return Point(
            lat = data.substring(at + CODE, at + CODE + COORD).toInt() / HUNDRED,
            lon = data.substring(at + CODE + COORD, at + RECORD).toInt() / HUNDRED,
        )
    }

    /** Binary search over the sorted records: where [postcode]'s record starts, or null. */
    private fun offsetOf(postcode: String): Int? {
        val data = PostcodeData.RECORDS
        var low = 0
        var high = PostcodeData.COUNT - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = data.substring(mid * RECORD, mid * RECORD + CODE).compareTo(postcode)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid * RECORD
            }
        }
        return null
    }

    /**
     * The postcode in an address as typed ("Musterweg 1, 12345 Musterstadt" or just "12345"): the
     * first group of exactly five digits that is a real postcode. House numbers are shorter, so they never match.
     */
    fun extract(text: String): String? =
        ANY_CODE.findAll(text).map { it.groupValues[1] }.firstOrNull { find(it) != null }

    private fun fixed(value: Double): String {
        val hundredths = kotlin.math.round(value * HUNDRED).toLong()
        val whole = hundredths / HUNDRED.toLong()
        val rest = (hundredths % HUNDRED.toLong()).toString().padStart(2, '0')
        return "$whole.$rest"
    }
}
