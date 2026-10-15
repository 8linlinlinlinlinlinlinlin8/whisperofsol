package com.linmjt.whisperofsol

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linmjt.whisperofsol.data.WalletRepository
import com.linmjt.whisperofsol.ui.AddressBookPrefs
import com.linmjt.whisperofsol.ui.components.PostSendDialog
import com.linmjt.whisperofsol.ui.screens.InboxScreen
import com.linmjt.whisperofsol.ui.screens.MainScreen
import com.linmjt.whisperofsol.ui.screens.RulesScreen
import com.linmjt.whisperofsol.ui.screens.SendWhisperScreen
import com.linmjt.whisperofsol.ui.screens.SettingsScreen
import com.linmjt.whisperofsol.ui.screens.WalletScreen
import com.linmjt.whisperofsol.ui.screens.AddressBookScreen
import com.linmjt.whisperofsol.ui.theme.WhisperOfSolTheme
import com.linmjt.whisperofsol.ui.navigation.Route
import com.linmjt.whisperofsol.ui.navigation.rememberAppRouter
import com.linmjt.whisperofsol.ui.viewmodel.SignAndSendRequest
import com.linmjt.whisperofsol.ui.viewmodel.SignMessageRequest
import com.linmjt.whisperofsol.ui.viewmodel.SendPurpose
import com.linmjt.whisperofsol.util.Logger
import kotlinx.coroutines.launch

