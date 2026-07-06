package com.chiko.onionchat.protocol

import com.chiko.onionchat.crypto.OnionIdentity
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject

/**
 * Wire protocol spoken to the untrusted relay (framed by [MessageCodec.writeFrame]).
 *
 * - HELLO  client → relay: registers the client's id to receive for (signed, so only
 *          the key owner can claim an id).
 * - SEND   client → relay: deliver an opaque [body] (an E2E sealed box) to `to`.
 * - DELIVER relay → client: a queued/forwarded message from `from`.
 *
 * The relay routes by id only; `body` is always an E2E sealed box it cannot read.
 */
object RelayProtocol {
    const val HELLO = "hello"
    const val SEND = "send"
    const val DELIVER = "deliver"

    data class Frame(
        val type: String,
        val id: String = "",
        val from: String = "",
        val to: String = "",
        val ts: Long = 0,
        val sig: ByteArray = EMPTY,
        val body: ByteArray = EMPTY,
    )

    fun hello(identity: OnionIdentity, ts: Long): ByteArray {
        val id = identity.onionAddress
        val sig = identity.sign(helloInput(id, ts))
        return JSONObject()
            .put("t", HELLO).put("id", id).put("ts", ts).put("sig", Base64.toBase64String(sig))
            .toString().toByteArray()
    }

    /** Verify a HELLO so the relay only lets the key owner register an id. */
    fun verifyHello(frame: Frame): Boolean =
        frame.type == HELLO && OnionIdentity.verify(frame.id, helloInput(frame.id, frame.ts), frame.sig)

    fun send(fromOnion: String, toOnion: String, ts: Long, sealedBody: ByteArray): ByteArray =
        JSONObject()
            .put("t", SEND).put("from", fromOnion).put("to", toOnion).put("ts", ts)
            .put("body", Base64.toBase64String(sealedBody))
            .toString().toByteArray()

    fun deliver(fromOnion: String, ts: Long, sealedBody: ByteArray): ByteArray =
        JSONObject()
            .put("t", DELIVER).put("from", fromOnion).put("ts", ts)
            .put("body", Base64.toBase64String(sealedBody))
            .toString().toByteArray()

    fun parse(bytes: ByteArray): Frame {
        val j = JSONObject(String(bytes, Charsets.UTF_8))
        return Frame(
            type = j.getString("t"),
            id = j.optString("id"),
            from = j.optString("from"),
            to = j.optString("to"),
            ts = j.optLong("ts"),
            sig = j.optString("sig").decodeB64(),
            body = j.optString("body").decodeB64(),
        )
    }

    private fun helloInput(id: String, ts: Long) = "$HELLO$id$ts".toByteArray(Charsets.UTF_8)
    private fun String.decodeB64() = if (isEmpty()) EMPTY else Base64.decode(this)
    private val EMPTY = ByteArray(0)
}
