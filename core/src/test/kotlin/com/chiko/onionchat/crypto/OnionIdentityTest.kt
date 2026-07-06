package com.chiko.onionchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionIdentityTest {

    @Test fun address_hasV3Shape() {
        val id = OnionIdentity.generate()
        val addr = id.onionAddress
        assertTrue(addr.endsWith(".onion"))
        assertEquals(56, addr.removeSuffix(".onion").length)
    }

    @Test fun address_isDeterministicFromSeed() {
        val a = OnionIdentity.generate()
        val b = OnionIdentity.fromSeed(a.exportSeed())
        assertEquals(a.onionAddress, b.onionAddress)
    }

    @Test fun publicKey_recoversFromAddressWithValidChecksum() {
        val id = OnionIdentity.generate()
        val recovered = OnionIdentity.publicKeyFromAddress(id.onionAddress)
        assertTrue(recovered.contentEquals(id.publicKey))
    }

    @Test fun signature_verifiesAgainstOwnAddress() {
        val id = OnionIdentity.generate()
        val msg = "attack at dawn".toByteArray()
        assertTrue(OnionIdentity.verify(id.onionAddress, msg, id.sign(msg)))
    }

    @Test fun tamperedMessage_failsVerification() {
        val id = OnionIdentity.generate()
        val sig = id.sign("hello".toByteArray())
        assertFalse(OnionIdentity.verify(id.onionAddress, "hell0".toByteArray(), sig))
    }

    @Test fun wrongIdentity_failsVerification() {
        val a = OnionIdentity.generate()
        val b = OnionIdentity.generate()
        val msg = "secret".toByteArray()
        assertFalse(OnionIdentity.verify(b.onionAddress, msg, a.sign(msg)))
    }
}
