package com.chiko.onionrelay

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Minimal, self-contained copy of the client's onion-address parsing + ed25519
 * verification, so the relay can authenticate HELLO (only the key owner may
 * register an id to receive messages for). The relay never decrypts payloads.
 */
object OnionVerify {

    private const val VERSION: Byte = 0x03
    private const val SUFFIX = ".onion"

    fun verify(onion: String, message: ByteArray, signature: ByteArray): Boolean = try {
        val pub = Ed25519PublicKeyParameters(publicKeyFromAddress(onion), 0)
        Ed25519Signer().run {
            init(false, pub)
            update(message, 0, message.size)
            verifySignature(signature)
        }
    } catch (_: Exception) {
        false
    }

    private fun publicKeyFromAddress(onion: String): ByteArray {
        val raw = base32Decode(onion.removeSuffix(SUFFIX))
        require(raw.size == 35 && raw[34] == VERSION) { "bad onion" }
        return raw.copyOfRange(0, 32)
    }

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private fun base32Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text.lowercase()) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "bad base32" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
