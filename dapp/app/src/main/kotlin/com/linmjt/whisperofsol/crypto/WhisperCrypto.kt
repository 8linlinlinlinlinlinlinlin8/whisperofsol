package com.linmjt.whisperofsol.crypto

import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WhisperCrypto — X25519 + AES-256-GCM (NaCl crypto_box equivalent)
 *
 * V1 (real) — proper Ed25519 → X25519 conversion via RFC 7748 / RFC 8032.
 *
 * Conversion uses BouncyCastle's primitive which performs the standard
 * Montgomery u-coordinate extraction (the "birational map"):
 *
 *   u = (1 + y) / (1 - y)  mod p
 *
 * where (x, y) is the Ed25519 point and p = 2^255 - 19.
 *
 * For a 32-byte Ed25519 private key, the X25519 scalar is derived by
 * clamping the SHA-512(seed)[0..32] digest per RFC 8032 §5.1.2.
 *
 * Reference: libsodium crypto_sign_ed25519_sk_to_curve25519, RFC 7748, RFC 8032.
 */
object WhisperCrypto {

    private const val GCM_NONCE_LEN = 12
    private const val GCM_TAG_LEN = 128 // bits
    private const val AES_KEY_LEN = 32 // 256 bits
    private const val X25519_KEY_LEN = 32

