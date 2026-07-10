package com.chiko.onionchat.cli

import com.chiko.onionchat.crypto.OnionIdentity
import com.chiko.onionchat.crypto.SealedBox
import com.chiko.onionchat.model.Contact
import com.chiko.onionchat.protocol.MessageCodec
import com.chiko.onionchat.protocol.RelayProtocol
import com.chiko.onionchat.relay.RelayServer
import org.bouncycastle.util.encoders.Base64
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * OnionChat terminal client.
 *
 * Speaks the identical protocol to the Android app (shared :core module), so a
 * terminal user and a phone user interoperate through the same untrusted relay.
 * Every message is an end-to-end sealed box signed by the sender's Tor v3
 * identity; the relay only routes opaque ciphertext.
 *
 * Transport:
 *   - relay address ending in ".onion"  -> dialled through Tor's SOCKS proxy
 *   - relay address "host:port"          -> plain TCP (local relay, for demos/tests)
 */
private const val VIRTUAL_PORT = 9999
private const val RECONNECT_MS = 3_000L

fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        printUsage(); return
    }
    val opts = Options.parse(args)
    Cli(opts).run()
}

private data class Options(
    val dataDir: File,
    val relay: String?,       // onion or host:port; null = offline (identity only)
    val socksHost: String,
    val socksPort: Int,
    val handleOverride: String?,
    val host: Boolean,        // run the relay in this process ("nothing else needed")
    val localPort: Int?,      // host without Tor on this TCP port (LAN/testing); null = publish onion
    val controlHost: String,
    val controlPort: Int,
    val controlPassword: String?,
    val p2p: Boolean,         // serverless direct P2P — interoperates with the app's empty-relay mode
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var dataDir = File(System.getProperty("user.home"), ".onionchat-cli")
            var relay: String? = null
            var socksHost = "127.0.0.1"
            var socksPort = 9050
            var handle: String? = null
            var host = false
            var localPort: Int? = null
            var controlHost = "127.0.0.1"
            var controlPort = 9051
            var controlPassword: String? = null
            var p2p = false
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--data"   -> args.getOrNull(++i)?.let { dataDir = File(it) }
                    "--relay"  -> relay = args.getOrNull(++i)
                    "--handle" -> handle = args.getOrNull(++i)
                    "--p2p"    -> p2p = true
                    "--host"   -> host = true
                    "--local"  -> {
                        // optional following port; default to the well-known virtual port
                        val next = args.getOrNull(i + 1)?.toIntOrNull()
                        localPort = next ?: 9999
                        if (next != null) i++
                        host = true
                    }
                    "--control" -> args.getOrNull(++i)?.let {
                        val (h, p) = it.splitHostPort(9051); controlHost = h; controlPort = p
                    }
                    "--control-password" -> controlPassword = args.getOrNull(++i)
                    "--socks"  -> args.getOrNull(++i)?.let {
                        val (h, p) = it.splitHostPort(9050); socksHost = h; socksPort = p
                    }
                }
                i++
            }
            return Options(dataDir, relay, socksHost, socksPort, handle, host, localPort, controlHost, controlPort, controlPassword, p2p)
        }
    }
}

private class Cli(private val opts: Options) {

    private val store = LocalStore(opts.dataDir)
    private val identity: OnionIdentity = store.loadOrCreateIdentity()
    private var handle: String = opts.handleOverride ?: store.handle ?: "cli"
    private val contacts = LinkedHashMap<String, Contact>().apply { putAll(store.loadContacts()) }

    private val writeLock = Any()
    @Volatile private var out: java.io.OutputStream? = null
    @Volatile private var running = true
    private var activePeer: String? = contacts.keys.firstOrNull()

    // effective relay address (may be set by --host to our own in-process relay)
    private var relay: String? = opts.relay
    @Volatile private var torControl: TorControl? = null
    private val p2pPeers = ConcurrentHashMap<String, Socket>()   // outbound conns to peers

