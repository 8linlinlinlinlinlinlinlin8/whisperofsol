package com.linmjt.whisperofsol.service

import android.util.Base64
import com.linmjt.whisperofsol.util.Logger
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Durable Nonce Service — addresses the Solana "Blockhash expired" problem
 * caused by Seed Vault user-input latency exceeding the 60–90s recent-blockhash TTL.
 *
 * Pattern (Option A: durable nonce):
 *   1. First time: create + initialize a per-wallet nonce account (one-time setup).
 *      - Seed = "nonce" (UTF-8) → deterministic address.
 *      - Owner = system_program; authority = user wallet pubkey.
 *      - Pays ~1.5M lamports rent-exempt from user wallet.
 *      - Initial stored nonce = the recent blockhash at init time (valid forever
 *        until consumed).
 *   2. Every send_message tx prepends an `advance_nonce_account` ix:
 *      - Consumes current stored nonce (matches tx.recent_blockhash).
 *      - Writes a fresh durable nonce to the account for the next send.
 *      - Does NOT touch recent_blockhashes_sysvar at runtime — system program
 *        reads the stored nonce from the account itself.
 *      - User signs once (no time pressure). Tx stays valid forever.
 *
 * Account layout (Solana nonce account, 80 bytes bincode-serialized):
 *   [4 bytes ] state_version (u32 LE)
 *   [4 bytes ] Nonce variant tag (u32 LE = 1 for Initialized)
 *   [32 bytes] authority (Pubkey)
 *   [32 bytes] blockhash (the stored durable nonce)
 *   [8 bytes ] lamports_per_signature (u64 LE, legacy/fee-calculator)
 *
 * Rent exempt minimum: 1,500,000 lamports (~0.0015 SOL) for the 80-byte data.
 *
 * Reference: https://github.com/anza-xyz/agave/blob/master/programs/system/src/system_processor.rs
 *            solana-program nonce/state.rs (NonceState / NonceData)
 */
class NonceAccountService(private val rpc: SolanaRpcService = SolanaRpcService()) {

    // The systemProgram and recentBlockhashesSysvar SolanaPublicKey instances
    // live in the companion object as @JvmStatic vals so they can be
    // referenced statically (e.g., from WhisperService) without needing
    // an instance of NonceAccountService. Within this class we reference
    // them by their unqualified name, which Kotlin resolves to the
    // companion's static fields.


