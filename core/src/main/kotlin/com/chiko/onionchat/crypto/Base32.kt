package com.chiko.onionchat.crypto

/** RFC 4648 base32 (lowercase, no padding) — the encoding Tor uses for v3 onion addresses. */
object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val REVERSE = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { i, c -> this[c.code] = i }
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text.lowercase()) {
            val v = REVERSE.getOrElse(c.code) { -1 }
            require(v >= 0) { "Invalid base32 char: $c" }
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
