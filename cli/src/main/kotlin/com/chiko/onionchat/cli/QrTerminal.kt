package com.chiko.onionchat.cli

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Renders a QR code as text so the Android app can scan it straight off the
 * terminal. Uses half-block glyphs (two vertical modules per character row) with
 * a forced black-on-white colour so it scans regardless of terminal theme.
 *
 * The payload matches the app's ContactQr format exactly: "onionchat|onion|handle|x25519".
 */
object QrTerminal {

    /** The exact string the Android app's QR scanner (ContactQr) expects. */
    fun contactPayload(onion: String, handle: String, x25519: String) =
        "onionchat|$onion|$handle|$x25519"

    fun render(text: String, quiet: Int = 2): String {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        val m = qr.matrix
        val w = m.width
        val h = m.height
        val W = w + quiet * 2
        val H = h + quiet * 2

        fun dark(x: Int, y: Int): Boolean {
            val mx = x - quiet
            val my = y - quiet
            return mx in 0 until w && my in 0 until h && m.get(mx, my).toInt() == 1
        }

        val black = "\u001b[30m"   // dark modules -> black foreground glyph
        val white = "\u001b[47m"   // light background so it's dark-on-light on any theme
        val reset = "\u001b[0m"
        val sb = StringBuilder()
        var y = 0
        while (y < H) {
            sb.append(black).append(white)
            for (x in 0 until W) {
                val top = dark(x, y)
                val bot = y + 1 < H && dark(x, y + 1)
                sb.append(
                    when {
                        top && bot -> '█'   // full block
                        top -> '▀'          // upper half
                        bot -> '▄'          // lower half
                        else -> ' '
                    }
                )
            }
            sb.append(reset).append('\n')
            y += 2
        }
        return sb.toString()
    }
}
