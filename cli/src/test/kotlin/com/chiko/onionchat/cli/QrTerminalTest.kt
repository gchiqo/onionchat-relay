package com.chiko.onionchat.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class QrTerminalTest {

    private val onion = "oqxuizbj5ategxnyzvkftxkftriqnn4d5wonpx7qdiof6c3glew2hyad.onion"
    private val x25519 = "oIXVzFz+dHojQ6IWjAiMG2/0VV7LQZgrg9Alj1WB4Ck="

    @Test fun payload_matchesAppContactQrFormat() {
        assertEquals(
            "onionchat|$onion|alice|$x25519",
            QrTerminal.contactPayload(onion, "alice", x25519),
        )
    }

    /** Encode the exact payload as a QR and decode it back — proves the app can scan it. */
    @Test fun payload_qrRoundTrips() {
        val payload = QrTerminal.contactPayload(onion, "alice", x25519)
        val scale = 8
        val bits = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 33 * scale, 33 * scale)
        val w = bits.width
        val h = bits.height
        val pixels = IntArray(w * h) { i -> if (bits.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val source = RGBLuminanceSource(w, h, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals(payload, result.text)
    }
}
