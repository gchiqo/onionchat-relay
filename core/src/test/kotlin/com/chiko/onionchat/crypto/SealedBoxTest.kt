package com.chiko.onionchat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SealedBoxTest {

    @Test fun sealOpen_roundTrip() {
        val bob = OnionIdentity.generate()
        val plain = "meet me at the docks".toByteArray()
        val sealed = SealedBox.seal(bob.x25519Public, plain)
        assertArrayEquals(plain, SealedBox.open(bob.x25519Private(), sealed))
    }

    @Test fun wrongRecipient_cannotOpen() {
        val bob = OnionIdentity.generate()
        val eve = OnionIdentity.generate()
        val sealed = SealedBox.seal(bob.x25519Public, "secret".toByteArray())
        assertThrows(Exception::class.java) { SealedBox.open(eve.x25519Private(), sealed) }
    }

    @Test fun x25519_isDeterministicFromSeed() {
        val a = OnionIdentity.generate()
        val b = OnionIdentity.fromSeed(a.exportSeed())
        assertArrayEquals(a.x25519Public, b.x25519Public)
    }

    @Test fun freshEphemeralEachTime_ciphertextsDiffer() {
        val bob = OnionIdentity.generate()
        val s1 = SealedBox.seal(bob.x25519Public, "hi".toByteArray())
        val s2 = SealedBox.seal(bob.x25519Public, "hi".toByteArray())
        assertEquals(false, s1.contentEquals(s2))
        assertArrayEquals("hi".toByteArray(), SealedBox.open(bob.x25519Private(), s1))
        assertArrayEquals("hi".toByteArray(), SealedBox.open(bob.x25519Private(), s2))
    }
}
