package com.linmjt.whisperofsol.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi

import com.linmjt.whisperofsol.ui.viewmodel.RecipientLookupStatus
import androidx.compose.ui.res.stringResource
import com.linmjt.whisperofsol.R

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.GradientButton
import com.linmjt.whisperofsol.ui.components.GlowCard
import com.linmjt.whisperofsol.ui.components.IconBadge
import com.linmjt.whisperofsol.ui.components.SecondaryButton
import com.linmjt.whisperofsol.ui.AddressBookPrefs
import androidx.compose.runtime.LaunchedEffect
import com.linmjt.whisperofsol.ui.theme.GlowGold
import com.linmjt.whisperofsol.ui.theme.Gold100
import com.linmjt.whisperofsol.ui.theme.Gold300
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.Gold700
import com.linmjt.whisperofsol.ui.theme.Green500
import com.linmjt.whisperofsol.ui.theme.Green700
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700
import com.linmjt.whisperofsol.ui.viewmodel.NonceSetupStatus
import com.linmjt.whisperofsol.ui.viewmodel.TreasuryStatus
import com.linmjt.whisperofsol.ui.uiErrorMessage

/**
 * SendWhisperScreen — compose & encrypt a new whisper.
 *
 * Sections:
 *   1. Top bar with back arrow + title
 *   2. Recipient address field (with QR button as trailingIcon — Stage 1.5 fix)
 *   3. Message body field (max 280 chars, char counter)
 *   4. Fee preview card (message fee + gas estimate)
 *   5. Encryption note
 *   6. Treasury status chip (Stage 1.5: replaces raw "Failed to fetch treasury: null")
 *   7. Send / Cancel buttons (Stage 1.5: disabled unless treasury is Loaded)
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SendWhisperScreen(
    uiState: com.linmjt.whisperofsol.ui.viewmodel.HomeUiState,
    onBack: () -> Unit,
    onSent: () -> Unit,
    onSendAnother: () -> Unit = {},
    // v1.9.6: dropped ByteArray? and onRecipientAddressChange.
    // We do NOT pre-lookup the recipient — every keystroke would trigger
    // an on-chain RPC call (13 RPCs per typed address). Instead, lookup
    // happens at send-time inside HomeViewModel.sendWhisper, so each
    // send = exactly 1 RPC call (no spam, no stale enc_pub leakage).
    onSend: (String, String) -> Unit = { _, _ -> },
    onRetryTreasury: () -> Unit = {},
) {
    var recipient by remember { mutableStateOf("") }
    // v1.4.0': recipient's Whisper ID (base64 X25519 pubkey) so we encrypt
    // to a key the recipient's dApp can derive. Optional — if blank we
    // fall back to wallet-derived X25519 pubkey (recipient can't decrypt).
    // v1.6.1: removed recipientWhisperId var — auto-looked up via PDA
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Show share-code panel right after a successful send_message. The
    // recipient needs (sender address + nonce) to call unlock_message
    // since V1 has no on-chain inbox index yet (per STATUS-V1.md).
    val showShareCode = uiState.lastTxSignature != null &&
        uiState.lastSentNonce != null &&
        uiState.lastSentSender != null &&
        !uiState.isSending

    if (showShareCode) {
        SendCompletePanel(
            senderAddress = uiState.lastSentSender!!,
            nonce = uiState.lastSentNonce!!,
            txSignature = uiState.lastTxSignature!!,
            onContinue = onSent,
            onSendAnother = {
                onSendAnother()
            },
        )
        return
    }

    // QR scanner launcher (Bug 2026-10-02 10:55 fix — real QR scanner,
    // not a disabled placeholder). Launches QrScannerActivity which uses
    // CameraX + ML Kit BarcodeScanning to decode a Solana wallet QR.
    // Result fills the recipient field automatically.
    val qrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(
                com.linmjt.whisperofsol.ui.screens.QrScannerActivity.EXTRA_SCANNED_ADDRESS
            )
            if (scanned != null) {
                // v1.9.6: no longer trigger a lookup here — lookup
                // happens at send-time inside HomeViewModel.sendWhisper.
                recipient = scanned
            }
        }
    }

    // Stage 1.5: only allow sending when fees are confirmed loaded.
    // The retry button is the only escape hatch when TreasuryStatus.Retryable.
    val treasuryReady = uiState.treasuryStatus == TreasuryStatus.Loaded
    val isSettingUpNonce = uiState.nonceSetupStatus == NonceSetupStatus.Checking ||
        uiState.nonceSetupStatus == NonceSetupStatus.Creating
    val canSend = recipient.isNotBlank() &&
                  message.isNotBlank() &&
                  !uiState.isSending &&
                  treasuryReady &&
                  !isSettingUpNonce
    // v1.9.6: removed recipientLookupStatus/encPub guards. We don't do
    // pre-send lookup anymore — HomeViewModel.sendWhisper does a fresh
    // on-chain check at send time. If recipient isn't on chain, the
    // send is REFUSED with UiError.RecipientNotSetup.

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ─── Top bar ─────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Send Whisper",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Encrypted end-to-end",  // v1.9.11: drop 🎄 emoji
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ─── Hero illustration ───────────────────────────────────
            // v3.0.16: cyberpunk glass — translucent surface + gold glow border + Shield vector icon (no emoji).
            // Text 不變: "Your message is sealed" / "Only the recipient can read it."
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = MaterialTheme.colorScheme.tertiary,
                        spotColor = MaterialTheme.colorScheme.tertiary,
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .border(
                        width = 1.2.dp,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(
                        icon = Icons.Outlined.Shield,
                        color = MaterialTheme.colorScheme.tertiary,
                        size = 40.dp,
                        iconSize = 22.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Your message is sealed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Only the recipient can read it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ─── Recipient ───────────────────────────────────────────
            // Stage 1.5 fix: QR button is now OutlinedTextField's trailingIcon
            // so it auto-aligns to the field's top/bottom edges instead of
            // floating beside it.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Recipient Wallet Address")
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },  // v1.9.6: no per-keystroke lookup
                    placeholder = {
                        Text(
                            text = "Solana wallet address (base58)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    trailingIcon = {
                        // Real QR scanner (Bug 2026-10-02 10:55 fix).
                        // CameraX + ML Kit BarcodeScanning in QrScannerActivity.
                        // On success, scanned address auto-fills the recipient field.
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    qrLauncher.launch(
                                        Intent(
                                            context,
                                            com.linmjt.whisperofsol.ui.screens.QrScannerActivity::class.java
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QrCode,
                                contentDescription = "Scan QR",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                )
            }

            // v1.9.6: REMOVED RecipientLookupStatusChip — no pre-send
            // lookup happens anymore. Lookup is at send-time inside
            // HomeViewModel.sendWhisper. If recipient isn't on chain,
            // send is REFUSED with UiError.RecipientNotSetup (shown as
            // error banner on the screen).
            //
            // v1.6.1: REMOVED manual Whisper ID input — we now auto-lookup
            // the recipient's enc_pub via the on-chain WhisperId PDA keyed
            // by their wallet address. Manual input was user-hostile
            // (forced Telegram / SMS Whisper ID share).

            // v3.0.8: Address book quick-pick + Manage chip.
            // - Quick-pick chips show up to 3 most recent entries (Recent sort).
            // - "Manage" chip opens the full [AddressBookSheet] (search + sort + add).
            // - Hidden entirely when the book is empty — the empty AddressBook
            //   sheet will surface the empty state with a CTA to add.
            var bookEntries by remember { mutableStateOf(AddressBookPrefs.getAll(context)) }
            var showAddressBook by remember { mutableStateOf(false) }
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            LaunchedEffect(Unit) { bookEntries = AddressBookPrefs.getAll(context) }
            // Refresh the quick-pick when the sheet closes (user may have added/removed entries).
            LaunchedEffect(showAddressBook) {
                if (!showAddressBook) bookEntries = AddressBookPrefs.getAll(context)
            }
            if (bookEntries.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.addressbook_quick_pick_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { showAddressBook = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.addressbook_manage_chip),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    // v3.0.8: horizontal scrolling row of quick-pick chips
                    // (3 max — FlowRow's API signature is unstable across
                    // Compose versions, so use plain Row + horizontalScroll).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        bookEntries.take(3).forEach { entry ->
                            FilterChip(
                                selected = recipient == entry.address,
                                onClick = { recipient = entry.address },
                                label = { Text(entry.displayLabel()) },
                                colors = FilterChipDefaults.filterChipColors(),
                            )
                        }
                    }
                }
            } else {
                // Empty book — surface a single "Add address" link instead of nothing.
                TextButton(
                    onClick = { showAddressBook = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Book,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.addressbook_add_first_cta))
                }
            }

            if (showAddressBook) {
                com.linmjt.whisperofsol.ui.components.AddressBookSheet(
                    sheetState = sheetState,
                    onDismiss = { showAddressBook = false },
                    onSelect = { entry ->
                        recipient = entry.address
                        showAddressBook = false
                    },
                )
            }

            // v3.0.8: Post-send prompt has been moved to the app-level
            // (WhisperOfSolApp in MainActivity.kt) because this screen
            // returns early when SendCompletePanel is shown (the success
            // view that pops up after a send) — see the `if (showShareCode)
            // { ...; return }` block above. With the dialog logic here, the
            // state would be set but the dialog would never render.
            // App-level dialog renders on top of any screen and survives
            // navigation away from SendWhisper.
            //
            // Debug-only force trigger (v3.0.8): still wired here so a test
            // can exercise the dialog without going through the full send flow.
            //   adb shell am start ... --es debug.app.force_postsend <address>

            // ─── Message ─────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Label("Secret Message")
                    Text(
                        text = "${message.length} / 280",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.length > 280) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= 280) message = it },
                    placeholder = {
                        Text(
                            text = "Write your encrypted whisper here…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }

            // ─── Fee preview ─────────────────────────────────────────
            GlowCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "FEE PREVIEW",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    FeeLine(label = "Message fee",   value = "0.01 SOL")
                    FeeLine(label = "Gas estimate",  value = "~0.0005 SOL")
                    FeeLine(label = "Storage rent",  value = "0.00089 SOL")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.PrivacyTip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Encrypted with X25519 + AES-GCM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // ─── Treasury status (Stage 1.5 — replaces raw exception leak) ─────
            TreasuryStatusChip(
                status = uiState.treasuryStatus,
                onRetry = onRetryTreasury,
            )

            // ─── Durable nonce status (v1.1.7) ────────────────────────
            NonceSetupStatusChip(status = uiState.nonceSetupStatus)

            // ─── Generic typed error banner (Stage 1.5) ─────────────
            if (uiState.error != null) {
                Text(
                    text = uiErrorMessage(uiState.error!!),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ─── Buttons ─────────────────────────────────────────────
            GradientButton(
                text = when {
                    uiState.nonceSetupStatus == NonceSetupStatus.Creating ->
                        "Setting up durable nonce…"
                    uiState.nonceSetupStatus == NonceSetupStatus.Checking ->
                        "Checking nonce account…"
                    uiState.isSending -> "Sending..."
                    !treasuryReady -> "Send Whisper (loading fees…)"
                    else -> "Send Whisper (0.01 SOL)"
                },
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = { onSend(recipient, message) },  // v1.9.6: no encPub — sendWhisper does fresh lookup
                enabled = canSend,
            )
            SecondaryButton(
                text = "Cancel",
                onClick = onBack,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * SendCompletePanel — shown right after a successful send_message.
 *
 * Tells the sender the two pieces of info the recipient needs to call
 * unlock_message V1-style: (1) the sender's base58 wallet address, and
 * (2) the nonce used to derive the Message PDA. Copy-to-clipboard button
 * makes sharing over Telegram / SMS a one-tap affair.
 */
