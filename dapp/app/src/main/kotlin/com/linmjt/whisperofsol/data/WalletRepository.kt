package com.linmjt.whisperofsol.data

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionParams
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.common.util.Base58
import com.linmjt.whisperofsol.util.Logger
import com.linmjt.whisperofsol.util.SeedVaultOnlySender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WalletRepository — MWA 2.2.0 connection to Seed Vault wallet
 *
 * v3.0.20: Seeker-only design. This app only supports Seed Vault
 * (com.solanamobile.wallet, Seeker 內建系統級 MWA provider).
 * 對其他 MWA 錢包 (Solflare / Backpack / Phantom) 不支援也不測試:
 *   - AndroidManifest.xml <queries> 只列 com.solanamobile.wallet
 *   - ConnectionIdentity 用 docs.solanamobile.com (Seed Vault 友好)
 *   - Seed Vault 是 system-level MWA provider,Seeker OS 自動 launch
 *
 * 為什麼不支援其他錢包: ConnectionIdentity.favicon.svg 的 fetch
 * 在非 Seed Vault 環境可能失敗(各錢包對 identityUri 處理不同),
 * 為了避免混淆的 UX,我們在 manifest 層面明確只查詢 Seed Vault。
 *
 * Real MWA integration (no stubs):
 *   1. connect(activityResultSender) → launches MWA authorize → Seed Vault prompt
 *   2. signAndSendTransactions(sender, transactions) → MWA 2.2.0 transact { ops -> signAndSendTransactions }
 *
 * StateFlow broadcasts current auth to ViewModels.
 * Singleton — HomeViewModel shares this with anything that needs MWA access.
 *
 * Cluster: devnet (per project goal).
 */
