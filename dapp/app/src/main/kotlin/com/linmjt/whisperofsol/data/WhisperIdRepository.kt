package com.linmjt.whisperofsol.data

import com.linmjt.whisperofsol.service.SolanaRpcService
import com.solana.publickey.SolanaPublicKey
import com.linmjt.whisperofsol.util.Base58
import com.linmjt.whisperofsol.util.Logger
import com.linmjt.whisperofsol.util.WsolLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * v1.6.1: WhisperId PDA lookup & register.
 *
 * Each wallet has its own WhisperId PDA at:
 *   PDA seeds = [b"whisper_id", wallet_pubkey]
 *   PDA data  = [8 disc | 32 wallet | 32 enc_pub | 64 signature | 8 created_at | 1 bump | 7 _pad]
 *             = 152 bytes on chain.
 *
 * Sender flow: lookup the recipient's WhisperId PDA → read enc_pub →
 * encrypt the message directly. No more out-of-band Whisper ID string
 * sharing required.
 *
 * Owner flow: after deriving X25519 keypair via wallet signature, store
 * enc_pub + signature on chain via the `register_whisper_id` instruction.
 * The signature is stored as proof-of-possession but is NOT verified on
 * chain (would cost ~30k CU via CPI).
 *
 * Program ID: CCTfaFAHLPHQJrEcfKvvbeCKoEL2S5uFogev2DUo1kKw (v1.9.0 clean devnet reset, was Dv1UzYXq... v1.8.33).
 *
 * Bug 2026-10-08 fix: was hardcoded to 76sLfLDTzTSiqgRTxk9vh1ZUDNiBTq2iTDZNJtWfGEX4
 * (v1 original). After v1.8.11 redeployment, register_whisper_id tx went to the
 * NEW program (GbjFeT...) but findEncPubByWallet still queried the OLD program
 * (76sLfL...) -- different program IDs mean different PDA address, so lookup
 * always returned null and other wallets couldn't find the sender's WhisperId.
 *
 * Anchor.toml [programs.devnet] is the source of truth: Dv1UzYXqDcmfPdf2Moopitj9KpTGyjySjE9c2jPG1qwj
 */
