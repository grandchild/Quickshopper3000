package org.effekthasch.quickshopper3k.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UlidTest {

    @Test
    fun lengthIs26() {
        assertEquals(26, Ulid.generate().length)
    }

    @Test
    fun onlyContainsCrockfordBase32Chars() {
        val valid = "0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghjkmnpqrstvwxyz".toSet()
        repeat(100) {
            for (c in Ulid.generate()) {
                assertTrue("Unexpected char '$c'", c in valid)
            }
        }
    }

    @Test
    fun timestampRoundTrips() {
        val ts = 1719500000000L
        val ulid = Ulid.generate(ts)
        assertEquals(ts, Ulid.timestamp(ulid))
    }

    @Test
    fun timestampRoundTripsZero() {
        val ulid = Ulid.generate(0L)
        assertEquals(0L, Ulid.timestamp(ulid))
    }

    @Test
    fun timestampRoundTripsMaxReasonable() {
        val ts = (1L shl 48) - 1
        val ulid = Ulid.generate(ts)
        assertEquals(ts, Ulid.timestamp(ulid))
    }

    @Test
    fun sameTimestampProducesDifferentUlids() {
        val ts = System.currentTimeMillis()
        val a = Ulid.generate(ts)
        val b = Ulid.generate(ts)
        assertNotEquals(a, b)
    }

    @Test
    fun lexicographicOrderMatchesTimeOrder() {
        val earlier = Ulid.generate(1000000L)
        val later = Ulid.generate(2000000L)
        assertTrue("'$earlier' should sort before '$later'", earlier < later)
    }

    @Test
    fun firstTenCharsEncodeTimestamp() {
        val ts = 1719500000000L
        val a = Ulid.generate(ts)
        val b = Ulid.generate(ts)
        assertEquals(a.substring(0, 10), b.substring(0, 10))
    }

    @Test
    fun differentTimestampsDifferInPrefix() {
        val a = Ulid.generate(1000L)
        val b = Ulid.generate(2000L)
        assertNotEquals(a.substring(0, 10), b.substring(0, 10))
    }

    @Test
    fun timestampDecodingIsCaseInsensitive() {
        val ts = 1719500000000L
        val ulid = Ulid.generate(ts)
        assertEquals(ts, Ulid.timestamp(ulid.lowercase()))
    }

    @Test
    fun isValidAcceptsGenerated() {
        repeat(100) { assertTrue(Ulid.isValid(Ulid.generate())) }
    }

    @Test
    fun isValidAcceptsLowercase() {
        assertTrue(Ulid.isValid(Ulid.generate().lowercase()))
    }

    @Test
    fun isValidRejectsTooShort() {
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FA"))
    }

    @Test
    fun isValidRejectsTooLong() {
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAVX"))
    }

    @Test
    fun isValidRejectsEmpty() {
        assertFalse(Ulid.isValid(""))
    }

    @Test
    fun isValidRejectsInvalidChars() {
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FA!"))
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAÜ"))
    }

    @Test
    fun isValidAcceptsCrockfordAmbiguous() {
        val base = Ulid.generate()
        assertTrue(Ulid.isValid(base.substring(0, 25) + "I"))
        assertTrue(Ulid.isValid(base.substring(0, 25) + "L"))
        assertTrue(Ulid.isValid(base.substring(0, 25) + "O"))
    }
}
