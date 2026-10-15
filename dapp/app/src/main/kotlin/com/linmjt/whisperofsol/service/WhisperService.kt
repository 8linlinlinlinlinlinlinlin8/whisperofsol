package com.linmjt.whisperofsol.service

import android.util.Base64
import com.linmjt.whisperofsol.util.Logger
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Solana RPC client (real, over OkHttp) + Whisper program interaction.
 *
 * Uses com.solanamobile:web3-solana-jvm for Transaction / Message / AccountMeta
 * construction, ensuring wire format matches Anchor v2 program expectations.
 */
class SolanaRpcService(private val rpcUrl: String = "https://api.devnet.solana.com") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonMedia = "application/json".toMediaType()

    /**
     * Send a JSON-RPC call (IO dispatcher — must not run on main thread).
     */
    private suspend fun rpcCall(method: String, params: Any): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)

        val request = Request.Builder()
            .url(rpcUrl)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                Logger.e(TAG, "RPC error: ${response.code} ${body}")
            }
            JSONObject(body)
        }
    }

    /**
     * Get account info (raw, including lamports).
     */
    suspend fun getAccountInfo(pubkey: String): JSONObject? {
        val params = JSONArray()
            .put(pubkey)
            .put(JSONObject().put("encoding", "base64").put("commitment", "confirmed"))
        val result = rpcCall("getAccountInfo", params)
        return result.optJSONObject("result")?.optJSONObject("value")
    }

    /**
     * Get SOL balance in lamports.
     */
    suspend fun getBalance(pubkey: String): Long {
        val params = JSONArray().put(pubkey)
        val result = rpcCall("getBalance", params)
        if (result.has("error")) {
            Logger.e(TAG, "getBalance RPC error: ${result.getJSONObject("error")}")
            return 0L
        }
        val value = result.optJSONObject("result")?.optLong("value", -1L) ?: -1L
        return if (value < 0) 0L else value
    }

    /**
     * Get recent blockhash via getLatestBlockhash RPC.
     */
    suspend fun getRecentBlockhash(): String? {
        val params = JSONArray()
            .put(JSONObject().put("commitment", "confirmed"))
        val result = rpcCall("getLatestBlockhash", params)
        if (result.has("error")) {
            Logger.e(TAG, "getLatestBlockhash RPC error: ${result.getJSONObject("error")}")
            return null
        }
        return result.optJSONObject("result")?.optJSONObject("value")?.optString("blockhash", null)
    }

    /**
     * Send a signed transaction (base64 encoded).
     */
    suspend fun sendTransaction(signedTxBase64: String): String? {
        val params = JSONArray()
            .put(signedTxBase64)
            .put(JSONObject().put("encoding", "base64").put("skipPreflight", false))
        val result = rpcCall("sendTransaction", params)
        if (result.has("error")) {
            Logger.e(TAG, "sendTransaction error: ${result.getJSONObject("error")}")
            return null
        }
        return result.optString("result", null).takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * Confirm a transaction by signature, polling.
     */
    suspend fun confirmTransaction(signature: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val params = JSONArray()
                .put(signature)
                .put(JSONObject().put("commitment", "confirmed"))
            val result = rpcCall("getSignatureStatuses", params)
            val value = result.optJSONObject("result")?.optJSONArray("value")
            if (value != null && value.length() > 0) {
                val first = value.optJSONObject(0)
                val confStatus = first?.optString("confirmationStatus", "")
                if (confStatus == "confirmed" || confStatus == "finalized") {
                    val err = first.optJSONObject("err")
                    return err == null || err.length() == 0
                }
            }
            kotlinx.coroutines.delay(1000)
        }
        return false
    }

    companion object {
        private const val TAG = "SolanaRpc"
    }
}

/**
 * Whisper program client.
 *
 * Program: CCTfaFAHLPHQJrEcfKvvbeCKoEL2S5uFogev2DUo1kKw (v1.9.0 clean devnet reset, was Dv1UzYXq... v1.8.33)
 *   - Treasury: 4k4Uu9dAXczWGexPwyHFh7m62PHHGBXJ58ej34JKajHc (PDA: [b"treasury"], bump=255)
 *   - ChristmasJackpot: HZ6Wixru6GjySL9E1t9arsSrf445kxoNuS5n9vcQVVxE (PDA: [b"christmas_jackpot"], bump=255)
 *
 * Message PDA: [b"msg", sender_pubkey, recipient_pubkey, nonce_u64_le]
 *
 * v1.8.11: Treasury + ChristmasJackpot PDA are derived from [b"seed"] + programId.
 *          programId 變了一定要跟著 update constant,否則 WhisperService init 的
 *          runtime derive self-check (line ~167) 會 throw IllegalArgumentException,
 *          HomeViewModel 建不出來,app 直接 crash on launch。
 */
class WhisperService(private val rpc: SolanaRpcService = SolanaRpcService()) {

