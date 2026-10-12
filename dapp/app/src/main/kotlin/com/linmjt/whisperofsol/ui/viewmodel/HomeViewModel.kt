package com.linmjt.whisperofsol.ui.viewmodel

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linmjt.whisperofsol.crypto.EncryptionResult
import com.linmjt.whisperofsol.crypto.WhisperCrypto
import com.linmjt.whisperofsol.crypto.WhisperKey
import com.linmjt.whisperofsol.crypto.WhisperKeyStore
import com.linmjt.whisperofsol.data.WalletRepository
import com.linmjt.whisperofsol.service.InboxMessageRef
import com.linmjt.whisperofsol.service.MessageDetail
import com.linmjt.whisperofsol.service.WhisperService
import com.linmjt.whisperofsol.util.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.nio.charset.StandardCharsets

/**
 * HomeViewModel — orchestrates wallet, treasury, and whisper operations.
 *
 * Real MWA integration:
 *   - connect()    → triggers MWA ActivityResultSender → Seed Vault prompts
 *   - sendWhisper() → builds unsigned tx → calls WalletRepository.signAndSendTransactions
 *
 * Activity plumbing:
 *   - [MainActivity] registers ActivityResultSender in onCreate
 *   - [MainActivity] subscribes to [connectRequests] / [signAndSendRequests]
 *     and invokes WalletRepository.connect / signAndSendTransactions on behalf of the ViewModel
 *   - [MainActivity] pushes results back via [onConnectResult] / [onSignAndSendResult]
 *
 * The ViewModel itself never holds a reference to the Activity (which would leak).
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            connected = readDebugConnectedFlag(),
            publicKeyShort = readDebugConnectedFlag().let { if (it) "Lin8...jT7" else null },
            treasuryLamports = readDebugConnectedFlag().let { if (it) 12_345_678_900L else null },
            jackpotLamports = readDebugConnectedFlag().let { if (it) 4_500_000_000L else null },
            expectedValue = readDebugConnectedFlag().let { if (it) 1_410_000L else null },
            treasuryStatus = readDebugTreasuryStatus(),
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Connect request — emitted when ViewModel wants the host Activity
     * to invoke MWA via ActivityResultSender. Activity observes this
     * and calls walletRepo.connect(sender).
     */
    private val _connectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val connectRequests: SharedFlow<Unit> = _connectRequests.asSharedFlow()

    /**
     * Sign-and-send request — emitted when sendWhisper needs Activity
     * to invoke MWA transact. Activity observes and calls
     * walletRepo.signAndSendTransactions(sender, transactions).
     */
    private val _signAndSendRequests = MutableSharedFlow<SignAndSendRequest>(extraBufferCapacity = 4)
    val signAndSendRequests: SharedFlow<SignAndSendRequest> = _signAndSendRequests.asSharedFlow()

    /**
     * v1.4.0': sign-message request — emitted when WhisperKeyStore needs
     * v1.6.0: ask the wallet to sign a fixed message so we can derive a
     * deterministic X25519 keypair from the signature. Cross-device
     * recovery works because the same wallet produces the same signature.
     */
    private val _signMessageRequests = MutableSharedFlow<SignMessageRequest>(extraBufferCapacity = 4)
    val signMessageRequests: SharedFlow<SignMessageRequest> = _signMessageRequests.asSharedFlow()

    private val walletRepo = WalletRepository.instance
    private val whisperService = WhisperService()
    private val whisperIdRepo = com.linmjt.whisperofsol.data.WhisperIdRepository()
    private val whisperKeyStore by lazy { WhisperKeyStore(getApplication()) }

    // ─── Send retry state (v1.1.8) ────────────────────────────────
    // Seed Vault user-password prompts on Seeker regularly exceed the
    // ~60–90 s Solana recent-blockhash TTL, surfacing "Blockhash expired"
    // on the device. We retry the send up to MAX_SEND_RETRIES times,
    // rebuilding the tx with a fresh blockhash between attempts. The
    // encrypted payload, recipient, and nonce are reused across attempts
    // so we only pay the encryption cost once.
    private var sendRetryCount: Int = 0
    private var sendRetryRecipientAddress: String? = null
    private var sendRetryRecipientKey: ByteArray? = null
    private var sendRetryNonce: Long = 0L
    private var sendRetryEncResult: EncryptionResult? = null
    private var sendRetryLastError: String? = null

    // v1.3.0: stash the (sender, nonce) for the most-recent unlock request so
    // onSignAndSendResult() can call fetchMessageDetail() once the tx confirms.
    private var pendingUnlockSenderKey: ByteArray? = null
    private var pendingUnlockRecipientKey: ByteArray? = null
    private var pendingUnlockNonce: Long = 0L

    /**
     * Trigger MWA authorize via Activity.
     */
    fun connect() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isConnecting = true,
                error = null,
                treasuryStatus = TreasuryStatus.Idle,
            )
            _connectRequests.tryEmit(Unit)
        }
    }

    /**
     * Activity calls this when the MWA authorize flow completes.
     */
    fun onConnectResult(success: Boolean) {
        if (success) {
            val pubKey = walletRepo.publicKey
            val pubKeyShort = pubKey?.let { formatAddress(it) }
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                connected = true,
                publicKeyShort = pubKeyShort,
                publicKey = pubKey,
                publicKeyFull = pubKey?.let { com.solana.mobilewalletadapter.common.util.Base58.encode(it) },
            )
            // v1.8.21: REMOVED auto-fire migrateInboxBump() on connect.
            // Per user feedback 2026-10-08: scaffold's connect is just 1
            // approve (no extra tx). Our auto-fire of init_inbox caused a
            // 2nd Transaction sheet + PIN prompt after AuthorizeDapp.
            // migrateInboxBump is still available as a public method — call
            // it lazily when user actually needs to receive a message
            // (e.g. when opening inbox screen for the first time).
            // migrateInboxBump()
            refreshBalance()
            refreshTreasury()
            fetchInbox()
            // v1.9.1 EXCEPTION: re-trigger refreshSetupStateFromChain() here after
            // the wallet connects. The early-return at app start (before
            // wallet connect) used to leave setupState=NotStarted forever,
            // and the user had to tap "Retry" to discover their on-chain PDA.
            //
            // refreshSetupStateFromChain() is a SAFE subset of ensureWhisperKey():
            //   - if cache hit + registered flag → use cache, no sign prompt
            //   - if on-chain PDA exists    → ReadyToLogin, no sign prompt
            //   - if neither                → NotStarted, NO sign prompt
            //                                 (signing only happens when user
            //                                  explicitly taps "Set up Whisper ID")
            //
            // v1.4.0': DON'T auto-trigger ensureWhisperKey() here.
            // (Replaced by v1.9.1 EXCEPTION above.) The original concern was
            // about scary signing prompts. refreshSetupStateFromChain() only
            // does cache + on-chain PDA reads, no wallet signing.
            viewModelScope.launch { refreshSetupStateFromChain() }
        } else {
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                error = UiError.ConnectFailed,
            )
        }
    }

    /**
     * Fetch the connected user's SOL balance from the devnet RPC.
     *
     * Runs after successful MWA authorize so the WalletScreen can show
     * "Lin8…jT7 · 1.234 SOL" instead of just "Wallet Connected".
     */
    fun refreshBalance() {
        viewModelScope.launch {
            val pubKey = walletRepo.publicKey ?: return@launch
            try {
                val balanceLamports = whisperService.getUserBalance(pubKey)
                _uiState.value = _uiState.value.copy(
                    userBalanceLamports = balanceLamports,
                    userBalanceStatus = BalanceStatus.Loaded,
                )
                Logger.d(TAG, "User balance: ${balanceLamports / 1_000_000_000.0} SOL")
            } catch (e: Throwable) {
                Logger.e(TAG, "Refresh user balance error", e)
                _uiState.value = _uiState.value.copy(
                    userBalanceStatus = BalanceStatus.Failed,
                )
            }
        }
    }

    /**
     * Activity calls this when MWA authorize flow fails / is cancelled.
     */
    fun onConnectError(message: String?) {
        _uiState.value = _uiState.value.copy(
            isConnecting = false,
            error = UiError.ConnectFailed,
        )
        if (message != null) {
            Logger.w("HomeViewModel", "MWA connect failed: $message")
        }
    }

    fun disconnect() {
        walletRepo.disconnect()
        _uiState.value = _uiState.value.copy(
            connected = false,
            publicKeyShort = null,
            publicKeyFull = null,
            publicKey = null,
            treasuryStatus = TreasuryStatus.Idle,
            userBalanceLamports = null,
            userBalanceStatus = BalanceStatus.Idle,
            nonceSetupStatus = NonceSetupStatus.Idle,
            sendRetryAttempt = 0,
            lastSendRetryError = null,
            // v1.4.0': keep the cached X25519 secret — it's encrypted at rest
            // and bound to the wallet. If the user reconnects the SAME wallet
            // (or another wallet), ensureWhisperKey() will use the cache or
            // re-derive as needed. Clearing would force every reconnect to pay
            // the wallet prompt cost.
        )
        // Clear deferred-send / retry state on disconnect.
        sendRetryCount = 0
        sendRetryRecipientAddress = null
        sendRetryRecipientKey = null
        sendRetryNonce = 0L
        sendRetryEncResult = null
        sendRetryLastError = null
    }
    /**
     * v1.8.4: STRICT ensureWhisperKey -- derive from wallet signature,
     * block app on failure.
     *
     * v1.6.0 used to fall back to SecureRandom when signMessage failed.
     * REMOVED in v1.8.4. Reason: user would see "Your Whisper ID" and
     * share it, but other devices couldn't find them on-chain -> silent
     * message loss. Now: failed setup = blocked app.
     *
     * v1.8.15: now `suspend` so callers (Compose LaunchedEffect, button
     * handlers) must run it in a coroutine. The EncryptedSharedPreferences
     * read + X25519 derivation are dispatched to IO inside the function --
     * previously this was a synchronous call on Main, which ANR'd on slow
     * Seekers / Android 16 phones because EncryptedSharedPreferences first
     * read triggers master key unwrap + file decryption on the calling
     * thread (1-3 seconds).
     */
    suspend fun ensureWhisperKey() {
        if (_uiState.value.setupState is SetupState.Complete) {
            Logger.d(TAG, "ensureWhisperKey: setup already complete")
            return
        }
        if (_uiState.value.setupState is SetupState.InProgress) {
            Logger.w(TAG, "ensureWhisperKey: already in progress, ignoring re-tap")
            return
        }
        // v1.8.15: IO-dispatch the prefs read + X25519 EC scalar mult.
        // getCached() can take 50-200ms on slow phones when the cache hit
        // triggers X25519.publicFromPrivate; isRegisteredOnChain() reads
        // an EncryptedSharedPreferences boolean. Both off Main.
        val (cached, registered) = withContext(Dispatchers.IO) {
            whisperKeyStore.getCached() to whisperKeyStore.isRegisteredOnChain()
        }
        if (cached != null && registered) {
            // v1.8.5: cache hit AND on-chain registered -- safe to trust.
            // Without the registeredOnChain flag, we could be loading a
            // SecureRandom-fallback XS from v1.8.3 that has no on-chain PDA
            // (silent message loss).
            _uiState.value = _uiState.value.copy(
                whisperKey = cached,
                whisperKeyInitialized = true,
                whisperKeyError = null,
                setupState = SetupState.Complete(onChain = true),
            )
            return
        }
        if (cached != null && !registered) {
            // v1.8.5: cache exists but never registered on-chain (likely
            // v1.8.3 SecureRandom fallback). Discard the local key and
            // force a proper signMessage + registerWhisperId flow.
            Logger.w(TAG, "ensureWhisperKey: cached xs but NOT registered on-chain -- discarding, forcing re-setup")
            whisperKeyStore.clear()  // suspend -- switches to IO internally
        }
        if (_uiState.value.publicKey == null) {
            // v1.9.1 fix: don't set Failed when wallet isn't connected yet --
            // the inline-call from MainActivity.LaunchedEffect runs BEFORE
            // wallet connect, so publicKey is null on first call. Surfacing
            // Failed here would block the user even if they later connect a
            // wallet that has an on-chain WhisperId PDA.
            //
            // Just mark init-done and leave setupState alone (defaults to
            // NotStarted). onConnectResult will re-trigger this function
            // after the wallet connects, and THAT call will perform the
            // on-chain PDA lookup + ReadyToLogin transition.
            Logger.d(TAG, "ensureWhisperKey: publicKey=null (wallet not connected yet); skipping chain check, will retry after connect")
            _uiState.value = _uiState.value.copy(
                whisperKeyInitialized = true,
                isSigningWhisperKey = false,
                // Don't set whisperKeyError or Failed -- user hasn't done
                // anything wrong, wallet just hasn't connected yet.
            )
            return
        }
        // v1.9.1: No local WhisperKey + wallet connected. Check on-chain PDA
        // before forcing a fresh signMessage+register flow (which would
        // waste GAS if user already registered from another device).
        // If we're being called from the user-tapped "Set up Whisper ID"
        // button, allow the sign message flow at the bottom of this function
        // to proceed. If we're being called from the post-connect auto path,
        // we only want the chain check (no sign prompt). The caller should
        // use [refreshSetupStateFromChain] instead in that case.
        val walletBase58 = com.solana.mobilewalletadapter.common.util.Base58.encode(_uiState.value.publicKey!!)
        val onChainEncPub: ByteArray? = try {
            withContext(Dispatchers.IO) { whisperIdRepo.findEncPubByWallet(walletBase58) }
        } catch (e: Throwable) {
            Logger.w(TAG, "ensureWhisperKey: on-chain PDA lookup failed -- assume not registered: ${e.message}")
            null
        }
        if (onChainEncPub != null && onChainEncPub.size == 32) {
            // PDA exists on chain for THIS wallet — user is already registered.
            // Set ReadyToLogin state. User can then tap "Login" to proceed
            // without re-registering. If they need to decrypt old messages
            // they'll have to re-setup (which will be a NEW registration that
            // overwrites — but our program keeps the existing PDA, so old
            // senders can still reach this wallet's old PDA if they knew the
            // old WhisperKey).
            Logger.i(TAG, "ensureWhisperKey: on-chain PDA found for ${walletBase58.take(8)}.. -- ReadyToLogin (no GAS)")
            val base64 = android.util.Base64.encodeToString(onChainEncPub, android.util.Base64.NO_WRAP)
            _uiState.value = _uiState.value.copy(
                whisperKeyInitialized = true,
                isSigningWhisperKey = false,
                setupState = SetupState.ReadyToLogin(base64),
                whisperKeyError = null,
            )
            return
        }
        // Defensive: line 251 already filtered null. If this throws, we have
        // a race we didn't anticipate -- better crash with a clear stack than
        // silently emit nothing.
        val pubkey = _uiState.value.publicKey
            ?: error("ensureWhisperKey: publicKey became null after explicit null check -- VM race?")
        val walletB58 = com.solana.mobilewalletadapter.common.util.Base58.encode(pubkey)
        val msg = whisperKeyStore.signingMessage(walletB58)  // cheap pure-string, safe on any thread
        val requestId = nextSignMessageId.getAndIncrement()
        // v1.8.24 DEBUG: trace why ensureWhisperKey's sign message flow was stuck
        // while the scaffold test button (signMessageScaffoldTest) worked.
        Logger.w(TAG, "ensureWhisperKey: about to tryEmit requestId=$requestId, message=${msg.take(16).joinToString("") { "%02x".format(it) }}…")
        // v1.8.8: tryEmit return-value check. With extraBufferCapacity=4,
        // this should succeed, but during Activity recreate or cold start
        // bursts the buffer can fill / no subscriber may be ready -- logging
        // the drop lets us diagnose "no log, UI stuck" symptoms.
        val emitted = _signMessageRequests.tryEmit(
            SignMessageRequest(message = msg, requestId = requestId)
        )
        if (!emitted) {
            Logger.e(TAG, "signMessageRequests.tryEmit DROPPED requestId=$requestId -- buffer full or no subscriber")
            android.util.Log.e("WSOL_DEBUG", "signMessageRequest DROPPED requestId=$requestId")
            _uiState.value = _uiState.value.copy(
                isSigningWhisperKey = false,
                setupState = SetupState.Failed(
                    "Setup request dropped (internal buffer full). Tap Generate Whisper ID again."
                ),
                whisperKeyError = "Internal: setup request dropped. Tap Retry.",
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            whisperKeyInitialized = true,
            isSigningWhisperKey = true,
            signMessageStartMs = System.currentTimeMillis(),
            setupState = SetupState.InProgress,
            whisperKeyError = null,
        )
    }

    /**
     * v1.9.1: Login using on-chain registered identity, without re-signing.
     *
     * Use case: user is registered on-chain (PDA exists) but local cache is
     * empty (fresh install, cleared data, device transfer). Tapping "Login"
     * on the Setup screen enters home using a "view-only" WhisperKey built
     * from the on-chain enc_pub. Trade-off: old inbox messages encrypted
     * to the missing privkey can't be decrypted on this device, but new
     * messages still work (others encrypt to the on-chain enc_pub).
     *
     * NO wallet signature requested. NO on-chain tx. NO GAS.
     */
    fun loginWithoutPrivKey() {
        val s = _uiState.value.setupState
        if (s !is SetupState.ReadyToLogin) {
            Logger.w(TAG, "loginWithoutPrivKey: setupState not ReadyToLogin (=$s), ignoring")
            return
        }
        val encPub = android.util.Base64.decode(s.onChainEncPubBase64, android.util.Base64.NO_WRAP)
        if (encPub.size != 32) {
            Logger.e(TAG, "loginWithoutPrivKey: on-chain enc_pub wrong size ${encPub.size}")
            return
        }
        // Build a "view-only" WhisperKey: real enc_pub, zero xs.
        // WhisperCrypto will use xs for decrypt (fails on old messages), but
        // xs is NOT used for encrypt-to-others (uses recipient's enc_pub from
        // their PDA at send-message time). New messages work fine.
        val viewOnlyKey = com.linmjt.whisperofsol.crypto.WhisperKey(
            xs = ByteArray(32),  // dummy — won't decrypt
            xp = encPub,
        )
        Logger.i(TAG, "loginWithoutPrivKey: entering home with view-only WhisperKey (no GAS)")
        _uiState.value = _uiState.value.copy(
            whisperKey = viewOnlyKey,
            whisperKeyInitialized = true,
            whisperKeyError = null,
            setupState = SetupState.Complete(onChain = true),
        )
    }

    /**
     * v1.9.1: SAFE subset of [ensureWhisperKey] for auto-call after wallet
     * connect. Performs cache + on-chain PDA check; NEVER emits a sign
     * message request to the wallet (no scary auto-prompts).
     *
     * Outcomes (mutually exclusive):
     *   - Cache hit + registered flag set  → setupState=Complete(onChain=true), use cache
     *   - On-chain PDA exists for wallet  → setupState=ReadyToLogin, show "Login" CTA
     *   - publicKey == null                → NotStarted (no action; UI shows spinner)
     *   - Neither cache nor chain         → NotStarted (user must tap "Set up Whisper ID"
     *                                          explicitly — which then triggers the sign
     *                                          message flow inside ensureWhisperKey)
     *
     * Called by:
     *   - onConnectResult (post-wallet-connect auto-refresh)
     *   - (could be called by other auto-fire spots if needed)
     */
    suspend fun refreshSetupStateFromChain() {
        // Skip if setup is already done or in flight.
        when (_uiState.value.setupState) {
            is SetupState.Complete -> {
                Logger.d(TAG, "refreshSetupStateFromChain: already Complete, skipping")
                return
            }
            is SetupState.InProgress -> {
                Logger.w(TAG, "refreshSetupStateFromChain: setup InProgress, skipping")
                return
            }
            else -> { /* proceed */ }
        }
        // Step 1: local cache check (fast, no RPC).
        val (cached, registered) = withContext(Dispatchers.IO) {
            whisperKeyStore.getCached() to whisperKeyStore.isRegisteredOnChain()
        }
        if (cached != null && registered) {
            _uiState.value = _uiState.value.copy(
                whisperKey = cached,
                whisperKeyInitialized = true,
                whisperKeyError = null,
                setupState = SetupState.Complete(onChain = true),
            )
            Logger.d(TAG, "refreshSetupStateFromChain: cache hit + registered flag — Complete")
            return
        }
        // Cache exists but flag says — discard (might be SecureRandom fallback).
        if (cached != null && !registered) {
            Logger.w(TAG, "refreshSetupStateFromChain: cache+not-flagged, discarding")
            whisperKeyStore.clear()
        }
        if (_uiState.value.publicKey == null) {
            Logger.d(TAG, "refreshSetupStateFromChain: wallet not connected yet, will retry after connect")
            _uiState.value = _uiState.value.copy(
                whisperKeyInitialized = true,
                isSigningWhisperKey = false,
            )
            return
        }
        // Step 2: on-chain PDA check (real RPC).
        val walletBase58 = com.solana.mobilewalletadapter.common.util.Base58.encode(_uiState.value.publicKey!!)
        val onChainEncPub: ByteArray? = try {
            withContext(Dispatchers.IO) { whisperIdRepo.findEncPubByWallet(walletBase58) }
        } catch (e: Throwable) {
            Logger.w(TAG, "refreshSetupStateFromChain: on-chain PDA lookup failed: ${e.message}")
            null
        }
        if (onChainEncPub != null && onChainEncPub.size == 32) {
            val base64 = android.util.Base64.encodeToString(onChainEncPub, android.util.Base64.NO_WRAP)
            _uiState.value = _uiState.value.copy(
                whisperKeyInitialized = true,
                isSigningWhisperKey = false,
                setupState = SetupState.ReadyToLogin(base64),
                whisperKeyError = null,
            )
            Logger.i(TAG, "refreshSetupStateFromChain: on-chain PDA found -- ReadyToLogin")
            return
        }
        // No cache, no PDA — user needs to set up explicitly.
        _uiState.value = _uiState.value.copy(
            whisperKeyInitialized = true,
            isSigningWhisperKey = false,
            setupState = SetupState.NotStarted,
            whisperKeyError = null,
        )
        Logger.d(TAG, "refreshSetupStateFromChain: no cache, no on-chain PDA -- NotStarted")
    }

    /**
     * v1.8.4: onSignMessageResult -- NO MORE SecureRandom fallback.
     *
     * signature == null (user cancelled / wallet timeout / wallet ANR) ->
     * setupState = Failed. User must Retry.
     *
     * signature != null -> derive WhisperKey from signature, then fire
     * registerWhisperIdOnChain. setupState stays InProgress until that
     * tx succeeds or fails (see registerWhisperIdOnChain).
     *
     * v1.8.15: now `suspend` because [WhisperKeyStore.deriveAndCache] does
     * SHA-512 + X25519 EC scalar multiplication + EncryptedSharedPreferences
     * write -- all IO-dispatched internally. Caller (MainActivity
     * `scope.launch { viewModel.onSignMessageResult(...) }`) already runs
     * in a coroutine, so no extra wrapping needed.
     */
    suspend fun onSignMessageResult(requestId: Long, signature: ByteArray?) {
        if (signature == null) {
            Logger.e(TAG, "onSignMessageResult: signature=null -- setup FAILED, no fallback")
            android.util.Log.e("WSOL_DEBUG", "onSignMessageResult: signature=null -- setup FAILED")
            _uiState.value = _uiState.value.copy(
                whisperKey = null,
                isSigningWhisperKey = false,
                signMessageStartMs = null,
                whisperKeyError = "Wallet did not sign the setup message. Tap Retry.",
                setupState = SetupState.Failed(
                    "Wallet did not sign the setup message.\n\n" +
                    "On Seeker, this often means the wallet Flutter UI hangs " +
                    "on a GPU surface allocation (MediaTek mali_gralloc bug).\n\n" +
                    "Tap Retry, or reboot the Seeker if it keeps happening.",
                ),
            )
            return
        }
        val key = whisperKeyStore.deriveAndCache(signature)
        if (key == null) {
            Logger.e(TAG, "onSignMessageResult: signature present but deriveAndCache returned null -- setup FAILED")
            android.util.Log.e("WSOL_DEBUG", "onSignMessageResult: signature present but deriveAndCache returned null -- setup FAILED")
            _uiState.value = _uiState.value.copy(
                whisperKey = null,
                isSigningWhisperKey = false,
                signMessageStartMs = null,
                whisperKeyError = "Signature was malformed (not 64 bytes). Tap Retry.",
                setupState = SetupState.Failed(
                    "Signature was malformed (not 64 bytes). Tap Retry.",
                ),
            )
            return
        }
        // SignMessage succeeded. WhisperKey ready locally.
        // v1.8.21: REMOVED auto-fire of registerWhisperIdOnChain() here.
        // Per user feedback 2026-10-08: scaffold's sign message is just 1
        // approve (no extra tx). Our auto-fire of register_whisper_id
        // caused a 2nd Transaction sheet + PIN prompt after SignMessage.
        // Setup is COMPLETE now (key locally derived & cached). The
        // on-chain register can be triggered lazily — e.g. when user
        // explicitly tries to be discovered (search Whisper ID feature)
        // or when sending first message. For now, setup is fully
        // functional locally: encryption/decryption works without
        // on-chain register.
        _uiState.value = _uiState.value.copy(
            whisperKey = key,
            whisperKeyError = null,
            isSigningWhisperKey = false,
            signMessageStartMs = null,
            setupState = SetupState.Complete(onChain = false),  // local-only
        )
    }

    fun cancelSignMessage() {
        if (!_uiState.value.isSigningWhisperKey) return
        Logger.w(TAG, "cancelSignMessage: user gave up — UI reset")
        _uiState.value = _uiState.value.copy(
            isSigningWhisperKey = false,
            signMessageStartMs = null,
            whisperKeyError = "Cancelled -- tap Generate Whisper ID to retry",
            // v1.8.4: setupState -> Failed (was implicitly InProgress)
            setupState = SetupState.Failed("Setup cancelled. Tap Generate Whisper ID to retry."),
        )
    }
    /**
     * v1.8.26: Setup flow that BYPASSES the entire SharedFlow/LaunchedEffect
     * collect chain. Calls walletAdapter.transact directly (same code path
     * as the working signMessageScaffoldTest), signs the canonical Whisper
     * setup message, derives the X25519 keypair from the signature.
     *
     * Use case: user has shown the setup path (ensureWhisperKey ->
     * _signMessageRequests.tryEmit -> MainActivity LaunchedEffect collect ->
     * scope.launch -> walletRepo.signMessages) is stuck while the
     * signMessageScaffoldTest path with identical MWA code works. This
     * routes the setup through the working path. If setup completes here,
     * the bug is in the SharedFlow + LaunchedEffect + scope.launch chain.
     * If setup still fails here, the bug is somewhere else.
     */
    fun signSetupMessageAndDeriveKey(
        sender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    ) {
        val pubkey = _uiState.value.publicKey
        if (pubkey == null) {
            Logger.e(TAG, "signSetupMessageAndDeriveKey: no publicKey")
            _uiState.value = _uiState.value.copy(
                setupState = SetupState.Failed("Wallet not connected. Tap Connect first."),
            )
            return
        }
        val walletB58 = com.solana.mobilewalletadapter.common.util.Base58.encode(pubkey)
        val message = whisperKeyStore.signingMessage(walletB58)

        _uiState.value = _uiState.value.copy(
            isSigningWhisperKey = true,
            signMessageStartMs = System.currentTimeMillis(),
            setupState = SetupState.InProgress,
            whisperKeyError = null,
        )

        viewModelScope.launch {
            try {
                // ─── SCAFFOLD-EXACT code (proven to work on device 100) ───
                val result = walletRepo.walletAdapter.transact(sender) {
                    signMessagesDetached(
                        arrayOf(message),
                        arrayOf(pubkey),
                    )
                }
                // ─── end scaffold-exact ──────────────────────────────

                val signature: ByteArray? = when (result) {
                    is com.solana.mobilewalletadapter.clientlib.TransactionResult.Success -> {
                        result.payload?.messages?.firstOrNull()?.signatures?.firstOrNull()
                    }
                    else -> null
                }
                if (signature == null) {
                    Logger.e(TAG, "signSetupMessageAndDeriveKey: signature null -- setup FAILED")
                    _uiState.value = _uiState.value.copy(
                        isSigningWhisperKey = false,
                        signMessageStartMs = null,
                        setupState = SetupState.Failed(
                            "Wallet did not sign the setup message. Tap Retry."
                        ),
                        whisperKeyError = "Wallet did not sign. Tap Retry.",
                    )
                    return@launch
                }
                // Derive X25519 key from signature (same as onSignMessageResult)
                val key = whisperKeyStore.deriveAndCache(signature)
                if (key == null) {
                    Logger.e(TAG, "signSetupMessageAndDeriveKey: signature present but deriveAndCache returned null")
                    _uiState.value = _uiState.value.copy(
                        whisperKey = null,
                        isSigningWhisperKey = false,
                        signMessageStartMs = null,
                        setupState = SetupState.Failed("Signature malformed. Tap Retry."),
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    whisperKey = key,
                    whisperKeyError = null,
                    isSigningWhisperKey = false,
                    signMessageStartMs = null,
                    setupState = SetupState.Complete(onChain = false),
                )
                Logger.i(TAG, "signSetupMessageAndDeriveKey: setup COMPLETE (local-only)")
            } catch (e: Throwable) {
                Logger.e(TAG, "signSetupMessageAndDeriveKey THROWABLE: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isSigningWhisperKey = false,
                    signMessageStartMs = null,
                    setupState = SetupState.Failed("Sign threw: ${e.message}. Tap Retry."),
                )
            }
        }
    }

    /**
     * v1.8.30: Combined setup + register on-chain, sequential, single tap.
     *
     * User feedback 2026-10-08 02:41: 'combine setup and register on chain
     * into one flow, sequential, only enter app after both done, don't break
     * sign message again'.
     *
     * Flow:
     *   1. Sign setup message (scaffold-exact pattern, proven in v1.8.26)
     *   2. Derive X25519 WhisperKey locally
     *   3. Build + sign + send register_whisper_id tx (same as v1.8.27)
     *   4. setupState = Complete(onChain = true) -> main app accessible
     *
     * Each step's UI state transitions:
     *   - isSigningWhisperKey=true    -> sign message sheet appears
     *   - isSigningWhisperKey=false,
     *     isRegisteringOnChain=true   -> tx sheet appears (after signature OK)
     *   - isRegisteringOnChain=false,
     *     setupState=Complete(onChain=true) -> main app
     *
     * Failure handling:
     *   - Sign fails -> setupState = Failed, user taps Retry
     *   - Register tx fails after local sign OK -> setupState = Complete(onChain=false),
     *     user can tap 'Register on-chain' button (v1.8.27 path) to retry
     *
     * IMPORTANT: Sign message code is copied verbatim from
     * signSetupMessageAndDeriveKey -- don't change it (it's the part
     * the user keeps telling me not to break).
     */
    fun signSetupAndRegisterOnChainInline(
        sender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    ) {
        val pubkey = _uiState.value.publicKey
        if (pubkey == null) {
            Logger.e(TAG, "signSetupAndRegisterOnChainInline: no publicKey")
            _uiState.value = _uiState.value.copy(
                setupState = SetupState.Failed("Wallet not connected. Tap Connect first."),
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isSigningWhisperKey = true,
            isRegisteringOnChain = false,
            signMessageStartMs = System.currentTimeMillis(),
            setupState = SetupState.InProgress,
            whisperKeyError = null,
        )

        viewModelScope.launch {
            // ─── STEP 1: Sign setup message (EXACT copy of v1.8.26 working path) ───
            val walletB58 = com.solana.mobilewalletadapter.common.util.Base58.encode(pubkey)
            val message = whisperKeyStore.signingMessage(walletB58)
            val signature: ByteArray? = try {
                val signResult = walletRepo.walletAdapter.transact(sender) {
                    signMessagesDetached(
                        arrayOf(message),
                        arrayOf(pubkey),
                    )
                }
                when (signResult) {
                    is com.solana.mobilewalletadapter.clientlib.TransactionResult.Success ->
                        signResult.payload?.messages?.firstOrNull()?.signatures?.firstOrNull()
                    else -> null
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: sign threw ${'$'}{e.message}", e)
                null
            }
            if (signature == null) {
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: signature null -- setup FAILED")
                _uiState.value = _uiState.value.copy(
                    isSigningWhisperKey = false,
                    isRegisteringOnChain = false,
                    signMessageStartMs = null,
                    setupState = SetupState.Failed(
                        "Wallet did not sign the setup message. Tap Retry."
                    ),
                    whisperKeyError = "Wallet did not sign. Tap Retry.",
                )
                return@launch
            }
            // Derive X25519 key from signature (smart-cast via local val)
            val sig = signature
            val key = if (sig != null) whisperKeyStore.deriveAndCache(sig) else null
            if (key == null && sig != null) {
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: signature present but deriveAndCache returned null")
                _uiState.value = _uiState.value.copy(
                    whisperKey = null,
                    isSigningWhisperKey = false,
                    isRegisteringOnChain = false,
                    signMessageStartMs = null,
                    setupState = SetupState.Failed("Signature malformed. Tap Retry."),
                )
                return@launch
            }
            if (key == null) {
                // sig is also null -> signature null case already handled above,
                // shouldn't reach here, but guard anyway
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                whisperKey = key,
                whisperKeyError = null,
                isSigningWhisperKey = false,
                isRegisteringOnChain = true,
                signMessageStartMs = null,
                // Keep setupState = InProgress -- we're not done yet
            )
            Logger.i(TAG, "signSetupAndRegisterOnChainInline: local key derived, now registering on-chain")

            // ─── STEP 2: Build + sign + send register tx (same as v1.8.27 path) ───
            // Local non-null captures (smart cast across try blocks doesn't always hold)
            val finalKey = key
            val finalSig = finalKey?.signature
            if (finalKey == null || finalSig == null) {
                // shouldn't reach here due to guards above, defensive
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: finalKey/finalSig null at register step")
                return@launch
            }
            val txBase64 = try {
                whisperService.buildRegisterWhisperIdTransaction(
                    userPubkey = pubkey,
                    encPub = finalKey.xp,
                    signature = finalSig,
                )
            } catch (e: Throwable) {
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: buildRegisterTx threw ${'$'}{e.message}", e)
                null
            }
            if (txBase64 == null) {
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: tx build returned null")
                _uiState.value = _uiState.value.copy(
                    isRegisteringOnChain = false,
                    setupState = SetupState.Complete(onChain = false),
                    whisperKeyError = "Build register tx failed. Tap 'Register on-chain' to retry.",
                )
                return@launch
            }
            val txBytes = java.util.Base64.getDecoder().decode(txBase64)
            Logger.w(TAG, "signSetupAndRegisterOnChainInline: tx built (${'$'}{txBytes.size} bytes), signing+sending...")

            val registerResult = try {
                val params = com.solana.mobilewalletadapter.clientlib.TransactionParams(
                    minContextSlot = null,
                    commitment = "processed",
                    skipPreflight = false,
                    maxRetries = null,
                    waitForCommitmentToSendNextTransaction = false,
                )
                walletRepo.walletAdapter.transact(sender) {
                    signAndSendTransactions(arrayOf(txBytes), params)
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "signSetupAndRegisterOnChainInline: register tx threw ${'$'}{e.message}", e)
                null
            }
            if (registerResult == null) {
                _uiState.value = _uiState.value.copy(
                    isRegisteringOnChain = false,
                    setupState = SetupState.Complete(onChain = false),
                    whisperKeyError = "Register tx threw. Tap 'Register on-chain' to retry.",
                )
                return@launch
            }

            when (registerResult) {
                is com.solana.mobilewalletadapter.clientlib.TransactionResult.Success -> {
                    val sigs = registerResult.payload?.signatures
                    Logger.i(TAG, "signSetupAndRegisterOnChainInline: SUCCESS, signatures=${'$'}{sigs?.size ?: 0}")
                    _uiState.value = _uiState.value.copy(
                        isRegisteringOnChain = false,
                        setupState = SetupState.Complete(onChain = true),
                        whisperKeyError = null,
                        lastTxSignature = sigs?.firstOrNull()?.let {
                            com.solana.mobilewalletadapter.common.util.Base58.encode(it)
                        },
                    )
                }
                is com.solana.mobilewalletadapter.clientlib.TransactionResult.NoWalletFound -> {
                    Logger.e(TAG, "signSetupAndRegisterOnChainInline: NoWalletFound")
                    _uiState.value = _uiState.value.copy(
                        isRegisteringOnChain = false,
                        setupState = SetupState.Complete(onChain = false),
                        whisperKeyError = "No wallet found for register tx. Tap 'Register on-chain' to retry.",
                    )
                }
                is com.solana.mobilewalletadapter.clientlib.TransactionResult.Failure -> {
                    Logger.e(TAG, "signSetupAndRegisterOnChainInline: Failure ${registerResult.e?.message}")
                    _uiState.value = _uiState.value.copy(
                        isRegisteringOnChain = false,
                        setupState = SetupState.Complete(onChain = false),
                        whisperKeyError = "Register tx failed: ${registerResult.e?.message}. Tap 'Register on-chain' to retry.",
                    )
                }
            }
        }
    }

    /**
     * v1.8.27: Register the locally-derived Whisper ID on-chain.
     *
     * Like signSetupMessageAndDeriveKey, this BYPASSES the broken SharedFlow
     * chain. Calls walletRepo.walletAdapter.transact { signAndSendTransactions(...) }
     * directly. The user taps a "Register on chain" button on MainScreen to
     * trigger this -- explicit, not auto-fired. Same register_whisper_id
     * instruction as v1.8.20's auto-fire, just no longer hidden from the user.
     *
     * On success: setupState = Complete(onChain = true), whisperKeyRegisteredOnChain = true.
     */
    fun registerWhisperIdOnChainInline(
        sender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    ) {
        val key = _uiState.value.whisperKey
        if (key == null) {
            Logger.e(TAG, "registerWhisperIdOnChainInline: no whisperKey -- setup incomplete?")
            return
        }
        if (key.signature == null) {
            Logger.e(TAG, "registerWhisperIdOnChainInline: key has no signature")
            return
        }
        val pubkey = _uiState.value.publicKey
        if (pubkey == null) {
            Logger.e(TAG, "registerWhisperIdOnChainInline: no publicKey")
            return
        }
        viewModelScope.launch {
            try {
                Logger.w(TAG, "registerWhisperIdOnChainInline: building tx...")
                val txBase64 = whisperService.buildRegisterWhisperIdTransaction(
                    userPubkey = pubkey,
                    encPub = key.xp,
                    signature = key.signature,
                )
                if (txBase64 == null) {
                    Logger.e(TAG, "registerWhisperIdOnChainInline: tx build returned null")
                    _uiState.value = _uiState.value.copy(
                        whisperKeyError = "Build register tx failed. Tap Retry.",
                    )
                    return@launch
                }
                val txBytes = java.util.Base64.getDecoder().decode(txBase64)
                Logger.w(TAG, "registerWhisperIdOnChainInline: tx built (${txBytes.size} bytes), calling adapter.transact...")

                // ─── SCAFFOLD-EXACT pattern (proven to work on device 100) ───
                val params = com.solana.mobilewalletadapter.clientlib.TransactionParams(
                    minContextSlot = null,
                    commitment = "processed",  // v1.8.17: same as mock-mwa-wallet default
                    skipPreflight = false,
                    maxRetries = null,
                    waitForCommitmentToSendNextTransaction = false,
                )
                val txArray = arrayOf(txBytes)
                val result: com.solana.mobilewalletadapter.clientlib.TransactionResult<com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.SignAndSendTransactionsResult> =
                    walletRepo.walletAdapter.transact(sender) {
                        // 'this' = AdapterOperations, 'it' = AuthorizationResult.
                        // Same scaffold pattern as signMessageScaffoldTest.
                        signAndSendTransactions(txArray, params)
                    }
                // ─── end scaffold-exact ──────────────────────────────

                when (result) {
                    is com.solana.mobilewalletadapter.clientlib.TransactionResult.Success -> {
                        val sigs = result.payload?.signatures
                        Logger.i(TAG, "registerWhisperIdOnChainInline: Success, signatures=${sigs?.size ?: 0}")
                        _uiState.value = _uiState.value.copy(
                            setupState = SetupState.Complete(onChain = true),
                            whisperKeyError = null,
                            lastSnackbar = "Whisper ID registered on-chain" +  // v1.9.11: drop ✅
                                if (sigs != null && sigs.isNotEmpty())
                                    " (tx " + com.solana.mobilewalletadapter.common.util.Base58.encode(sigs.first()) + ")"
                                else "",
                        )
                        // Persist the registeredOnChain flag (off Main)
                        whisperKeyStore.setRegisteredOnChain(true)
                    }
                    is com.solana.mobilewalletadapter.clientlib.TransactionResult.Failure -> {
                        val msg = result.e?.message ?: "register tx failed"
                        Logger.e(TAG, "registerWhisperIdOnChainInline: Failure: $msg")
                        _uiState.value = _uiState.value.copy(
                            whisperKeyError = "On-chain register failed: $msg. Tap Retry.",
                            lastSnackbar = "$msg",  // v1.9.11: drop ❌
                        )
                    }
                    is com.solana.mobilewalletadapter.clientlib.TransactionResult.NoWalletFound -> {
                        Logger.e(TAG, "registerWhisperIdOnChainInline: NoWalletFound")
                        _uiState.value = _uiState.value.copy(
                            whisperKeyError = "No wallet found",
                            lastSnackbar = "No wallet found",  // v1.9.11: drop ❌
                        )
                    }
                    else -> {
                        Logger.e(TAG, "registerWhisperIdOnChainInline: unexpected result ${result::class.java.name}")
                    }
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "registerWhisperIdOnChainInline THROWABLE: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    whisperKeyError = "Register tx threw: ${e.message}. Tap Retry.",
                    lastSnackbar = "${e.message}",  // v1.9.11: drop ❌
                )
            }
        }
    }

    /**
     * v1.6.1: Look up the recipient's enc_pub by wallet address.
     *
     * Called by SendWhisperScreen whenever the recipient address field
     * changes (with debounce). Stores the result in UI state so the
     * Send button can pick it up without re-querying.
     */
    fun lookupRecipientEncPub(address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                recipientLookupStatus = RecipientLookupStatus.Idle,
                recipientLookupFor = null,
                recipientEncPub = null,
            )
            return
        }
        // basic sanity: must be a base58 of length 32-44 (Solana pubkey)
        if (trimmed.length !in 32..44) {
            _uiState.value = _uiState.value.copy(
                recipientLookupStatus = RecipientLookupStatus.NotFound,
                recipientLookupFor = trimmed,
                recipientEncPub = null,
            )
            return
        }
        // skip if we already looked it up for the same address and got a result
        val current = _uiState.value
        if (current.recipientLookupFor == trimmed &&
            (current.recipientLookupStatus == RecipientLookupStatus.Found ||
             current.recipientLookupStatus == RecipientLookupStatus.NotFound ||
             current.recipientLookupStatus == RecipientLookupStatus.Error)
        ) {
            return
        }
        _uiState.value = _uiState.value.copy(
            recipientLookupStatus = RecipientLookupStatus.Looking,
            recipientLookupFor = trimmed,
            recipientEncPub = null,
        )
        viewModelScope.launch {
            val encPub = whisperIdRepo.findEncPubByWallet(trimmed)
            if (encPub != null) {
                _uiState.value = _uiState.value.copy(
                    recipientLookupStatus = RecipientLookupStatus.Found,
                    recipientEncPub = encPub,
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    recipientLookupStatus = RecipientLookupStatus.NotFound,
                    recipientEncPub = null,
                )
            }
        }
    }

    /**
     * v1.8.1: synchronous variant of lookupRecipientEncPub — used inside sendWhisper
     * when caller didn't pass enc_pub (so the onValueChange lookup either didn't fire
     * or returned null). Returns the resolved enc_pub (or null) and updates state.
     */
    private suspend fun lookupRecipientEncPubSync(address: String): ByteArray? {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || trimmed.length !in 32..44) {
            android.util.Log.e("WSOL_DEBUG", "lookupRecipientEncPubSync: bad address format len=${trimmed.length}")
            return null
        }
        android.util.Log.e("WSOL_DEBUG", "lookupRecipientEncPubSync: calling findEncPubByWallet for ${trimmed.take(12)}...")
        val encPub = whisperIdRepo.findEncPubByWallet(trimmed)
        android.util.Log.e("WSOL_DEBUG", "lookupRecipientEncPubSync: findEncPubByWallet returned ${encPub != null}")
        if (encPub != null) {
            _uiState.value = _uiState.value.copy(
                recipientLookupStatus = RecipientLookupStatus.Found,
                recipientEncPub = encPub,
            )
        } else {
            _uiState.value = _uiState.value.copy(
                recipientLookupStatus = RecipientLookupStatus.NotFound,
                recipientEncPub = null,
            )
        }
        return encPub
    }

    /**
     * v1.6.1: clear recipient lookup state (e.g. when navigating away
     * from SendWhisperScreen).
     */
    fun clearRecipientLookup() {
        _uiState.value = _uiState.value.copy(
            recipientLookupStatus = RecipientLookupStatus.Idle,
            recipientLookupFor = null,
            recipientEncPub = null,
        )
    }

    /**
     * v1.6.0: Force a fresh keypair (debug only).
     *
     * v1.8.15: wraps the suspend chain in `viewModelScope.launch` so the
     * caller (a debug menu button on Main) doesn't need its own coroutine.
     */
    fun resetWhisperKey() {
        Logger.w(TAG, "resetWhisperKey: clearing and regenerating")
        viewModelScope.launch {
            whisperKeyStore.clear()
            _uiState.value = _uiState.value.copy(whisperKey = null)
            ensureWhisperKey()
        }
    }

    /**
     * v1.6.1: Register the connected wallet's WhisperId (enc_pub + signature)
     * on chain via the `register_whisper_id` Anchor instruction.
     *
     * Updates UI state with [whisperIdOnChainRegistered] on success. Doesn't
     * throw — failures are logged but not surfaced (the dApp still works
     * locally without the on-chain register).
     *
     * The PDA is init_if_needed so calling this again is idempotent (and
     * overwrites the on-chain enc_pub if our local keypair changed).
     */
    private suspend fun registerWhisperIdOnChain(key: WhisperKey) {
        if (key.signature == null) {
            Logger.d(TAG, "registerWhisperIdOnChain: no signature (SecureRandom key) — skipping on-chain register")
            return
        }
        val pubkey = _uiState.value.publicKey ?: return
        val walletB58 = com.solana.mobilewalletadapter.common.util.Base58.encode(pubkey)
        Logger.d(TAG, "registerWhisperIdOnChain: wallet=${walletB58.take(8)}... encPub=${key.xp.size}bytes sig=${key.signature.size}bytes")

        try {
            val txBase64 = whisperService.buildRegisterWhisperIdTransaction(
                userPubkey = pubkey,
                encPub = key.xp,
                signature = key.signature,
            )
            if (txBase64 == null) {
                Logger.e(TAG, "registerWhisperIdOnChain: tx build returned null")
                return
            }
            val txBytes = java.util.Base64.getDecoder().decode(txBase64)
            Logger.d(TAG, "registerWhisperIdOnChain: dispatching sign+send (${txBytes.size} bytes)")
            // v1.8.8: tryEmit return-value check
            val emitted = _signAndSendRequests.tryEmit(
                SignAndSendRequest(
                    transactions = listOf(txBytes),
                    purpose = SendPurpose.RegisterWhisperId,
                )
            )
            if (!emitted) {
                Logger.e(TAG, "signAndSendRequests.tryEmit DROPPED -- RegisterWhisperId")
                android.util.Log.e("WSOL_DEBUG", "registerWhisperIdOnChain: signAndSend DROPPED")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "registerWhisperIdOnChain: build failed", e)
        }
    }

    private val nextSignMessageId = java.util.concurrent.atomic.AtomicLong(0)

    fun refreshTreasury() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                treasuryStatus = TreasuryStatus.Loading,
                error = null,
            )
            try {
                val treasuryBal = whisperService.getTreasuryLamports()
                val jackpotBal = whisperService.getChristmasJackpotLamports()
                _uiState.value = _uiState.value.copy(
                    treasuryLamports = treasuryBal,
                    jackpotLamports = jackpotBal,
                    expectedValue = 1_410_000L,
                    treasuryStatus = TreasuryStatus.Loaded,
                    treasuryRetryCount = 0,
                )
            } catch (e: Throwable) {
                Logger.e(TAG, "Refresh treasury error", e)
                val newRetryCount = _uiState.value.treasuryRetryCount + 1
                _uiState.value = _uiState.value.copy(
                    treasuryStatus = if (newRetryCount >= MAX_TREASURY_RETRIES) {
                        TreasuryStatus.Failed
                    } else {
                        TreasuryStatus.Retryable
                    },
                    treasuryRetryCount = newRetryCount,
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Fetch the connected user's on-chain Inbox PDA.
     * Called on: connect success, send success, unlock success, and pull-to-refresh.
     */
    fun fetchInbox() {
        val pubKey = walletRepo.publicKey ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inboxStatus = InboxStatus.Loading)
            try {
                val messages = whisperService.fetchInbox(pubKey)
                _uiState.value = _uiState.value.copy(
                    inboxMessages = messages,
                    inboxStatus = InboxStatus.Loaded,
                )
                Logger.d(TAG, "fetchInbox: ${messages.size} messages")
            } catch (e: Throwable) {
                Logger.e(TAG, "fetchInbox", e)
                _uiState.value = _uiState.value.copy(inboxStatus = InboxStatus.Failed)
            }
        }
    }

    /**
     * v1.2.0: Migrate the connected user's inbox bump on connect.
     *
     * Background: the original send_message used `init_if_needed` for the
     * recipient_inbox PDA but forgot to write `inbox.bump` after creation,
     * so the field was zero-initialized. unlock_message's PDA constraint
     * uses `bump = recipient_inbox.bump`, and bump=0 is never valid →
     * unlock always failed with InvalidSeeds.
     *
     * The program now self-heals via init_inbox (which always rewrites
     * bump, even on existing accounts). We call it once after connect
     * so the user's inbox is healthy before they try to unlock anything.
     */
    fun migrateInboxBump() {
        val pubKey = walletRepo.publicKey ?: return
        viewModelScope.launch {
            try {
                val txBase64 = whisperService.buildInitInboxTransaction(pubKey)
                if (txBase64 == null) {
                    Logger.w(TAG, "migrateInboxBump: tx build returned null")
                    return@launch
                }
                val txBytes = Base64.getDecoder().decode(txBase64)
                Logger.d(TAG, "migrateInboxBump: sending init_inbox tx (self-heal bump)")
                // v1.8.8: tryEmit return-value check
                val emitted = _signAndSendRequests.tryEmit(
                    SignAndSendRequest(
                        transactions = listOf(txBytes),
                        purpose = SendPurpose.InitInbox,
                    )
                )
                if (!emitted) {
                    Logger.e(TAG, "signAndSendRequests.tryEmit DROPPED -- InitInbox")
                    android.util.Log.e("WSOL_DEBUG", "migrateInboxBump: signAndSend DROPPED")
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "migrateInboxBump", e)
            }
        }
    }

    /**
     * Unlock a specific message from the on-chain Inbox (direct from InboxScreen list).
     * Builds unlock_message tx with recipient = self (the connected user),
     * sender = the message sender, nonce = the message nonce.
     */
    fun unlockByRef(senderBase58: String, nonce: Long) {
        viewModelScope.launch {
            val recipientKey = walletRepo.publicKey
                ?: run {
                    _uiState.value = _uiState.value.copy(error = UiError.WalletNotConnected)
                    return@launch
                }
            try {
                val senderKey = decodeBase58(senderBase58.trim())
                if (senderKey.size != 32) {
                    _uiState.value = _uiState.value.copy(error = UiError.InvalidRecipientAddress)
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isSending = true, error = null)

                val txBase64 = whisperService.buildUnlockMessageTransaction(
                    senderKey,     // sender from sender's perspective
                    recipientKey,  // recipient = self
                    nonce,
                )
                if (txBase64 == null) {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.TransactionBuildFailed,
                    )
                    return@launch
                }

                val txBytes = Base64.getDecoder().decode(txBase64)
                Logger.d(TAG, "unlockByRef: nonce=$nonce, sender=${senderBase58.take(8)}...")

                // Stash for fetchMessageDetail() after unlock_message confirms.
                pendingUnlockSenderKey = senderKey.copyOf()
                pendingUnlockRecipientKey = recipientKey.copyOf()
                pendingUnlockNonce = nonce

                // v1.8.8: tryEmit return-value check
                val emitted = _signAndSendRequests.tryEmit(
                    SignAndSendRequest(
                        transactions = listOf(txBytes),
                        purpose = SendPurpose.UnlockMessage,
                    )
                )
                if (!emitted) {
                    Logger.e(TAG, "signAndSendRequests.tryEmit DROPPED -- UnlockMessage (1st site)")
                    android.util.Log.e("WSOL_DEBUG", "unlockByRef (1st): signAndSend DROPPED")
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "unlockByRef error", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = UiError.SendFailed,
                )
            }
        }
    }

    /**
     * Unlock a whisper manually — recipient inputs the sender's base58
     * pubkey + the nonce from when send_message was broadcast.
     *
     * V1 limitation (known): there's no on-chain inbox index yet (that's
     * a Stage 2 V2 feature), so recipients can't see incoming whispers
     * in the Inbox automatically. This manual unlock flow is the
     * workaround: the sender can copy their nonce + address from the
     * success screen and share with the recipient out-of-band (Telegram,
     * SMS, etc.), who then enters it here.
     *
     * Build unlock_message tx with recipient = self, sender = provided
     * address, nonce = provided nonce.
     */
    fun unlockWhisper(senderAddress: String, nonceText: String) {
        viewModelScope.launch {
            val recipientKey = walletRepo.publicKey
                ?: run {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.WalletNotConnected,
                    )
                    return@launch
                }
            val nonce = nonceText.trim().toLongOrNull()
            if (nonce == null || nonce <= 0L) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = UiError.InvalidNonce,
                )
                return@launch
            }
            try {
                val senderKey = decodeBase58(senderAddress.trim())
                if (senderKey.size != 32) {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.InvalidRecipientAddress,
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isSending = true, error = null)

                // Build unlock_message tx (recipient = self, sender = provided, nonce = provided)
                val txBase64 = whisperService.buildUnlockMessageTransaction(
                    senderKey,    // first arg = sender pubkey from sender's perspective
                    recipientKey, // second arg = recipient pubkey (self)
                    nonce,
                )
                if (txBase64 == null) {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.TransactionBuildFailed,
                    )
                    return@launch
                }

                val txBytes = Base64.getDecoder().decode(txBase64)
                Logger.d(
                    TAG,
                    "unlock_message tx ready: ${txBytes.size} bytes, " +
                            "nonce=$nonce, sender=${senderAddress.take(8)}..."
                )

                _uiState.value = _uiState.value.copy(
                    pendingNonce = nonce,
                    pendingRecipient = senderAddress,
                )

                // Stash for fetchMessageDetail() after unlock_message confirms.
                pendingUnlockSenderKey = senderKey.copyOf()
                pendingUnlockRecipientKey = recipientKey.copyOf()
                pendingUnlockNonce = nonce

                // v1.8.8: tryEmit return-value check
                val emitted = _signAndSendRequests.tryEmit(
                    SignAndSendRequest(
                        transactions = listOf(txBytes),
                        purpose = SendPurpose.UnlockMessage,
                    )
                )
                if (!emitted) {
                    Logger.e(TAG, "signAndSendRequests.tryEmit DROPPED -- UnlockMessage (2nd site)")
                    android.util.Log.e("WSOL_DEBUG", "unlockByRef (2nd): signAndSend DROPPED")
                }
            } catch (e: Throwable) {
                Logger.e(TAG, "Unlock whisper error", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = UiError.SendFailed,
                )
            }
        }
    }

    /**
     * Send a whisper — real MWA sign-and-send pipeline (v1.1.8).
     *
     *   1. Encrypt content for recipient (paid once, reused across retries)
     *   2. Stash recipient/encryption result for retry
     *   3. Build send_message Solana transaction with a fresh recent blockhash
     *   4. Emit sign-and-send request to Activity (which invokes MWA transact)
     *
     * Retry handling lives in [onSignAndSendResult]: when MWA fails with a
     * "blockhash/expired/timeout" error, [onSignAndSendResult] waits 3 s and
     * re-emits a fresh request via [attemptSendWhisper]. Up to
     * [MAX_SEND_RETRIES] (3) total attempts are made before giving up.
     */
    fun sendWhisper(recipientAddress: String, message: String) {
        viewModelScope.launch {
            Logger.d(TAG, "sendWhisper: enter recipient=${recipientAddress.take(8)}... msgLen=${message.length}")
            // v1.9.6: ALWAYS do a fresh on-chain lookup at send time.
            // No cached state, no per-keystroke lookups. Each Send triggers
            // exactly 1 RPC call. Eliminates:
            //  - stale enc_pub leakage after QR re-scan (v1.9.5 root cause)
            //  - 13 RPCs/typed address from per-keystroke lookup (user feedback 2026-10-08)
            //  - cancel-then-rescan race conditions
            _uiState.value = _uiState.value.copy(isSending = true, error = null)
            val senderKey = walletRepo.publicKey
                ?: run {
                    Logger.e(TAG, "sendWhisper: no wallet connected")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.WalletNotConnected,
                    )
                    return@launch
                }
            try {
                val recipientKey = decodeBase58(recipientAddress)
                if (recipientKey.size != 32) {
                    Logger.e(TAG, "sendWhisper: bad recipient addr size=${recipientKey.size} (input='${recipientAddress}')")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.InvalidRecipientAddress,
                    )
                    return@launch
                }

                // v1.9.6: ALWAYS do fresh on-chain lookup at send time.
                // 1 RPC call per Send (no per-keystroke spam).
                val finalEncPub: ByteArray? = lookupRecipientEncPubSync(recipientAddress)
                if (finalEncPub == null) {
                    android.util.Log.e("WSOL_DEBUG", "sendWhisper: REFUSED — no on-chain enc_pub for ${recipientAddress.take(12)}... (recipient must run Whisper ID setup first)")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.RecipientNotSetup,
                    )
                    return@launch
                }

                // v1.8.0: STRICT mode — enc_pub MUST come from chain.
                // Removed the silent birational-map fallback (v1.7.1 bug):
                //   * If PDA doesn't exist → recipient hasn't set up WhisperOfSol
                //   * If on-chain enc_pub is junk → recipient's Whisper ID is corrupted
                //   In both cases we REFUSE to send (recipient cannot decrypt).
                val recipientX25519Pub: ByteArray = when {
                    finalEncPub.size != 32 -> {
                        Logger.e(TAG, "sendWhisper: enc_pub unexpected size=${finalEncPub.size}")
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            error = UiError.RecipientEncPubCorrupted,
                        )
                        return@launch
                    }
                    !whisperIdRepo.isValidX25519PubKey(finalEncPub) -> {
                        android.util.Log.e("WSOL_DEBUG", "sendWhisper: REFUSED — on-chain enc_pub=${bytesToHex(finalEncPub)} is junk (placeholder/legacy)")
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            error = UiError.RecipientEncPubCorrupted,
                        )
                        return@launch
                    }
                    else -> {
                        Logger.d(TAG, "sendWhisper: using on-chain PDA enc_pub for encryption (v1.8.0 strict mode)")
                        finalEncPub
                    }
                }
                // v1.8.0 DEBUG: log enc_pub used for encryption as ERROR (always shows in logcat).
                android.util.Log.e("WSOL_DEBUG", "RECIPIENT_PUB_USED=${bytesToHex(recipientX25519Pub)}")
                android.util.Log.e("WSOL_DEBUG", "RECIPIENT_PUB_HEX_SHORT=${bytesToHex(recipientX25519Pub).take(16)}…")
                _uiState.value = _uiState.value.copy(
                    debugEncPubHex = bytesToHex(recipientX25519Pub),
                )
                val plaintext = message.toByteArray(Charsets.UTF_8)
                val encResult = WhisperCrypto.encryptForRecipient(plaintext, recipientX25519Pub)
                Logger.d(TAG, "sendWhisper: encrypted ciphertext=${'$'}{encResult.ciphertext.size} encKey=${'$'}{encResult.encryptedKey.size} contentHash=${'$'}{encResult.contentHash.size}")

                // Stash state for retry. Nonce stays the same across retries —
                // only the blockhash is refreshed (so the message PDA derivation
                // is consistent across attempts).
                sendRetryCount = 0
                sendRetryRecipientAddress = recipientAddress
                sendRetryRecipientKey = recipientKey
                sendRetryNonce = System.currentTimeMillis()
                sendRetryEncResult = encResult
                sendRetryLastError = null

                _uiState.value = _uiState.value.copy(
                    isSending = true,
                    error = null,
                    sendRetryAttempt = 0,
                    lastSendRetryError = null,
                )

                attemptSendWhisper(senderKey, recipientKey, sendRetryNonce)
            } catch (e: Throwable) {
                Logger.e(TAG, "Send whisper error", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = UiError.SendFailed,
                )
            }
        }
    }

    /**
     * Build the send_message tx with a fresh recent blockhash and emit a
     * sign-and-send request. Called from [sendWhisper] (first attempt) and
     * from [onSignAndSendResult] (retry attempts).
     *
     * Failures are observed by the Activity, which feeds the MWA result back
     * through [onSignAndSendResult] — retry logic lives there, not here.
     */
    private suspend fun attemptSendWhisper(
        senderKey: ByteArray,
        recipientKey: ByteArray,
        nonce: Long,
    ) {
        val encResult = sendRetryEncResult
        val recipientAddress = sendRetryRecipientAddress
        if (encResult == null || recipientAddress == null) {
            Logger.e(TAG, "attemptSendWhisper: missing retry state (encrypted result / recipient)")
            _uiState.value = _uiState.value.copy(
                isSending = false,
                error = UiError.SendFailed,
            )
            return
        }

        val txBase64 = whisperService.buildSendMessageTransaction(
            senderKey,
            recipientKey,
            nonce,
            encResult.contentHash,
            // content_uri = first 64 bytes of ciphertext (chain limit)
            encResult.ciphertext.copyOfRange(0, encResult.ciphertext.size.coerceAtMost(64)),
            encResult.encryptedKey,
        )
        if (txBase64 == null) {
            Logger.e(TAG, "attemptSendWhisper: failed to build tx (blockhash fetch / PDA / ix)")
            _uiState.value = _uiState.value.copy(
                isSending = false,
                error = UiError.TransactionBuildFailed,
            )
            return
        }

        val txBytes = Base64.getDecoder().decode(txBase64)
        val attemptNumber = sendRetryCount + 1
        Logger.d(
            TAG,
            "send_message tx ready: ${'$'}{txBytes.size} bytes, attempt=$attemptNumber/${'$'}{MAX_SEND_RETRIES}, " +
                    "nonce=$nonce, recipient=${'$'}{recipientAddress.take(8)}..."
        )

        // Store nonce + recipient in pending send state so the success screen
        // can render the share-code regardless of which attempt succeeded.
        _uiState.value = _uiState.value.copy(
            pendingNonce = nonce,
            pendingRecipient = recipientAddress,
        )

        // v1.8.8: tryEmit return-value check
        val emitted = _signAndSendRequests.tryEmit(
            SignAndSendRequest(
                transactions = listOf(txBytes),
                purpose = SendPurpose.SendMessage,
            )
        )
        if (!emitted) {
            Logger.e(TAG, "signAndSendRequests.tryEmit DROPPED -- SendMessage")
            android.util.Log.e("WSOL_DEBUG", "attemptSendWhisper: signAndSend DROPPED")
        }
    }

    /**
     * Determine whether a send-message failure message indicates a transient
     * blockhash / timeout problem that justifies a retry with a fresh blockhash.
     */
    private fun isRetryableFailure(error: String?): Boolean {
        if (error == null) return false
        val msg = error.lowercase()
        return msg.contains("blockhash") ||
                msg.contains("expired") ||
                msg.contains("timeout") ||
                msg.contains("block height exceeded")
    }

    /**
     * Schedule the next send attempt after a retryable failure.
     * Waits 3 seconds (so the user sees the failure feedback flash), then
     * rebuilds the tx with a fresh blockhash and re-emits the request.
     */
    private fun scheduleRetry(
        senderKey: ByteArray,
        errorMessage: String,
    ) {
        val recipientKey = sendRetryRecipientKey ?: run {
            Logger.e(TAG, "scheduleRetry: missing recipient key — giving up")
            _uiState.value = _uiState.value.copy(
                isSending = false,
                error = UiError.SendFailed,
            )
            return
        }
        val nextAttempt = sendRetryCount + 1
        Logger.w(
            TAG,
            "sendWhisper: retryable failure attempt=${'$'}{sendRetryCount}/${'$'}{MAX_SEND_RETRIES} " +
                    "→ scheduling retry ${'$'}{nextAttempt}: ${'$'}{errorMessage}"
        )
        sendRetryCount = nextAttempt
        sendRetryLastError = errorMessage
        _uiState.value = _uiState.value.copy(
            isSending = true, // keep spinner up
            error = null,
            sendRetryAttempt = nextAttempt,
            lastSendRetryError = errorMessage,
        )
        viewModelScope.launch {
            kotlinx.coroutines.delay(RETRY_DELAY_MS)
            attemptSendWhisper(senderKey, recipientKey, sendRetryNonce)
        }
    }

    /** Reset all retry state after the send flow finishes (success or final fail). */
    private fun clearRetryState() {
        sendRetryCount = 0
        sendRetryRecipientAddress = null
        sendRetryRecipientKey = null
        sendRetryNonce = 0L
        sendRetryEncResult = null
        sendRetryLastError = null
    }

    /**
     * v1.3.0: Clear the last-send success state so the SendWhisperScreen
     * shows the empty form again instead of the SendCompletePanel. Called
     * when the user taps "Send Another" on the success screen.
     */
    fun clearLastSent() {
        _uiState.value = _uiState.value.copy(
            lastTxSignature = null,
            lastSentNonce = null,
            lastSentSender = null,
            pendingNonce = null,
            pendingRecipient = null,
            error = null,
        )
        clearRetryState()
    }

    /**
     * Activity calls this after MWA transact completes (success or failure).
     *
     * Send-message retry (v1.1.8): when MWA returns a failure whose message
     * contains "blockhash", "expired", "timeout", or "block height exceeded",
     * we wait 3 s and re-emit a fresh sign-and-send request via
     * [scheduleRetry] (up to [MAX_SEND_RETRIES] total attempts). Other
     * failures, and the final exhausted retry, surface as a [UiError.SendFailed]
     * and clear all retry state.
     */
    fun onSignAndSendResult(
        request: SignAndSendRequest,
        signature: String?,
        error: String?,
    ) {
        when (request.purpose) {
            SendPurpose.SendMessage -> {
                if (signature != null) {
                    Logger.d(TAG, "send_message confirmed (attempt ${sendRetryCount + 1}): $signature")
                    val senderBase58 = walletRepo.publicKey?.let {
                        com.solana.mobilewalletadapter.common.util.Base58.encode(it)
                    }
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        lastTxSignature = signature,
                        // Promote pendingNonce → lastSentNonce so the success
                        // view can render the share-code even after pending
                        // state is cleared.
                        lastSentNonce = _uiState.value.pendingNonce,
                        lastSentSender = senderBase58,
                        error = null,
                        pendingNonce = null,
                        pendingRecipient = null,
                        sendRetryAttempt = 0,
                        lastSendRetryError = null,
                    )
                    clearRetryState()
                    // Refresh treasury + inbox
                    refreshTreasury()
                    fetchInbox()
                } else {
                    Logger.e(TAG, "send_message failed (attempt ${sendRetryCount + 1}): $error")
                    val senderKey = walletRepo.publicKey
                    if (isRetryableFailure(error) &&
                        sendRetryCount < MAX_SEND_RETRIES - 1 &&
                        senderKey != null
                    ) {
                        // Schedule a retry with a fresh recent blockhash.
                        scheduleRetry(senderKey, error ?: "unknown error")
                    } else {
                        // Either non-retryable failure, or we exhausted our
                        // retry budget. Surface as a SendFailed error.
                        _uiState.value = _uiState.value.copy(
                            isSending = false,
                            error = UiError.SendFailed,
                            pendingNonce = null,
                            pendingRecipient = null,
                            sendRetryAttempt = 0,
                            lastSendRetryError = null,
                        )
                        clearRetryState()
                    }
                }
            }
            SendPurpose.UnlockMessage -> {
                if (signature != null) {
                    Logger.d(TAG, "unlock_message confirmed: $signature")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        lastTxSignature = signature,
                        error = null,
                    )
                    // Refresh treasury + inbox
                    refreshTreasury()
                    fetchInbox()
                    // v1.3.0: fetch the freshly-unlocked Message PDA so we can
                    // show "Reward won: X SOL" + encrypted content blobs.
                    // v1.4.0': also try to decrypt locally using the
                    // wallet-signature-derived X25519 secret.
                    val sKey = pendingUnlockSenderKey
                    val rKey = pendingUnlockRecipientKey
                    val nonce = pendingUnlockNonce
                    if (sKey != null && rKey != null) {
                        viewModelScope.launch {
                            val detail = whisperService.fetchMessageDetail(sKey, rKey, nonce)
                            if (detail != null) {
                                Logger.d(TAG, "fetchMessageDetail: reward=${detail.rewardPaidLamports} lamports, hash=${detail.contentHashHex.take(16)}...")
                                // v1.4.0': attempt local decryption if we
                                // have the wallet-derived X25519 secret.
                                val xs = _uiState.value.whisperKey?.xs
                                // v1.8.2 DEBUG: log cached enc_priv + derived enc_pub + eph_pub
                                // so we can see if it matches what's on chain.
                                android.util.Log.e("WSOL_DEBUG", "=== DECRYPT ATTEMPT ===")
                                if (xs != null) {
                                    android.util.Log.e("WSOL_DEBUG", "  cached enc_priv = ${bytesToHex(xs)}")
                                    val xp = com.google.crypto.tink.subtle.X25519.publicFromPrivate(xs)
                                    android.util.Log.e("WSOL_DEBUG", "  derived enc_pub  = ${bytesToHex(xp)}")
                                }
                                android.util.Log.e("WSOL_DEBUG", "  msg eph_pub (from chain) = ${detail.encryptedKeyHex.take(0)}...wait see below")
                                val plaintext = if (xs != null) {
                                    try {
                                        val ciphertext = hexToBytes(detail.encryptedContentHex)
                                        val encryptedKey = hexToBytes(detail.encryptedKeyHex)
                                        val ephPub2 = encryptedKey.copyOfRange(0, 32)
                                        android.util.Log.e("WSOL_DEBUG", "  msg eph_pub (parsed)    = ${bytesToHex(ephPub2)}")
                                        val ss = com.google.crypto.tink.subtle.X25519.computeSharedSecret(xs, ephPub2)
                                        android.util.Log.e("WSOL_DEBUG", "  computed sharedSecret   = ${bytesToHex(ss)}")
                                        val pt = WhisperCrypto.decryptForRecipient(
                                            encryptedContent = ciphertext,
                                            encryptedKeyBytes = encryptedKey,
                                            recipientX25519Secret = xs,
                                        )
                                        val ptStr = String(pt, Charsets.UTF_8)
                                        Logger.d(TAG, "decrypt SUCCESS: ${ptStr.take(80)}")
                                        android.util.Log.e("WSOL_DEBUG", "  RESULT: SUCCESS — plaintext=${ptStr.take(80)}")
                                        ptStr
                                    } catch (e: Exception) {
                                        Logger.w(TAG, "decrypt failed: ${e.message}")
                                        android.util.Log.e("WSOL_DEBUG", "  RESULT: FAILED — ${e.message}")
                                        null
                                    }
                                } else {
                                    Logger.w(TAG, "decrypt skipped: no whisperKey loaded")
                                    android.util.Log.e("WSOL_DEBUG", "  RESULT: SKIPPED — no whisperKey in state")
                                    null
                                }
                                _uiState.value = _uiState.value.copy(
                                    lastUnlockedMessage = detail,
                                    lastUnlockedRef = UnlockedRef(
                                        senderBase58 = detail.senderBase58,
                                        nonce = detail.nonce,
                                    ),
                                    lastUnlockedPlaintext = plaintext,
                                )
                            } else {
                                Logger.w(TAG, "fetchMessageDetail returned null after unlock")
                            }
                            // Clear stash
                            pendingUnlockSenderKey = null
                            pendingUnlockRecipientKey = null
                            pendingUnlockNonce = 0L
                        }
                    }
                } else {
                    Logger.e(TAG, "unlock_message failed: $error")
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = UiError.SendFailed,
                    )
                }
            }
            SendPurpose.InitInbox -> {
                if (signature != null) {
                    Logger.d(TAG, "init_inbox (bump self-heal) confirmed: $signature")
                    // No UI change needed - this is a background migration.
                    // Refresh inbox so the bump fix takes effect for next unlock.
                    fetchInbox()
                } else {
                    // init_inbox failing is non-fatal — if account doesn't
                    // exist yet the program can't bump (no-op), and if user
                    // cancels the MWA prompt we just skip. Either way the
                    // user can still send messages; new inboxes will be
                    // created with correct bump via send_message (v1.2.0).
                    Logger.w(TAG, "init_inbox (bump self-heal) skipped: $error")
                }
            }
            SendPurpose.ActivateChristmas,
            SendPurpose.DeactivateChristmas -> {
                // Both admin toggles use the same UI outcome: refresh
                // treasury state so the UI reflects the new christmas_mode.
                if (signature != null) {
                    val action = if (request.purpose == SendPurpose.ActivateChristmas) "ACTIVATED" else "deactivated"
                    Logger.d(TAG, "Christmas mode $action: $signature")
                    refreshTreasury()
                } else {
                    Logger.e(TAG, "Christmas toggle failed: $error")
                }
            }
            SendPurpose.RegisterWhisperId -> {
                if (signature != null) {
                    Logger.d(TAG, "register_whisper_id OK: $signature")
                    android.util.Log.e("WSOL_DEBUG", "register_whisper_id OK: $signature -- PDA written")
                    // v1.8.5: persist the registeredOnChain flag so next app
                    // launch can trust the cached WhisperKey (no silent-fail).
                    // v1.8.15: setRegisteredOnChain is now suspend + IO-dispatched
                    // (single boolean prefs write is cheap but still touches Tink
                    // crypto on caller thread). Fire-and-forget the persist; the UI
                    // state copy below is what the user sees immediately.
                    viewModelScope.launch { whisperKeyStore.setRegisteredOnChain(true) }
                    _uiState.value = _uiState.value.copy(
                        whisperIdOnChainRegistered = true,
                        whisperIdRegistrationError = null,
                        // v1.8.4: setup fully complete -- both signMessage
                        // and register tx succeeded. Main app tabs unlock.
                        setupState = SetupState.Complete(onChain = true),
                    )
                } else {
                    // v1.8.2: register tx FAILED -- silent-fail bug.
                    // v1.8.4: now blocks the app. User sees Failed state.
                    // v1.8.5: also clear the registeredOnChain flag.
                    // v1.8.15: setRegisteredOnChain is now suspend -- fire-and-forget.
                    viewModelScope.launch { whisperKeyStore.setRegisteredOnChain(false) }
                    Logger.e(TAG, "register_whisper_id FAILED: $error")
                    android.util.Log.e("WSOL_DEBUG", "register_whisper_id FAILED: $error -- PDA NOT written on chain. Setup blocked.")
                    _uiState.value = _uiState.value.copy(
                        whisperIdOnChainRegistered = false,
                        whisperIdRegistrationError = "register_whisper_id failed: $error",
                        // v1.8.4: setup FAILED. Main app tabs stay hidden.
                        whisperKeyError = "register_whisper_id tx failed: $error. Tap Retry.",
                        setupState = SetupState.Failed(
                            "register_whisper_id tx failed: $error.\n\n" +
                            "Sign message succeeded locally but on-chain register failed. " +
                            "Without on-chain PDA, other devices cannot find your Whisper ID.\n\n" +
                            "Tap Retry to attempt the on-chain register again.",
                        ),
                    )
                }
            }
        }
    }

    private fun formatAddress(pubkey: ByteArray): String {
        val base58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger(1, pubkey)
        val sb = StringBuilder()
        while (num.signum() != 0) {
            val r = num.mod(java.math.BigInteger.valueOf(58))
            sb.insert(0, base58[r.toInt()])
            num = num.divide(java.math.BigInteger.valueOf(58))
        }
        for (b in pubkey) {
            if (b.toInt() == 0) sb.insert(0, base58[0])
            else break
        }
        return sb.toString().let { "${it.take(4)}...${it.takeLast(4)}" }
    }

    private fun decodeBase58(s: String): ByteArray {
        // v1.1.6: fixed base58 decode bug — previous drop(1) stripped leading
        // 0 byte from positive BigInteger (which has NO sign byte). Solana
        // pubkeys with first nibble >= 8 lost their first byte, returning
        // 31 bytes instead of 32 and failing recipient validation.
        val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val map = HashMap<Char, Int>()
        for (i in ALPHABET.indices) map[ALPHABET[i]] = i
        // count leading '1' chars (= leading 0x00 bytes in decoded result)
        var leadingZeros = 0
        for (c in s) {
            if (c == '1') leadingZeros++ else break
        }
        var num = java.math.BigInteger.ZERO
        for (c in s) {
            val d = map[c] ?: throw IllegalArgumentException("Invalid base58: $c")
            num = num.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(d.toLong()))
        }
        // BigInteger.toByteArray() returns the minimum 2's-complement
        // representation. For positive num, there is no sign byte — the
        // first byte is the most significant data byte. Previous code's
        // unconditional drop(1) silently truncated the first byte.
        // For zero input, toByteArray() returns a single 0x00 byte.
        val raw = num.toByteArray()
        // BigInteger.toByteArray() returns the minimum 2's-complement
        // representation. For positive num, it PREPENDS a leading 0x00 byte
        // whenever the most significant bit of the data is set (so the
        // value isn't interpreted as negative). For a 32-byte Solana
        // pubkey whose first nibble >= 8, raw ends up 33 bytes:
        //   [0x00, MSB, ..., LSB]  ←  raw[0] is the sign byte, not data
        // The v1.1.6 fix only stripped raw[0] when leadingZeros > 0, which
        // missed pubkeys starting with a high-bit byte and zero leading '1's.
        // The correct rule: if raw[0] == 0x00 and the rest of raw still has
        // room for one more byte (i.e. raw.size > leadingZeros + 1), raw[0]
        // is a sign byte and must be stripped.
        val dataStart: Int
        val data: ByteArray
        if (raw.isNotEmpty() && raw[0] == 0.toByte() && num.signum() >= 0
            && raw.size > leadingZeros + 1
        ) {
            // raw[0] is the positive sign byte; data starts at index 1.
            dataStart = 1
            data = ByteArray(raw.size - 1)
            System.arraycopy(raw, 1, data, 0, raw.size - 1)
        } else if (num.signum() < 0 && raw[0] == 0.toByte()) {
            // Negative BigInteger: first 0x00 is sign byte
            dataStart = 1
            data = ByteArray(raw.size - 1)
            System.arraycopy(raw, 1, data, 0, raw.size - 1)
        } else {
            dataStart = 0
            data = raw
        }
        // Prepend leadingZeros 0x00 bytes for base58 leading '1' chars
        if (leadingZeros > 0) {
            val out = ByteArray(leadingZeros + data.size)
            // zeros already in array
            System.arraycopy(data, 0, out, leadingZeros, data.size)
            return out
        }
        return data
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val MAX_TREASURY_RETRIES = 3
        // v1.1.8: Send retry budget for blockhash-expired / timeout failures.
        // Seed Vault password prompts on Seeker can easily exceed the ~60-90s
        // recent-blockhash TTL. 3 attempts gives the user time to unlock +
        // approve while still capping infinite loops.
        private const val MAX_SEND_RETRIES = 3
        // v1.1.8: gap between retry attempts. Long enough for the user to
        // notice the failure feedback and prepare to sign again.
        private const val RETRY_DELAY_MS = 3000L

        fun readDebugConnectedFlag(): Boolean {
            return try {
                if (!com.linmjt.whisperofsol.BuildConfig.DEBUG) false
                else {
                    val sysClass = Class.forName("android.os.SystemProperties")
                    val getMethod = sysClass.getMethod("get", String::class.java, String::class.java)
                    (getMethod.invoke(null, "debug.app.connected", "false") as? String) == "true"
                }
            } catch (_: Throwable) {
                false
            }
        }

        fun readDebugTreasuryStatus(): TreasuryStatus {
            if (!readDebugConnectedFlag()) return TreasuryStatus.Idle
            return try {
                val sysClass = Class.forName("android.os.SystemProperties")
                val getMethod = sysClass.getMethod("get", String::class.java, String::class.java)
                when (getMethod.invoke(null, "debug.app.treasury_status", "loaded") as? String) {
                    "loading"   -> TreasuryStatus.Loading
                    "retryable" -> TreasuryStatus.Retryable
                    "failed"    -> TreasuryStatus.Failed
                    else        -> TreasuryStatus.Loaded
                }
            } catch (_: Throwable) {
                TreasuryStatus.Loaded
            }
        }
    }
}