@Composable
private fun SendCompletePanel(
    senderAddress: String,
    nonce: Long,
    txSignature: String,
    onContinue: () -> Unit,
    onSendAnother: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var copied by remember { mutableStateOf(false) }

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ─── Top bar ────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onContinue) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Whisper Sent",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            // ─── Hero ────────────────────────────
            GlowCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp,
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(
                        icon = Icons.Filled.CheckCircle,
                        color = MaterialTheme.colorScheme.primary,
                        size = 56.dp,
                        iconSize = 28.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Your whisper is sealed on-chain",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Share the code below with the recipient.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            // ─── Share code card ────────────────────────────
            GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "SHARE CODE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Recipient needs these two values to unlock:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))

                    ShareRow(label = stringResource(R.string.send_share_sender_label), value = senderAddress)
                    Spacer(Modifier.height(10.dp))
                    ShareRow(label = stringResource(R.string.send_share_nonce_label), value = nonce.toString())

                    Spacer(Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (copied) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                val shareText = "Whisper unlock code\n\n" +
                                    "Sender: $senderAddress\n" +
                                    "Nonce: $nonce\n\n" +
                                    "Open Whisper of Sol → Inbox → paste above into " +
                                    "'Unlock by Nonce' panel."
                                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper unlock code", shareText))
                                copied = true
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (copied) "Copied! Send to recipient via Telegram / SMS"
                                            else "Copy share code",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                }
            }

            // ─── Tx hash ────────────────────────────
            Text(
                text = "Tx: ${txSignature.take(12)}...${txSignature.takeLast(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            GradientButton(
                text = "Send Another",
                icon = Icons.Filled.Add,
                onClick = onSendAnother,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue to Inbox")
            }
        }
    }
}

