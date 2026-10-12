package com.linmjt.whisperofsol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.ui.components.BrandLogo
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.GoldGradientButton
import com.linmjt.whisperofsol.ui.components.SnowflakeIcon
import com.linmjt.whisperofsol.ui.theme.Gold300
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.BuildConfig
import com.linmjt.whisperofsol.ui.uiErrorMessage
import androidx.compose.ui.res.stringResource
import com.linmjt.whisperofsol.R

/**
 * WalletScreen — first impression / connect wallet.
 *
 * Visual goals:
 *   • Brand wordmark + emblem prominent (Christmas sock logo)
 *   • Centered gradient hero (red→gold)
 *   • Big gold Connect Wallet CTA — festive, premium
 *   • Clean footer — no version string, no dev tagline (Stage 1.5 fix)
 *   • Snow particles in background
 */
@Composable
fun WalletScreen(
    uiState: com.linmjt.whisperofsol.ui.viewmodel.HomeUiState,
    onConnected: () -> Unit,
    onConnect: () -> Unit = {},
    onRequestSetup: () -> Unit = {},
    // v1.9.1: when on-chain PDA already exists, show this CTA so they can
    // enter home without re-registering (saves GAS).
    onLoginExisting: () -> Unit = {},
) {

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))

            // ─── Brand ─────────────────────────────────────────────
            BrandLogo(size = 120.dp, showWordmark = false)

            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            // Decorative snowflake row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SnowflakeIcon(tint = Gold300, size = 16.dp)
                Text(
                    text = "X25519 + AES-GCM end-to-end encrypted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SnowflakeIcon(tint = Gold300, size = 16.dp)
            }

            Spacer(Modifier.weight(1f))

            // ─── Connect CTA ────────────────────────────────────────
            GoldGradientButton(
                text = when {
                    uiState.isConnecting -> stringResource(R.string.wallet_connecting)
                    uiState.connected    -> stringResource(R.string.wallet_connected)  // v1.9.11: drop ✓
                    else -> stringResource(R.string.wallet_connect)
                },
                onClick = {
                    if (!uiState.connected) {
                        onConnect()
                    } else {
                        onConnected()
                    }
                },
                enabled = !uiState.isConnecting,
            )

            // ─── Connected-wallet card (Bug 2026-10-02 fix) ──────────────────
            // After successful MWA authorize, show the wallet's short
            // address + live SOL balance so users can confirm they're
            // connected to the right account before sending whispers.
            if (uiState.connected) {
                Spacer(Modifier.height(20.dp))
                ConnectedWalletCard(
                    publicKeyShort = uiState.publicKeyShort,
                    userBalanceLamports = uiState.userBalanceLamports,
                    balanceStatus = uiState.userBalanceStatus,
                )
                Spacer(Modifier.height(14.dp))
                WhisperIdCard(
                    whisperKey = uiState.whisperKey,
                    whisperKeyError = uiState.whisperKeyError,
                    // v1.8.32: pass signing flags so button label reflects stage
                    isSigningWhisperKey = uiState.isSigningWhisperKey,
                    isRegisteringOnChain = uiState.isRegisteringOnChain,
                    // v1.9.1: pass setupState so card can show Login (registered)
                    // vs Set up (not registered) vs Retry (error).
                    setupState = uiState.setupState,
                    onRequestSetup = onRequestSetup, // may need to call ensureWhisperKey -- see MainActivity
                    onLoginExisting = onLoginExisting,
                )
            }

            if (uiState.isConnecting) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Gold500,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.wallet_opening_seed_vault),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Error feedback (Stage 1.5: UiError → localized message, never raw)
            if (uiState.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = uiErrorMessage(uiState.error!!),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(28.dp))

            // ─── Footer ─── build version (user request 2026-10-02) ────────
            Text(
                text = "v${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // Auto-advance once connected
    androidx.compose.runtime.LaunchedEffect(uiState.connected) {
        if (uiState.connected) onConnected()
    }
}

/**
 * Connected-wallet card — shows short address + SOL balance after
 * MWA authorize. Bug 2026-10-02 fix: users reported no wallet info
 * visible after connect.
 */
