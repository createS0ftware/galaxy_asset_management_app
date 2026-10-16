package com.galaxy.assettrail.core.payload

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Hashes {
    private val random = SecureRandom()

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256(text: String): ByteArray = sha256(text.toByteArray(Charsets.UTF_8))

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd-length hex" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** "2e88…9c51": how the screens abbreviate a transaction or payload hash. */
    fun short(hex: String): String = if (hex.length <= 8) hex else hex.take(4) + "…" + hex.takeLast(4)
}