@Composable
private fun ShareRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Treasury status chip — Stage 1.5 fix.
 *
 * Replaces the original `"Failed to fetch treasury: null"` red text with
 * a friendly, action-oriented chip:
 *   • Loaded     → hidden (silent success)
 *   • Loading    → spinner + "Fetching fees…"
 *   • Retryable  → warning icon + "Unable to load fees. Tap to retry."
 *   • Failed     → warning icon + "Service temporarily unavailable."
 */
@Composable
private fun TreasuryStatusChip(
    status: TreasuryStatus,
    onRetry: () -> Unit,
) {
    when (status) {
        TreasuryStatus.Loaded -> Unit  // no chip — silent success

        TreasuryStatus.Loading -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Fetching fees…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TreasuryStatus.Retryable -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Gold500.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable { onRetry() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = Gold500,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Unable to load fees. Tap to retry.",
                style = MaterialTheme.typography.bodySmall,
                color = Gold500,
                fontWeight = FontWeight.SemiBold,
            )
        }

        TreasuryStatus.Failed -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Red700.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = Red700,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Service temporarily unavailable. Please try again later.",
                style = MaterialTheme.typography.bodySmall,
                color = Red700,
                fontWeight = FontWeight.SemiBold,
            )
        }

        TreasuryStatus.Idle -> Unit  // first render — no chip yet
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun FeeLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * NonceSetupStatusChip — visual indicator for the durable nonce account
 * setup lifecycle (v1.1.7).
 *
 *   Idle      → hidden
 *   Checking  → subtle "Checking nonce…" with spinner
 *   Creating  → prominent "Setting up durable nonce… sign on your wallet"
 *   Ready     → hidden (silent success — tx build will use the stored nonce)
 *   Failed    → prominent red "Couldn’t set up durable nonce"
 *
 * Shown above the Send button so the user understands why the button
 * label changes to "Setting up durable nonce…" while they're waiting
 * on the Seed Vault prompt.
 */