    val programId: SolanaPublicKey = SolanaPublicKey.from(PROGRAM_ID)
    // v1.1.10: 加上 treasuryPda 的 runtime derive + 驗證。避免 typo / PDA mismatch。
    // 原來只是一個 hardcoded 常數，在 commit 469e711 時被改錯成
    // `BDvhcW3rVVVv...` （W → V typo），導致 simulation / sign 都失敗。
    val treasuryPda: SolanaPublicKey = SolanaPublicKey.from(TREASURY_PDA)
        .also {
            // Sanity check：硬編碼值必須等於從 [b"treasury"] 派生的 PDA。
            // 不一致時在 init 階段就丟例外，避免上線後才失敗。
            val derived = runBlocking {
                ProgramDerivedAddress.find(listOf("treasury".toByteArray()), programId)
            }
            val derivedPda = derived.getOrNull()?.bytes
            require(derivedPda != null && derivedPda.contentEquals(it.bytes)) {
                "TREASURY_PDA hardcode mismatch: ${it.string()} != " +
                    "${derivedPda?.let { b -> SolanaPublicKey(b).string() } ?: "<null>"}"
            }
        }
    val christmasJackpotPda: SolanaPublicKey = SolanaPublicKey.from(CHRISTMAS_JACKPOT_PDA)
    val systemProgram: SolanaPublicKey = SolanaPublicKey.from(SYSTEM_PROGRAM_ID)

    /**
     * Read Treasury PDA balance (lamports).
     */
    suspend fun getTreasuryLamports(): Long =
        rpc.getBalance(TREASURY_PDA)

    /**
     * Read ChristmasJackpot PDA balance (lamports).
     */
    suspend fun getChristmasJackpotLamports(): Long =
        rpc.getBalance(CHRISTMAS_JACKPOT_PDA)

    /**
     * Read the connected user's own SOL balance (in lamports).
     *
     * Used by WalletScreen after MWA authorize to display the user's
     * balance next to their wallet address (Bug 2026-10-02 fix — users
     * reported seeing no wallet info after connect).
     */
    suspend fun getUserBalance(pubkeyBytes: ByteArray): Long {
        val base58 = com.solana.mobilewalletadapter.common.util.Base58.encode(pubkeyBytes)
        return rpc.getBalance(base58)
    }

    /**
     * v1.3.0: Fetch a Message PDA from on-chain to display after a successful
     * unlock. Returns null if the PDA doesn't exist (sender never broadcast)
     * or the data is malformed.
     *
     * Message struct layout (Anchor v2 Pod types, no padding):
     *   [8 disc][8 nonce][8 unlocked_at][8 reward_paid][8 created_at]
     *   [32 sender][32 recipient][32 content_hash]
     *   [32 content_uri_a][32 content_uri_b]
     *   [32 encrypted_key_a][32 encrypted_key_b][32 encrypted_key_c]
     *   [1 content_uri_len][1 encrypted_key_len][8 unlock_count][1 bump]
     *   total = 8+8+8+8+8+32+32+32+32+32+32+32+32+1+1+8+1 = 299 bytes
     */
    suspend fun fetchMessageDetail(
        senderPubkey: ByteArray,
        recipientPubkey: ByteArray,
        nonce: Long,
    ): MessageDetail? {
        return try {
            val sender = SolanaPublicKey(senderPubkey)
            val recipient = SolanaPublicKey(recipientPubkey)
            val messagePda = findMessagePda(sender, recipient, nonce)
            Logger.d(TAG, "fetchMessageDetail: PDA=${messagePda.toString()}")

            val accountInfo = rpc.getAccountInfo(messagePda.toString()) ?: run {
                Logger.w(TAG, "fetchMessageDetail: accountInfo null (PDA not yet on chain)")
                return null
            }
            val dataArray = accountInfo.optJSONArray("data") ?: return null
            val dataBase64 = dataArray.optString(0, "")
            if (dataBase64.isEmpty()) return null

            val data = Base64.decode(dataBase64, Base64.NO_WRAP)
            Logger.d(TAG, "fetchMessageDetail: data.size=${data.size} bytes (expected ~299, on-chain 307 includes Anchor v2 8-byte trailing pad)")

            if (data.size < 16) return null
            if (data.size < 299) {
                Logger.w(TAG, "fetchMessageDetail: data.size=${data.size} shorter than expected 299 — sender used old format?")
            }

            // offsets after disc (offset 8):
            val offNonce = 8
            val offUnlockedAt = 16
            val offRewardPaid = 24
            val offCreatedAt = 32
            val offSender = 40
            val offRecipient = 72
            val offContentHash = 104
            val offUriA = 136
            val offUriB = 168
            val offKeyA = 200
            val offKeyB = 232
            val offKeyC = 264
            val offUriLen = 296
            val offKeyLen = 297

            val nonceOnChain = readUInt64LE(data, offNonce)
            val unlockedAt = readInt64LE(data, offUnlockedAt)
            val rewardPaid = readUInt64LE(data, offRewardPaid)
            val createdAt = readInt64LE(data, offCreatedAt)
            val senderBytes = data.copyOfRange(offSender, offSender + 32)
            val recipientBytes = data.copyOfRange(offRecipient, offRecipient + 32)
            val contentHashBytes = data.copyOfRange(offContentHash, offContentHash + 32)
            val uriA = data.copyOfRange(offUriA, offUriA + 32)
            val uriB = data.copyOfRange(offUriB, offUriB + 32)
            val keyA = data.copyOfRange(offKeyA, offKeyA + 32)
            val keyB = data.copyOfRange(offKeyB, offKeyB + 32)
            val keyC = data.copyOfRange(offKeyC, offKeyC + 32)
            val uriLen = data[offUriLen].toInt() and 0xFF
            val keyLen = data[offKeyLen].toInt() and 0xFF

            // Concatenate uri_a + uri_b → first uriLen bytes = ciphertext
            val uriConcat = ByteArray(64)
            System.arraycopy(uriA, 0, uriConcat, 0, 32)
            System.arraycopy(uriB, 0, uriConcat, 32, 32)
            val encryptedContent = if (uriLen <= 64) uriConcat.copyOf(uriLen) else ByteArray(0)

            // Concatenate key_a + key_b + key_c → first keyLen bytes = encrypted_key
            val keyConcat = ByteArray(96)
            System.arraycopy(keyA, 0, keyConcat, 0, 32)
            System.arraycopy(keyB, 0, keyConcat, 32, 32)
            System.arraycopy(keyC, 0, keyConcat, 64, 32)
            val encryptedKey = if (keyLen <= 96) keyConcat.copyOf(keyLen) else ByteArray(0)

            val senderB58 = com.solana.mobilewalletadapter.common.util.Base58.encode(senderBytes)
            val recipientB58 = com.solana.mobilewalletadapter.common.util.Base58.encode(recipientBytes)

            MessageDetail(
                senderBase58 = senderB58,
                recipientBase58 = recipientB58,
                nonce = nonceOnChain.toLong(),
                createdAt = createdAt,
                unlockedAt = unlockedAt,
                rewardPaidLamports = rewardPaid.toLong(),
                contentHashHex = bytesToHex(contentHashBytes),
                encryptedContentHex = bytesToHex(encryptedContent),
                encryptedKeyHex = bytesToHex(encryptedKey),
            )
        } catch (e: Throwable) {
            Logger.e(TAG, "fetchMessageDetail error: ${e.message}", e)
            null
        }
    }