/**
 * Sign-and-send request emitted by the ViewModel; the host Activity
 * is responsible for invoking MWA `transact { signAndSendTransactions(...) }`
 * and pushing the result back via `onSignAndSendResult`.
 */
data class SignAndSendRequest(
    val transactions: List<ByteArray>,
    val purpose: SendPurpose,
)

/**
 * v1.6.0: sign message request to derive the X25519 keypair.
 */
data class SignMessageRequest(
    val message: ByteArray,
    val requestId: Long,
)

enum class SendPurpose {
    SendMessage,
    UnlockMessage,
    InitInbox,
    ActivateChristmas,
    DeactivateChristmas,
    /** v1.6.1: store our WhisperId (enc_pub + signature) on chain so other
     *  dApps can auto-fill by our wallet address. */
    RegisterWhisperId,
}

/**
 * Typed user-facing errors. The ViewModel never injects raw exception
 * messages into the UI — it picks one of these and the screen picks a
 * localized message via [uiErrorMessage].
 */
sealed class UiError {
    object ConnectFailed : UiError()
    object InvalidRecipientAddress : UiError()
    object InvalidWhisperId : UiError()
    object SendFailed : UiError()
    object WalletNotConnected : UiError()
    object TransactionBuildFailed : UiError()
    object InvalidNonce : UiError()
    object NonceSetupFailed : UiError()
    // v1.8.0: recipient-side errors when their on-chain enc_pub is missing
    // or junk. Sender must wait for recipient to set up WhisperOfSol
    // (or re-register) before a Whisper can be delivered.
    object RecipientNotSetup : UiError()
    object RecipientEncPubCorrupted : UiError()
}