@Composable
private fun NonceSetupStatusChip(status: NonceSetupStatus) {
    when (status) {
        NonceSetupStatus.Idle -> Unit
        NonceSetupStatus.Ready -> Unit

        NonceSetupStatus.Checking -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Checking nonce account…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        NonceSetupStatus.Creating -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Gold500.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Gold700,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "Setting up durable nonce…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Gold700,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Sign the prompt on your wallet — takes a moment.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NonceSetupStatus.Failed -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Red500.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = Red700,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Couldn’t set up durable nonce. Try again.",
                style = MaterialTheme.typography.bodySmall,
                color = Red700,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * v1.6.1: Show the current state of the on-chain WhisperId PDA lookup.
 * Renders a compact chip below the recipient address field that tells the
 * user whether their recipient has set up WhisperOfSol and the dApp can
 * auto-fill the encryption key.
 */
@Composable
private fun RecipientLookupStatusChip(
    status: RecipientLookupStatus,
    lookedUpFor: String?,
) {
    val (text, color, showSpinner) = when (status) {
        RecipientLookupStatus.Idle -> Triple(
            "Enter recipient wallet address",
            MaterialTheme.colorScheme.onSurfaceVariant,
            false,
        )
        RecipientLookupStatus.Looking -> Triple(
            "Looking up recipient's WhisperOfSol ID on chain…",
            MaterialTheme.colorScheme.onSurfaceVariant,
            true,
        )
        RecipientLookupStatus.Found -> Triple(
            "Recipient has set up WhisperOfSol — will encrypt with their on-chain key",  // v1.9.11: drop ✓
            Gold700,
            false,
        )
        RecipientLookupStatus.NotFound -> Triple(
            "Recipient hasn't set up WhisperOfSol yet — send will use wallet-derived fallback (recipient may not be able to decrypt)",  // v1.9.11: drop ✗
            MaterialTheme.colorScheme.error,
            false,
        )
        RecipientLookupStatus.Error -> Triple(
            "Lookup failed (RPC error)",  // v1.9.11: drop ✗
            MaterialTheme.colorScheme.error,
            false,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = color,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
