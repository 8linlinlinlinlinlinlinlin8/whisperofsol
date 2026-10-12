package com.linmjt.whisperofsol.util

/**
 * Base58 encoder/decoder extracted from HomeViewModel for reuse.
 *
 * v1.6.1: Shared utility used by WhisperIdRepository (PDA wallet field
 * verification). Based on the v1.1.6 fix that no longer drops the first
 * byte unconditionally — Solana pubkeys with first nibble >= 8 should
 * keep their leading byte.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val lookup: Map<Char, Int> = HashMap<Char, Int>().also { map ->
        for (i in ALPHABET.indices) map[ALPHABET[i]] = i
    }

    fun decode(s: String): ByteArray {
        // Count leading '1' chars (= leading 0x00 bytes in decoded result)
        var leadingZeros = 0
        for (c in s) {
            if (c == '1') leadingZeros++ else break
        }
        var num = java.math.BigInteger.ZERO
        for (c in s) {
            val d = lookup[c] ?: throw IllegalArgumentException("Invalid base58: $c")
            num = num.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(d.toLong()))
        }
        val raw = num.toByteArray()
        // BigInteger.toByteArray() returns the minimum 2's-complement
        // representation. For positive num, it PREPENDS a leading 0x00 byte
        // whenever the most significant bit of the data is set (so the value
        // isn't interpreted as negative). For a 32-byte Solana pubkey whose
        // first nibble >= 8, raw ends up 33 bytes:
        //   [0x00, MSB, ..., LSB]  ←  raw[0] is the sign byte, not data.
        // Strip raw[0] when raw.size > leadingZeros + 1.
        val dataStart: Int
        val data: ByteArray
        if (raw.isNotEmpty() && raw[0] == 0.toByte() && num.signum() >= 0
            && raw.size > leadingZeros + 1
        ) {
            dataStart = 1
            data = ByteArray(raw.size - 1).also {
                System.arraycopy(raw, 1, it, 0, it.size)
            }
        } else {
            dataStart = 0
            data = raw
        }
        if (data.size + leadingZeros != 32 && (data.size + leadingZeros) != 44) {
            // Not strictly an error — caller can check size. We just return.
        }
        val out = ByteArray(leadingZeros + data.size)
        for (i in data.indices) out[leadingZeros + i] = data[i]
        return out
    }
}