    fun run() {
        opts.handleOverride?.let { store.handle = it }
        printBanner()
        if (opts.p2p) {
            startP2p()
        } else {
            if (opts.host) startHost()
            if (relay != null) {
                Thread(::connectLoop, "relay-conn").apply { isDaemon = true; start() }
            } else {
                sys("no --relay/--host/--p2p given: offline mode (identity only, cannot send/receive)")
            }
        }
        commandLoop()
    }

    // ---------------- serverless P2P (interoperates with the app's empty-relay mode) ----------------

    private fun startP2p() {
        val server = ServerSocket(0, 128, InetAddress.getLoopbackAddress())
        val port = server.localPort
        val onion = try {
            sys("publishing your identity onion via Tor control (${opts.controlHost}:${opts.controlPort})…")
            val tc = TorControl.connect(opts.controlHost, opts.controlPort, opts.controlPassword)
            torControl = tc
            tc.publishOnion(VIRTUAL_PORT, port, torIdentityKeyBlob())
        } catch (e: Exception) {
            sys("could not publish onion: ${e.message}")
            sys("start Tor with a control port (torrc: 'ControlPort 9051' + a HashedControlPassword)")
            sys("and pass --control-password <pw>. Tor's SOCKS (9050) is also used to reach peers.")
            exitProcess(2)
        }
        if (onion != identity.onionAddress) sys("warning: hosted onion != identity")
        Thread({ acceptP2p(server) }, "p2p-accept").apply { isDaemon = true; start() }
        println("==============================================================")
        println("  SERVERLESS P2P — no relay. You're reachable at your identity onion.")
        println("  On the PHONE: leave the Relay field EMPTY, just scan this QR:")
        println("==============================================================")
        showQr()
        println("  (give the onion ~1–2 min to publish before the phone connects)")
    }

    private fun acceptP2p(server: ServerSocket) {
        while (running) {
            val socket = try { server.accept() } catch (e: Exception) { break }
            Thread({ readP2pPeer(socket) }, "p2p-peer").apply { isDaemon = true; start() }
        }
    }

    private fun readP2pPeer(socket: Socket) {
        try {
            val input = socket.getInputStream().buffered()
            while (running) onDeliver(MessageCodec.readFrame(input))   // frame = an E2E sealed box
        } catch (_: Exception) {
        } finally { runCatching { socket.close() } }
    }

    private fun sendP2p(text: String) {
        val peer = activePeer ?: run { sys("no recipient — /add <onion> <x25519> or /to first"); return }
        val contact = contacts[peer] ?: run { sys("unknown contact: ${short(peer)}"); return }
        if (contact.x25519.isBlank()) { sys("no encryption key for ${short(peer)} — need their /me or QR"); return }
        val sealed = SealedBox.seal(Base64.decode(contact.x25519), MessageCodec.encode(identity, handle, text, now()))
        repeat(2) { attempt ->
            try {
                val socket = p2pPeers[peer]?.takeIf { !it.isClosed } ?: dialPeer(peer).also { p2pPeers[peer] = it }
                synchronized(socket) { MessageCodec.writeFrame(socket.getOutputStream(), sealed) }
                println("you -> ${short(peer)}: $text")
                return
            } catch (e: Exception) {
                p2pPeers.remove(peer)?.let { runCatching { it.close() } }
                if (attempt == 1) sys("send failed — peer offline or still publishing? (${e.message})")
            }
        }
    }

