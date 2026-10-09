package com.cwbridge.android.engine

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Minimal RFC 6238 TOTP (HMAC-SHA1, 30s, 6 digits) with Base32 secret support.
 * No external deps — uses only javax.crypto.
 */
object Totp {

    private const val PERIOD_SEC = 30L
    private const val DIGITS = 6
    private val MOD = 10.0.pow(DIGITS).toLong()

    /** Current TOTP code for the given Base32 secret. */
    fun code(secretBase32: String, timeMs: Long = System.currentTimeMillis()): String {
        val key = decodeBase32(secretBase32)
        val counter = timeMs / 1000L / PERIOD_SEC
        return hotp(key, counter)
    }

    /**
     * Returns true if [userCode] matches the current window or ±[window] adjacent windows.
     * Default window=1 (accepts previous / current / next 30s slot).
     */
    fun verify(
        secretBase32: String,
        userCode: String,
        window: Int = 1,
        timeMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cleaned = userCode.filter { it.isDigit() }
        if (cleaned.length != DIGITS) return false
        val key = decodeBase32(secretBase32)
        val counter = timeMs / 1000L / PERIOD_SEC
        for (i in -window..window) {
            if (hotp(key, counter + i) == cleaned) return true
        }
        return false
    }

    private fun hotp(key: ByteArray, counter: Long): String {
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % MOD
        return otp.toString().padStart(DIGITS, '0')
    }

    /** RFC 4648 Base32 (no padding required). */
    fun decodeBase32(input: String): ByteArray {
        val cleaned = input.trim().uppercase().replace("=", "").filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }
        require(cleaned.isNotEmpty()) { "empty or invalid base32 secret" }

        val out = ArrayList<Byte>((cleaned.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in cleaned) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in '2'..'7' -> ch - '2' + 26
                else -> continue
            }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add((buffer shr (bitsLeft - 8)).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
