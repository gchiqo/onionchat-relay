package com.chiko.onionchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class Base32Test {

    @Test fun roundTrip_onionPayloadSize() {
        val rng = SecureRandom()
        repeat(100) {
            val data = ByteArray(35).also { rng.nextBytes(it) }
            val decoded = Base32.decode(Base32.encode(data))
            assertTrue(data.contentEquals(decoded))
        }
    }

    @Test fun encode_isLowercaseRfc4648Alphabet() {
        val encoded = Base32.encode(ByteArray(35) { it.toByte() })
        assertTrue(encoded.all { it in "abcdefghijklmnopqrstuvwxyz234567" })
        assertEquals(56, encoded.length)
    }

    @Test fun roundTrip_variableLengths() {
        val rng = SecureRandom()
        for (n in 1..40) {
            val data = ByteArray(n).also { rng.nextBytes(it) }
            assertTrue(data.contentEquals(Base32.decode(Base32.encode(data))))
        }
    }
}
