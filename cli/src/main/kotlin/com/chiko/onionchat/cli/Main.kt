package com.chiko.onionchat.cli

import com.chiko.onionchat.crypto.OnionIdentity
import com.chiko.onionchat.crypto.SealedBox
import com.chiko.onionchat.model.Contact
import com.chiko.onionchat.protocol.MessageCodec
import com.chiko.onionchat.protocol.RelayProtocol
import org.bouncycastle.util.encoders.Base64
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

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
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var dataDir = File(System.getProperty("user.home"), ".onionchat-cli")
            var relay: String? = null
            var socksHost = "127.0.0.1"
            var socksPort = 9050
            var handle: String? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--data"   -> args.getOrNull(++i)?.let { dataDir = File(it) }
                    "--relay"  -> relay = args.getOrNull(++i)
                    "--handle" -> handle = args.getOrNull(++i)
                    "--socks"  -> args.getOrNull(++i)?.let {
                        val (h, p) = it.splitHostPort(9050); socksHost = h; socksPort = p
                    }
                }
                i++
            }
            return Options(dataDir, relay, socksHost, socksPort, handle)
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

    fun run() {
        opts.handleOverride?.let { store.handle = it }
        printBanner()
        if (opts.relay != null) {
            Thread(::connectLoop, "relay-conn").apply { isDaemon = true; start() }
        } else {
            sys("no --relay given: offline mode (identity only, cannot send/receive)")
        }
        commandLoop()
    }

    // ---------------- connection ----------------

    private fun connectLoop() {
        val relay = opts.relay ?: return
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

        Start:
          onionchat --relay <addr> [--handle <name>] [--data <dir>] [--socks host:port]
          <addr> = a .onion (dialled via Tor SOCKS, default 127.0.0.1:9050)
                   or host:port for a local relay (e.g. 127.0.0.1:9999)

        Commands:
          /me                       show your onion + x25519 + a shareable /add line
          /add <onion> <x25519> [h] add/refresh a contact and make them the recipient
          /to <index|onion>         switch the active recipient
          /list                     list contacts (* = active)
          /name <handle>            change your display handle
          <text>                    send <text> to the active recipient
          /help                     this help
          /quit                     exit
    """.trimIndent())
}
