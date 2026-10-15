package com.linmjt.whisperofsol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.ChristmasCountdown
import com.linmjt.whisperofsol.ui.components.GradientButton
import com.linmjt.whisperofsol.ui.components.GlowCard
import com.linmjt.whisperofsol.ui.components.GoldGradientButton
import com.linmjt.whisperofsol.ui.components.IconBadge
import com.linmjt.whisperofsol.ui.components.SnowflakeIcon
import com.linmjt.whisperofsol.ui.components.StatTile
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.Gold300
import com.linmjt.whisperofsol.ui.theme.Green500
import com.linmjt.whisperofsol.ui.theme.Green700
import com.linmjt.whisperofsol.ui.theme.HeroCardShape
import com.linmjt.whisperofsol.ui.theme.Midnight900
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700
import com.linmjt.whisperofsol.util.WsolLog
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import androidx.compose.ui.res.stringResource
import com.linmjt.whisperofsol.R

/**
 * MainScreen — home dashboard after wallet connect.
 *
 * Sections (top-to-bottom):
 *   1. Snow particle background + greeting header
 *   2. 12/25 Christmas countdown banner
 *   3. Treasury balance tile (big number)
 *   4. Quick actions: Send Whisper (primary CTA) + 3 small action chips
 *   5. Inbox preview — top 3 incoming messages (mock V1)
 *   6. Bottom nav strip: Rules / Inbox / Settings
 */