    private fun dialPeer(peer: String): Socket {
        require(peer.endsWith(".onion")) { "peer must be an onion address" }
        return Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(opts.socksHost, opts.socksPort))).apply {
            connect(InetSocketAddress.createUnresolved(peer, VIRTUAL_PORT), 60_000)
        }
    }

    // ---------------- host mode (run the relay in-process) ----------------

    private fun startHost() {
        val server = ServerSocket(opts.localPort ?: 0, 128, InetAddress.getLoopbackAddress())
        val port = server.localPort
        Thread({ RelayServer { line -> hostLog(line) }.serve(server) }, "relay-host")
            .apply { isDaemon = true; start() }

        val overTor = opts.localPort == null
        val advertised = if (!overTor) {
            "127.0.0.1:$port"            // no Tor: reachable on this machine / LAN
        } else {
            try {
                sys("publishing your identity onion via Tor control (${opts.controlHost}:${opts.controlPort})…")
                val tc = TorControl.connect(opts.controlHost, opts.controlPort, opts.controlPassword)
                torControl = tc
                // host on the identity key, so the hosted onion IS our identity —
                // one QR is both "where to reach me" and "who I am".
                val onion = tc.publishOnion(RelayServer.VIRTUAL_PORT, port, torIdentityKeyBlob())
                if (onion != identity.onionAddress)
                    sys("warning: hosted onion ($onion) != identity (${identity.onionAddress})")
                onion
            } catch (e: Exception) {
                sys("could not publish onion: ${e.message}")
                sys("enable Tor's control port (torrc: 'ControlPort 9051' + 'CookieAuthentication 1'),")
                sys("or host without Tor for LAN/testing: onionchat --host --local 9999")
                exitProcess(2)
            }
        }
        relay = "127.0.0.1:$port"        // we reach our own relay directly over loopback
        println("==============================================================")
        println("  HOSTING in THIS process — no separate relay needed.")
        println("  Peers connect with:   --relay $advertised")
        println(if (overTor) "  (this is your identity onion, reachable over Tor — keep running)"
                else "  (local mode: reachable on this machine/LAN, no Tor)")
        println("==============================================================")
        if (overTor) {
            println("  Phone: scan this QR, set it as the Relay address, then Connect:")
            showQr()
        }
    }

    /** Tor ED25519-V3 key spec derived from our identity seed (so onion == identity). */
    private fun torIdentityKeyBlob(): String {
        val h = MessageDigest.getInstance("SHA-512").digest(identity.exportSeed())  // 64 bytes
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 63) or 64).toByte()
        return "ED25519-V3:" + Base64.toBase64String(h)   // clamped a(32) || RH(32)
    }

    private fun myQrPayload() = QrTerminal.contactPayload(identity.onionAddress, handle, myX25519())

    private fun showQr() {
        println(QrTerminal.render(myQrPayload()))
        println("  ${identity.onionAddress}")
    }

    private fun hostLog(line: String) { println("\n· relay $line"); prompt() }

    // ---------------- connection ----------------

    private fun connectLoop() {
        val relay = this.relay ?: return
        while (running) {
            try {
                openSocket(relay).use { socket ->
                    val input = socket.getInputStream().buffered()
                    val output = socket.getOutputStream().buffered()
                    synchronized(writeLock) {
                        MessageCodec.writeFrame(output, RelayProtocol.hello(identity, now()))
                        out = output
                    }
                    sys("connected to relay ($relay)")
                    while (running) {
                        val frame = RelayProtocol.parse(MessageCodec.readFrame(input))
                        if (frame.type == RelayProtocol.DELIVER) onDeliver(frame.body)
                    }
                }
            } catch (e: Exception) {
                if (running) sys("relay connection lost: ${e.message}")
            } finally {
                out = null
            }
            if (running) Thread.sleep(RECONNECT_MS)
        }
    }

    private fun openSocket(relay: String): Socket =
        if (relay.endsWith(".onion")) {
            // dial the onion through Tor's SOCKS proxy (address resolved by Tor)
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(opts.socksHost, opts.socksPort))).apply {
                connect(InetSocketAddress.createUnresolved(relay, VIRTUAL_PORT), 60_000)
            }
        } else {
            val (host, port) = relay.splitHostPort(VIRTUAL_PORT)
            Socket().apply { connect(InetSocketAddress(host, port), 15_000) }
        }

    // ---------------- receiving ----------------

    private fun onDeliver(sealed: ByteArray) {
        val inner = runCatching { SealedBox.open(identity.x25519Private(), sealed) }.getOrNull() ?: return
        val msg = MessageCodec.decode(inner) ?: return   // null => bad signature, drop
        // learn/refresh the sender so we can reply
        upsertContact(Contact(msg.fromOnion, msg.handle, msg.x25519))
        if (activePeer == null) activePeer = msg.fromOnion
        println("\n${msg.handle} [${short(msg.fromOnion)}]: ${msg.body}")
        prompt()
    }

    // ---------------- sending ----------------

    private fun send(text: String) {
        if (opts.p2p) { sendP2p(text); return }
        val peer = activePeer ?: run { sys("no recipient set — /to <onion> or /add first"); return }
        val contact = contacts[peer] ?: run { sys("unknown contact: $peer"); return }
        if (contact.x25519.isBlank()) { sys("no encryption key for $peer (need their /me line)"); return }
        val output = out ?: run { sys("not connected to a relay yet"); return }
        val ts = now()
        val inner = MessageCodec.encode(identity, handle, text, ts)
        val sealed = SealedBox.seal(Base64.decode(contact.x25519), inner)
        val frame = RelayProtocol.send(identity.onionAddress, peer, ts, sealed)
        try {
            synchronized(writeLock) { MessageCodec.writeFrame(output, frame) }
            println("you -> ${short(peer)}: $text")
        } catch (e: Exception) {
            sys("send failed: ${e.message}")
        }
    }

    // ---------------- command loop ----------------

    private fun commandLoop() {
        prompt()
        while (running) {
            val line = readlnOrNull() ?: break
            val t = line.trim()
            when {
                t.isEmpty() -> {}
                t == "/quit" || t == "/exit" -> { running = false; break }
                t == "/help" -> printUsage()
                t == "/me"   -> printMe()
                t == "/qr"   -> showQr()
                t == "/list" -> printContacts()
                t.startsWith("/name ") -> { handle = t.removePrefix("/name ").trim().ifBlank { "cli" }; store.handle = handle; sys("handle set to \"$handle\"") }
                t.startsWith("/add ")  -> cmdAdd(t.removePrefix("/add ").trim())
                t.startsWith("/to ")   -> cmdTo(t.removePrefix("/to ").trim())
                t.startsWith("/") -> sys("unknown command: $t  (/help)")
                else -> send(t)
            }
            if (running) prompt()
        }
        sys("bye")
    }

    private fun cmdAdd(rest: String) {
        // format: <onion> <x25519-base64> [handle...]
        val parts = rest.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2 || !parts[0].endsWith(".onion")) {
            sys("usage: /add <onion> <x25519-base64> [handle]"); return
        }
        val c = Contact(parts[0], parts.getOrNull(2)?.ifBlank { "unknown" } ?: "unknown", parts[1])
        upsertContact(c)
        activePeer = c.onion
        sys("added ${c.handle} [${short(c.onion)}] and set as recipient")
    }

    private fun cmdTo(arg: String) {
        val target = arg.toIntOrNull()?.let { contacts.values.toList().getOrNull(it) }?.onion
            ?: contacts.keys.firstOrNull { it == arg || short(it) == arg }
            ?: arg.takeIf { it.endsWith(".onion") && contacts.containsKey(it) }
        if (target == null) { sys("no such contact: $arg (/list)"); return }
        activePeer = target
        sys("now talking to ${contacts[target]?.handle} [${short(target)}]")
    }

    // ---------------- helpers ----------------

    private fun upsertContact(c: Contact) {
        val existing = contacts[c.onion]
        val merged = if (existing == null) c else existing.copy(
            handle = c.handle.takeIf { it.isNotBlank() && it != "unknown" } ?: existing.handle,
            x25519 = existing.x25519.ifBlank { c.x25519 },
        )
        contacts[c.onion] = merged
        store.saveContacts(contacts.values)
    }

    private fun printBanner() {
        println("==============================================================")
        println("  OnionChat — terminal client")
        println("  you are: $handle")
        println("  onion  : ${identity.onionAddress}")
        println("==============================================================")
        println("  Share your identity with a peer (they run this /add line):")
        println("  /add ${identity.onionAddress} ${myX25519()} $handle")
        println("  Type /help for commands.")
        println("==============================================================")
    }

    private fun printMe() {
        println("handle : $handle")
        println("onion  : ${identity.onionAddress}")
        println("x25519 : ${myX25519()}")
        println("share  : /add ${identity.onionAddress} ${myX25519()} $handle")
    }

    private fun printContacts() {
        if (contacts.isEmpty()) { sys("no contacts yet — /add <onion> <x25519> [handle]"); return }
        contacts.values.forEachIndexed { i, c ->
            val mark = if (c.onion == activePeer) "*" else " "
            println(" $mark [$i] ${c.handle}  ${short(c.onion)}  ${if (c.x25519.isBlank()) "(no key)" else ""}")
        }
    }

    private fun myX25519() = Base64.toBase64String(identity.x25519Public)
    private fun prompt() { print("> "); System.out.flush() }
    private fun sys(m: String) { println("* $m") }

    private fun now() = System.currentTimeMillis()
    private fun short(onion: String) = onion.take(10) + "…"
}

