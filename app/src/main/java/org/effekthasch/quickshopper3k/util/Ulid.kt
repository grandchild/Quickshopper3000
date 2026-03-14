package org.effekthasch.quickshopper3k.util

import java.security.SecureRandom

object Ulid {

    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val DECODE = IntArray(128) { -1 }.also { d ->
        ENCODING.forEachIndexed { i, c ->
            d[c.code] = i
            if (c.isLetter()) d[c.lowercaseChar().code] = i
        }
        d['I'.code] = 1; d['i'.code] = 1
        d['L'.code] = 1; d['l'.code] = 1
        d['O'.code] = 0; d['o'.code] = 0
    }
    private val random = SecureRandom()

    fun generate(): String = generate(System.currentTimeMillis())

    fun generate(timestamp: Long): String {
        val chars = CharArray(26)
        encodeTimestamp(timestamp, chars)
        encodeRandom(chars)
        return String(chars)
    }

    fun timestamp(ulid: String): Long {
        var ts = 0L
        for (i in 0 until 10) {
            ts = (ts shl 5) or decodeChar(ulid[i]).toLong()
        }
        return ts
    }

    private fun encodeTimestamp(timestamp: Long, chars: CharArray) {
        var t = timestamp
        for (i in 9 downTo 0) {
            chars[i] = ENCODING[(t and 0x1F).toInt()]
            t = t shr 5
        }
    }

    private fun encodeRandom(chars: CharArray) {
        val bytes = ByteArray(10)
        random.nextBytes(bytes)
        var bitBuffer = 0L
        var bitsInBuffer = 0
        var byteIndex = 0
        for (i in 10 until 26) {
            if (bitsInBuffer < 5) {
                bitBuffer = (bitBuffer shl 8) or (bytes[byteIndex].toLong() and 0xFF)
                bitsInBuffer += 8
                byteIndex++
            }
            bitsInBuffer -= 5
            chars[i] = ENCODING[((bitBuffer shr bitsInBuffer) and 0x1F).toInt()]
        }
    }

    private fun decodeChar(c: Char): Int {
        val code = c.code
        if (code >= 128) return -1
        return DECODE[code]
    }
}
