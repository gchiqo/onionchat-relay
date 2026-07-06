# OnionChat

A serverless, anonymous, ephemeral messaging system over Tor v3 onion services.

Your identity is a **self-authenticating** Tor v3 `.onion` address derived from an
Ed25519 key — no accounts, no phone number, no certificate authority. Messages are
end-to-end encrypted (ephemeral-X25519 → AES-256-GCM) and signed by the sender's
identity key. An **untrusted relay** stores-and-forwards opaque ciphertext between
peers; it can neither read messages nor impersonate anyone.

This repository is the JVM side of the project: the **relay** and a **terminal
client** that speaks the exact same protocol as the Android app, so a terminal user
and a phone user interoperate through the same relay.

## Modules

| Module | What it is |
|--------|------------|
| `:core`  | Shared, platform-independent protocol: identity (`OnionIdentity`), sealed-box E2E crypto (`SealedBox`), message envelope (`MessageCodec`), relay wire protocol (`RelayProtocol`), `Base32`. No Android imports — the Android app reuses the same packages. Unit-tested. |
| `:relay` | The untrusted store-and-forward relay. Hosts a Tor v3 onion service by default, or a plain-TCP loopback listener (`--local`) for tests and demos. |
| `:cli`   | The terminal chat client. |

## Protocol in one paragraph

Identity = `base32(ed25519_pub ‖ checksum ‖ 0x03).onion`. To send, the client builds a
JSON envelope `{from, handle, body, ts, x25519, sig}` signed with Ed25519, then wraps it
in a sealed box to the recipient's X25519 key. The opaque bytes go to the relay as a
`SEND` frame; the relay routes by recipient id and pushes a `DELIVER` frame (or queues it
until the recipient reconnects). The recipient opens the box and verifies the signature —
`Seal(Sign(message))`. See `core/.../protocol` and the thesis for details.

## Build

Requires a JDK 21+. Everything is offline-friendly once dependencies are cached.

```bash
./gradlew build            # compile everything + run :core unit tests
./gradlew :core:test       # just the protocol tests
```

## Run — local demo (no Tor, instant)

The fastest way to see two terminals chat (great for a live demo):

```bash
# 1) start a local relay (no Tor) on port 9999
./gradlew :relay:installDist
relay/build/install/relay/bin/relay --local 9999

# 2) in a second terminal — Alice
./gradlew :cli:installDist
cli/build/install/onionchat/bin/onionchat --data /tmp/alice --handle alice --relay 127.0.0.1:9999

# 3) in a third terminal — Bob
cli/build/install/onionchat/bin/onionchat --data /tmp/bob --handle bob --relay 127.0.0.1:9999
```

Each client prints an `/add …` line on start. Paste Bob's `/add` line into Alice's
terminal (and vice-versa), then just type to chat. Messages sent while a peer is offline
are queued by the relay and delivered when they reconnect.

## Run — over Tor (real deployment)

```bash
# start the relay; it publishes a v3 onion service and prints its address
relay/build/install/relay/bin/relay
#  RELAY ONION ADDRESS:  <56-char>.onion

# each client needs a local Tor SOCKS proxy (system tor on 127.0.0.1:9050 by default)
onionchat --relay <relay-address>.onion --handle alice
onionchat --relay <relay-address>.onion --socks 127.0.0.1:9050 --handle bob
```

The Android app connects to the very same relay onion, so **phone ↔ terminal** works
with no changes.

## CLI commands

```
/me                        show your onion + x25519 + a shareable /add line
/add <onion> <x25519> [h]  add/refresh a contact and make them the recipient
/to <index|onion>          switch the active recipient
/list                      list contacts (* = active)
/name <handle>             change your display handle
<text>                     send to the active recipient
/help    /quit
```

Identity seed, handle, and contacts are stored under `--data` (default
`~/.onionchat-cli`). The seed is the only long-term secret; keep it to keep your address.

## Security notes

- The relay only ever sees sealed boxes; it cannot read message content.
- A `HELLO` registration is signed, so the relay only lets the key owner claim an address.
- Conversations in the Android app are ephemeral (memory-only). The CLI persists contacts
  and the identity seed for convenience; message history is not written to disk beyond your
  terminal's own scrollback.
- Out of scope: a global passive traffic-correlation adversary, and endpoint compromise.

See the thesis for the full design, threat model, and evaluation.