class WalletRepository(
    private val rpcCluster: com.solana.mobilewalletadapter.clientlib.RpcCluster =
        com.solana.mobilewalletadapter.clientlib.RpcCluster.Devnet
) {

    /**
     * ConnectionIdentity — must be filled per
     * https://docs.solanamobile.com/mobile-wallet-adapter/using-mwa
     *
     * Rules:
     *   - identityUri must NOT be github.com / placeholder / IPFS
     *   - iconUri must be relative to identityUri
     */
    private val connectionIdentity = ConnectionIdentity(
        identityName = "Whisper of Sol",
        // v1.4.0 fix: use a known-resolving identityUri. The earlier
        // "https://whisperofsol.app" had no DNS record -> SMS Wallet's
        // favicon fetch hung for 5 s, triggering ANR right after the user
        // tapped "Connect" in the bottom sheet. docs.solanamobile.com is
        // verified 200 OK with a valid /favicon.ico (relative URI).
        identityUri = Uri.parse("https://docs.solanamobile.com"),
        // v1.8.3: switch from .ico to .svg. docs.solanamobile.com/favicon.svg
        // returns image/svg+xml + 200 OK (verified 2026-10-05).
        //
        // v1.8.6-experiment tested dropping iconUri entirely (empty string)
        // on Wun's hypothesis that icon rendering triggered the wallet
        // FPS hang. Result: FPS recovery observed was due to fresh device
        // state, NOT the iconUri change (icon must be present in wallet
        // approval UI for proper UX). Reverted to favicon.svg.
        iconUri = Uri.parse("favicon.svg")
    )

    /**
     * v1.8.19: SINGLETON MobileWalletAdapter — created once per WalletRepository
     * instance, never recreated on subsequent connect() calls.
     *
     * Per user feedback 2026-10-07 23:33 + the official
     * solana-kotlin-compose-scaffold reference (Hilt @Provides singleton),
     * MWA adapter should be a single instance per app. Recreating it on
     * every connect() call:
     *   - Wasted a fresh WebSocket port each call (the port is in mWalletUriBase)
     *   - Lost any in-flight state (authToken, association session)
     *   - Race: a previously-created adapter's MWABottomSheetActivity may still
     *     be on screen when we try to create a new one, causing ANR
     *     (user reported this on device 100: 'MWABottomSheetActivity ANR' log).
     *
     * Scaffold uses Hilt @Provides — we use a class-level val (same effect
     * since WalletRepository itself is a singleton in our app).
     */
    internal val walletAdapter: MobileWalletAdapter = MobileWalletAdapter(
        connectionIdentity = connectionIdentity,
        timeout = 90_000,  // v1.8.18: scaffold default (Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
    ).also { it.rpcCluster = this.rpcCluster }

    /** ActivityResultSender (must be the same instance passed in for connect + transact) */
    internal var connectSender: ActivityResultSender? = null

    // v1.8.8: serialize all MWA transact() calls. Without this, two concurrent
    // signMessages / signAndSendTransactions calls (e.g., quick double-tap on
    // a Retry button, or a setup retry racing with a stale request) can both
    // hit adapter.transact() simultaneously, confusing the wallet's session
    // state machine and potentially triggering MWABottomSheetActivity ANR.
    // Fair mutex: concurrent callers queue, not stack.
    private val transactMutex = Mutex()

    /** Current authorization state */
    private val _currentAuth = MutableStateFlow<WalletAuth?>(null)
    val currentAuth: StateFlow<WalletAuth?> = _currentAuth.asStateFlow()

    /** Public key as bytes (for convenience, derived from auth) */
    val publicKey: ByteArray?
        get() = _currentAuth.value?.publicKeyBytes

    var lastError: String? = null
        private set

    fun initialize() {
        Logger.d(TAG, "WalletRepository initialized (MWA 2.2.0 real integration)")
    }

    fun isConnected(): Boolean = _currentAuth.value != null

    /** v1.8.23: snapshot of the current auth (or null). */
    fun currentAuth(): WalletAuth? = _currentAuth.value

    fun cleanup() {
        disconnectSession()
    }

    /**
     * Connect to MWA wallet.
     *
     * @param sender ActivityResultSender pre-registered in Activity.onCreate
     * @return WalletAuth on success, null on failure
     */
    suspend fun connect(sender: ActivityResultSender): WalletAuth? {
        val repo = this
        return withContext(Dispatchers.Main) {
            try {
                // v3.0.21: Restrict to Seed Vault only. Throws on failure —
                // no silent chooser fallback. The UI layer surfaces lastError
                // to the user as a clear "Seeker only" message.
                SeedVaultOnlySender.wrap(sender)

                // v1.8.18: REVERT v1.1.7's 600_000 (10 min) → 90_000 (90s).
                //
                // Root cause per user feedback 2026-10-07 17:33 + reference
                // https://github.com/solana-mobile/solana-kotlin-compose-scaffold:
                //
                //   The official dApp scaffold uses `MobileWalletAdapter(connectionIdentity)`
                //   which defaults to MWA SDK Scenario.DEFAULT_CLIENT_TIMEOUT_MS = 90000 (90s).
                //   We were passing an explicit 600_000 (10 min), 6.6× longer than the
                //   official scaffold. Result: when Seed Vault hangs on Seeker biometric
                //   (root cause was Phantom 5.x Seeker bug OR Seed Vault onboarding), the
                //   dApp spinner just sits for 10 minutes -- no error, no Retry, user
                //   thinks it's broken.
                //
                // v1.1.7's rationale (user-driven password prompts exceeding 3 min)
                // was the wrong tradeoff: it traded fail-fast UX for tolerating edge-case
                // slow unlocks. After 90 s the SDK returns Failure; user sees the error
                // and can Retry -- which is the right behavior for the 99% case where
                // the wallet is hung (not just slow).
                //
                // v1.8.18 also removes the SIGN_MESSAGES_TIMEOUT_MS=60_000L workaround
                // (v1.8.10) for the same reason: with the adapter at 90 s, our extra
                // wrapper is redundant -- SDK Scenario.DEFAULT_CLIENT_TIMEOUT_MS handles it.
                // v1.8.19: reuse the singleton walletAdapter (see class-level property).
                Logger.d(TAG, "Dispatching MWA authorize to Seed Vault...")

                val result = repo.walletAdapter.connect(sender)
                when (result) {
                    is TransactionResult.Success -> {
                        val authResult = result.authResult
                        val publicKeyBytes = authResult.publicKey
                        val publicKeyBase58 = Base58.encode(publicKeyBytes)
                        Logger.d(
                            TAG,
                            "Authorized: ${publicKeyBase58.take(4)}...${publicKeyBase58.takeLast(4)}"
                        )
                        repo.lastError = null
                        // v1.8.19: walletAdapter is now a singleton (class val),
                        // not re-assigned on each connect.
                        repo.connectSender = sender
                        val auth = WalletAuth(
                            publicKeyBytes = publicKeyBytes,
                            publicKeyBase58 = publicKeyBase58,
                            authToken = authResult.authToken,
                            accountLabel = authResult.accountLabel,
                        )
                        repo._currentAuth.value = auth
                        auth
                    }
                    is TransactionResult.Failure -> {
                        val msg = result.e?.message ?: "Connection failed"
                        Logger.e(TAG, "MWA Failure: $msg")
                        repo.lastError = msg
                        // v1.8.19: walletAdapter is a singleton -- no longer
                        // need to drop the reference. Connect failure is recorded
                        // in lastError; next connect() reuses the same adapter.
                        repo.connectSender = null
                        null
                    }
                    is TransactionResult.NoWalletFound -> {
                        Logger.e(TAG, "No wallet found")
                        repo.lastError = "No compatible wallet installed"
                        // v1.8.19: walletAdapter is a singleton, no need to clear
                        repo.connectSender = null
                        null
                    }
                    else -> {
                        Logger.e(TAG, "Unknown result: ${result::class.java.name}")
                        repo.lastError = "Unknown error"
                        null
                    }
                }
            } catch (e: SeedVaultOnlySender.SeedVaultNotInstalledException) {
                Logger.e(TAG, "Seed Vault not installed — Seeker-only", e)
                repo.lastError = e.message
                repo.connectSender = null
                null
            } catch (e: SeedVaultOnlySender.ReflectionFailedException) {
                Logger.e(TAG, "Failed to wrap ActivityResultSender", e)
                repo.lastError = e.message
                repo.connectSender = null
                null
            } catch (e: Exception) {
                Logger.e(TAG, "Connect exception", e)
                repo.lastError = e.message
                // v1.8.19: walletAdapter is a singleton, no need to clear.
                // The next connect() call will reuse the same adapter.
                repo.connectSender = null
                null
            }
        }
    }

    /**
     * Sign and send transactions through MWA 2.2.0 transact API.
     *
     * @param sender ActivityResultSender (same as connect)
     * @param transactions List of unsigned transactions (bytes) to sign and send
     * @return Pair<List<signature bytes>, error message?> on completion
     */
    suspend fun signAndSendTransactions(
        sender: ActivityResultSender,
        transactions: List<ByteArray>,
        // v1.1.17: caller picks whether to skip preflight simulation.
        // Send message: preflight works (SMS Wallet simulates treasury
        //   CPI for 0.01 SOL fee), so leave on to show balance delta.
        // Unlock message: wallet can't simulate our custom program
        //   (unlock_message -> claims flag), preflight returns
        //   "couldn't be simulated" and the wallet disables Approve.
        //   Set skipPreflight=true so user can approve.
        skipPreflight: Boolean = false,
    ): SignAndSendResult = transactMutex.withLock {
        withContext(Dispatchers.Main) {
        val adapter = walletAdapter
        if (adapter == null) {
            return@withContext SignAndSendResult(
                signatures = emptyList(),
                error = "Wallet not connected"
            )
        }
        try {
            // v1.8.17 (revert v1.8.16): aligned with mock-mwa-wallet's
            // SendTransactionsUseCase default. The mock wallet passes
            // preflightCommitment = "processed" so the dApp gets a tx
            // signature as soon as the tx hits a processed block, not
            // "confirmed" / "finalized" (which can take 12s+). See
            // mock-mwa-wallet/app/src/main/java/com/solana/mwallet/
            // usecase/SendTransactionsUseCase.kt:121.
            //
            // Why this matters: previously we passed commitment = null,
            // which lets the wallet choose its own default. On Seed Vault
            // (Saga/Seeker), the wallet's default wait window was the
            // reason the dApp UI spinner didn't update for 100s+ — the
            // wallet was still waiting on commitment before returning
            // signatures. Setting "processed" here matches the official mock
            // wallet and shortens the perceived spinner window dramatically.
            val params = TransactionParams(
                minContextSlot = null,
                commitment = "processed",
                skipPreflight = skipPreflight,
                maxRetries = null,
                waitForCommitmentToSendNextTransaction = false,
            )

            val txArray = transactions.toTypedArray()

            // v1.1.4+ debug: dump tx bytes + params
            txArray.forEachIndexed { i, bytes ->
                Logger.d(TAG, "signAndSend tx[$i]: ${bytes.size} bytes, base64=${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).take(60)}...")
            }
            Logger.d(TAG, "signAndSend params: skipPreflight=${params.skipPreflight} commitment=${params.commitment} minContextSlot=${params.minContextSlot} maxRetries=${params.maxRetries}")

            val result: TransactionResult<com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.SignAndSendTransactionsResult> =
                adapter.transact(sender, null) { _ ->
                    // v1.8.8 note: `_` is the MWA SDK client interface
                    // (MobileWalletAdapterClient). MWA 2.2.0 SDK manages the
                    // session lifecycle (including reauthorize) internally;
                    // the dApp does NOT receive authToken updates from this
                    // lambda. _currentAuth.authToken is only used for UI
                    // display, not for signing — real signing uses the SDK's
                    // internal session.
                    //
                    // v1.8.17: rely on MWA SDK Scenario.DEFAULT_CLIENT_TIMEOUT_MS
                    // (90s) built into the library, mirroring the mock wallet's
                    // pattern of trusting the SDK's per-scenario timeout rather
                    // than wrapping every call in our own. The previously added
                    // 60s wrapper (v1.8.16) was removed -- it was a band-aid
                    // that masked the real cause (commitment=null letting the
                    // wallet wait for "confirmed"/"finalized").
                    signAndSendTransactions(txArray, params)
                }

            // v1.1.4+ debug: log the exact result type
            Logger.d(TAG, "signAndSend result type: ${result::class.java.simpleName}")
            when (result) {
                is TransactionResult.Success -> {
                    val signatures: Array<ByteArray>? = result.payload?.signatures
                    Logger.d(TAG, "signAndSend Success: signatures=${signatures?.size ?: 0}")
                    if (signatures.isNullOrEmpty()) {
                        return@withContext SignAndSendResult(
                            signatures = emptyList(),
                            error = "No signature in result"
                        )
                    }
                    val sigsBase58 = signatures.map { Base58.encode(it) }
                    Logger.d(TAG, "signAndSend success: ${sigsBase58.size} tx, sigs=${sigsBase58}")
                    SignAndSendResult(
                        signatures = sigsBase58,
                        error = null
                    )
                }
                is TransactionResult.Failure -> {
                    val e = result.e
                    // v1.1.4+ debug: dump the full exception
                    val msg = e?.message ?: "Signing failed"
                    val typeName = e?.let { it::class.java.name } ?: "<null>"
                    val causeMsg = e?.cause?.let { "${it::class.java.name}: ${it.message}" } ?: "<null>"
                    Logger.e(TAG, "MWA signAndSend failure: type=$typeName message=$msg cause=$causeMsg")
                    if (e != null) {
                        Logger.e(TAG, "MWA signAndSend exception stacktrace", e)
                    }
                    // Also dump the raw toString of result
                    Logger.e(TAG, "MWA signAndSend raw result: $result")
                    SignAndSendResult(
                        signatures = emptyList(),
                        error = msg
                    )
                }
                is TransactionResult.NoWalletFound -> {
                    Logger.e(TAG, "No wallet found during signAndSend")
                    SignAndSendResult(
                        signatures = emptyList(),
                        error = "No wallet found"
                    )
                }
                else -> {
                    Logger.e(TAG, "Unexpected signAndSend result: ${result::class.java.name} toString=$result")
                    SignAndSendResult(
                        signatures = emptyList(),
                        error = "Unexpected result type: ${result::class.java.simpleName}"
                    )
                }
            }
        } catch (e: Throwable) {
            // v1.1.4+ debug: catch Throwable (not just Exception) so we capture Errors too
            Logger.e(TAG, "signAndSendTransactions THROWABLE caught", e)
            // v1.8.8: hard throwable -> stale adapter suspect. Clear so next
            // connect() rebuilds MWA session cleanly instead of reusing a
            // half-open WebSocket. Next connect will create a fresh adapter.
            if (walletAdapter != null) {
                Logger.w(TAG, "signAndSendTransactions: throwable caught; walletAdapter is singleton so no clear needed (v1.8.19)")
                // v1.8.19: walletAdapter is a class-level singleton, cannot null it.
                connectSender = null
            }
            SignAndSendResult(
                signatures = emptyList(),
                error = e.message ?: e::class.java.simpleName
            )
        }
    }
    }

    fun disconnectSession() {
        // v1.8.19: walletAdapter is a singleton. We can only clear auth state.
        // The adapter itself lives for the entire WalletRepository lifetime.
        connectSender = null
        _currentAuth.value = null
    }

    /**
     * v1.4.0': Sign arbitrary byte messages with the connected wallet's Ed25519
     * key via MWA signMessagesDetached. The wallet produces a 64-byte signature
     * (R || S, RFC 8032) for each message.
     *
     * Critical contract: the underlying wallet implementation MUST use
     * deterministic Ed25519 (RFC 8032 §5.1.6) for the signature to be stable
     * across re-signings. All major Solana wallets (SMS Wallet / Seed Vault,
     * Phantom, Solflare) follow this spec.
     *
     * Use case: WhisperKeyStore calls this once with a fixed message to
     * derive a deterministic X25519 secret for content decryption. Because
     * the signature is deterministic, the same wallet re-signing the same
     * message on a fresh device produces the same secret — enabling
     * cross-device recovery without backing up device state.
     */
    suspend fun signMessages(
        sender: ActivityResultSender,
        messages: List<ByteArray>,
    ): SignMessagesResult {
        // v1.8.25: SIMPLIFIED to mirror solana-kotlin-compose-scaffold verbatim.
        // Removed transactMutex.withLock wrapper, removed withContext(Main)
        // wrapper, removed extra 'null' arg to adapter.transact. v1.8.24's
        // diagnostic build traced the hang to this point (before
        // adapter.transact) -- we believe one of those wrappers is the
        // culprit. Scaffold pattern (no wrapper, single arg transact) works
        // on the same wallet+adapter.
        Logger.w(TAG, "signMessages: ENTER (v1.8.25 scaffold-mirror path), ${messages.size} message(s)")
        val adapter = walletAdapter
        if (adapter == null) {
            Logger.e(TAG, "signMessages: no adapter (wallet not connected)")
            return SignMessagesResult.Failure
        }
        val pubKey = _currentAuth.value?.publicKeyBytes
        if (pubKey == null) {
            Logger.e(TAG, "signMessages: no publicKey in currentAuth")
            return SignMessagesResult.Failure
        }
        Logger.w(TAG, "signMessages: about to call adapter.transact")
        return try {
            val addresses = Array(messages.size) { pubKey }
            val messagesArray = messages.toTypedArray()
            Logger.d(TAG, "signMessages: ${messagesArray.size} message(s)")

            val result: TransactionResult<com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.SignMessagesResult> =
                adapter.transact(sender) {
                    // ─── SCAFFOLD-EXACT ────────────────────────────────
                    // Same call signature as scaffold's MainViewModel.signMessage:
                    //   adapter.transact(sender) { signMessagesDetached(...) }
                    // No `_ ->` parameter rename, no `null` for signInWithSolana.
                    signMessagesDetached(addresses, messagesArray)
                }

            when (result) {
                is TransactionResult.Success -> {
                    val signedMessages = result.payload?.messages
                    if (signedMessages == null || signedMessages.isEmpty()) {
                        Logger.e(TAG, "signMessages: success but no signed messages")
                        SignMessagesResult.Failure
                    } else {
                        val sigs = signedMessages.mapIndexed { idx, signed ->
                            val sig = signed.signatures.firstOrNull()
                            if (sig == null) {
                                Logger.e(TAG, "signMessages[$idx]: null signature")
                                ByteArray(0)
                            } else {
                                Logger.d(TAG, "signMessages[$idx]: ${sig.size} bytes")
                                sig
                            }
                        }
                        SignMessagesResult(sigs)
                    }
                }
                is TransactionResult.Failure -> {
                    val e = result.e
                    Logger.e(TAG, "signMessages failure: type=${e?.javaClass?.name} message=${e?.message}")
                    SignMessagesResult.Failure
                }
                else -> {
                    Logger.e(TAG, "signMessages unexpected result: ${result::class.java.name}")
                    SignMessagesResult.Failure
                }
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "signMessages THROWABLE: ${e.message}", e)
            SignMessagesResult.Failure
        }
    }

    fun disconnect() {
        disconnectSession()
        Logger.d(TAG, "Disconnected")
    }

    companion object {
        private const val TAG = "WalletRepo"

        // v1.8.7: cap signMessages at 30s. Seeker hardware signing via Seed Vault can be
        // slow when ExoPlayer/MediaCodec is busy with the auth UI video; 10s was too tight
        // — wallet response would arrive at ~10s but get cancelled by the 8ms race window
        // before withTimeoutOrNull cancellation propagated to Main.
        // Returning Failure triggers HomeViewModel.setupState = Failed (NO SecureRandom
        // fallback since v1.8.4).
        // v1.8.18: removed SIGN_MESSAGES_TIMEOUT_MS = 60_000L. The SDK's
        // Scenario.DEFAULT_CLIENT_TIMEOUT_MS (90s) handles per-request timeout
        // since we changed MobileWalletAdapter timeout from 600_000 → 90_000.
        // No more wrapper needed.
        // (history: was 30_000L in v1.8.7, 60_000L from v1.8.10 to v1.8.17.)

        @JvmStatic
        val instance: WalletRepository = WalletRepository()
    }
}

data class WalletAuth(
    val publicKeyBytes: ByteArray,
    val publicKeyBase58: String,
    val authToken: String?,
    val accountLabel: String?,
)

data class SignAndSendResult(
    val signatures: List<String>,
    val error: String?,
) {
    val isSuccess: Boolean get() = signatures.isNotEmpty() && error == null
}

/**
 * v1.4.0': result of [WalletRepository.signMessages].
 *
 * Each entry in [signatures] is a 64-byte Ed25519 signature produced by the
 * wallet for the corresponding message in the input list. Signatures are in
 * the same order as the input messages.
 *
 * Used by [com.linmjt.whisperofsol.crypto.WhisperKeyStore] to derive the
 * device-bound X25519 secret from a deterministic wallet signature over
 * the fixed message `WhisperOfSol:X25519:v1`.
 */
data class SignMessagesResult(
    val signatures: List<ByteArray>,
) {
    val isSuccess: Boolean get() = signatures.isNotEmpty()

    companion object {
        val Failure = SignMessagesResult(emptyList())
    }
}