/** File-backed identity seed, handle, and contact list under a data directory. */
private class LocalStore(private val dir: File) {
    private val seedFile = File(dir, "seed")
    private val handleFile = File(dir, "handle")
    private val contactsFile = File(dir, "contacts")

    init { dir.mkdirs() }

    fun loadOrCreateIdentity(): OnionIdentity {
        if (seedFile.exists()) return OnionIdentity.fromSeed(Base64.decode(seedFile.readText().trim()))
        return OnionIdentity.generate().also { seedFile.writeText(Base64.toBase64String(it.exportSeed())) }
    }

    var handle: String?
        get() = handleFile.takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
        set(v) { if (v != null) handleFile.writeText(v) }

    fun loadContacts(): Map<String, Contact> {
        if (!contactsFile.exists()) return emptyMap()
        return contactsFile.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 3 && p[0].endsWith(".onion")) p[0] to Contact(p[0], p[2], p[1]) else null
        }.toMap()
    }

    fun saveContacts(contacts: Collection<Contact>) {
        contactsFile.writeText(contacts.joinToString("\n") { "${it.onion}\t${it.x25519}\t${it.handle}" })
    }
}

private fun String.splitHostPort(default: Int): Pair<String, Int> {
    val idx = lastIndexOf(':')
    return if (idx <= 0) this to default
    else substring(0, idx) to (substring(idx + 1).toIntOrNull() ?: default)
}

