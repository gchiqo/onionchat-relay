package com.chiko.onionchat.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end sealed box: ephemeral-X25519 ECDH → AES-256-GCM. The sender uses a
 * fresh ephemeral key each message (forward secrecy); only the holder of the
 * recipient's X25519 private key can open it. The relay only ever sees the
 * resulting ciphertext, never plaintext.
 *
 * Wire layout: ephemeralPublicKey(32) || GCM(iv || ciphertext || tag).
 */
object SealedBox {

    private const val X25519_LEN = 32
    private val rng = SecureRandom()

    fun seal(recipientX25519Pub: ByteArray, plaintext: ByteArray): ByteArray {
        val gen = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(rng)) }
        val pair = gen.generateKeyPair()
        val ephemeralPriv = pair.private as X25519PrivateKeyParameters
        val ephemeralPub = (pair.public as X25519PublicKeyParameters).encoded
        val key = deriveKey(agree(ephemeralPriv, recipientX25519Pub), ephemeralPub, recipientX25519Pub)
        return ephemeralPub + aesGcmEncrypt(key, plaintext)
    }

    fun open(myPrivate: X25519PrivateKeyParameters, sealed: ByteArray): ByteArray {
        val ephemeralPub = sealed.copyOfRange(0, X25519_LEN)
        val body = sealed.copyOfRange(X25519_LEN, sealed.size)
        val myPub = myPrivate.generatePublicKey().encoded
        val key = deriveKey(agree(myPrivate, ephemeralPub), ephemeralPub, myPub)
        return aesGcmDecrypt(key, body)
    }

    private fun aesGcmEncrypt(key: SecretKeySpec, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: SecretKeySpec, blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun agree(priv: X25519PrivateKeyParameters, peerPub: ByteArray): ByteArray {
        val agreement = X25519Agreement().apply { init(priv) }
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), out, 0)
        return out
    }

    private fun deriveKey(shared: ByteArray, ephemeralPub: ByteArray, recipientPub: ByteArray): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(shared)
        md.update(ephemeralPub)
        md.update(recipientPub)
        return SecretKeySpec(md.digest(), "AES")
    }
}