/**
 * v1.8.4: Setup lifecycle state machine.
 *
 * Why: v1.8.3 had a SecureRandom fallback that let users see a "Whisper ID"
 * even when signMessage failed (wallet hung, ANR, etc). User would share
 * that ID, but other devices can't find it on-chain → silent message loss.
 *
 * Now: if any step of setup fails, [SetupState.Failed] is shown and the
 * main app tabs (send/inbox) are hidden. User must Retry until either
 * setup completes OR they manually Disconnect.
 *
 * Transitions:
 *   NotStarted → InProgress (user taps "Generate Whisper ID")
 *   InProgress → Complete (signMessage OK + registerWhisperId OK)
 *   InProgress → Failed (signMessage null/timeout OR registerWhisperId error)
 *   Failed     → InProgress (user taps "Retry")
 *   Complete   → (terminal; only cleared by clearing app data)
 */
sealed class SetupState {
    /** No setup attempt yet, or app data was cleared. */
    object NotStarted : SetupState()
    /** dApp is mid-MWA-call (signMessage or registerWhisperId in flight). */
    object InProgress : SetupState()
    /**
     * Setup failed. Reason explains what failed. The dApp's main features
     * (send, inbox, decrypt) are HIDDEN until setup succeeds.
     */
    data class Failed(val reason: String) : SetupState()
    /**
     * Setup complete. [onChain] = true means registerWhisperId tx succeeded
     * (PDA on chain, other devices can find this user).
     * [onChain] = false means signMessage worked but register failed (local
     * WhisperKey works, but cross-device recovery is broken — show warning).
     */
    data class Complete(val onChain: Boolean) : SetupState()
    /**
     * v1.9.1: User is already registered on-chain for the connected wallet
     * (PDA exists), but local X25519 privkey is missing (cache cleared, fresh
     * install, etc.). UI should show a "Login" CTA instead of "Set up Whisper ID"
     * — logging in doesn't re-register, so saves GAS. Trade-off: old inbox
     * messages encrypted to the missing privkey can't be decrypted.
     */
    data class ReadyToLogin(val onChainEncPubBase64: String) : SetupState()
}

