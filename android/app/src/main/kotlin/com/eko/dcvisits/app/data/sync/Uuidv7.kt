package com.eko.dcvisits.app.data.sync

import java.security.SecureRandom
import java.util.UUID

/**
 * UUIDv7 — time-ordered ids (C1: "client-generated UUIDv7 for field-originated
 * records"). Layout: 48-bit big-endian Unix-millis, version nibble 0x7,
 * 12 + 62 random bits, RFC-4122 variant.
 */
object Uuidv7 {
    private val rng = SecureRandom()

    fun next(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        rng.nextBytes(bytes)

        bytes[0] = ((nowMs ushr 40) and 0xFF).toByte()
        bytes[1] = ((nowMs ushr 32) and 0xFF).toByte()
        bytes[2] = ((nowMs ushr 24) and 0xFF).toByte()
        bytes[3] = ((nowMs ushr 16) and 0xFF).toByte()
        bytes[4] = ((nowMs ushr 8) and 0xFF).toByte()
        bytes[5] = (nowMs and 0xFF).toByte()

        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()          // version 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()          // variant

        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(msb, lsb).toString()
    }
}
