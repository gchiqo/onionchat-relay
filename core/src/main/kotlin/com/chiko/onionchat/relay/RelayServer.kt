package com.chiko.onionchat.relay

import com.chiko.onionchat.crypto.OnionIdentity
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The untrusted store-and-forward routing core, with no Tor coupling: give it a
 * [ServerSocket] and it serves clients on it. Used by the standalone relay (over a
 * Tor onion service) and by the CLI's in-process `--host` mode (so the client
 * process IS the relay — nothing else to run).
 *
 * It only ever sees opaque sealed boxes: it authenticates a signed HELLO so only
 * a key's owner may register that id, then routes SEND payloads by recipient id,
 * queuing for offline recipients. It never decrypts anything.
 */
class RelayServer(private val log: (String) -> Unit = {}) {

    private val connections = ConcurrentHashMap<String, DataOutputStream>()
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

    /** Blocks accepting connections until the socket is closed. */
    fun serve(server: ServerSocket) {
        while (true) {
            val socket = server.accept()
            Thread { handleClient(socket) }.start()
        }
    }

    private fun handleClient(socket: Socket) {
        var id: String? = null
        var output: DataOutputStream? = null
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            output = DataOutputStream(socket.getOutputStream().buffered())

            val hello = JSONObject(String(readFrame(input)))
            if (hello.getString("t") != "hello" || !verifyHello(hello)) {
                socket.close(); return
            }
            id = hello.getString("id")
            connections[id] = output
            log("[+] ${id.take(16)}… online (${connections.size} connected)")

            // flush anything queued while they were offline
            queues.remove(id)?.forEach { synchronized(output) { writeFrame(output, it) } }

            while (true) {
                val frame = JSONObject(String(readFrame(input)))
                if (frame.getString("t") != "send") continue
                val to = frame.getString("to")
                val deliver = JSONObject()
                    .put("t", "deliver")
                    .put("from", frame.getString("from"))
                    .put("ts", frame.optLong("ts"))
                    .put("body", frame.getString("body"))
                    .toString().toByteArray()

                val target = connections[to]
                if (target != null) {
                    synchronized(target) { writeFrame(target, deliver) }
                    log("[>] ${to.take(16)}… delivered")
                } else {
                    val q = queues.getOrPut(to) { ConcurrentLinkedQueue() }
                    if (q.size < MAX_QUEUE) q.add(deliver)
                    log("[~] ${to.take(16)}… offline, queued (${q.size})")
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            if (id != null && output != null) connections.remove(id, output)
            runCatching { socket.close() }
            if (id != null) log("[-] ${id.take(16)}… disconnected (${connections.size} connected)")
        }
    }

    private fun verifyHello(hello: JSONObject): Boolean {
        val id = hello.getString("id")
        val ts = hello.optLong("ts")
        val sig = Base64.decode(hello.getString("sig"))
        return OnionIdentity.verify(id, ("hello$id$ts").toByteArray(), sig)
    }

    private fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readInt()
        require(len in 0..MAX_FRAME) { "bad frame length" }
        return ByteArray(len).also { input.readFully(it) }
    }

    companion object {
        const val VIRTUAL_PORT = 9999
        private const val MAX_FRAME = 64 * 1024
        private const val MAX_QUEUE = 200
    }
}