/**
 * MainActivity — Whisper of Sol entry point.
 *
 * Owns the MWA ActivityResultSender (must be pre-registered in onCreate).
 * Hosts [WhisperOfSolApp], which observes the ViewModel and triggers
 * MWA flows via SharedFlow emissions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mwaSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender

    /**
     * v3.0.6: Apply saved locale to resources BEFORE Compose runs.
     * Uses [Resources.updateConfiguration] (deprecated in API 25+ but still
     * works) to set the activity's locale in-place. This way the first
     * frame already has the right strings, and we don't need to override
     * the entire Context (which broke Compose internals in v3.0.5).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        Logger.d(TAG, "onCreate")
        // Apply saved locale to the activity's resources, then seed
        // LanguageState so SettingsScreen knows the current choice.
        applyLocaleFromPrefs()
        com.linmjt.whisperofsol.ui.LanguageState.current.value =
            com.linmjt.whisperofsol.ui.screens.readSavedLanguage(this)

        // v1.8.13 fix attempt: tried forcing IPv4 socket stack via  // v1.9.11: drop ⚠️
        // `System.setProperty("java.net.preferIPv4Stack", "true")` to work
        // around an Android 16 / Seeker OS quirk where the wallet's WebSocket
        // client gets ECONNREFUSED trying to reach our dApp's IPv6-mapped
        // IPv4 listener. **Did not help** — the listener still binds as
        // `[::ffff:127.0.0.1]:<port>` and the wallet can't connect. Reverted
        // v1.8.14 → v1.8.13. See logcat 2026-10-07 13:29:09 for full diagnosis.
        //
        // The real fix likely requires either:
        //   1. A Seeker OS update that restores app-to-app localhost routing
        //   2. Forcing the MWA library to bind via InetSocketAddress("0.0.0.0", port)
        //      instead of "127.0.0.1" (would need library patch)
        //   3. Using an MWA "remote" association scheme (external relay)
        // Until then, setup will continue to fail with ECONNREFUSED on Seeker
        // regardless of wallet choice.

        // MWA sender 預註冊 (必須在 STARTED 之前)  // v1.9.11: drop ✅
        mwaSender = com.solana.mobilewalletadapter.clientlib.ActivityResultSender(this)

        WalletRepository.instance.initialize()

        enableEdgeToEdge()
        setContent {
            // v3.0.6: simple setContent (no CompositionLocalProvider wrap).
            // The activity's resources are already set to the right locale
            // by [applyLocaleFromPrefs] in onCreate (and by SettingsScreen's
            // onClick at runtime), so every stringResource() in the tree
            // returns the new locale's strings automatically.
            WhisperOfSolTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WhisperOfSolApp(activity = this@MainActivity, mwaSender = mwaSender)
                }
            }
        }
    }

    /**
     * v3.0.6: Read the saved language from prefs and apply it to the
     * activity's resources via [Resources.updateConfiguration]. Called
     * from onCreate so the first frame already has the right strings.
     */
    private fun applyLocaleFromPrefs() {
        val tag = com.linmjt.whisperofsol.ui.screens.readSavedLanguage(this) ?: return
        com.linmjt.whisperofsol.ui.screens.applyLocaleToResources(this, tag)
    }

    override fun onDestroy() {
        super.onDestroy()
        WalletRepository.instance.cleanup()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

/**
 * WhisperOfSolApp — top-level composable orchestrating the 5 screens.
 *
 * Bridges the Activity (which holds the MWA ActivityResultSender) to
 * the ViewModel (which must NOT hold an Activity reference to avoid leaks).
 *
 *   - ViewModel emits `connectRequests` → Activity collects → walletRepo.connect(sender)
 *   - ViewModel emits `signAndSendRequests` → Activity collects → walletRepo.signAndSendTransactions(sender, …)
 *   - Activity pushes results back via viewModel.onConnectResult / onSignAndSendResult
 */
@Composable
fun WhisperOfSolApp(
    activity: ComponentActivity,
    mwaSender: com.solana.mobilewalletadapter.clientlib.ActivityResultSender,
    viewModel: com.linmjt.whisperofsol.ui.viewmodel.HomeViewModel = viewModel(),
) {

    // v1.5.0: kick off X25519 keypair generation immediately at app start.
    // v1.8.15: ensureWhisperKey is now `suspend` because the underlying
    // EncryptedSharedPreferences read + X25519 derivation are IO-dispatched
    // (1-3 sec on slow Seekers -- would ANR if called on Main).
    // LaunchedEffect already runs in a coroutine on Main, so this just
    // compiles after the suspend change -- no other wrap needed.
    // The prefs file is preloaded synchronously in WhisperOfSolApp.onCreate
    // so the first call here is just a value read, not a 1-3 sec master key
    // unwrap.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.ensureWhisperKey()
    }
    // v3.0.7 fix: Debug builds can override the start route for testing
    // via Intent extras only — system properties are no longer trusted.
    // System properties (`adb shell setprop debug.app.start_route X`)
    // persist across app restarts, which means a test session could
    // permanently hijack the start screen even for the developer.
    // Intent extras don't persist, so the route defaults back to Wallet
    // on the next normal launch.
    //
    // Usage:
    //   adb shell am start -n com.linmjt.whisperofsol.debug/com.linmjt.whisperofsol.MainActivity \
    //       --es debug.app.start_route send
    val startRoute = remember {
        if (com.linmjt.whisperofsol.BuildConfig.DEBUG) {
            when (activity.intent?.getStringExtra("debug.app.start_route")) {
                "main"  -> Route.Main
                "send"  -> Route.SendWhisper
                "inbox" -> Route.Inbox
                "rules" -> Route.Rules
                "settings" -> Route.Settings
                "addressbook" -> Route.AddressBook
                else    -> Route.Wallet
            }
        } else {
            Route.Wallet
        }
    }
    val router = rememberAppRouter(initial = startRoute)
    val uiState by viewModel.uiState.collectAsState()

    // ─── Bridge: Activity ↔ ViewModel ─────────────────────────
    val walletRepo = WalletRepository.instance
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Connect requests → MWA authorize
    LaunchedEffect(Unit) {
        viewModel.connectRequests.collect {
            Logger.d("WhisperApp", "Connect request → invoking MWA authorize")
            scope.launch {
                val auth = walletRepo.connect(mwaSender)
                if (auth != null) {
                    viewModel.onConnectResult(success = true)
                } else {
                    viewModel.onConnectError(walletRepo.lastError)
                }
            }
        }
    }

    // Sign-and-send requests → MWA transact
    LaunchedEffect(Unit) {
        viewModel.signAndSendRequests.collect { req: SignAndSendRequest ->
            Logger.d(
                "WhisperApp",
                "SignAndSend request: ${req.transactions.size} tx, purpose=${req.purpose}"
            )
            scope.launch {
                val result = walletRepo.signAndSendTransactions(mwaSender, req.transactions, skipPreflight = req.purpose == SendPurpose.UnlockMessage)
                if (result.isSuccess) {
                    viewModel.onSignAndSendResult(
                        request = req,
                        signature = result.signatures.firstOrNull(),
                        error = null,
                    )
                } else {
                    viewModel.onSignAndSendResult(
                        request = req,
                        signature = null,
                        error = result.error,
                    )
                }
            }
        }
    }

    // v1.6.0: sign-message collect. The X25519 keypair is derived from a
    // wallet signature on a canonical message (one-time setup, ~45 s).
    LaunchedEffect(Unit) {
        viewModel.signMessageRequests.collect { req: com.linmjt.whisperofsol.ui.viewmodel.SignMessageRequest ->
            Logger.d(
                "WhisperApp",
                "SignMessage request: id=${req.requestId} message=${req.message.take(16).joinToString("") { "%02x".format(it) }}…"
            )
            scope.launch {
                val result = walletRepo.signMessages(mwaSender, listOf(req.message))
                if (result.isSuccess) {
                    viewModel.onSignMessageResult(req.requestId, result.signatures.firstOrNull())
                } else {
                    viewModel.onSignMessageResult(req.requestId, null)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = router.current,
            transitionSpec = {
                (slideInHorizontally(initialOffsetX = { it / 4 }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut())
            },
            label = "screenTransition",
            contentKey = { it.route },
        ) { route ->
            when (route) {
                Route.Wallet -> WalletScreen(
                    uiState = uiState,
                    onConnected = { router.navigate(Route.Main) },
                    onConnect = { viewModel.connect() },
                    // v1.8.22: Wire Set-up-Whisper-ID button to ensureWhisperKey,
                    // not viewModel.connect(). Previously the button on
                    // WalletScreen's WhisperIdCard called onConnect() which
                    // just re-authorized the wallet — the actual sign-message
                    // step was never triggered, leaving the user stuck in a
                    // reconnect loop (user feedback 2026-10-08 00:44).
                    onRequestSetup = {
                        // v1.8.30: Combined setup + register on-chain flow.
                        // Sign setup message -> derive key -> sign+send register tx.
                        // Single tap, sequential, only enter app after both succeed.
                        viewModel.signSetupAndRegisterOnChainInline(mwaSender)
                    },
                    // v1.9.1: If user already registered on-chain (PDA exists),
                    // show "Login" CTA instead of forcing them through setup
                    // again — saves GAS. ViewModel marks setupState=ReadyToLogin,
                    // tapping this enters using a view-only WhisperKey and
                    // navigates to home (no auto-trigger via `connected`, since
                    // that flag was set during the initial wallet connect).
                    onLoginExisting = {
                        viewModel.loginWithoutPrivKey()
                        router.navigate(Route.Main)
                    },
                )
                Route.Main -> MainScreen(
                    uiState = uiState,
                    onNavigateSend  = { router.navigate(Route.SendWhisper) },
                    onNavigateInbox = { router.navigate(Route.Inbox) },
                    onNavigateRules = { router.navigate(Route.Rules) },
                    onNavigateSettings = { router.navigate(Route.Settings) },
                    onRefresh = { viewModel.refreshTreasury() },
                    onSetupWhisperId = {
                        // v1.8.30: Same combined flow as WalletScreen's onRequestSetup.
                        viewModel.signSetupAndRegisterOnChainInline(mwaSender)
                    },
                    // v1.8.27: explicit on-chain register trigger (not auto-fire).
                    // After local-only setup (v1.8.26), user taps this to send
                    // the register_whisper_id tx. Expects 1 approve for the
                    // Transaction sheet + PIN.
                    onRegisterWhisperIdOnChain = {
                        viewModel.registerWhisperIdOnChainInline(mwaSender)
                    },
                    // v1.9.2: Login CTA shown on MainScreen when PDA exists.
                    // Goes through loginWithoutPrivKey() which uses view-only
                    // WhisperKey (no signing, no GAS).
                    onLoginExisting = { viewModel.loginWithoutPrivKey() },
                    onCancelSignMessage = { viewModel.cancelSignMessage() },
                    onDisconnect = {
                        viewModel.disconnect()
                        router.navigate(Route.Wallet)
                    },
                )
                Route.SendWhisper -> SendWhisperScreen(
                    uiState = uiState,
                    onBack = {
                        viewModel.clearLastSent()
                        router.navigate(Route.Main)
                    },
                    onSent = { router.navigate(Route.Inbox) },
                    onSendAnother = {
                        viewModel.clearLastSent()
                    },
                    onSend = { recipient, message -> viewModel.sendWhisper(recipient, message) },
                    // v1.9.6: removed onRecipientAddressChange — no
                    // per-keystroke lookup. sendWhisper does fresh
                    // on-chain check at send time. ~1 RPC per send.
                    onRetryTreasury = { viewModel.refreshTreasury() },
                )
                Route.Inbox -> InboxScreen(
                    uiState = uiState,
                    onBack = { router.navigate(Route.Main) },
                    onUnlockByRef = { senderBase58, nonce ->
                        viewModel.unlockByRef(senderBase58, nonce)
                    },
                    onRefresh = { viewModel.fetchInbox() },
                )
                Route.Rules -> RulesScreen(
                    onBack = { router.navigate(Route.Main) },
                    // v1.9.12: pass treasuryLamports so the Mystery
                    // Jackpot tier can show the dynamic 50% reward
                    // (instead of a stale hardcoded 0.3 SOL).
                    treasuryLamports = uiState.treasuryLamports,
                )
                // v3.0.0: Settings screen (language switcher + about).
                Route.Settings -> SettingsScreen(
                    onBack = { router.navigate(Route.Main) },
                    onNavigateAddressBook = { router.navigate(Route.AddressBook) },
                )
                Route.AddressBook -> AddressBookScreen(
                    onBack = { router.navigate(Route.Settings) },
                )
            }
        }

        // v3.0.8 (bug fix 2026-10-13): Post-send prompt moved to app-level
        // so it actually renders. The previous implementation lived inside
        // SendWhisperScreen, but that screen returns early when
        // SendCompletePanel is shown (`if (showShareCode) { ...; return }`),
        // so the dialog state was set but never rendered.
        //
        // Now the dialog renders on top of any route — user sees the
        // success view (Inbox or SendCompletePanel) in the background and
        // chooses Save / Not now from the modal.
        //
        // Triggered by `uiState.lastSentRecipient` becoming non-null on a
        // successful send (set in HomeViewModel.onSignAndSendResult).
        // Dismiss / save both call `clearLastSent` so subsequent navigation
        // doesn't re-trigger.
        var postSendRecipient by remember { mutableStateOf<String?>(null) }
        // Debug-only synthetic trigger (v3.0.8.1): exercises the dialog
        // without going through the full send flow. On a Seeker device
        // MWA is broken (ECONNREFUSED) so we can't otherwise verify the
        // dialog renders after a real send. Stripped from release builds.
        //   adb shell am start ... --es debug.app.force_postsend <address>
        LaunchedEffect(Unit) {
            if (com.linmjt.whisperofsol.BuildConfig.DEBUG) {
                val forced = activity.intent?.getStringExtra("debug.app.force_postsend")
                if (!forced.isNullOrBlank()
                    && !AddressBookPrefs.contains(activity, forced)
                    && !AddressBookPrefs.isPostSendPromptDisabled(activity)
                ) {
                    postSendRecipient = forced
                }
            }
        }
        LaunchedEffect(uiState.lastSentRecipient) {
            val r = uiState.lastSentRecipient
            if (!r.isNullOrBlank()
                && !AddressBookPrefs.contains(activity, r)
                && !AddressBookPrefs.isPostSendPromptDisabled(activity)
            ) {
                postSendRecipient = r
            }
        }
        postSendRecipient?.let { addr ->
            PostSendDialog(
                recipientAddress = addr,
                onDismiss = {
                    // "Not now" — no save, just close + clear the trigger.
                    postSendRecipient = null
                    viewModel.clearLastSent()
                },
                onSave = { nickname ->
                    // "Save" — write to prefs (empty nickname ok; user can
                    // rename later from the Manage sheet).
                    AddressBookPrefs.add(activity, addr, nickname)
                    postSendRecipient = null
                    viewModel.clearLastSent()
                },
            )
        }
    }
}
