<div align="center">

# 🧅 OnionChat

### Serverless · Anonymous · Ephemeral messaging over Tor v3 onion services

**No accounts. No phone number. No server that can read your messages.**
Your identity is a self‑authenticating `.onion` address derived from an Ed25519 key —
the cryptography *is* the registration.

[![release](https://img.shields.io/github/v/release/gchiqo/onionchat-relay?color=7c3aed&label=release)](https://github.com/gchiqo/onionchat-relay/releases/latest)
![platforms](https://img.shields.io/badge/clients-Android%20·%20Linux%20·%20macOS%20·%20Windows%20·%20Termux%20·%20FreeBSD-4c1d95)
![kotlin](https://img.shields.io/badge/Kotlin-JVM%20%2B%20Android-7c3aed)
![tor](https://img.shields.io/badge/Tor-v3%20onion%20services-a855f7)
![e2e](https://img.shields.io/badge/E2E-X25519%20%2F%20AES--256--GCM-34d399)

📱 **[Download](https://github.com/gchiqo/onionchat-relay/releases/latest)** ·
🎤 **[Slides](https://gchiqo.github.io/onionchat-relay/OnionChat-Presentation.html)** ·
📄 **[Thesis](https://gchiqo.github.io/onionchat-relay/OnionChat-Thesis.html)** ·
🌐 **[Project page](https://gchiqo.github.io/onionchat-relay/)**

</div>

---

## Why

Every mainstream messenger ties your identity to a **phone number**, routes everything
through a **central server**, and keeps a **history** that can be seized. Even
end‑to‑end encryption only protects *what* you say — not *who* you are, *who* you talk
to, or the company that can be compelled to hand over metadata. OnionChat removes the
account, the trusted server, and the stored history from the architecture entirely.

## What makes it different

- 🔑 **Identity = a key, not a number.** Your address is `base32(ed25519_pub ‖ …).onion` — self‑authenticating, no certificate authority, no registration.
- 🌐 **Truly serverless (default).** Each device hosts its own onion and connects to contacts **directly** over Tor. An **untrusted relay** is *optional*, only for offline delivery — and it still can't read a thing.
- 🔒 **Sealed + signed.** Every message: fresh ephemeral‑X25519 → AES‑256‑GCM, then Ed25519‑signed. Confidential, authentic, per‑message forward secrecy.
- 💨 **Ephemeral.** Conversations live in memory and vanish on exit. Nothing to seize.
- 🧅 **Metadata‑resistant.** All traffic over Tor; no phone number to target.
- 💻 **One protocol, three clients.** Android app, terminal client, and relay all share the same core — phone ⇄ phone, phone ⇄ terminal, terminal ⇄ terminal.

## How it works

```
  ANDROID / TERMINAL A                                    ANDROID / TERMINAL B
  ┌──────────────────┐                                    ┌──────────────────┐
  │ OnionIdentity     │        Seal( Sign( msg ) )         │ OnionIdentity     │
  │ ed25519 → .onion  │  ═══════════════════════════════▶  │ SealedBox.open    │
  │ SealedBox.seal    │        direct, over Tor            │ verify signature  │
  └──────────────────┘        (no server between)          └──────────────────┘
        each device hosts its own v3 onion service and dials the peer's onion
        ── optional untrusted relay only adds offline store‑and‑forward ──
```

Full design, threat model and evaluation: **[the thesis](https://gchiqo.github.io/onionchat-relay/OnionChat-Thesis.html)**.

## Quick start

**📱 Phone ↔ Phone — no relay, no setup**
Install the [APK](https://github.com/gchiqo/onionchat-relay/releases/latest), leave the **Relay** field empty, wait for **`Reachable ✓`**, then use the split‑screen **scan** to add each other. Done.

**💻 Terminal ↔ Phone — no relay on the phone** (needs a local Tor with a control port)
```bash
unzip onionchat-cli-*.zip && cd onionchat-*
bin/onionchat --p2p --control-password <pw> --handle alice   # prints a QR
```
On the phone: leave Relay empty, scan that QR, chat. See the [release page](https://github.com/gchiqo/onionchat-relay/releases/latest) for the one‑time `torrc` setup.

**⚡ 60‑second local demo — no Tor at all**
```bash
./demo.sh          # two terminals, one process hosts the relay, a live conversation
```

## Repository layout

| Module | What it is |
|--------|------------|
| **`:core`** | Shared, platform‑independent protocol: `OnionIdentity`, `SealedBox`, `MessageCodec`, `RelayProtocol`, `RelayServer`, `Base32`. Unit‑tested. Reused by every client. |
| **`:relay`** | The untrusted store‑and‑forward relay — a Tor v3 onion service (or `--local` plain TCP). |
| **`:cli`** | The terminal client (`--p2p` serverless, `--relay`, or `--host`). |
| **Android app** | Separate repo → **[github.com/gchiqo/onionchat](https://github.com/gchiqo/onionchat)** (Jetpack Compose; same `:core` protocol). |

## Terminal commands

```
onionchat --p2p                    serverless direct P2P (talks to the app with no relay)
onionchat --relay <addr>           connect through a relay (.onion via Tor, or host:port)
onionchat --host                   run a relay in this process and be reachable as an onion
onionchat --host --local 9999      host over plain TCP (LAN / testing, no Tor)

  /me   /qr   /add <onion> <x25519> [name]   /to <i|onion>   /list   /name <h>   /help   /quit
```

## Security notes

- The relay (if used) only ever sees **end‑to‑end sealed boxes** — content is unreadable, and a signed `HELLO` stops it from claiming someone else's identity.
- App conversations are **ephemeral** (memory only). The identity seed + contacts persist.
- **Out of scope:** a global traffic‑correlation adversary, and a compromised device/OS (e.g. Pegasus — no messenger can stop that; OnionChat's answer is to store almost nothing and keep a minimal attack surface).
- Keep devices on **automatic date/time** — Tor v3 onions are time‑sensitive.

## Build

```bash
./gradlew build         # compile everything + run the :core protocol tests
./gradlew :cli:installDist :relay:installDist
```

---

<div align="center">
<sub>Bachelor's thesis · Giorgi Chikovani · Georgian Technical University · 2026 &nbsp;·&nbsp;
<a href="https://gchiqo.github.io/onionchat-relay/">project page</a></sub>
</div>
