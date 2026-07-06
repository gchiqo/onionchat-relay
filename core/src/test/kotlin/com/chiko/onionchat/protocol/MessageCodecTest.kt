package com.chiko.onionchat.protocol

import com.chiko.onionchat.crypto.OnionIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessageCodecTest {

    @Test fun encodeThenDecode_returnsMessage() {
        val id = OnionIdentity.generate()
        val payload = MessageCodec.encode(id, "alice", "hello world", 1700000000L)
        val msg = MessageCodec.decode(payload)
        assertNotNull(msg)
        assertEquals(id.onionAddress, msg!!.fromOnion)
        assertEquals("alice", msg.handle)
        assertEquals("hello world", msg.body)
        assertEquals(1700000000L, msg.sentAt)
    }

    @Test fun tamperedBody_failsSignatureAndDecodesNull() {
        val id = OnionIdentity.generate()
        val json = JSONObject(String(MessageCodec.encode(id, "alice", "pay 5", 1L)))
        json.put("body", "pay 5000")
        assertNull(MessageCodec.decode(json.toString().toByteArray()))
    }

    @Test fun forgedSender_failsVerification() {
        val real = OnionIdentity.generate()
        val attacker = OnionIdentity.generate()
        val json = JSONObject(String(MessageCodec.encode(attacker, "x", "spoof", 1L)))
        json.put("from", real.onionAddress) // claim someone else's identity
        assertNull(MessageCodec.decode(json.toString().toByteArray()))
    }

    @Test fun garbagePayload_decodesNull() {
        assertNull(MessageCodec.decode("not json".toByteArray()))
    }
}
