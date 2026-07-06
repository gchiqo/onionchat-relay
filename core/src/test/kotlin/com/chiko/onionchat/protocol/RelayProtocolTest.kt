package com.chiko.onionchat.protocol

import com.chiko.onionchat.crypto.OnionIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {

    @Test fun hello_verifies() {
        val id = OnionIdentity.generate()
        val frame = RelayProtocol.parse(RelayProtocol.hello(id, 1000L))
        assertEquals(RelayProtocol.HELLO, frame.type)
        assertEquals(id.onionAddress, frame.id)
        assertTrue(RelayProtocol.verifyHello(frame))
    }

    @Test fun hello_forgedIdFails() {
        val real = OnionIdentity.generate()
        val attacker = OnionIdentity.generate()
        // attacker signs its own hello but swaps in the victim's id
        val tampered = RelayProtocol.parse(RelayProtocol.hello(attacker, 1L))
            .copy(id = real.onionAddress)
        assertFalse(RelayProtocol.verifyHello(tampered))
    }

    @Test fun send_roundTrips() {
        val body = byteArrayOf(1, 2, 3, 9, 8, 7)
        val frame = RelayProtocol.parse(RelayProtocol.send("a.onion", "b.onion", 42L, body))
        assertEquals(RelayProtocol.SEND, frame.type)
        assertEquals("a.onion", frame.from)
        assertEquals("b.onion", frame.to)
        assertArrayEquals(body, frame.body)
    }

    @Test fun deliver_roundTrips() {
        val body = byteArrayOf(5, 5, 5)
        val frame = RelayProtocol.parse(RelayProtocol.deliver("a.onion", 7L, body))
        assertEquals(RelayProtocol.DELIVER, frame.type)
        assertEquals("a.onion", frame.from)
        assertArrayEquals(body, frame.body)
    }
}
