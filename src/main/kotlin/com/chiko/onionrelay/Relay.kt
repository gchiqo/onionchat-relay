package com.chiko.onionrelay

import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val VIRTUAL_PORT = 9999
private const val MAX_FRAME = 64 * 1024
private const val MAX_QUEUE = 200

// id -> live connection; id -> queued messages for an offline recipient (ephemeral, in memory)
private val connections = ConcurrentHashMap<String, DataOutputStream>()
private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

fun main() = runBlocking {
    val server = ServerSocket(0, 128, InetAddress.getLoopbackAddress())
    val localPort = server.localPort
    val workDir = File("relay-data/tor").absoluteFile.apply { mkdirs() }
    val cacheDir = File("relay-data/cache").absoluteFile.apply { mkdirs() }

    val runtime = TorRuntime.Builder(
        TorRuntime.Environment.Builder(workDir.path.toFile(), cacheDir.path.toFile(), ResourceLoaderTorExec::getOrCreate) { }
    ) {
        config { environment ->
            TorOption.HiddenServiceDir.tryConfigure {
                directory(environment.workDirectory.resolve("hs"))
                version(3)
                port(virtual = VIRTUAL_PORT.toPort()) { target(port = localPort.toPort()) }
            }
        }
        observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { println("[tor] $it") }
        observerStatic(RuntimeEvent.ERROR, OnEvent.Executor.Immediate) { println("[tor-err] $it") }
    }

    println("Starting Tor (first run extracts the tor binary, then bootstraps ~30s)…")
    runtime.startDaemonAsync()

    val hostname = File(workDir, "hs/hostname")
    while (!hostname.exists()) delay(500)
    val onion = hostname.readText().trim()
    println("============================================================")
    println(" RELAY ONION ADDRESS:")
    println("   $onion")
    println(" Put this into both phones' \"Relay address\" field.")
    println(" (give it ~1 min after first start to publish before connecting)")
    println("============================================================")

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
        println("[+] ${id.take(16)}… online (${connections.size} connected)")

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
                println("[>] ${to.take(16)}… delivered")
            } else {
                val q = queues.getOrPut(to) { ConcurrentLinkedQueue() }
                if (q.size < MAX_QUEUE) q.add(deliver)
                println("[~] ${to.take(16)}… offline, queued (${q.size})")
            }
        }
    } catch (_: Exception) {
        // client disconnected
    } finally {
        if (id != null && output != null) connections.remove(id, output)
        runCatching { socket.close() }
        if (id != null) println("[-] ${id.take(16)}… disconnected (${connections.size} connected)")
    }
}

private fun verifyHello(hello: JSONObject): Boolean {
    val id = hello.getString("id")
    val ts = hello.optLong("ts")
    val sig = Base64.decode(hello.getString("sig"))
    return OnionVerify.verify(id, ("hello$id$ts").toByteArray(), sig)
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
