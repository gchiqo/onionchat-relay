package com.chiko.onionchat.protocol

import com.chiko.onionchat.crypto.OnionIdentity
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire format for a single chat message: a JSON envelope signed by the sender's
 * identity key. Because the address is derived from that key, verifying the
 * signature against the claimed `from` onion proves the sender — no PKI needed.
 */
data class SignedMessage(
    val fromOnion: String,
    val handle: String,
    val body: String,
    val sentAt: Long,
    /** Sender's X25519 public key (base64) so the recipient can encrypt replies. */
    val x25519: String = "",
)

object MessageCodec {

    private const val VERSION = 1
    private const val MAX_FRAME = 64 * 1024

    fun encode(identity: OnionIdentity, handle: String, body: String, sentAt: Long): ByteArray {
        val from = identity.onionAddress
        val x = Base64.toBase64String(identity.x25519Public)
        val sig = identity.sign(signingInput(from, handle, body, sentAt, x))
        val json = JSONObject()
            .put("v", VERSION)
            .put("from", from)
            .put("handle", handle)
            .put("body", body)
            .put("ts", sentAt)
            .put("x", x)
            .put("sig", Base64.toBase64String(sig))
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parse and verify. Returns null if the signature does not match the claimed sender. */
    fun decode(payload: ByteArray): SignedMessage? = try {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        val from = json.getString("from")
        val handle = json.getString("handle")
        val body = json.getString("body")
        val ts = json.getLong("ts")
        val x = json.optString("x")
        val sig = Base64.decode(json.getString("sig"))
        if (OnionIdentity.verify(from, signingInput(from, handle, body, ts, x), sig)) {
            SignedMessage(from, handle, body, ts, x)
        } else null
    } catch (_: Exception) {
        null
    }

    // length-prefixed framing for stream transports (e.g. a Tor stream)
    fun writeFrame(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME) { "Frame too large" }
        DataOutputStream(out).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    fun readFrame(input: InputStream): ByteArray {
        val dis = DataInputStream(input)
        val len = dis.readInt()
        require(len in 0..MAX_FRAME) { "Bad frame length" }
        return ByteArray(len).also { dis.readFully(it) }
    }

    private fun signingInput(from: String, handle: String, body: String, ts: Long, x: String): ByteArray =
        "$from\n$handle\n$body\n$ts\n$x".toByteArray(Charsets.UTF_8)
}
