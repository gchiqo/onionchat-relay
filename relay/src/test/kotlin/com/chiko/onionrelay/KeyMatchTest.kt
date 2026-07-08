package com.chiko.onionrelay

import com.chiko.onionchat.crypto.OnionIdentity
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

/**
 * Does kmp-tor's ED25519_V3 key (what P2pTransport hosts via ADD_ONION) produce the
 * SAME onion as our OnionIdentity (what the QR/contact carries)? If not, every phone
 * hosts an address that doesn't match its QR — which would fully explain the P2P
 * "SOCKS general failure" on dial.
 */
class KeyMatchTest {

    @Test fun kmpTorOnionMatchesIdentity_fromSeed() {
        repeat(5) {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val identityOnion = OnionIdentity.fromSeed(seed).onionAddress          // e.g. abcd….onion
            val torOnion = ED25519_V3.PrivateKey.generate(seed, 0)
                .generatePublicKey().address().toString()

            println("identity : $identityOnion")
            println("kmp-tor  : $torOnion")
            println("---")
            val a = identityOnion.removeSuffix(".onion")
            val b = torOnion.removeSuffix(".onion")
            assertEquals("hosted onion must equal identity onion", a, b)
        }
    }
}
