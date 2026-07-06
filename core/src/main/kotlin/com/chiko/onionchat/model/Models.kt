package com.chiko.onionchat.model

enum class Direction { INCOMING, OUTGOING }

data class Message(
    val id: String,
    val peerOnion: String,
    val handle: String,
    val body: String,
    val sentAt: Long,
    val direction: Direction,
)

data class Contact(
    val onion: String,
    val handle: String,
    /** Recipient's X25519 public key (base64) used to E2E-encrypt to them. */
    val x25519: String = "",
)