    /**
     * Get recent blockhash for transaction construction.
     */
    suspend fun getRecentBlockhash(): String? = rpc.getRecentBlockhash()

    /**
     * Build a `send_message` transaction.
     *
     * v1.1.8 (revert to v1.1.6 recent-blockhash): dropped v1.1.7's durable
     * nonce / advance_nonce_account ix. Seeker SMS Wallet cluster validation
     * misclassified any tx whose recent_blockhash = a durable nonce hash
     * as a mainnet tx ("Network mismatch"). Going back to a plain recent
     * blockhash from `getLatestBlockhash` + a retry loop in HomeViewModel
     * (3 attempts, 3 s apart) covers the same Seed Vault latency case
     * without triggering cluster validation.
     *
     * @param senderPubkey 32-byte Ed25519 public key
     * @param recipientPubkey 32-byte Ed25519 public key
     * @param nonce u64
     * @param contentHash 32-byte SHA-256 of plaintext
     * @param contentUri ciphertext bytes (will be split into 64-byte chunks with length byte)
     * @param encryptedKey 96-byte encrypted key (32 ephemeral pub + 12 wrap nonce + 52 wrapped key)
     * @return Base64-encoded unsigned transaction bytes
     */
    suspend fun buildSendMessageTransaction(
        senderPubkey: ByteArray,
        recipientPubkey: ByteArray,
        nonce: Long,
        contentHash: ByteArray,
        contentUri: ByteArray,
        encryptedKey: ByteArray,
    ): String? {
        return try {
            val sender = SolanaPublicKey(senderPubkey)
            val recipient = SolanaPublicKey(recipientPubkey)
            val recentBlockhash = rpc.getRecentBlockhash() ?: return null

            // Derive message PDA
            val messagePda = findMessagePda(sender, recipient, nonce)
            // Derive recipient inbox PDA (init_if_needed on send_message)
            val recipientInboxPda = findInboxPda(recipientPubkey)
            // v2.0: sender outbox PDA removed (over-designed, UI never displayed).

            // Instruction data (matches lib.rs send_message params):
            //   [1 byte disc=1]
            //   [32 recipient]
            //   [8 nonce u64 LE]
            //   [32 content_hash]
            //   [32 content_uri_a]
            //   [32 content_uri_b]
            //   [1 content_uri_len]
            //   [32 encrypted_key_a]
            //   [32 encrypted_key_b]
            //   [32 encrypted_key_c]
            //   [1 encrypted_key_len]
            val ixData = ByteArray(1 + 32 + 8 + 32 + 32 + 32 + 1 + 32 + 32 + 32 + 1)
            var off = 0
            ixData[off] = 1; off += 1
            System.arraycopy(recipientPubkey, 0, ixData, off, 32); off += 32
            writeU64LE(nonce, ixData, off); off += 8
            System.arraycopy(contentHash, 0, ixData, off, 32); off += 32
            // content_uri split into 64 bytes (32 + 32), padded with zeros
            val paddedUri = ByteArray(64)
            val uriLen = contentUri.size.coerceAtMost(64)
            System.arraycopy(contentUri, 0, paddedUri, 0, uriLen)
            System.arraycopy(paddedUri, 0, ixData, off, 32); off += 32
            System.arraycopy(paddedUri, 32, ixData, off, 32); off += 32
            ixData[off] = uriLen.toByte(); off += 1
            // encrypted_key split into 96 bytes (32 + 32 + 32), padded with zeros
            val paddedKey = ByteArray(96)
            val keyLen = encryptedKey.size.coerceAtMost(96)
            System.arraycopy(encryptedKey, 0, paddedKey, 0, keyLen)
            System.arraycopy(paddedKey, 0, ixData, off, 32); off += 32
            System.arraycopy(paddedKey, 32, ixData, off, 32); off += 32
            System.arraycopy(paddedKey, 64, ixData, off, 32); off += 32
            ixData[off] = keyLen.toByte(); off += 1

            val instruction = TransactionInstruction(
                programId = programId,
                accounts = listOf(
                    AccountMeta(messagePda, isSigner = false, isWritable = true),
                    AccountMeta(treasuryPda, isSigner = false, isWritable = true),
                    AccountMeta(recipientInboxPda, isSigner = false, isWritable = true),
                    AccountMeta(sender, isSigner = true, isWritable = true),
                    AccountMeta(systemProgram, isSigner = false, isWritable = false),
                ),
                data = ixData,
            )

            val message = Message.Builder()
                .addInstruction(instruction)
                .addFeePayer(sender) // 0.3.2-beta6: explicit fee payer (fix Signing failed bug)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(TAG, "send_message tx built: ${txBytes.size} bytes (v1.1.8 recent blockhash)")
            // Debug: dump ix_data + accounts (v2.1.0 onboarding debug)
            val ixDataHex = ixData.joinToString("") { "%02x".format(it) }
            Logger.d(TAG, "send_message ixData: ${ixDataHex}")
            Logger.d(
                TAG,
                "send_message accounts: " +
                        "[0]msg=${messagePda.string().take(8)}... " +
                        "[1]treasury=${treasuryPda.string().take(8)}... " +
                        "[2]inbox=${recipientInboxPda.string().take(8)}... " +
                        "[3]sender=${sender.string().take(8)}... " +
                        "[4]system"
            )
            Logger.d(
                TAG,
                "send_message tx first 50 bytes hex: " +
                        txBytes.take(50).joinToString("") { "%02x".format(it) }
            )
            // v1.1.4+ debug: dump full tx hex so we can compare byte-for-byte with TS test
            Logger.d(TAG, "send_message FULL tx hex (${txBytes.size} bytes): " +
                txBytes.joinToString("") { "%02x".format(it) })
            // v1.1.4+ debug: dump base64 of the full tx (what we hand to MWA)
            Logger.d(TAG, "send_message FULL tx base64: " +
                android.util.Base64.encodeToString(txBytes, android.util.Base64.NO_WRAP))
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(TAG, "buildSendMessageTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * Build an `unlock_message` transaction.
     */
    suspend fun buildUnlockMessageTransaction(
        senderPubkey: ByteArray,
        recipientPubkey: ByteArray,
        nonce: Long,
    ): String? {
        return try {
            val recipient = SolanaPublicKey(recipientPubkey)
            val sender = SolanaPublicKey(senderPubkey)
            val recentBlockhash = rpc.getRecentBlockhash() ?: return null
            val messagePda = findMessagePda(sender, recipient, nonce)
            val recipientInboxPda = findInboxPda(recipientPubkey)

            // Instruction data (matches lib.rs unlock_message params):
            //   [1 byte disc=2]
            //   [32 sender]
            //   [8 nonce u64 LE]
            val ixData = ByteArray(1 + 32 + 8)
            var off = 0
            ixData[off] = 2; off += 1
            System.arraycopy(senderPubkey, 0, ixData, off, 32); off += 32
            writeU64LE(nonce, ixData, off); off += 8

            // Account order MUST match pub struct UnlockMessage in lib.rs:
            //   0: message (writable, PDA)
            //   1: treasury (writable, PDA)
            //   2: christmas_jackpot (writable, PDA)
            //   3: recipient_inbox (writable, PDA)  -- previously MISSING!
            //   4: recipient (writable Signer)        -- pays the CPI transfers + signs
            //   5: system_program
            //
            // v1.1.18 bug: previous code put `recipient` in slot 3 and
            // skipped `recipient_inbox` entirely, plus set
            // addFeePayer(sender) instead of (recipient). Solana rejects
            // the tx because fee payer must be a signer, and Anchor
            // can't deserialize the accounts struct because the order
            // is wrong. MWA surfaces this as "Transaction simulation
            // failed" and disables Approve.
            val instruction = TransactionInstruction(
                programId = programId,
                accounts = listOf(
                    AccountMeta(messagePda, isSigner = false, isWritable = true),
                    AccountMeta(treasuryPda, isSigner = false, isWritable = true),
                    AccountMeta(christmasJackpotPda, isSigner = false, isWritable = true),
                    AccountMeta(recipientInboxPda, isSigner = false, isWritable = true),
                    AccountMeta(recipient, isSigner = true, isWritable = true),
                    AccountMeta(systemProgram, isSigner = false, isWritable = false),
                ),
                data = ixData,
            )

            val message = Message.Builder()
                .addInstruction(instruction)
                .addFeePayer(recipient) // v1.1.18: recipient is signer AND pays the CPI transfers
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(TAG, "unlock_message tx built: ${txBytes.size} bytes")
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(TAG, "buildUnlockMessageTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * v1.2.0: Build an `init_inbox` transaction for the connected user.
     *
     * Self-heals the inbox bump=0 bug (see migrateInboxBump in HomeViewModel).
     * Tag = 9 (init_inbox per Anchor v2 discrim list in lib.rs).
     *
     *   Account order MUST match pub struct InitInbox:
     *     0: inbox PDA (writable, owned by user)
     *     1: owner (writable Signer) — pays rent if creating
     *     2: system_program
     */
    suspend fun buildInitInboxTransaction(userPubkey: ByteArray): String? {
        return try {
            val user = SolanaPublicKey(userPubkey)
            val inboxPda = findInboxPda(userPubkey)
            val recentBlockhash = rpc.getRecentBlockhash() ?: return null

            val ixData = byteArrayOf(9) // discrim = init_inbox

            val instruction = TransactionInstruction(
                programId = programId,
                accounts = listOf(
                    AccountMeta(inboxPda, isSigner = false, isWritable = true),
                    AccountMeta(user, isSigner = true, isWritable = true),
                    AccountMeta(systemProgram, isSigner = false, isWritable = false),
                ),
                data = ixData,
            )

            val message = Message.Builder()
                .addInstruction(instruction)
                .addFeePayer(user)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(TAG, "init_inbox tx built: ${txBytes.size} bytes")
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(TAG, "buildInitInboxTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * v1.6.1: Build `register_whisper_id` instruction (tag=11).
     *
     * Writes (or overwrites) the on-chain WhisperId PDA for [userPubkey],
     * storing their X25519 enc_pub and the wallet signature that derived it.
     * PDA seeds = [b"whisper_id", user_pubkey].
     *
     * Account order MUST match pub struct RegisterWhisperId:
     *   0: whisper_id PDA (writable, init_if_needed)
     *   1: wallet (Signer, writable — pays rent for new PDA)
     *   2: system_program (readonly)
     *
     * Returns base64-encoded unsigned transaction (user signs via MWA).
     */
    suspend fun buildRegisterWhisperIdTransaction(
        userPubkey: ByteArray,
        encPub: ByteArray,
        signature: ByteArray,
    ): String? {
        require(encPub.size == 32) { "encPub must be 32 bytes, got ${encPub.size}" }
        require(signature.size == 64) { "signature must be 64 bytes, got ${signature.size}" }
        return try {
            val user = SolanaPublicKey(userPubkey)
            val whisperIdPda = findWhisperIdPda(userPubkey)
            val recentBlockhash = rpc.getRecentBlockhash() ?: return null

            val ixData = ByteArray(1 + 32 + 64)
            ixData[0] = 11  // discrim = register_whisper_id
            System.arraycopy(encPub, 0, ixData, 1, 32)
            System.arraycopy(signature, 0, ixData, 33, 64)

            val instruction = TransactionInstruction(
                programId = programId,
                accounts = listOf(
                    AccountMeta(whisperIdPda, isSigner = false, isWritable = true),
                    AccountMeta(user, isSigner = true, isWritable = true),
                    AccountMeta(systemProgram, isSigner = false, isWritable = false),
                ),
                data = ixData,
            )

            val message = Message.Builder()
                .addInstruction(instruction)
                .addFeePayer(user)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(TAG, "register_whisper_id tx built: ${txBytes.size} bytes, pda=${whisperIdPda.string().take(12)}...")
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(TAG, "buildRegisterWhisperIdTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * v1.3.0: Build `activate_christmas_jackpot` instruction (tag=4).
     * Authority-gated; the connected wallet must match `treasury.authority`.
     * Toggles `christmas_mode_active = true` so unlock_message can roll the
     * 0.1% bonus bucket paying 50% of current Treasury lamports.
     *
     * Account order MUST match pub struct ActivateChristmasJackpot:
     *   0: treasury (writable, PDA)
     *   1: christmas_jackpot (writable, PDA)
     *   2: authority (Signer)
     */
    suspend fun buildActivateChristmasTransaction(authorityPubkey: ByteArray): String? {
        return try {
            val authority = SolanaPublicKey(authorityPubkey)
            val recentBlockhash = rpc.getRecentBlockhash() ?: return null

            val ixData = byteArrayOf(4) // discrim = activate_christmas_jackpot

            val instruction = TransactionInstruction(
                programId = programId,
                accounts = listOf(
                    AccountMeta(treasuryPda, isSigner = false, isWritable = true),
                    AccountMeta(christmasJackpotPda, isSigner = false, isWritable = true),
                    AccountMeta(authority, isSigner = true, isWritable = false),
                ),
                data = ixData,
            )

            val message = Message.Builder()
                .addInstruction(instruction)
                .addFeePayer(authority)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(TAG, "activate_christmas tx built: ${txBytes.size} bytes")
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(TAG, "buildActivateChristmasTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * v1.3.0: Build `deactivate_christmas_jackpot` instruction (tag=8).
     * Companion to activate. Same account order, opposite effect.
     */
    suspend fun buildDeactivateChristmasTransaction(authorityPubkey: ByteArray): String? {
        return try {
            val authority = SolanaPublicKey(authorityPubkey)
            val recentBlockhash = rpc.getRecentBlockhash() ?: return null

            val ixData = byteArrayOf(8) // discrim = deactivate_christmas_jackpot

            val instruction = TransactionInstruction(
                programId = programId,
                accounts = listOf(
                    AccountMeta(treasuryPda, isSigner = false, isWritable = true),
                    AccountMeta(christmasJackpotPda, isSigner = false, isWritable = false),
                    AccountMeta(authority, isSigner = true, isWritable = false),
                ),
                data = ixData,
            )

            val message = Message.Builder()
                .addInstruction(instruction)
                .addFeePayer(authority)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(TAG, "deactivate_christmas tx built: ${txBytes.size} bytes")
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.e(TAG, "buildDeactivateChristmasTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * Send a signed transaction.
     */
    suspend fun sendSignedTransaction(signedTxBase64: String): String? {
        return rpc.sendTransaction(signedTxBase64)
    }

    /**
     * Confirm a transaction.
     */
    suspend fun confirmTransaction(signature: String): Boolean =
        rpc.confirmTransaction(signature)

    /**
     * Derive the Message PDA for a given sender/recipient/nonce tuple.
     * Returns SolanaPublicKey, or null if derivation fails.
     */
    fun findMessagePda(
        sender: SolanaPublicKey,
        recipient: SolanaPublicKey,
        nonce: Long,
    ): SolanaPublicKey {
        val nonceBytes = ByteArray(8)
        writeU64LE(nonce, nonceBytes, 0)
        val seeds = listOf(
            "msg".toByteArray(StandardCharsets.UTF_8),
            sender.bytes,
            recipient.bytes,
            nonceBytes,
        )
        // ProgramDerivedAddress.find is suspend; wrap with runBlocking for sync API
        val pdaResult = runBlocking { ProgramDerivedAddress.find(seeds, programId) }
        if (pdaResult.isSuccess) {
            val pdaKey = pdaResult.getOrNull()
            if (pdaKey != null) {
                return SolanaPublicKey(pdaKey.bytes)
            }
        }
        throw IllegalStateException("Failed to derive message PDA: ${pdaResult.exceptionOrNull()?.message}")
    }

    private fun writeU64LE(value: Long, dst: ByteArray, offset: Int) {
        var v = value
        for (i in 0..7) {
            dst[offset + i] = (v and 0xff).toByte()
            v = v shr 8
        }
    }

    /**
     * Derive the Inbox PDA for a given user.
     * Seeds = [b"inbox", user_pubkey_bytes]
     */
    fun findInboxPda(userPubkey: ByteArray): SolanaPublicKey {
        val seeds = listOf(
            "inbox".toByteArray(StandardCharsets.UTF_8),
            userPubkey,
        )
        val pdaResult = runBlocking { ProgramDerivedAddress.find(seeds, programId) }
        if (pdaResult.isSuccess) {
            val pdaKey = pdaResult.getOrNull()
            if (pdaKey != null) {
                return SolanaPublicKey(pdaKey.bytes)
            }
        }
        throw IllegalStateException("Failed to derive inbox PDA: ${pdaResult.exceptionOrNull()?.message}")
    }

    /**
     * v1.6.1: Derive the WhisperId PDA for a given user.
     * Seeds = [b"whisper_id", user_pubkey]
     */
    fun findWhisperIdPda(userPubkey: ByteArray): SolanaPublicKey {
        val seeds = listOf(
            "whisper_id".toByteArray(StandardCharsets.UTF_8),
            userPubkey,
        )
        val pdaResult = runBlocking { ProgramDerivedAddress.find(seeds, programId) }
        if (pdaResult.isSuccess) {
            val pdaKey = pdaResult.getOrNull()
            if (pdaKey != null) {
                return SolanaPublicKey(pdaKey.bytes)
            }
        }
        throw IllegalStateException("Failed to derive whisper_id PDA: ${pdaResult.exceptionOrNull()?.message}")
    }

    /**
     * Fetch a user's Inbox PDA from the chain.
     * Returns a list of MessageRef entries, or empty list if the inbox doesn't exist yet.
     *
     * Inbox PDA layout (440 bytes total = 8 disc + 432 struct):
     *   [8 bytes  ] account discriminator
     *   [392 bytes] messages: [MessageRef; 8] — 8 × 49 bytes each
     *   [32 bytes ] owner pubkey
     *   [4 bytes  ] len: u32 LE (number of active messages)
     *   [1 byte   ] bump
     *   [3 bytes  ] _pad
     *
     * MessageRef (49 bytes):
     *   [32 bytes] sender pubkey
     *   [8 bytes ] nonce: u64 LE
     *   [8 bytes ] created_at: i64 LE
     *   [1 byte  ] claimed: 0 = false, 1 = true
     */
    suspend fun fetchInbox(userPubkey: ByteArray): List<InboxMessageRef> {
        return try {
            val inboxPda = findInboxPda(userPubkey)
            Logger.d(TAG, "fetchInbox: looking at PDA=" + inboxPda.toString())
            val accountInfo = rpc.getAccountInfo(inboxPda.toString())
                ?: return emptyList() // Inbox doesn't exist yet (not an error)

            // v1.1.12 fix: getAccountInfo 回的 data 是 JSONArray ["<base64>", "<encoding>"]。
            // 舊 code 用 optString("data") 拿到的是整個 array 的 toString(),不是有效 base64 → fetchInbox 永遠失敗。
            Logger.d(TAG, "fetchInbox: accountInfo has data=${accountInfo.has("data")}")
            val dataArray = accountInfo.optJSONArray("data")
            Logger.d(TAG, "fetchInbox: dataArray=${dataArray != null}, len=${dataArray?.length() ?: -1}")
            if (dataArray == null) return emptyList()
            val dataBase64 = dataArray.optString(0, "")
            Logger.d(TAG, "fetchInbox: dataBase64.length=${dataBase64.length}")
            if (dataBase64.isEmpty()) return emptyList()

            val data = Base64.decode(dataBase64, Base64.NO_WRAP)
            if (data.size < 8 + 49) return emptyList() // Too short to be valid

            // v1.1.12 fix: len field is at offset 432, not 400.
            // Layout: [8 disc][392 messages][32 owner][4 len u32 LE][1 bump][3 _pad]
            // 舊 code 用 8 + 392 = 400 是 owner pubkey 起點，會讀到 owner bytes 當 len。
            // 因為 program 用 init_if_needed 沒寫 inbox.owner (留全 0),讀出來剛好是 0 → 永遠 emptyList()。
            //
            // v1.1.13 sanity check: 同時讀出 owner (offset 400-432) 並跟 requesting user 比對。
            // 目前 program 用 init_if_needed 沒寫 owner → ownerBytes 永遠是 all-zero,
            // 所以「owner == user」永遠 false。Sanity check 只在 owner 非零且不匹配時 warn
            // (表示可能是 on-chain corruption 或 PDA 算錯)。
            val ownerOff = 8 + 392
            val ownerBytes = data.copyOfRange(ownerOff, ownerOff + 32)
            val ownerBase58 = com.solana.mobilewalletadapter.common.util.Base58.encode(ownerBytes)
            val expectedOwnerBase58 = com.solana.mobilewalletadapter.common.util.Base58.encode(userPubkey)
            val ownerMatches = ownerBytes.contentEquals(userPubkey)
            val ownerIsZero = ownerBytes.all { it == 0.toByte() }
            Logger.d(TAG, "fetchInbox: on-chain owner=$ownerBase58 expected=$expectedOwnerBase58 match=$ownerMatches zero=$ownerIsZero")
            if (!ownerMatches && !ownerIsZero) {
                Logger.w(TAG, "fetchInbox: owner mismatch (NOT zero)! on-chain=$ownerBase58 expected=$expectedOwnerBase58 — possible PDA derivation bug or on-chain corruption")
            }

            val msgCount = readUInt32LE(data, ownerOff + 32) // = 432, correct offset for len
            Logger.d(TAG, "fetchInbox: msgCount=$msgCount data.size=${data.size} bytes@432=${data[ownerOff + 32].toInt() and 0xFF} ${data[ownerOff + 33].toInt() and 0xFF} ${data[ownerOff + 34].toInt() and 0xFF} ${data[ownerOff + 35].toInt() and 0xFF} hex=${data[ownerOff + 32].toString(16)} ${data[ownerOff + 33].toString(16)} ${data[ownerOff + 34].toString(16)} ${data[ownerOff + 35].toString(16)}")
            if (msgCount == 0u) return emptyList()

            val result = mutableListOf<InboxMessageRef>()
            for (i in 0 until msgCount.toInt()) {
                val msgOff = 8 + i * 49
                if (msgOff + 49 > data.size) break

                val senderBytes = data.copyOfRange(msgOff, msgOff + 32)
                val senderBase58 = com.solana.mobilewalletadapter.common.util.Base58.encode(senderBytes)
                val nonce = readUInt64LE(data, msgOff + 32)
                val createdAt = readInt64LE(data, msgOff + 40)
                val claimed = data[msgOff + 48].toInt() != 0

                result.add(InboxMessageRef(senderBase58, nonce, createdAt, claimed))
            }
            result
        } catch (e: Exception) {
            Logger.e(TAG, "fetchInbox: ${e.message}", e)
            emptyList()
        }
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): UInt {
        // v1.1.14: each shifted term MUST be parenthesized.
        // In Kotlin all infix function calls (and / or / shl) share the same
        // precedence and are left-associative, so the unparenthesized form
        //    byte0 | byte1 << 8
        // parses as
        //    (byte0 | byte1) << 8
        // not the intended
        //    byte0 | (byte1 << 8)
        // For bytes [0x01, 0x00, 0x00, 0x00] this still passes a unit test
        // (1 << 8 = 256, ... eventually wraps to 0 when shifted left 24),
        // so the bug went undetected until inbox decoding started reading
        // non-zero data at offset 432 on Solana devnet.
        return (data[offset].toUInt() and 0xFFu) or
                ((data[offset + 1].toUInt() and 0xFFu) shl 8) or
                ((data[offset + 2].toUInt() and 0xFFu) shl 16) or
                ((data[offset + 3].toUInt() and 0xFFu) shl 24)
    }

    private fun readUInt64LE(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) {
            result = result or ((data[offset + i].toLong() and 0xFFL) shl (8 * i))
        }
        return result
    }

    private fun readInt64LE(data: ByteArray, offset: Int): Long = readUInt64LE(data, offset)

    /** Render a byte array as lowercase hex (no 0x prefix, no separators). */
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * v1.9.3: Pre-flight inbox-space check for the recipient of an outgoing
     * send. Returns a snapshot of how many unclaimed slots the recipient
     * currently has in their Inbox PDA, or `null` if the check itself
     * failed (RPC timeout, malformed data, etc.).
     *
     * Returns `null` ONLY for genuine error paths — NOT for "inbox doesn't
     * exist on chain yet" (that returns `InboxSpace(unclaimedCount = 0,
     * hasSpace = true)` since a brand-new inbox trivially has room).
     *
     * Caller contract: when this returns non-null and `hasSpace == false`,
     * the send MUST be aborted before going to MWA. When this returns
     * null, the caller should fail-open (let the chain reject) rather than
     * block legitimate sends on a transient RPC hiccup.
     *
     * The check is O(1) (one getAccountInfo RPC, scan 8 fixed slots). It
     * mirrors the on-chain `send_message` inbox check in lib.rs:
     *
     *     if msg_count >= INBOX_MAX_MESSAGES {
     *         // need at least one claimed slot to evict
     *         for i in 0..INBOX_MAX_MESSAGES {
     *             if inbox.messages[i].claimed { ... overwrite ... }
     *         }
     *         require!(evict_idx.is_some(), InboxFull);
     *     }
     *
     * If all 8 slots are unclaimed, the program would reject the tx — we
     * catch it here so the user gets a clear UI error instead of a "tx
     * failed" wall from the wallet.
     */
    suspend fun checkRecipientInboxSpace(recipientPubkey: ByteArray): InboxSpace? {
        return try {
            val inboxPda = findInboxPda(recipientPubkey)
            val accountInfo = rpc.getAccountInfo(inboxPda.toString())
            if (accountInfo == null) {
                // Inbox PDA doesn't exist yet (recipient has never received
                // a whisper). Brand-new inbox = 0/8 unclaimed = has space.
                return InboxSpace(unclaimedCount = 0, hasSpace = true)
            }
            val dataArray = accountInfo.optJSONArray("data")
            if (dataArray == null) {
                Logger.w(TAG, "checkRecipientInboxSpace: accountInfo has no 'data' array — fail-open")
                return InboxSpace(unclaimedCount = 0, hasSpace = true)
            }
            if (dataArray.length() < 1) {
                return InboxSpace(unclaimedCount = 0, hasSpace = true)
            }
            val dataBase64 = dataArray.optString(0, "")
            if (dataBase64.isEmpty()) {
                return InboxSpace(unclaimedCount = 0, hasSpace = true)
            }
            val data = Base64.decode(dataBase64, Base64.NO_WRAP)
            // Layout: 8 disc + 392 messages (8×49) + 32 owner + 4 len + 1 bump + 3 _pad
            // Need at least 8 + 49 to even check the first slot.
            if (data.size < 8 + 49) {
                return InboxSpace(unclaimedCount = 0, hasSpace = true)
            }
            var unclaimed = 0
            for (i in 0 until 8) {
                val msgOff = 8 + i * 49
                if (msgOff + 49 > data.size) break
                val claimed = data[msgOff + 48].toInt() != 0
                if (!claimed) unclaimed++
            }
            InboxSpace(unclaimedCount = unclaimed, hasSpace = unclaimed < 8)
        } catch (e: Exception) {
            Logger.e(TAG, "checkRecipientInboxSpace: RPC error — fail-open", e)
            null
        }
    }

    companion object {
        private const val TAG = "WhisperService"

        const val PROGRAM_ID = "CCTfaFAHLPHQJrEcfKvvbeCKoEL2S5uFogev2DUo1kKw"  // v1.9.0: clean devnet reset (was Dv1UzYXq... v1.8.33)
        // v1.9.0: PDAs derived from [b"seed"] + new programId.
        // Runtime self-check in WhisperService.init 會比對 hardcode vs derived,不一致就 throw。
        const val TREASURY_PDA = "4k4Uu9dAXczWGexPwyHFh7m62PHHGBXJ58ej34JKajHc"
        const val CHRISTMAS_JACKPOT_PDA = "HZ6Wixru6GjySL9E1t9arsSrf445kxoNuS5n9vcQVVxE"
        const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
    }
}

/**
 * A single entry in a user's on-chain Inbox PDA.
 *
 * @property senderBase58 Base58-encoded sender public key
 * @property nonce The nonce used when this message was sent
 * @property createdAt Unix timestamp (seconds) when the message was sent
 * @property claimed True if the recipient has already unlocked this message
 */
data class InboxMessageRef(
    val senderBase58: String,
    val nonce: Long,
    val createdAt: Long,
    val claimed: Boolean,
)

/**
 * v1.9.3: Result of a pre-flight inbox-space check on a recipient.
 *
 * @property unclaimedCount How many of the 8 inbox slots are unclaimed
 *   (i.e. unread) for the recipient. 0..8 inclusive.
 * @property hasSpace True if a new message could be delivered without
 *   triggering the on-chain InboxFull error. Equivalent to
 *   `unclaimedCount < 8`.
 */
data class InboxSpace(
    val unclaimedCount: Int,
    val hasSpace: Boolean,
)

/**
 * v1.3.0: On-chain Message PDA detail.
 *
 * Returned by [WhisperService.fetchMessageDetail] after a successful unlock
 * so the InboxScreen can show:
 * - reward paid (in lamports → user-readable SOL)
 * - encrypted content SHA-256 hash
 * - encrypted content + key blobs (hex), for inspection / manual decrypt
 *
 * NOTE: actual X25511 MWA-based decryption is V2 (requires wallet adapter
 * that exposes X25519 secret, or a separate device key). For v1.3.0 we
 * surface the on-chain data only — the dApp can still display "content
 * hash matches" against a SHA-256 the sender published out-of-band.
 */
data class MessageDetail(
    val senderBase58: String,
    val recipientBase58: String,
    val nonce: Long,
    val createdAt: Long,
    val unlockedAt: Long,
    val rewardPaidLamports: Long,
    val contentHashHex: String,
    val encryptedContentHex: String,
    val encryptedKeyHex: String,
)
