package io.github.respawnedguy.familyapp.domain.offers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Postcode table from GeoNames; examples are city centres (public places), never a family's address. */
class PostcodesTest {

    @Test
    fun knownPostcodesGiveTheCentreOfTheirArea() {
        assertEquals(Postcodes.Point(52.53, 13.38), Postcodes.find("10115")) // Berlin-Mitte
        assertEquals(Postcodes.Point(48.13, 11.57), Postcodes.find("80331")) // München Altstadt
        assertEquals(Postcodes.Point(53.55, 10.01), Postcodes.find("20095")) // Hamburg
    }

    @Test
    fun firstAndLastRecordAreFound() {
        assertEquals(Postcodes.Point(51.06, 13.73), Postcodes.find("01053"))
        assertEquals(Postcodes.Point(51.21, 10.56), Postcodes.find("99998"))
    }

    @Test
    fun unknownOrMalformedPostcodesGiveNothing() {
        listOf("00000", "99999", "01001", "1011", "101155", "1011a", "").forEach { assertNull(Postcodes.find(it), it) }
    }

    @Test
    fun tableHoldsEveryRecordInOrder() {
        val data = PostcodeData.RECORDS
        assertEquals(PostcodeData.COUNT * 13, data.length)
        val codes = (0 until PostcodeData.COUNT).map { data.substring(it * 13, it * 13 + 5) }
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun postcodeIsTakenFromAnAddressAsTyped() {
        assertEquals("10115", Postcodes.extract("Musterweg 12, 10115 Berlin"))
        assertEquals("80331", Postcodes.extract("80331"))
        assertEquals("80331", Postcodes.extract("  80331 München "))
        // A number that is not a postcode is skipped, the real one after it counts.
        assertEquals("20095", Postcodes.extract("Postfach 99999, 20095 Hamburg"))
    }

    @Test
    fun addressWithoutPostcodeGivesNothing() {
        assertNull(Postcodes.extract("Musterweg 12, Berlin"))
        assertNull(Postcodes.extract("Tel. 0301234567"))
        assertNull(Postcodes.extract(""))
    }

    @Test
    fun coordinatesAreWrittenWithTwoDecimalsAndADot() {
        val p = Postcodes.Point(52.5, 13.05)
        assertEquals("52.50", p.latText)
        assertEquals("13.05", p.lonText)
    }
}
