package com.chiko.onionchat.cli

import org.bouncycastle.util.encoders.Hex
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Minimal Tor control-port client (control-spec.txt). Used by `--host` to publish
 * an ephemeral v3 onion service (ADD_ONION) that maps a virtual port to the
 * in-process relay's loopback port — so the client process hosts a Tor-reachable
 * relay using the SAME Tor the user already runs (no bundled tor binary needed,
 * so it also works on Termux/FreeBSD).
 *
 * The connection is kept open for the process lifetime: an ephemeral ADD_ONION
 * service is torn down automatically when the control connection closes, so the
 * onion disappears cleanly when the host exits.
 */
class TorControl private constructor(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: OutputStream,
) {
    /**
     * Publish `virtualPort -> 127.0.0.1:targetPort`; returns the onion address.
     * [keyBlob] is a Tor key spec: "NEW:ED25519-V3" for an ephemeral key, or
     * "ED25519-V3:<base64>" to host a specific identity (so onion == identity).
     */
    fun publishOnion(virtualPort: Int, targetPort: Int, keyBlob: String = "NEW:ED25519-V3"): String {
        val reply = command("ADD_ONION $keyBlob Port=$virtualPort,127.0.0.1:$targetPort")
        val serviceId = reply.firstNotNullOfOrNull {
            Regex("ServiceID=([a-z2-7]+)").find(it)?.groupValues?.get(1)
        } ?: error("Tor did not return a ServiceID: ${reply.joinToString(" | ")}")
        return "$serviceId.onion"
    }

    fun close() = runCatching { socket.close() }.let {}

    private fun command(line: String): List<String> {
        writer.write((line + "\r\n").toByteArray(StandardCharsets.US_ASCII))
        writer.flush()
        return readReply(line)
    }

    /** Read a control reply; success codes are 2xx. Lines: "250-..." continue, "250 ..." ends. */
    private fun readReply(context: String): List<String> {
        val lines = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: error("Tor control closed during: $context")
            if (line.length < 4) { lines.add(line); continue }
            val code = line.substring(0, 3)
            lines.add(line.substring(4))
            val sep = line[3]
            if (sep == ' ') { // final line of this reply
                if (!code.startsWith("2")) error("Tor control error ($code) on '$context': ${lines.last()}")
                return lines
            }
        }
    }

    companion object {
        fun connect(host: String, port: Int, password: String?): TorControl {
            val socket = Socket().apply { connect(InetSocketAddress(host, port), 10_000) }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = socket.getOutputStream()
            val tc = TorControl(socket, reader, writer)
            tc.authenticate(password)
            return tc
        }
    }

    private fun authenticate(password: String?) {
        val info = command("PROTOCOLINFO 1")
        val methods = info.firstOrNull { it.startsWith("AUTH ") }.orEmpty()
        val cookieFile = Regex("COOKIEFILE=\"(.*?)\"").find(methods)?.groupValues?.get(1)
            ?.replace("\\\\", "\\")?.replace("\\\"", "\"")

        val arg = when {
            password != null && methods.contains("HASHEDPASSWORD") ->
                "\"" + password.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            methods.contains("NULL") -> ""
            cookieFile != null -> Hex.toHexString(File(cookieFile).readBytes())
            else -> error("No supported Tor control auth method (need NULL, cookie, or --control-password). Methods: $methods")
        }
        command(if (arg.isEmpty()) "AUTHENTICATE" else "AUTHENTICATE $arg")
    }
}