private fun printUsage() {
    println("""
        OnionChat terminal client

        Connect to a relay:
          onionchat --relay <addr> [--handle <name>] [--data <dir>] [--socks host:port]
          <addr> = a .onion (dialled via Tor SOCKS, default 127.0.0.1:9050)
                   or host:port for a local relay (e.g. 127.0.0.1:9999)

        Serverless P2P — talk to the phone with NO relay (phone: leave Relay empty):
          onionchat --p2p [--control-password <pw>] [--socks host:port]
                      hosts your identity onion and dials contacts directly, exactly
                      like the app's serverless mode. Needs Tor with a control port
                      (torrc: ControlPort 9051 + a HashedControlPassword) and SOCKS.
                      Share the printed QR / add line; the phone just scans it.

        Host the relay in THIS process (nothing else to run):
          onionchat --host [--control host:port] [--control-password <pw>]
                      publishes an onion via your running Tor's control port;
                      share the printed --relay <onion> line with your peers.
          onionchat --host --local [port]
                      host without Tor on 127.0.0.1:<port> (LAN / testing).

        Commands:
          /me                       show your onion + x25519 + a shareable /add line
          /qr                       show your identity as a QR the phone app can scan
          /add <onion> <x25519> [h] add/refresh a contact and make them the recipient
          /to <index|onion>         switch the active recipient
          /list                     list contacts (* = active)
          /name <handle>            change your display handle
          <text>                    send <text> to the active recipient
          /help                     this help
          /quit                     exit
    """.trimIndent())
}
