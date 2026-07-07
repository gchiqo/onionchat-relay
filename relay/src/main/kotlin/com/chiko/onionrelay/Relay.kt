package com.chiko.onionrelay

import com.chiko.onionchat.relay.RelayServer
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
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

private const val VIRTUAL_PORT = RelayServer.VIRTUAL_PORT

fun main(args: Array<String>) {
    val localIdx = args.indexOf("--local")
    if (localIdx >= 0) {
        val port = args.getOrNull(localIdx + 1)?.toIntOrNull() ?: VIRTUAL_PORT
        runLocal(port)
    } else {
        runTor()
    }
}

/**
 * Plain-TCP loopback relay: no Tor, no onion service. Same routing logic as the
 * Tor path, so CLI and app clients (pointed at 127.0.0.1:<port>) interoperate.
 * Intended for offline testing and a fast, reliable live demo.
 */
private fun runLocal(port: Int) {
    val server = ServerSocket(port, 128, InetAddress.getLoopbackAddress())
    println("============================================================")
    println(" LOCAL RELAY (no Tor) listening on 127.0.0.1:${server.localPort}")
    println(" Connect clients with:  --relay 127.0.0.1:${server.localPort}")
    println("============================================================")
    RelayServer(::println).serve(server)
}

private fun runTor() = runBlocking {
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

    RelayServer(::println).serve(server)
}