@Composable
fun MainScreen(
    uiState: com.linmjt.whisperofsol.ui.viewmodel.HomeUiState,
    onNavigateSend:  () -> Unit,
    onNavigateInbox: () -> Unit,
    onNavigateRules: () -> Unit,
    // v3.0.0: Settings nav (was V1 stub `onClick = { /* V1 stub */ }`)
    onNavigateSettings: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSetupWhisperId: () -> Unit = {},
    onCancelSignMessage: () -> Unit = {},
    // v1.8.27: explicit register-on-chain trigger (not auto-fire)
    onRegisterWhisperIdOnChain: () -> Unit = {},
    // v1.9.2: Login button when on-chain PDA exists (no GAS path)
    onLoginExisting: () -> Unit = {},
) {

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 32.dp, bottom = 32.dp
            ),
        ) {
            // v1.8.4: BLOCK main app until setup completes. If setup is not
            // Complete, only the setup card is shown (full-width, centered
            // in the visible area). This prevents the user from sending/
            // decrypting without a valid WhisperKey.
            if (uiState.setupState !is com.linmjt.whisperofsol.ui.viewmodel.SetupState.Complete) {
                item {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.Text(
                                text = stringResource(R.string.setup_required_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Gold500,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.Text(
                                text = stringResource(R.string.setup_required_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            WhisperIdCompactCard(
                                setupState = uiState.setupState,
                                whisperKey = uiState.whisperKey,
                                whisperKeyError = uiState.whisperKeyError,
                                isSigning = uiState.isSigningWhisperKey,
                                signMessageStartMs = uiState.signMessageStartMs,
                                onSetup = onSetupWhisperId,
                                onCancel = onCancelSignMessage,
                                // v1.9.3 CRITICAL FIX: was missing in v1.9.2,
                                // so the ReadyToLogin Login button was
                                // wired to default `{}` (no callback) and
                                // tapping it did nothing. Now properly
                                // wired to viewModel.loginWithoutPrivKey.
                                onLoginExisting = onLoginExisting,
                            )
                            // v1.8.23: SCAFFOLD-EXACT test button — just signs
                            // (debug scaffold test button removed in v1.8.29)
                        }
                    }
                }
                return@LazyColumn
            }
            // ─── Header greeting ─────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Stage 1.5: dropped the "Ho ho ho! [tree]" greeting — Christmas
                    // theming is already carried by the rest of the UI, the
                    // duplicate emoji subtitle reads as hackathon marketing fluff.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            )
                            .clickable { onRefresh() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // ─── Christmas countdown banner ──────────────────────────
            item {
                ChristmasCountdown()
            }

            // ─── User wallet balance (Bug 2026-10-02 10:55 fix) ─────────
            // User explicitly asked for clear, prominent balance display.
            // Earlier this only showed in WalletScreen, but that screen
            // auto-navigates to MainScreen on connect so the user never
            // saw it. Now it's a dedicated GlowCard with a large display
            // number for the user's own SOL.
            item {
                // v1.9.8: consolidated — was UserBalanceCard (top) + IntegratedWalletCard
                // (bottom). Now ONE card at the top that shows: big balance, tappable
                // address (QR), Whisper ID (no copy/share), Disconnect, and the
                // setup state machine when the user hasn't completed setup.
                IntegratedWalletCard(
                    publicKeyShort = uiState.publicKeyShort,
                    publicKeyFull = uiState.publicKeyFull,
                    userBalanceLamports = uiState.userBalanceLamports,
                    balanceStatus = uiState.userBalanceStatus,
                    setupState = uiState.setupState,
                    whisperKey = uiState.whisperKey,
                    whisperKeyError = uiState.whisperKeyError,
                    isSigning = uiState.isSigningWhisperKey,
                    isRegisteringOnChain = uiState.isRegisteringOnChain,
                    signMessageStartMs = uiState.signMessageStartMs,
                    onSetup = onSetupWhisperId,
                    onCancel = onCancelSignMessage,
                    onLoginExisting = onLoginExisting,
                    onRegisterOnChain = onRegisterWhisperIdOnChain,
                    onDisconnect = onDisconnect,
                )
            }

            // ─── Treasury / Jackpot tiles ─────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.chip_treasury),
                        value = uiState.treasuryLamports?.let { formatSol(it) } ?: "—",
                        icon = Icons.Filled.AccountBalanceWallet,
                        accentColor = Green700,
                    )
                    StatTile(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.chip_jackpot),
                        value = uiState.jackpotLamports?.let { formatSol(it) } ?: "—",
                        icon = Icons.Outlined.CardGiftcard,
                        accentColor = Gold500,
                        subValue = "12/25 prize pool",
                    )
                }
            }

            // ─── Expected reward card ─────────────────────────────────
            item {
                GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    Row(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(icon = Icons.Filled.Lock, color = Red700, size = 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "EXPECTED REWARD",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = uiState.expectedValue?.let { "${formatSol(it)} per unlock" }
                                    ?: stringResource(R.string.chip_loading),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "R = Σ reward × probability  •  On-chain verifiable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ─── Send Whisper CTA ─────────────────────────────────────
            item {
                GradientButton(
                    text = stringResource(R.string.action_send_encrypted_whisper),
                    icon = Icons.Filled.Lock,
                    onClick = onNavigateSend,
                )
            }

            // ─── Quick action chips ──────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickActionChip(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.nav_inbox),
                        icon = Icons.Filled.Inbox,
                        accent = MaterialTheme.colorScheme.primary,
                        // v1.9.9: real unread count from on-chain inbox PDA.
                        // "Unread" = not yet claimed (not unlocked). Was hardcoded
                        // "3" since the v1.6.0 release.
                        badge = formatUnreadBadge(uiState.inboxMessages.count { !it.claimed }),
                        onClick = onNavigateInbox,
                    )
                    QuickActionChip(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.nav_rules),
                        icon = Icons.AutoMirrored.Filled.Rule,
                        accent = Gold500,
                        onClick = onNavigateRules,
                    )
                    QuickActionChip(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.nav_settings),
                        icon = Icons.Filled.Settings,
                        accent = MaterialTheme.colorScheme.secondary,
                        onClick = onNavigateSettings,
                    )
                }
            }

            // ─── Inbox preview ───────────────────────────────────────
            // v1.9.10: was 3 hardcoded mock items since v1.6.0. Now reads
            // from uiState.inboxMessages (populated by fetchInbox() on
            // connect). Shows the 3 most recent messages.
            //
            // Real data available per InboxMessageRef:
            //   senderBase58, nonce, createdAt, claimed
            // NO plaintext or unlock-cost data on-chain yet (V2 will add
            // decrypted plaintext + reward). For now:
            //   claimed (unlocked)  → "Unlocked message"
            //   !claimed (locked)   → "Encrypted whisper"
            item {
                val recent = uiState.inboxMessages.sortedByDescending { it.createdAt }.take(3)
                GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.inbox_recent_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.action_view_all),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onNavigateInbox() },
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        if (recent.isEmpty()) {
                            // v1.9.10: empty-state instead of 3 fake items
                            Text(
                                text = stringResource(R.string.inbox_recent_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        } else {
                            // v1.9.11: drop 🔓/🔒 emoji prefix — InboxPreviewItem
                            // already renders a leading IconBadge for the
                            // lock/unlock state, so text is just plain label.
                            recent.forEach { msg ->
                                InboxPreviewItem(
                                    sender = formatShortBase58(msg.senderBase58),
                                    preview = if (msg.claimed) stringResource(R.string.inbox_recent_unlocked) else stringResource(R.string.inbox_recent_encrypted),
                                    time = relativeTimeShort(msg.createdAt),
                                    unlocked = msg.claimed,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // v1.9.8: removed the bottom IntegratedWalletCard call — the top
            // IntegratedWalletCard now does everything (balance + address +
            // whisper ID + setup state + disconnect) in one card.
        }
    }
}

@Composable
private fun QuickActionChip(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    accent: Color,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = accent.copy(alpha = 0.25f),
                spotColor   = accent.copy(alpha = 0.25f),
            )
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                IconBadge(icon = icon, color = accent, size = 44.dp)
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(Red500, Red700),
                                ),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = badge,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InboxPreviewItem(
    sender: String,
    preview: String,
    time: String,
    unlocked: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(
            icon = if (unlocked) Icons.Outlined.CardGiftcard else Icons.Filled.Lock,
            color = if (unlocked) Gold500 else MaterialTheme.colorScheme.outline,
            size = 36.dp,
            iconSize = 18.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sender,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun formatSol(lamports: Long): String {
    val sol = lamports / 1_000_000_000.0
    return when {
        sol >= 1000.0 -> String.format("%.1fK SOL", sol / 1000.0)
        sol >= 1.0    -> String.format("%.2f SOL", sol)
        else          -> String.format("%.4f SOL", sol)
    }
}

/**
 * v1.9.9: Format an inbox unread count as a badge string.
 * Returns null when there's nothing to show (caller hides the badge).
 * Caps the visible number at 99+ to keep the 18dp circle readable.
 */
private fun formatUnreadBadge(unreadCount: Int): String? {
    if (unreadCount <= 0) return null
    return if (unreadCount > 99) "99+" else unreadCount.toString()
}

/**
 * v1.9.10: Format a base58 wallet address as "8xDr...f9K2" for previews.
 */
private fun formatShortBase58(addr: String): String {
    if (addr.length <= 10) return addr
    return addr.take(4) + "..." + addr.takeLast(4)
}

/**
 * v1.9.10: Relative time formatter for inbox previews.
 * Same logic as InboxScreen.relativeTime but kept local to avoid cross-file
 * coupling for a 10-line helper.
 */
private fun relativeTimeShort(timestampSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val diff = nowSeconds - timestampSeconds
    return when {
        diff < 60      -> "just now"
        diff < 3600    -> "${diff / 60}m ago"
        diff < 86400   -> "${diff / 3600}h ago"
        diff < 604800  -> "${diff / 86400}d ago"
        else           -> "${diff / 604800}w ago"
    }
}

/**
 * v1.9.7: Integrated YOUR WALLET card.
 *
 * Replaces the v1.9.6 'WALLET' card + WhisperIdCompactCard with a single
 * consolidated section. The big 'YOUR WALLET' balance card at the top
 * of MainScreen already shows the SOL balance prominently; this card
 * complements it with:
 *   • Full base58 wallet address (tappable, opens QR dialog)
 *   • SOL balance (inline, fallback for when top card is collapsed)
 *   • Disconnect button in the header
 *   • Whisper ID display (no copy button, no 'share this' message —
 *     the v1.6.1 dApp does on-chain PDA lookup, no manual sharing needed)
 *   • Setup state handling (NotStarted / InProgress / Failed /
 *     ReadyToLogin / Complete) inherited from WhisperIdCompactCard
 */
@Composable
private fun IntegratedWalletCard(
    publicKeyShort: String?,
    publicKeyFull: String?,
    userBalanceLamports: Long?,
    balanceStatus: com.linmjt.whisperofsol.ui.viewmodel.BalanceStatus,
    setupState: com.linmjt.whisperofsol.ui.viewmodel.SetupState,
    whisperKey: com.linmjt.whisperofsol.crypto.WhisperKey?,
    whisperKeyError: String?,
    isSigning: Boolean,
    isRegisteringOnChain: Boolean,
    signMessageStartMs: Long?,
    onSetup: () -> Unit,
    onCancel: () -> Unit,
    onLoginExisting: () -> Unit,
    onRegisterOnChain: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var showQrDialog by remember { mutableStateOf(false) }
    // v1.6.0: elapsed seconds while signing
    val elapsedSecState = remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(isSigning, signMessageStartMs) {
        if (isSigning && signMessageStartMs != null) {
            while (isSigning) {
                elapsedSecState.value = (System.currentTimeMillis() - signMessageStartMs) / 1000
                kotlinx.coroutines.delay(500)
            }
        } else {
            elapsedSecState.value = 0
        }
    }
    val elapsedSec = elapsedSecState.value

    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {

            // ─── Header: YOUR WALLET label + Disconnect ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Filled.AccountBalanceWallet,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "YOUR WALLET",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.action_disconnect),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onDisconnect() },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ─── Wallet address (tappable → QR dialog) ─────────────────────
            if (publicKeyFull != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showQrDialog = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wallet_address),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = publicKeyShort ?: publicKeyFull.take(8) + "...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = stringResource(R.string.cd_show_qr),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.wallet_not_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ─── Balance (big & prominent, v1.9.8) ──────────────────────
            // v1.9.8: was a small inline display in v1.9.7 (titleLarge), now
            // uses displayMedium like the original UserBalanceCard so SOL
            // balance is the visual anchor of the integrated card.
            when {
                userBalanceLamports != null -> {
                    val sol = userBalanceLamports.toDouble() / 1_000_000_000.0
                    Text(
                        text = String.format("%.4f", sol),
                        style = MaterialTheme.typography.displayMedium,
                        color = Gold500,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "SOL",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                balanceStatus == com.linmjt.whisperofsol.ui.viewmodel.BalanceStatus.Failed -> {
                    Text(
                        text = stringResource(R.string.wallet_balance_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Gold500,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.wallet_balance_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // Divider
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            Spacer(Modifier.height(16.dp))

            // ─── Whisper ID section (no copy button, no share msg) ─────────
            when (setupState) {
                is com.linmjt.whisperofsol.ui.viewmodel.SetupState.Complete -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Gold500,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.whisper_id_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val whisperId = whisperKey?.whisperIdBase64() ?: ""
                    Text(
                        text = whisperId,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!setupState.onChain) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.register_local_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.Button(onClick = onRegisterOnChain) {
                            androidx.compose.material3.Text(stringResource(R.string.register_on_chain))
                        }
                    }
                    // No copy button, no "share this" message (v1.9.7).
                    // v1.6.1+: send flow does auto on-chain PDA lookup.
                }
                is com.linmjt.whisperofsol.ui.viewmodel.SetupState.Failed -> {
                    Text(
                        text = stringResource(R.string.setup_failed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = setupState.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.Button(onClick = onSetup) {
                        androidx.compose.material3.Text(stringResource(R.string.setup_retry))
                    }
                }
                is com.linmjt.whisperofsol.ui.viewmodel.SetupState.InProgress -> {
                    val stage = when {
                        isRegisteringOnChain && elapsedSec < 3  -> stringResource(R.string.register_progress_approve)
                        isRegisteringOnChain && elapsedSec < 15 -> stringResource(R.string.register_progress_confirming)
                        isRegisteringOnChain && elapsedSec < 30 -> stringResource(R.string.register_progress_waiting_land)
                        isRegisteringOnChain && elapsedSec < 60 -> stringResource(R.string.register_progress_almost)
                        isRegisteringOnChain                   -> stringResource(R.string.register_progress_long)
                        elapsedSec < 3  -> stringResource(R.string.register_progress_connecting_wallet)
                        elapsedSec < 15 -> "SMS Wallet is waking up..."
                        elapsedSec < 30 -> "SMS Wallet is processing your request..."
                        elapsedSec < 60 -> stringResource(R.string.register_progress_almost_done)
                        else            -> stringResource(R.string.register_progress_too_long)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Gold500,
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.register_elapsed_format, elapsedSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (elapsedSec >= 60) {
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.OutlinedButton(onClick = onCancel) {
                            androidx.compose.material3.Text("Cancel & retry")
                        }
                    }
                }
                com.linmjt.whisperofsol.ui.viewmodel.SetupState.NotStarted -> {
                    androidx.compose.material3.Button(onClick = onSetup) {
                        androidx.compose.material3.Text(stringResource(R.string.setup_generate_whisper_id))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.setup_one_time_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is com.linmjt.whisperofsol.ui.viewmodel.SetupState.ReadyToLogin -> {
                    androidx.compose.material3.Button(onClick = onLoginExisting) {
                        androidx.compose.material3.Text(stringResource(R.string.setup_login))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.setup_login_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showQrDialog && publicKeyFull != null) {
        WalletQrCodeDialog(
            publicKeyFull = publicKeyFull,
            publicKeyShort = publicKeyShort,
            onDismiss = { showQrDialog = false },
        )
    }
}

/**
 * v1.9.7: QR code dialog showing the user's full wallet address.
 * Generated client-side via ZXing core (no network). Anyone scanning
 * this QR can paste the address into Phantom / SMS Wallet to send a
 * whisper to this user (their dApp does on-chain PDA lookup).
 */
@Composable
private fun WalletQrCodeDialog(
    publicKeyFull: String,
    publicKeyShort: String?,
    onDismiss: () -> Unit,
) {
    val qrBitmap = remember(publicKeyFull) { generateQrCodeBitmap(publicKeyFull, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.wallet_your_address_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qrBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_wallet_qr),
                        modifier = Modifier
                            .size(280.dp)
                            .padding(8.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.wallet_qr_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = publicKeyShort ?: publicKeyFull.take(10) + "...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = publicKeyFull,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                )
            }
        },
    )
}

/**
 * Generate a QR code Bitmap from text using ZXing core.
 * Returns null on failure (extremely rare).
 */
private fun generateQrCodeBitmap(text: String, sizePx: Int): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (e: Exception) {
        WsolLog.e("generateQrCodeBitmap failed: ${e.message}")
        null
    }
}// Snippet — append at end of MainScreen.kt

/**
 * v1.4.0': Compact Whisper ID card for MainScreen.
 */
@Composable
private fun WhisperIdCompactCard(
    setupState: com.linmjt.whisperofsol.ui.viewmodel.SetupState = com.linmjt.whisperofsol.ui.viewmodel.SetupState.NotStarted,
    whisperKey: com.linmjt.whisperofsol.crypto.WhisperKey? = null,
    whisperKeyError: String? = null,
    isSigning: Boolean = false,
    // v1.8.30: register tx stage flag (vs sign message stage)
    isRegisteringOnChain: Boolean = false,
    signMessageStartMs: Long? = null,
    onSetup: () -> Unit,
    onCancel: () -> Unit = {},
    // v1.8.27: explicit register-on-chain trigger (not auto-fire)
    onRegisterOnChain: () -> Unit = {},
    // v1.9.2: Login button for ReadyToLogin state (no GAS path)
    onLoginExisting: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val (copied, setCopied) = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // v1.6.0: tick elapsed seconds while signing
    val elapsedSecState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(isSigning, signMessageStartMs) {
        if (isSigning && signMessageStartMs != null) {
            while (isSigning) {
                elapsedSecState.value = (System.currentTimeMillis() - signMessageStartMs) / 1000
                kotlinx.coroutines.delay(500)
            }
        } else {
            elapsedSecState.value = 0
        }
    }
    val elapsedSec = elapsedSecState.value

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.whisper_id_share_title),
            style = MaterialTheme.typography.labelMedium,
            color = Gold500,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.whisper_id_share_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        // v1.8.4: drive UI off setupState (state machine) instead of inferring
        // from whisperKey + whisperKeyError. Old logic showed "Your Whisper ID"
        // even on SecureRandom fallback — that's how users ended up sharing a
        // fake ID. Now: failed setup = explicit error UI, never "Your Whisper ID".
        when (setupState) {
            is com.linmjt.whisperofsol.ui.viewmodel.SetupState.Complete -> {
                val whisperId = whisperKey?.whisperIdBase64() ?: ""
                Text(
                    text = whisperId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (!setupState.onChain) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.register_local_only_long),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    // v1.8.27: explicit register-on-chain trigger.
                    // Sends the register_whisper_id tx -- 1 approve + 1 PIN.
                    androidx.compose.material3.Button(onClick = onRegisterOnChain) {
                        androidx.compose.material3.Text(stringResource(R.string.register_whisper_id_on_chain))
                    }
                }
                Spacer(Modifier.height(8.dp))
                val clipLabel = stringResource(R.string.whisper_id_clip_label)
                val copiedLabel = stringResource(R.string.action_copied)
                val copyLabel = stringResource(R.string.whisper_id_copy_label)
                androidx.compose.material3.Button(
                    onClick = {
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText(clipLabel, whisperId),
                        )
                        setCopied(true)
                    },
                ) {
                    androidx.compose.material3.Text(if (copied) copiedLabel else copyLabel)
                }
            }
            is com.linmjt.whisperofsol.ui.viewmodel.SetupState.Failed -> {
                Text(
                    text = stringResource(R.string.setup_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = setupState.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Button(onClick = onSetup) {
                    androidx.compose.material3.Text(stringResource(R.string.setup_retry))
                }
            }
            is com.linmjt.whisperofsol.ui.viewmodel.SetupState.InProgress -> {
                val stage = when {
                    // v1.8.30: distinct messages for register tx stage vs sign message stage
                    isRegisteringOnChain && elapsedSec < 3  -> stringResource(R.string.register_progress_approve)
                    isRegisteringOnChain && elapsedSec < 15 -> stringResource(R.string.register_progress_confirming)
                    isRegisteringOnChain && elapsedSec < 30 -> stringResource(R.string.register_progress_waiting_land)
                    isRegisteringOnChain && elapsedSec < 60 -> stringResource(R.string.register_progress_almost)
                    isRegisteringOnChain                   -> stringResource(R.string.register_progress_long)
                    elapsedSec < 3  -> stringResource(R.string.register_progress_connecting_wallet)
                    elapsedSec < 15 -> "SMS Wallet is waking up..."
                    elapsedSec < 30 -> "SMS Wallet is processing your request..."
                    elapsedSec < 60 -> stringResource(R.string.register_progress_almost_done)
                    else            -> stringResource(R.string.register_progress_too_long)
                }
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = Gold500,
                        strokeWidth = 4.dp,
                    )
                    Spacer(Modifier.height(14.dp))
                    androidx.compose.material3.Text(
                        text = stage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.register_elapsed_format, elapsedSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.Text(
                        text = if (isRegisteringOnChain)
                            stringResource(R.string.register_progress_pin_prompt)
                        else
                            stringResource(R.string.register_progress_dialog_prompt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (elapsedSec >= 60) {
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedButton(onClick = onCancel) {
                            androidx.compose.material3.Text("Cancel & retry")
                        }
                    }
                }
            }
            com.linmjt.whisperofsol.ui.viewmodel.SetupState.NotStarted -> {
                androidx.compose.material3.Button(onClick = onSetup) {
                    androidx.compose.material3.Text("Generate Whisper ID")
                }
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Text(
                    text = "One-time setup (~45s) - derives a deterministic X25519 keypair for cross-device recovery. App is blocked until setup succeeds.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            is com.linmjt.whisperofsol.ui.viewmodel.SetupState.ReadyToLogin -> {
                // v1.9.2: already registered on-chain but no local privkey.
                // Show "Login" to enter without re-registering.
                // v1.9.2 fix: use onLoginExisting (NOT onSetup), so we don't
                // accidentally fire signSetupAndRegisterOnChainInline (which
                // would force a register tx + GAS). User feedback 12:33:
                // tapping this button with onSetup was charging GAS even
                // though the on-chain PDA already existed.
                // v2.0.2: removed "(no GAS)" suffix — user feedback: button
                // text shouldn't expose gas mechanics. The subtitle below
                // ("without re-registering") already conveys the no-tx intent.
                androidx.compose.material3.Button(onClick = onLoginExisting) {
                    androidx.compose.material3.Text("Login")
                }
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Text(
                    text = "On-chain Whisper ID found. Tap to log in without re-registering.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}