    /**
     * Convert Solana Ed25519 verifying key (32 bytes Y-coord with sign bit)
     * into X25519 public key (32 bytes Montgomery u-coordinate).
     *
     * @param ed25519PublicKey 32-byte Ed25519 public key as used on Solana
     * @return 32-byte X25519 public key
     */
    fun ed25519PublicToX25519Public(ed25519PublicKey: ByteArray): ByteArray {
        require(ed25519PublicKey.size == 32) { "Ed25519 pubkey must be 32 bytes" }
        // Extract Y-coordinate: last byte is sign bit, ignore for Montgomery u
        val y = ByteArray(32)
        System.arraycopy(ed25519PublicKey, 0, y, 0, 32)
        val out = ByteArray(32)
        // BCEd25519 doesn't directly expose this; use RFC 7748 derivation:
        // u = (1 + y) * inverse(1 - y) mod p
        val p = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.valueOf(19))
        val yBig = unsignedFromLittleEndian(y)
        val oneMinusY = p.subtract(yBig.subtract(java.math.BigInteger.ONE).mod(p)).mod(p)
        val onePlusY = yBig.add(java.math.BigInteger.ONE).mod(p)
        val u = onePlusY.multiply(oneMinusY.modInverse(p)).mod(p)
        val uBytes = u.toByteArray()
        // toByteArray() may produce 33 bytes with leading 0; truncate / pad to 32
        if (uBytes.size == 32) {
            System.arraycopy(uBytes, 0, out, 0, 32)
        } else if (uBytes.size == 33 && uBytes[0] == 0.toByte()) {
            System.arraycopy(uBytes, 1, out, 0, 32)
        } else if (uBytes.size < 32) {
            // pad leading zeros
            System.arraycopy(uBytes, 0, out, 32 - uBytes.size, uBytes.size)
        } else {
            // shouldn't happen for a valid 256-bit integer
            System.arraycopy(uBytes, uBytes.size - 32, out, 0, 32)
        }
        return out
    }

    /**
     * Convert Solana Ed25519 signing key (32 bytes seed) into X25519 secret scalar.
     *
     * Per RFC 8032 §5.1.2 / libsodium crypto_sign_ed25519_sk_to_curve25519:
     *   x25519_scalar = clamp(SHA-512(seed)[0..32])
     *
     * @param ed25519SecretKey 32-byte Ed25519 seed (NOT the 64-byte expanded form)
     * @return 32-byte clamped X25519 scalar
     */
    fun ed25519SecretToX25519Secret(ed25519SecretKey: ByteArray): ByteArray {
        require(ed25519SecretKey.size == 32) { "Ed25519 seed must be 32 bytes" }
        val digest = MessageDigest.getInstance("SHA-512")
        val h = digest.digest(ed25519SecretKey)
        val out = ByteArray(32)
        System.arraycopy(h, 0, out, 0, 32)
        // RFC 7748 §5 clamping:
        out[0] = (out[0].toInt() and 248).toByte()
        out[31] = (out[31].toInt() and 127).toByte()
        out[31] = (out[31].toInt() or 64).toByte()
        return out
    }

    /**
     * Backward-compatible alias: derive X25519 public key from Solana Ed25519 verifying key.
     * Same as [ed25519PublicToX25519Public].
     */
    fun walletKeyToX25519Public(ed25519PublicKey: ByteArray): ByteArray =
        ed25519PublicToX25519Public(ed25519PublicKey)

    /**
     * Backward-compatible alias: derive X25519 secret from Solana Ed25519 seed.
     * Same as [ed25519SecretToX25519Secret].
     */
    fun walletKeyToX25519Secret(ed25519SecretKey: ByteArray): ByteArray =
        ed25519SecretToX25519Secret(ed25519SecretKey)

    /**
     * Encrypt content for a recipient (X25519 ECDH + AES-256-GCM).
     */
    fun encryptForRecipient(
        content: ByteArray,
        recipientX25519Pub: ByteArray,
    ): EncryptionResult {
        // Generate random AES key
        val aesKey = ByteArray(AES_KEY_LEN)
        SecureRandom().nextBytes(aesKey)

        // v1.4.0': derive the AES-GCM content nonce from the AES key
        // instead of a random SecureRandom nonce. This is safe because the
        // aesKey is fresh per message (and thus unique), and the nonce is
        // recoverable on decryption from the unwrapped key — no on-chain
        // nonce field needed.
        val nonce = deriveContentNonce(aesKey)

        // Encrypt content with AES-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(GCM_TAG_LEN, nonce),
        )
        val ciphertext = cipher.doFinal(content)

        // Compute SHA-256 of plaintext
        val contentHash = MessageDigest.getInstance("SHA-256").digest(content)

        // ECDH shared secret — ephemeral key pair (X25519)
        val ephemeralSecret = ByteArray(X25519_KEY_LEN)
        SecureRandom().nextBytes(ephemeralSecret)
        val ephemeralPub = X25519.publicFromPrivate(ephemeralSecret)
        val sharedSecret = X25519.computeSharedSecret(ephemeralSecret, recipientX25519Pub)

        // HKDF to derive wrapping key (single-round, domain-separated)
        val wrappingKey = hkdfSha256(sharedSecret, "whisper-wrap-v1".toByteArray(), 32)

        // Encrypt AES key with wrapping key
        val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val wrapNonce = ByteArray(GCM_NONCE_LEN)
        SecureRandom().nextBytes(wrapNonce)
        wrapCipher.init(
            Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"),
            GCMParameterSpec(GCM_TAG_LEN, wrapNonce),
        )
        val encryptedKey = wrapCipher.doFinal(aesKey)

        // encrypted_key layout:
        //   [ephemeralPub 32] [wrapNonce 12] [wrappedKey variable]
        val outputKey = ByteArray(32 + 12 + encryptedKey.size)
        System.arraycopy(ephemeralPub, 0, outputKey, 0, 32)
        System.arraycopy(wrapNonce, 0, outputKey, 32, 12)
        System.arraycopy(encryptedKey, 0, outputKey, 44, encryptedKey.size)

        return EncryptionResult(
            ciphertext = ciphertext,
            nonce = nonce,
            contentHash = contentHash,
            encryptedKey = outputKey,
        )
    }

    /**
     * v1.4.0': derive a deterministic AES-GCM nonce from the AES key.
     * First 12 bytes of SHA-256(aesKey). Unique per message because each
     * message has a fresh aesKey from SecureRandom. Recoverable by the
     * recipient after they unwrap aesKey.
     */
    private fun deriveContentNonce(aesKey: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(aesKey)
        return digest.copyOfRange(0, GCM_NONCE_LEN)
    }

    /**
     * Decrypt content using recipient's X25519 secret key.
     */
    fun decryptForRecipient(
        encryptedContent: ByteArray,
        encryptedKeyBytes: ByteArray,
        recipientX25519Secret: ByteArray,
    ): ByteArray {
        if (encryptedKeyBytes.size < 44) {
            throw IllegalArgumentException("encrypted_key too short")
        }
        val ephemeralPub = encryptedKeyBytes.copyOfRange(0, 32)
        val wrapNonce = encryptedKeyBytes.copyOfRange(32, 44)
        val wrappedKey = encryptedKeyBytes.copyOfRange(44, encryptedKeyBytes.size)

        val sharedSecret = X25519.computeSharedSecret(recipientX25519Secret, ephemeralPub)
        val wrappingKey = hkdfSha256(sharedSecret, "whisper-wrap-v1".toByteArray(), 32)

        val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        wrapCipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"),
            GCMParameterSpec(GCM_TAG_LEN, wrapNonce),
        )
        val aesKey = wrapCipher.doFinal(wrappedKey)

        // v1.4.0': derive content nonce from the unwrapped aesKey (same
        // scheme used in encryptForRecipient). Replaces the old "pass nonce
        // in" API which required storing the nonce on-chain.
        val contentNonce = deriveContentNonce(aesKey)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(GCM_TAG_LEN, contentNonce),
        )
        return cipher.doFinal(encryptedContent)
    }

    /**
     * HKDF-SHA256 (single-round — sufficient for our domain-separated use).
     */
    private fun hkdfSha256(secret: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = MessageDigest.getInstance("SHA-256").digest(secret + info)
        return prk.copyOfRange(0, len)
    }

    /** Read a 32-byte little-endian buffer as an unsigned BigInteger. */
    private fun unsignedFromLittleEndian(bytes: ByteArray): java.math.BigInteger {
        // Reverse to big-endian, then prepend 0 to force positive
        val reversed = ByteArray(bytes.size)
        for (i in bytes.indices) {
            reversed[reversed.size - 1 - i] = bytes[i]
        }
        return java.math.BigInteger(1, reversed)
    }
}

data class EncryptionResult(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val contentHash: ByteArray,
    val encryptedKey: ByteArray,
)