/**
 * Treasury fetch lifecycle.
 */
sealed class TreasuryStatus {
    object Idle : TreasuryStatus()
    object Loading : TreasuryStatus()
    object Loaded : TreasuryStatus()
    object Retryable : TreasuryStatus()
    object Failed : TreasuryStatus()
}

/**
 * UI-bound state.
 */
data class HomeUiState(
    val connected: Boolean = false,
    val publicKeyShort: String? = null,
    // v1.9.7: full base58 pubkey for QR code generation in the integrated
    // YOUR WALLET card (tappable address → QR dialog showing full pubkey).
    val publicKeyFull: String? = null,
    val publicKey: ByteArray? = null,
    val isConnecting: Boolean = false,
    val treasuryLamports: Long? = null,
    val jackpotLamports: Long? = null,
    val expectedValue: Long? = null,
    val isSending: Boolean = false,
    val error: UiError? = null,
    val treasuryStatus: TreasuryStatus = TreasuryStatus.Idle,
    val treasuryRetryCount: Int = 0,
    /** Signature of the last successful on-chain transaction (for UI display) */
    val lastTxSignature: String? = null,
    /** Last sent nonce (kept after onSignAndSendResult so the success
     *  view can render the share code). */
    val lastSentNonce: Long? = null,
    /** Sender's own pubkey base58 for the last sent message (so the
     *  recipient can call unlockWhisper with sender + nonce). */
    val lastSentSender: String? = null,
    /** Pending send nonce (for diagnostics) */
    val pendingNonce: Long? = null,
    /** Pending recipient base58 (for diagnostics) */
    val pendingRecipient: String? = null,
    /** User's own SOL balance (fetched via getBalance RPC after connect). */
    val userBalanceLamports: Long? = null,
    /** Lifecycle of the user balance fetch. */
    val userBalanceStatus: BalanceStatus = BalanceStatus.Idle,
    /** On-chain inbox messages for the connected user. */
    val inboxMessages: List<InboxMessageRef> = emptyList(),
    /** Lifecycle of the inbox fetch. */
    val inboxStatus: InboxStatus = InboxStatus.Idle,
    /** Lifecycle of the durable nonce account setup (v1.1.7). */
    val nonceSetupStatus: NonceSetupStatus = NonceSetupStatus.Idle,
    /** v1.1.8: how many send attempts we've done so far on the current
     *  retry-able failure (0 = first attempt, 2 = third/last). */
    val sendRetryAttempt: Int = 0,
    /** v1.1.8: human-readable reason for the retry (shown in the UI
     *  so the user knows we're going to try again). */
    val lastSendRetryError: String? = null,
    /** v1.3.0: On-chain Message PDA detail for the most-recently unlocked
     *  message. Non-null only briefly after a successful unlock, so the
     *  InboxScreen can show "Reward won: X SOL" + the encrypted payload. */
    val lastUnlockedMessage: MessageDetail? = null,
    /** v1.3.0: Sender + nonce of the just-unlocked message (used by
     *  InboxScreen to match the unlocked state against the inbox list). */
    val lastUnlockedRef: UnlockedRef? = null,
    /** v1.4.0': plaintext recovered from the just-unlocked Message PDA.
     *  Null if decryption failed (sender didn't encrypt to our Whisper ID)
     *  or if we don't have the X25519 secret yet. */
    val lastUnlockedPlaintext: String? = null,
    /** v1.4.0': derived X25519 keypair (deterministic from wallet signature).
     *  Once non-null, the dApp can decrypt incoming messages locally.
     *  v1.8.4: only set when setup truly succeeded (signMessage OK). If
     *  signMessage fails, this stays null and setupState = Failed. */
    val whisperKey: WhisperKey? = null,
    /** v1.8.4: setup lifecycle state machine. Drives the setup card UI
     *  AND gates the main app tabs (send/inbox). See SetupState. */
    val setupState: SetupState = SetupState.NotStarted,
    /** v1.7.2 DEBUG: hex of the enc_pub used for the most recent encryption
     *  attempt (after on-chain-lookup → fallback resolution). Displayed
     *  in the SendWhisperScreen for cross-checking with the on-chain value. */
    val debugEncPubHex: String? = null,
    /** v1.4.0': whether the WhisperKeyStore has prompted the wallet for the
     *  initial signature yet. UI uses this to show "Set up Whisper ID" CTA. */
    val whisperKeyInitialized: Boolean = false,
    /** v1.4.0': one-shot error flag for failed signMessages attempts. */
    val whisperKeyError: String? = null,
    /** v1.4.2: true while waiting for MWA signMessages response. UI shows
     *  spinner / disables button so user knows the dApp is alive (it was
     *  silently waiting 45–60 s before — looked like an ANR).
     *  v1.6.0: only shown during ONE-TIME setup (next derive — after that, ephemeral per message, no spinner needed). */
    val isSigningWhisperKey: Boolean = false,
    /** v1.8.30: True while we're in the middle of the register_whisper_id tx
     *  portion of the combined setup flow. Distinguishes "signing setup message"
     *  (isSigningWhisperKey=true) from "signing+sending register tx"
     *  (isRegisteringOnChain=true). Drives UI labels and spinner. */
    val isRegisteringOnChain: Boolean = false,
    /** v1.4.4: System.currentTimeMillis() when ensureWhisperKey() started.
     *  UI uses this for an elapsed-time display + stage-aware messaging. */
    val signMessageStartMs: Long? = null,

    /** v1.6.1: has the connected wallet's WhisperId been registered on chain.
     *  Drives the MainScreen "Your Whisper ID is on-chain" badge and the
     *  SendScreen "encrypted via on-chain PDA" hint. */
    val whisperIdOnChainRegistered: Boolean = false,
    // v1.8.2: error message when register_whisper_id tx fails (silent-fail bug)
    val whisperIdRegistrationError: String? = null,

    /** v1.6.1: per-recipient lookup state for the Send screen. Encoded as
     *  three states so the UI can show "Looking up..." / "Ready" / "Not found".
     *  Plain address string keyed by user to show "looked up for: null" diagnostics. */
    val recipientLookupStatus: RecipientLookupStatus = RecipientLookupStatus.Idle,
    val recipientLookupFor: String? = null,
    val recipientEncPub: ByteArray? = null,
    // v1.8.23: scaffold-style sign message result. Shows success/failure
    // + signature (Base58) so user can verify basic MWA sign works.
    val lastSnackbar: String? = null,

)

