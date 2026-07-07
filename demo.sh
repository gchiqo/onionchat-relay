#!/usr/bin/env bash
#
# demo.sh — a self-contained two-"terminal" OnionChat conversation.
#
# Alice runs the relay IN HER OWN PROCESS (--host), Bob just connects to her, and
# they chat both ways. Uses a local loopback relay (no Tor) so it's instant and
# reproducible anywhere; over real Tor it's the identical code path with the onion
# published via ADD_ONION.
#
# Usage:  ./demo.sh
#
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-9994}"
CLI="cli/build/install/onionchat/bin/onionchat"
DIR="$(mktemp -d)"
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$DIR"' EXIT

# 1. Build the CLI launcher if needed.
if [[ ! -x "$CLI" ]]; then
  echo "Building the CLI (one-time)…"
  ./gradlew -q :cli:installDist
fi

echo "== OnionChat demo: Alice hosts the relay in her process; Bob connects. =="
echo

# 2. Create Alice's identity up front so Bob has her /add line (onion + x25519 key).
printf '/me\n/quit\n' | "$CLI" --data "$DIR/alice" --handle alice > "$DIR/alice_me" 2>&1
ADD_LINE="$(grep -m1 '^share  : /add ' "$DIR/alice_me" | sed 's/^share  : \/add //')"
A_ONION="$(echo "$ADD_LINE" | awk '{print $1}')"
A_X25519="$(echo "$ADD_LINE" | awk '{print $2}')"
echo "Alice's identity onion: $A_ONION"
echo

mkfifo "$DIR/alice_in" "$DIR/bob_in"

# 3. TERMINAL 1 — Alice hosts the relay in-process (no separate relay), on loopback.
"$CLI" --host --local "$PORT" --data "$DIR/alice" --handle alice < "$DIR/alice_in" > "$DIR/alice_out" 2>&1 &
exec 3> "$DIR/alice_in"
# 4. TERMINAL 2 — Bob connects to Alice's in-process relay.
"$CLI" --relay "127.0.0.1:$PORT" --data "$DIR/bob" --handle bob < "$DIR/bob_in" > "$DIR/bob_out" 2>&1 &
exec 4> "$DIR/bob_in"
sleep 3   # let both connect

# 5. A scripted, deterministic back-and-forth.
echo "/add $A_ONION $A_X25519 alice" >&4;         sleep 1.5   # Bob adds Alice
echo "hey alice, bob here over tor 👋"     >&4;    sleep 2.5   # Bob  -> Alice
echo "hi bob! nothing but our two terminals 🎉" >&3; sleep 2.5 # Alice-> Bob (auto-learned Bob)
echo "is this really end-to-end encrypted?" >&4;  sleep 2.5   # Bob  -> Alice
echo "yep — a fresh sealed box per message" >&3;  sleep 2.5   # Alice-> Bob
echo "/list" >&4;                                 sleep 1.5   # Bob lists contacts
echo "/quit" >&3; echo "/quit" >&4;               sleep 1
exec 3>&-; exec 4>&-; sleep 1

echo "==================== TERMINAL 1 — ALICE (host) ===================="
cat "$DIR/alice_out"
echo
echo "==================== TERMINAL 2 — BOB ===================="
cat "$DIR/bob_out"