@Composable
private fun ConnectedWalletCard(
    publicKeyShort: String?,
    userBalanceLamports: Long?,
    balanceStatus: com.linmjt.whisperofsol.ui.viewmodel.BalanceStatus,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Short pubkey
        if (publicKeyShort != null) {
            Text(
                text = publicKeyShort,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
        }

        // Balance — loading / loaded / failed
        when (balanceStatus) {
            com.linmjt.whisperofsol.ui.viewmodel.BalanceStatus.Idle,
            com.linmjt.whisperofsol.ui.viewmodel.BalanceStatus.Loaded -> {
                if (userBalanceLamports != null) {
                    val solAmount = userBalanceLamports.toDouble() / 1_000_000_000.0
                    Text(
                        text = String.format("%.4f SOL", solAmount),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Gold500,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Gold500,
                        strokeWidth = 2.dp,
                    )
                }
            }
            com.linmjt.whisperofsol.ui.viewmodel.BalanceStatus.Failed -> {
                Text(
                    text = stringResource(R.string.wallet_balance_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
/**
 * v1.4.0': Whisper ID card.
 *
 * Shows the user's derived X25519 pubkey (base64) — what they need to share
 * with senders so messages can be encrypted to them.
 *
 * States:
 *  - whisperKey == null + no error  -> "Generating Whisper ID…" spinner
 *  - whisperKey != null             -> show Whisper ID + Copy + Share buttons
 *  - whisperKeyError != null        -> show error + retry button
 */
@Composable
private fun WhisperIdCard(
    whisperKey: com.linmjt.whisperofsol.crypto.WhisperKey?,
    whisperKeyError: String?,
    // v1.8.32: pass signing flags so the button label can reflect current stage
    isSigningWhisperKey: Boolean = false,
    isRegisteringOnChain: Boolean = false,
    // v1.9.1: setupState drives the button label — Set up vs Login vs Retry
    setupState: com.linmjt.whisperofsol.ui.viewmodel.SetupState =
        com.linmjt.whisperofsol.ui.viewmodel.SetupState.NotStarted,
    onRequestSetup: () -> Unit,
    onLoginExisting: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val (copied, setCopied) = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            whisperKeyError != null -> {
                Text(
                    text = stringResource(R.string.whisper_id_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = whisperKeyError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                GoldGradientButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRequestSetup,
                )
            }
            whisperKey != null -> {
                val whisperId = whisperKey.whisperIdBase64()
                Text(
                    text = stringResource(R.string.whisper_id_share_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = Gold500,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = whisperId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldGradientButton(
                        text = if (copied) stringResource(R.string.action_copied) else stringResource(R.string.action_copy),  // v1.9.11: drop ✓
                        onClick = {
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("Whisper ID", whisperId),
                            )
                            setCopied(true)
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.whisper_id_share_desc_old),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            else -> {
                // v1.9.1: Distinguish "ready to register" vs "already on-chain".
                // setupState == ReadyToLogin  → PDA exists; show "Login" (no GAS).
                // setupState == NotStarted   → first-time setup; show "Set up Whisper ID".
                // setupState == Failed       → error; show "Retry" (handled by parent branch).
                val isReadyToLogin = setupState is com.linmjt.whisperofsol.ui.viewmodel.SetupState.ReadyToLogin
                if (isReadyToLogin) {
                    Text(
                        text = stringResource(R.string.setup_already_registered),
                        style = MaterialTheme.typography.labelMedium,
                        color = Gold500,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.setup_login_long_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    GoldGradientButton(
                        text = stringResource(R.string.setup_login),
                        enabled = true,
                        onClick = onLoginExisting,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.setup_need_re_register),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    // No Whisper ID, no error, not ready-to-login: explicit CTA.
                    // The wallet will warn about 'unsafe characters' because
                    // the message is a 32-byte signature output, but it does
                    // NOT move funds.
                    Text(
                        text = stringResource(R.string.setup_set_up_whisper_id),
                        style = MaterialTheme.typography.labelMedium,
                        color = Gold500,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.setup_sign_and_register_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    GoldGradientButton(
                        text = when {
                            isRegisteringOnChain -> stringResource(R.string.setup_approve_tx)
                            isSigningWhisperKey -> stringResource(R.string.setup_approve_sign)
                            else -> stringResource(R.string.setup_set_up_cta)
                        },
                        enabled = !isSigningWhisperKey && !isRegisteringOnChain,
                        onClick = onRequestSetup,
                    )
                }
            }
        }
    }
}