/**
 * v1.6.1: Lifecycle of the on-chain WhisperId PDA lookup for a recipient.
 * Drives the SendWhisperScreen "looking up / found / not found / error" UX.
 */
sealed class RecipientLookupStatus {
    object Idle : RecipientLookupStatus()
    object Looking : RecipientLookupStatus()
    object Found : RecipientLookupStatus()
    object NotFound : RecipientLookupStatus()
    object Error : RecipientLookupStatus()
}

/** v1.3.0: small value object identifying a single inbox row by sender+nonce. */
data class UnlockedRef(
    val senderBase58: String,
    val nonce: Long,
)

/**
 * Lifecycle of the connected user's balance fetch — used by WalletScreen
 * to show a spinner / error chip without leaking raw RPC exception text.
 */
sealed class BalanceStatus {
    object Idle : BalanceStatus()
    object Loaded : BalanceStatus()
    object Failed : BalanceStatus()
}

/**
 * Lifecycle of the connected user's on-chain inbox fetch.
 */
sealed class InboxStatus {
    object Idle : InboxStatus()
    object Loading : InboxStatus()
    object Loaded : InboxStatus()
    object Failed : InboxStatus()
}

/**
 * Lifecycle of the durable nonce account setup (v1.1.7).
 *
 *   Idle     – never checked
 *   Checking – RPC getAccountInfo in flight
 *   Creating – nonce account missing; createAccountWithSeed + initializeNonceAccount
 *              tx built and waiting for the user to sign
 *   Ready    – nonce account exists; tx can be built with durable nonce
 *   Failed   – setup tx failed or account still missing after attempt
 */