    /**
     * Compute the nonce account address for a given wallet pubkey.
     *
     * createAccountWithSeed derives the address as:
     *   address = sha256(base_pubkey || seed_bytes || owner_program_id)
     *
     * For our case: base = user wallet, seed = "nonce", owner = system_program.
     * This is deterministic and reproducible from the wallet pubkey alone — no
     * keypair file or extra storage needed.
     */
    fun findNonceAccountAddress(walletPubkey: SolanaPublicKey): SolanaPublicKey {
        val seed = "nonce"
        val seedBytes = seed.toByteArray(StandardCharsets.UTF_8)
        val base = walletPubkey.bytes
        val programId = systemProgram.bytes
        val combined = ByteArray(base.size + seedBytes.size + programId.size)
        System.arraycopy(base, 0, combined, 0, base.size)
        System.arraycopy(seedBytes, 0, combined, base.size, seedBytes.size)
        System.arraycopy(programId, 0, combined, base.size + seedBytes.size, programId.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(combined)
        // Solana public keys are 32 bytes; the sha256 output is exactly 32.
        return SolanaPublicKey(hash)
    }

    /**
     * Check whether the nonce account exists AND is initialized.
     * Returns false if account is missing, uninitialized, or data is wrong size.
     */
    suspend fun nonceAccountReady(nonceAccount: SolanaPublicKey): Boolean {
        val info = rpc.getAccountInfo(nonceAccount.toString()) ?: return false
        val owner = info.optString("owner", null) ?: return false
        if (owner != SYSTEM_PROGRAM_ID) return false
        // Solana getAccountInfo returns `data` as JSONArray: [base64_str, "base64"].
        // optString on a JSONArray returns the array's toString() which is not valid base64.
        val dataArr = info.optJSONArray("data") ?: return false
        val dataBase64 = dataArr.optString(0, null) ?: return false
        val data = try {
            Base64.decode(dataBase64, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Logger.e(TAG, "nonceAccountReady: bad base64", e)
            return false
        }
        if (data.size != NONCE_ACCOUNT_DATA_SIZE) return false
        // Verify variant tag == Initialized (1)
        val variant = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return variant == NONCE_VARIANT_INITIALIZED
    }

    /**
     * Read the stored durable nonce from a nonce account.
     * Returns the 32-byte blockhash as a SolanaPublicKey, or null if missing/uninitialized.
     */
    suspend fun getStoredNonce(nonceAccount: SolanaPublicKey): SolanaPublicKey? {
        val info = rpc.getAccountInfo(nonceAccount.toString()) ?: return null
        val owner = info.optString("owner", null) ?: return null
        if (owner != SYSTEM_PROGRAM_ID) return null
        // Solana getAccountInfo returns `data` as JSONArray: [base64_str, "base64"].
        val dataArr = info.optJSONArray("data") ?: return null
        val dataBase64 = dataArr.optString(0, null) ?: return null
        val data = try {
            Base64.decode(dataBase64, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Logger.e(TAG, "getStoredNonce: bad base64", e)
            return null
        }
        if (data.size < NONCE_BLOCKHASH_OFFSET + 32) return null
        val variant = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (variant != NONCE_VARIANT_INITIALIZED) return null
        val blockhash = ByteArray(32)
        System.arraycopy(data, NONCE_BLOCKHASH_OFFSET, blockhash, 0, 32)
        return SolanaPublicKey(blockhash)
    }

    /**
     * Build a 1-tx setup: createAccountWithSeed + initializeNonceAccount.
     *
     * The user wallet signs as:
     *   - fee payer
     *   - funder of the new account (1.5M lamports rent)
     *   - base for createAccountWithSeed derivation
     *   - authority for initializeNonceAccount
     *
     * After this tx confirms, the nonce account exists and is initialized,
     * with the current recent blockhash stored as the first durable nonce.
     *
     * Returns the base64-encoded unsigned transaction, or null on failure.
     */
    suspend fun buildInitializeNonceAccountTransaction(
        senderPubkey: ByteArray,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sender = SolanaPublicKey(senderPubkey)
            val nonceAccount = findNonceAccountAddress(sender)
            val recentBlockhash = rpc.getRecentBlockhash() ?: run {
                Logger.e(TAG, "buildInitializeNonceAccountTransaction: no recent blockhash")
                return@withContext null
            }

            // ── ix 0: createAccountWithSeed ────────────────────────────
            // Funds the new nonce account with rent-exempt lamports and
            // assigns system_program as owner.
            val createIxData = encodeCreateAccountWithSeed(
                base = sender,
                seed = NONCE_SEED,
                lamports = NONCE_ACCOUNT_RENT_LAMPORTS,
                space = NONCE_ACCOUNT_DATA_SIZE.toLong(),
                owner = systemProgram,
            )
            val createIx = TransactionInstruction(
                programId = systemProgram,
                accounts = listOf(
                    // 0: funding account (signer, writable)
                    AccountMeta(sender, isSigner = true, isWritable = true),
                    // 1: new account (writable, not signer)
                    AccountMeta(nonceAccount, isSigner = false, isWritable = true),
                    // 2: base (signer, read-only) — must equal base in derivation
                    AccountMeta(sender, isSigner = true, isWritable = false),
                ),
                data = createIxData,
            )

            // ── ix 1: initializeNonceAccount ──────────────────────────
            // Sets the nonce authority to the user wallet.
            val initIxData = encodeInitializeNonceAccount(sender)
            val initIx = TransactionInstruction(
                programId = systemProgram,
                accounts = listOf(
                    // 0: nonce account (writable, not signer)
                    AccountMeta(nonceAccount, isSigner = false, isWritable = true),
                    // 1: recent_blockhashes_sysvar (readonly)
                    AccountMeta(recentBlockhashesSysvar, isSigner = false, isWritable = false),
                    // 2: rent sysvar (readonly) — required by InitializeNonceAccount
                    AccountMeta(rentSysvar, isSigner = false, isWritable = false),
                ),
                data = initIxData,
            )

            val message = Message.Builder()
                .addInstruction(createIx)
                .addInstruction(initIx)
                .addFeePayer(sender)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(
                TAG,
                "buildInitializeNonceAccountTransaction: ${txBytes.size} bytes, " +
                        "nonceAccount=${nonceAccount.toString().take(8)}..., " +
                        "funding=${sender.toString().take(8)}..."
            )
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Logger.e(TAG, "buildInitializeNonceAccountTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * Build a separate `advance_nonce_account` only transaction.
     *
     * Used as a standalone "warmup" step when the caller wants to pre-advance
     * the nonce (e.g., to refresh the stored nonce ahead of building a tx).
     * For the typical [advance_nonce + send_message] single-tx pattern,
     * prefer [com.linmjt.whisperofsol.service.WhisperService.buildSendMessageTransaction]
     * which composes both ix's atomically.
     *
     * Returns the base64-encoded unsigned transaction, or null on failure.
     */
    suspend fun buildAdvanceNonceAccountTransaction(
        senderPubkey: ByteArray,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sender = SolanaPublicKey(senderPubkey)
            val nonceAccount = findNonceAccountAddress(sender)
            val recentBlockhash = rpc.getRecentBlockhash() ?: run {
                Logger.e(TAG, "buildAdvanceNonceAccountTransaction: no recent blockhash")
                return@withContext null
            }

            val advanceIxData = encodeAdvanceNonceAccount()
            val advanceIx = TransactionInstruction(
                programId = systemProgram,
                accounts = listOf(
                    // 0: nonce account (writable, not signer)
                    AccountMeta(nonceAccount, isSigner = false, isWritable = true),
                    // 1: recent_blockhashes_sysvar (readonly)
                    AccountMeta(recentBlockhashesSysvar, isSigner = false, isWritable = false),
                    // 2: nonce authority (signer) — must match authority in nonce account
                    AccountMeta(sender, isSigner = true, isWritable = false),
                ),
                data = advanceIxData,
            )

            val message = Message.Builder()
                .addInstruction(advanceIx)
                .addFeePayer(sender)
                .setRecentBlockhash(recentBlockhash)
                .build()

            val tx = Transaction(message)
            val txBytes = tx.serialize()
            Logger.d(
                TAG,
                "buildAdvanceNonceAccountTransaction: ${txBytes.size} bytes, " +
                        "nonceAccount=${nonceAccount.toString().take(8)}..."
            )
            Base64.encodeToString(txBytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Logger.e(TAG, "buildAdvanceNonceAccountTransaction: ${e.message}", e)
            null
        }
    }

    /**
     * Read the current stored nonce from the user's nonce account.
     * Convenience wrapper for [getStoredNonce].
     */
    suspend fun getStoredNonceForUser(senderPubkey: ByteArray): SolanaPublicKey? {
        val nonceAccount = findNonceAccountAddress(SolanaPublicKey(senderPubkey))
        return getStoredNonce(nonceAccount)
    }

    /**
     * Check if the user's nonce account is ready.
     * Convenience wrapper for [nonceAccountReady].
     */
    suspend fun nonceAccountReadyForUser(senderPubkey: ByteArray): Boolean {
        val nonceAccount = findNonceAccountAddress(SolanaPublicKey(senderPubkey))
        return nonceAccountReady(nonceAccount)
    }

    // ─── Instruction encoders (bincode-compatible) ────────────────────
    //
    // SystemInstruction enum variants (bincode u32 LE tags):
    //   0 = CreateAccount
    //   1 = Assign
    //   2 = Transfer
    //   3 = CreateAccountWithSeed
    //   4 = AdvanceNonceAccount
    //   5 = WithdrawNonceAccount
    //   6 = InitializeNonceAccount
    //   7 = AuthorizeNonceAccount
    //   8 = Allocate
    //   9 = AllocateWithSeed
    //   10 = AssignWithSeed
    //   11 = TransferWithSeed
    //
    // Sources:
    //   solana-program/src/system_instruction.rs
    //   agave/programs/system/src/system_processor.rs

    /**
     * Encode SystemInstruction::CreateAccountWithSeed payload.
     *
     * Layout (bincode):
     *   [4 bytes] variant = 3 (u32 LE)
     *   [32 bytes] base: Pubkey
     *   [8 bytes ] seed_len: u64 LE
     *   [N bytes ] seed bytes (UTF-8)
     *   [8 bytes ] lamports: u64 LE
     *   [8 bytes ] space: u64 LE
     *   [32 bytes] owner: Pubkey
     */
    private fun encodeCreateAccountWithSeed(
        base: SolanaPublicKey,
        seed: String,
        lamports: Long,
        space: Long,
        owner: SolanaPublicKey,
    ): ByteArray {
        val seedBytes = seed.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 32 + 8 + seedBytes.size + 8 + 8 + 32)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(3) // CreateAccountWithSeed variant
        buf.put(base.bytes)
        buf.putLong(seedBytes.size.toLong())
        buf.put(seedBytes)
        buf.putLong(lamports)
        buf.putLong(space)
        buf.put(owner.bytes)
        return buf.array()
    }

    /**
     * Encode SystemInstruction::InitializeNonceAccount payload.
     *
     * Layout (bincode):
     *   [4 bytes] variant = 6 (u32 LE)
     *   [32 bytes] authority: Pubkey
     */
    private fun encodeInitializeNonceAccount(authority: SolanaPublicKey): ByteArray {
        val buf = ByteBuffer.allocate(4 + 32).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(6) // InitializeNonceAccount variant
        buf.put(authority.bytes)
        return buf.array()
    }

    /**
     * Encode SystemInstruction::AdvanceNonceAccount payload.
     *
     * Layout (bincode):
     *   [4 bytes] variant = 4 (u32 LE)
     */
    private fun encodeAdvanceNonceAccount(): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(4) // AdvanceNonceAccount variant
        return buf.array()
    }

    companion object {
        private const val TAG = "NonceAccount"

        // ─── Pubkey constants ─────────────────────────────────────
        const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
        // The "B1ockHashes" (with capital I instead of lowercase L) typo is intentional
        // and part of the Solana protocol — see solana-program/src/sysvar.rs.
        const val RECENT_BLOCKHASHES_SYSVAR_ID = "SysvarRecentB1ockHashes11111111111111111111"
        const val RENT_SYSVAR_ID = "SysvarRent111111111111111111111111111111111"

        // ─── Nonce account data layout (80 bytes) ─────────────────
        const val NONCE_ACCOUNT_DATA_SIZE = 80
        // Offset to stored durable blockhash:
        //   [4 bytes] state_version + [4 bytes] variant + [32 bytes] authority
        const val NONCE_BLOCKHASH_OFFSET = 4 + 4 + 32
        // Bincode-serialized enum tag for Initialized variant (= 1).
        const val NONCE_VARIANT_INITIALIZED = 1

        // ─── Rent / sizing ─────────────────────────────────────────
        // Rent-exempt minimum for an 80-byte account (verified via
        // solana rent-exemption calculator for SystemAccount at 6_912_000
        // lamports per byte-year for ~1.95 MB minimum balance).
        const val NONCE_ACCOUNT_RENT_LAMPORTS = 1_500_000L

        // ─── Seed for createAccountWithSeed ────────────────────────
        // Used to deterministically derive the nonce account address
        // from the user's wallet pubkey.
        const val NONCE_SEED = "nonce"

        // Rent sysvar cached as field for convenience in init tx.
        // (Declared here so it's not lazily re-decoded each call.)
        @JvmStatic
        val rentSysvar: SolanaPublicKey = SolanaPublicKey.from(RENT_SYSVAR_ID)

        // System program cached.
        @JvmStatic
        val systemProgram: SolanaPublicKey = SolanaPublicKey.from(SYSTEM_PROGRAM_ID)

        // Recent blockhashes sysvar cached.
        @JvmStatic
        val recentBlockhashesSysvar: SolanaPublicKey = SolanaPublicKey.from(RECENT_BLOCKHASHES_SYSVAR_ID)
    }
}