class WhisperIdRepository(
    private val rpc: SolanaRpcService = SolanaRpcService(),
    private val programId: SolanaPublicKey = SolanaPublicKey.from(
        "CCTfaFAHLPHQJrEcfKvvbeCKoEL2S5uFogev2DUo1kKw"  // v1.9.0: clean devnet reset (was Dv1UzYXq... v1.8.33)
    ),
) {

    companion object {
        private const val TAG = "WhisperIdRepository"

        /** PDA account size = 8 (discriminator) + 144 (struct). */
        const val WHISPER_ID_ACCOUNT_SIZE = 152

        /**
         * Offsets within the account data (excluding 8-byte discriminator):
         *   - 0..32   wallet (32)
         *   - 32..64  enc_pub (32)
         *   - 64..128 signature (64)
         *   - 128..136 created_at (PodI64, 8)
         *   - 136..137 bump (u8, 1)
         *   - 137..144 _pad (7)
         */
        const val OFFSET_ENC_PUB = 8 + 32       // = 40 from start of account data
        const val OFFSET_SIGNATURE = 8 + 32 + 32 // = 72 from start of account data
    }

    /**
     * Derive the WhisperId PDA address for a given wallet pubkey (base58).
     * Returns base58 string.
     */
    fun findWhisperIdPda(walletBase58: String): String {
        val seeds = listOf(
            "whisper_id".toByteArray(StandardCharsets.UTF_8),
            Base58.decode(walletBase58),
        )
        val pdaResult = runBlocking {
            com.solana.publickey.ProgramDerivedAddress.find(seeds, programId)
        }
        if (pdaResult.isSuccess) {
            val pda = pdaResult.getOrNull()
            if (pda != null) {
                return SolanaPublicKey(pda.bytes).string()
            }
        }
        throw IllegalStateException("PDA derivation failed: ${pdaResult.exceptionOrNull()?.message}")
    }

    /**
     * v1.9.2: Look up the recipient's full WhisperId record (enc_pub + signature)
     * by their wallet address.
     *
     * Returns null if:
     *   - PDA doesn't exist on chain (recipient never set up WhisperOfSol)
     *   - PDA exists but data layout doesn't match expected size
     *   - RPC error
     *
     * Returns a [WhisperIdRecord] otherwise. `signature` may be a 64-byte array
     * for PDAs written by the v1.6.1+ register_whisper_id flow, or null for
     * older PDAs (pre-v1.6.1 placeholder-only data).
     */
    suspend fun findWhisperIdByWallet(walletBase58: String): WhisperIdRecord? = withContext(Dispatchers.IO) {
        val pda = try {
            findWhisperIdPda(walletBase58)
        } catch (e: Throwable) {
            WsolLog.e("findWhisperIdByWallet: PDA derive failed for ${walletBase58.take(8)}...", e)
            return@withContext null
        }
        WsolLog.e("findWhisperIdByWallet: looking up PDA=${pda.take(12)}... for wallet=${walletBase58.take(8)}...")
        try {
            val accountInfo = rpc.getAccountInfo(pda)
            if (accountInfo == null) {
                WsolLog.e("findWhisperIdByWallet: getAccountInfo returned null — PDA not found or RPC error")
                return@withContext null
            }
            val dataArr = accountInfo.optJSONArray("data")
            if (dataArr == null) {
                WsolLog.e("findWhisperIdByWallet: accountInfo has no 'data' array")
                return@withContext null
            }
            if (dataArr.length() < 1) {
                WsolLog.e("findWhisperIdByWallet: data array length=${dataArr.length()} (expected >= 1)")
                return@withContext null
            }
            val dataB64 = dataArr.getString(0)
            val data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)

            if (data.size != WHISPER_ID_ACCOUNT_SIZE) {
                WsolLog.e("WhisperId PDA size mismatch: ${data.size} (expected $WHISPER_ID_ACCOUNT_SIZE) — outdated program?")
                return@withContext null
            }
            // Verify wallet field matches (offset 8..40 == wallet pubkey)
            val storedWallet = data.copyOfRange(8, 40)
            val expectedWallet = Base58.decode(walletBase58)
            if (!storedWallet.contentEquals(expectedWallet)) {
                WsolLog.e("WhisperId PDA wallet field mismatch. stored=${bytesToHex(storedWallet)} expected=${walletBase58.take(16)}...")
                return@withContext null
            }
            val encPub = data.copyOfRange(OFFSET_ENC_PUB, OFFSET_ENC_PUB + 32)
            WsolLog.e("findWhisperIdByWallet: encPub=${bytesToHex(encPub)} size=${encPub.size}")
            if (!isValidX25519PubKey(encPub)) {
                WsolLog.e("findWhisperIdByWallet: on-chain enc_pub looks suspicious (all-same bytes or low entropy)")
            }
            // v1.9.2: also extract the 64-byte signature for key recovery flows
            // (loginWithoutPrivKey re-derives enc_priv from this signature so
            // users don't have to re-sign on every fresh install).
            val rawSig = data.copyOfRange(OFFSET_SIGNATURE, OFFSET_SIGNATURE + 64)
            val signature: ByteArray? = if (rawSig.all { it == 0.toByte() }) {
                // Pre-v1.6.1 PDAs had no signature; treat as missing.
                android.util.Log.w(TAG, "findWhisperIdByWallet: on-chain signature is all-zero (legacy PDA, no recovery possible)")
                null
            } else {
                rawSig
            }
            WhisperIdRecord(encPub = encPub, signature = signature)
        } catch (e: Throwable) {
            WsolLog.e("findWhisperIdByWallet failed for ${walletBase58.take(8)}...", e)
            null
        }
    }

    /**
     * Look up the recipient's enc_pub by their wallet address.
     *
     * Returns null if:
     *   - PDA doesn't exist on chain (recipient never set up WhisperOfSol)
     *   - PDA exists but data layout doesn't match expected size
     *   - RPC error
     *
     * Returns the 32-byte X25519 enc_pub otherwise.
     */
    suspend fun findEncPubByWallet(walletBase58: String): ByteArray? = withContext(Dispatchers.IO) {
        val pda = try {
            findWhisperIdPda(walletBase58)
        } catch (e: Throwable) {
            WsolLog.e("findEncPubByWallet: PDA derive failed for ${walletBase58.take(8)}...", e)
            return@withContext null
        }
        WsolLog.e("findEncPubByWallet: looking up PDA=${pda.take(12)}... for wallet=${walletBase58.take(8)}...")
        try {
            val accountInfo = rpc.getAccountInfo(pda)
            if (accountInfo == null) {
                WsolLog.e("findEncPubByWallet: getAccountInfo returned null — PDA not found or RPC error")
                return@withContext null
            }
            val dataArr = accountInfo.optJSONArray("data")
            if (dataArr == null) {
                WsolLog.e("findEncPubByWallet: accountInfo has no 'data' array")
                return@withContext null
            }
            if (dataArr.length() < 1) {
                WsolLog.e("findEncPubByWallet: data array length=${dataArr.length()} (expected >= 1)")
                return@withContext null
            }
            val dataB64 = dataArr.getString(0)
            val data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)

            if (data.size != WHISPER_ID_ACCOUNT_SIZE) {
                WsolLog.e("WhisperId PDA size mismatch: ${data.size} (expected $WHISPER_ID_ACCOUNT_SIZE) — outdated program?")
                return@withContext null
            }
            // Verify wallet field matches (offset 8..40 == wallet pubkey)
            val storedWallet = data.copyOfRange(8, 40)
            val expectedWallet = Base58.decode(walletBase58)
            if (!storedWallet.contentEquals(expectedWallet)) {
                WsolLog.e("WhisperId PDA wallet field mismatch. stored=${bytesToHex(storedWallet)} expected=${walletBase58.take(16)}...")
                return@withContext null
            }
            val encPub = data.copyOfRange(OFFSET_ENC_PUB, OFFSET_ENC_PUB + 32)
            WsolLog.e("findEncPubByWallet: encPub=${bytesToHex(encPub)} size=${encPub.size}")
            if (!isValidX25519PubKey(encPub)) {
                WsolLog.e("findEncPubByWallet: on-chain enc_pub looks suspicious (all-same bytes or low entropy)")
            }
            encPub
        } catch (e: Throwable) {
            WsolLog.e("findEncPubByWallet failed for ${walletBase58.take(8)}...", e)
            null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * v1.7.1 defensive check: detect obviously-invalid enc_pub values that
     * some wallets accidentally wrote during early WhisperId setup.
     *
     * Currently rejects:
     *   - All-zero (32 × 0x00)
     *   - All-same-byte (e.g. all 0xee, all 0xcc) — early dev/test data
     *   - Low entropy (≤ 1 unique byte across the 32-byte value)
     *
     * A real X25519 pubkey derived from a Solana Ed25519 seed will have
     * high entropy (~30+ unique bytes). Old wallet-signature flows that
     * wrote a placeholder (Buffer.alloc(32, 0xee)) trip this check and
     * the caller falls back to birational-map derivation.
     *
     * Does NOT do a full curve-point check — that's expensive on the
     * client. The fallback path will detect a real point invalidity
     * downstream via AES-GCM auth-tag failure on decrypt.
     */
    fun isValidX25519PubKey(bytes: ByteArray): Boolean {
        if (bytes.size != 32) return false
        val first = bytes[0]
        val allSame = bytes.all { it == first }
        if (allSame) return false
        val unique = bytes.toSet().size
        return unique >= 4  // real X25519 pubkeys have ~30+ unique bytes
    }

    /**
     * Build the `register_whisper_id` instruction data.
     *
     * Layout: [disc=11] ++ [enc_pub 32] ++ [signature 64]
     *
     * Caller is responsible for wrapping this in a transaction with the
     * proper account metas (whisper_id_pda, wallet, system_program) and
     * signing via MWA signAndSendTransaction.
     */
    fun buildRegisterInstructionData(encPub: ByteArray, signature: ByteArray): ByteArray {
        require(encPub.size == 32) { "enc_pub must be 32 bytes, got ${encPub.size}" }
        require(signature.size == 64) { "signature must be 64 bytes, got ${signature.size}" }
        val out = ByteArray(1 + 32 + 64)
        out[0] = 11  // IX_DISC_REGISTER_WHISPER_ID
        System.arraycopy(encPub, 0, out, 1, 32)
        System.arraycopy(signature, 0, out, 33, 64)
        return out
    }

    /**
     * Compute account metas for the `register_whisper_id` instruction.
     * Returns a JSONArray of {pubkey, isSigner, isWritable} objects.
     */
    fun registerAccountMetas(walletBase58: String): JSONArray {
        val pda = findWhisperIdPda(walletBase58)
        return JSONArray().apply {
            // whisper_id PDA: not signer, writable (init_if_needed)
            put(JSONObject().put("pubkey", pda).put("isSigner", false).put("isWritable", true))
            // wallet: signer + writable (pays rent)
            put(JSONObject().put("pubkey", walletBase58).put("isSigner", true).put("isWritable", true))
            // system program
            put(JSONObject().put("pubkey", "11111111111111111111111111111111").put("isSigner", false).put("isWritable", false))
        }
    }
}

/**
 * v1.9.2: On-chain WhisperId PDA data, returned by [WhisperIdRepository.findWhisperIdByWallet].
 *
 * @property encPub 32-byte X25519 public key registered on-chain.
 * @property signature 64-byte Ed25519 signature over the canonical
 *   "WhisperOfSol encryption key v1 | <wallet_pubkey>" message. The wallet
 *   produced this when the user originally ran Set up Whisper ID. Used by
 *   [loginWithoutPrivKey] to re-derive the local X25519 secret without
 *   re-prompting the wallet. Null for pre-v1.6.1 PDAs that wrote no signature.
 */
data class WhisperIdRecord(
    val encPub: ByteArray,
    val signature: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WhisperIdRecord) return false
        return encPub.contentEquals(other.encPub) &&
            (signature?.contentEquals(other.signature ?: ByteArray(0)) ?: (other.signature == null))
    }
    override fun hashCode(): Int =
        encPub.contentHashCode() * 31 + (signature?.contentHashCode() ?: 0)
}