sealed class NonceSetupStatus {
    object Idle : NonceSetupStatus()
    object Checking : NonceSetupStatus()
    object Creating : NonceSetupStatus()
    object Ready : NonceSetupStatus()
    object Failed : NonceSetupStatus()
}

/**
 * Map a [UiError] to a localized user message.
 */
// uiErrorMessage moved to com.linmjt.whisperofsol.ui.UiStrings.kt
// (v3.0.0: now uses stringResource() for proper localization)
/**
 * v1.4.0': decode a hex string (with or without 0x prefix, lower or upper
 * case) to a byte array. Used to convert MessageDetail blob fields back
 * to raw bytes before AES-GCM decryption.
 */
private fun hexToBytes(hex: String): ByteArray {
    val s = hex.trim().removePrefix("0x").removePrefix("0X")
    require(s.length % 2 == 0) { "hex string must have even length: ${'$'}{s.length}" }
    val out = ByteArray(s.length / 2)
    var i = 0
    while (i < s.length) {
        val hi = Character.digit(s[i], 16)
        val lo = Character.digit(s[i + 1], 16)
        require(hi != -1 && lo != -1) { "invalid hex at ${'$'}i: ${'$'}{s.substring(i, i + 2)}" }
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}

/** v1.7.2 DEBUG: hex encoder for logging enc_pub values to logcat. */
private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }