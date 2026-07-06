package com.chiko.onionchat.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jcajce.provider.digest.SHA3
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A self-authenticating identity. The ed25519 public key deterministically
 * yields a Tor v3 onion address (rend-spec-v3 §6), which IS the user's unique
 * ID — no registration, no central authority. The 32-byte private seed is the
 * only secret to persist for a stable ("persistent") identity.
 */
class OnionIdentity private constructor(
    private val priv: Ed25519PrivateKeyParameters,
    val publicKey: ByteArray,
) {
    /** The .onion address derived from the public key (56 chars + ".onion"). */
    val onionAddress: String by lazy { addressFor(publicKey) }

    /** 32-byte seed to store for a persistent identity. */
    fun exportSeed(): ByteArray = priv.encoded

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, priv)
        update(message, 0, message.size)
        generateSignature()
    }

    /** X25519 private key for decryption, derived deterministically from the ed25519 seed. */
    fun x25519Private(): X25519PrivateKeyParameters {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(priv.encoded)
        md.update("onionchat-x25519".toByteArray(Charsets.US_ASCII))
        return X25519PrivateKeyParameters(md.digest(), 0)
    }

    /** X25519 public key others encrypt to (shared in the QR/contact). */
    val x25519Public: ByteArray by lazy { x25519Private().generatePublicKey().encoded }

    companion object {
        private const val VERSION: Byte = 0x03
        private const val SUFFIX = ".onion"
        private const val CHECKSUM_PREFIX = ".onion checksum"

        fun generate(random: SecureRandom = SecureRandom()): OnionIdentity {
            val priv = Ed25519PrivateKeyParameters(random)
            return OnionIdentity(priv, priv.generatePublicKey().encoded)
        }

        fun fromSeed(seed: ByteArray): OnionIdentity {
            val priv = Ed25519PrivateKeyParameters(seed, 0)
            return OnionIdentity(priv, priv.generatePublicKey().encoded)
        }

        /** rend-spec-v3: onion = base32(PUBKEY || CHECKSUM[:2] || VERSION) + ".onion". */
        fun addressFor(publicKey: ByteArray): String {
            require(publicKey.size == 32) { "ed25519 public key must be 32 bytes" }
            val checksum = checksum(publicKey)
            val payload = publicKey + checksum.copyOfRange(0, 2) + VERSION
            return Base32.encode(payload) + SUFFIX
        }

        /** Recover and validate the public key encoded in an onion address. */
        fun publicKeyFromAddress(onion: String): ByteArray {
            val core = onion.removeSuffix(SUFFIX)
            val raw = Base32.decode(core)
            require(raw.size == 35) { "Bad onion address length" }
            val pub = raw.copyOfRange(0, 32)
            val version = raw[34]
            require(version == VERSION) { "Unsupported onion version" }
            val expected = checksum(pub).copyOfRange(0, 2)
            require(raw.copyOfRange(32, 34).contentEquals(expected)) { "Onion checksum mismatch" }
            return pub
        }

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

        private fun checksum(publicKey: ByteArray): ByteArray {
            val d = SHA3.Digest256()
            d.update(CHECKSUM_PREFIX.toByteArray(Charsets.US_ASCII))
            d.update(publicKey)
            d.update(VERSION)
            return d.digest()
        }
    }
}
