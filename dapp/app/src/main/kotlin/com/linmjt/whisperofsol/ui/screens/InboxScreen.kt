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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.GradientButton
import com.linmjt.whisperofsol.ui.components.GoldGradientButton
import com.linmjt.whisperofsol.ui.components.GlowCard
import com.linmjt.whisperofsol.ui.components.IconBadge
import com.linmjt.whisperofsol.ui.theme.Gold300
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.Green500
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700

/**
 * InboxScreen — encrypted message inbox.
 *
 * Visual:
 *   • Top bar with title + back
 *   • "Unlock by Nonce" panel (V1 workaround — see below)
 *   • Search bar (BasicTextField with custom styling)
 *   • Lazy list of inbox items — gift-box icon for unlocked, lock icon for
 *     encrypted. Each row shows sender, preview, timestamp, status badge.
 *
 * V1 limitation (user-reported 2026-10-02 11:15):
 *   No on-chain inbox index yet (Stage 2 V2 feature). Recipients can't
 *   auto-see incoming whispers in this list. The "Unlock by Nonce"
 *   panel lets the recipient manually enter the sender's address +
 *   nonce (which the sender shares out-of-band) to call unlock_message.
 */
@Composable
fun InboxScreen(
    uiState: com.linmjt.whisperofsol.ui.viewmodel.HomeUiState,
    onBack: () -> Unit,
    onUnlockByRef: (senderBase58: String, nonce: Long) -> Unit,
    onRefresh: () -> Unit = {},
) {
    val inboxMessages = uiState.inboxMessages
    val inboxStatus = uiState.inboxStatus
    val lastUnlockedMessage = uiState.lastUnlockedMessage
    val lastUnlockedRef = uiState.lastUnlockedRef
    val lastUnlockedPlaintext = uiState.lastUnlockedPlaintext
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<InboxItem?>(null) }

    // v1.1.11: 進入 InboxScreen 時自動從鏈上 fetch。
    // 舊 code 只有在 connect / send / unlock 三個事件才會 fetch，
    // 所以用戶從 MainScreen 直接點 Inbox chip 會看到空的初始狀態，
    // 即使鏈上有收到新訊息。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        onRefresh()
    }

    // Convert on-chain InboxMessageRef list to InboxItem for display
    val items = remember(inboxMessages) {
        if (inboxMessages.isEmpty()) {
            // No messages yet — show empty state (handled by InboxList)
            emptyList()
        } else {
            inboxMessages.map { ref ->
                val shortSender = shortAddress(ref.senderBase58)
                InboxItem(
                    sender = shortSender,
                    preview = if (ref.claimed) "Already unlocked" else "Tap to unlock · free (gas only)",  // v1.9.11: drop ✓/🔒
                    fullMessage = if (ref.claimed) "Decrypted (V2: recipient secret required)" else "Tap Unlock to decrypt + claim reward on-chain.",  // v1.9.11: drop ✓
                    time = relativeTime(ref.createdAt),
                    date = formatDate(ref.createdAt),
                    unlocked = ref.claimed,
                    reward = if (ref.claimed) "Claimed" else "Free (gas only)",  // v1.9.11: drop ✓
                    nonce = ref.nonce,
                )
            }
        }
    }

    if (selected == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            InboxList(
                items = items,
                query = query,
                onQueryChange = { query = it },
                onBack = onBack,
                onSelect = { selected = it },
                onUnlockItem = { item ->
                    // Look up the original InboxMessageRef by nonce to get senderBase58
                    val ref = inboxMessages.find { it.nonce == item.nonce }
                    if (ref != null && !ref.claimed) {
                        onUnlockByRef(ref.senderBase58, item.nonce)
                    }
                },
                isLoading = inboxStatus == com.linmjt.whisperofsol.ui.viewmodel.InboxStatus.Loading,
            )
        }
    } else {
        val sel = selected!!
        // Look up the original InboxMessageRef so we can pass full senderBase58
        val ref = inboxMessages.find { it.nonce == sel.nonce }
        val justUnlocked = lastUnlockedMessage != null &&
            ref != null &&
            ref.senderBase58 == lastUnlockedMessage.senderBase58 &&
            ref.nonce == lastUnlockedMessage.nonce
        if (justUnlocked && lastUnlockedMessage != null) {
            // v1.3.3: dedicated unlocked screen so the user clearly sees
            // what was paid + the on-chain proof (SHA-256 hash + ciphertext
            // blob + encrypted key). Scrollable so the long hex doesn't
            // get cut off on smaller devices.
            val rewardSol = lastUnlockedMessage.rewardPaidLamports / 1_000_000_000.0
            val rewardStr = if (rewardSol > 0) "%.6f SOL".format(rewardSol) else "0 SOL"
            WhisperUnlockedScreen(
                senderShort = shortAddress(ref.senderBase58),
                date = formatDate(lastUnlockedMessage.createdAt),
                rewardStr = rewardStr,
                rewardTier = when {
                    // v1.9.11: drop emoji prefix — keep plain tier labels.
                    lastUnlockedMessage.rewardPaidLamports >= 50_000_000 -> "CHRISTMAS BONUS"
                    lastUnlockedMessage.rewardPaidLamports >= 5_000_000 -> "LARGE"
                    lastUnlockedMessage.rewardPaidLamports >= 1_000_000 -> "MEDIUM"
                    lastUnlockedMessage.rewardPaidLamports >= 300_000 -> "SMALL"
                    lastUnlockedMessage.rewardPaidLamports > 0 -> "COMFORT"
                    else -> "EARNING MODE"
                },
                contentHashHex = lastUnlockedMessage.contentHashHex,
                encryptedContentHex = lastUnlockedMessage.encryptedContentHex,
                encryptedKeyHex = lastUnlockedMessage.encryptedKeyHex,
                plaintext = lastUnlockedPlaintext,
                onBack = { selected = null },
            )
        } else {
            WhisperDetailScreen(
                item = sel,
                onBack = { selected = null },
                onUnlock = {
                    if (ref != null && !ref.claimed) {
                        onUnlockByRef(ref.senderBase58, sel.nonce)
                    }
                },
            )
        }
    }
}

@Composable
private fun InboxList(
    items: List<InboxItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelect: (InboxItem) -> Unit,
    onUnlockItem: (InboxItem) -> Unit = {},
    isLoading: Boolean = false,
) {
    val filtered = items.filter {
        query.isBlank() ||
        it.sender.contains(query, ignoreCase = true) ||
        it.preview.contains(query, ignoreCase = true)
    }

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inbox",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "${items.size} whispers • ${items.count { it.unlocked }} unlocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Search
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = @Composable { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Search senders or previews…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (filtered.isEmpty()) {
                EmptyInboxState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    items(filtered) { item ->
                        InboxRow(item = item, onClick = { onSelect(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(item: InboxItem, onClick: () -> Unit) {
    GlowCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        cornerRadius = 20.dp,
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = if (item.unlocked) Icons.Filled.CardGiftcard else Icons.Filled.Lock,
                color = if (item.unlocked) Gold500 else MaterialTheme.colorScheme.outline,
                size = 44.dp,
                iconSize = 22.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sender takes the remaining width but won't push the
                    // chip out — fill = false lets the Row first give the
                    // chip its intrinsic width, then the sender absorbs
                    // what's left.
                    Text(
                        text = item.sender,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusChip(
                        text = if (item.unlocked) "UNLOCKED" else "LOCKED",
                        color = if (item.unlocked) Gold500 else MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    text = item.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (item.unlocked) "REWARD" else "COST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.reward,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (item.unlocked) Gold500 else Red700,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    // Stage 1.5 fix: pill width was inheriting from its column and the
    // text "UNLOCKED" was wrapping to "UNLOCKE / D". Fix it on three fronts:
    //   1. softWrap = false — never break mid-word
    //   2. maxLines = 1     — never wrap to a second line
    //   3. widthIn(min=…)  — give the chip its own min width so the
    //      surrounding Row doesn't squash it.
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp))
            .widthIn(min = 84.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            softWrap = false,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyInboxState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // v1.9.11: was Text("🎁", fontSize=56.sp). Use Icon instead.
            Icon(
                imageVector = Icons.Filled.CardGiftcard,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = Gold500,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No whispers yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Your inbox is empty. Send one to get started!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WhisperDetailScreen(item: InboxItem, onBack: () -> Unit, onUnlock: () -> Unit = {}) {
    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "From ${item.sender}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = item.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    text = if (item.unlocked) "UNLOCKED" else "LOCKED",
                    color = if (item.unlocked) Gold500 else Red700,
                )
            }

            GlowCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 28.dp,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    IconBadge(
                        icon = if (item.unlocked) Icons.Filled.CardGiftcard else Icons.Filled.Lock,
                        color = if (item.unlocked) Gold500 else Red700,
                        size = 56.dp,
                        iconSize = 28.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (item.unlocked) "Whisper Content" else "Encrypted Whisper",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (item.unlocked) item.fullMessage
                              else "This message is sealed with X25519 + AES-GCM.\n\nUnlocking is FREE — you only pay ~0.000005 SOL gas. If treasury ≥ 0.1 SOL, a random reward is also paid out. The content hash + ciphertext are stored in the Message PDA on-chain — your wallet's X25519 secret reveals it.",  // v1.9.11: drop 🔒 prefix
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!item.unlocked) {
                        Spacer(Modifier.height(20.dp))
                        GoldGradientButton(
                            text = "Unlock (free, gas only)",
                            onClick = onUnlock,
                        )
                    } else {
                        Spacer(Modifier.height(20.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Reward won:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = item.reward,
                                style = MaterialTheme.typography.titleMedium,
                                color = Green500,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            GlowCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "TRANSACTION DETAILS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    DetailRow("Network", "Solana Devnet")
                    DetailRow("Signature", item.date.takeLast(8) + "...")
                    DetailRow("Slot", "${(item.date.hashCode() % 1_000_000L).coerceAtLeast(100_000L)}")
                    DetailRow("Encrypted key", item.sender.take(4) + "..." + item.sender.takeLast(4))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class InboxItem(
    val sender: String,
    val preview: String,
    val fullMessage: String,
    val time: String,
    val date: String,
    val unlocked: Boolean,
    val reward: String,
    /** Nonce used to derive the Message PDA: [b"msg", sender_pubkey, recipient_pubkey, nonce]. */
    val nonce: Long = 0L,
)

/**
 * v1.3.3: dedicated screen shown right after a successful unlock_message
 * confirmation. Surfaces:
 *  - Reward paid (in SOL) + tier
 *  - SHA-256 content hash (full hex)
 *  - Ciphertext hex (full)
 *  - Encrypted key hex (full)
 *  - Plaintext recovery hint (V2 will add on-device decryption)
 *
 * All hex blobs are copyable via the per-row copy button so the user can
 * verify the SHA-256 against the sender's hash out-of-band (Twitter DM,
 * in-person) until V2 lands a recipient device X25519 keypair.
 */
@Composable
private fun WhisperUnlockedScreen(
    senderShort: String,
    date: String,
    rewardStr: String,
    rewardTier: String,
    contentHashHex: String,
    encryptedContentHex: String,
    encryptedKeyHex: String,
    plaintext: String?,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "From $senderShort",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(text = "UNLOCKED", color = Green500)
            }

            // Reward banner
            GlowCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 28.dp,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    IconBadge(
                        icon = Icons.Filled.CardGiftcard,
                        color = Gold500,
                        size = 64.dp,
                        iconSize = 32.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = rewardTier,
                        style = MaterialTheme.typography.titleSmall,
                        color = Gold500,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Reward: $rewardStr",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Plaintext card — v1.4.0': shown if we successfully decrypted locally
            if (plaintext != null) {
                GlowCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "PLAINTEXT REVEALED",  // v1.9.11: drop 🎉 prefix
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Green500,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = plaintext,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                // Plaintext status banner — honest about why we can't show it
                GlowCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "PLAINTEXT NOT REVEALED",  // v1.9.11: drop 🔐 prefix
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Red700,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "The sender did not encrypt to your Whisper ID — either the sender skipped the optional 'Recipient Whisper ID' field, or used a different wallet. The encrypted content is on-chain; you can verify the SHA-256 below against the sender's hash out-of-band.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // On-chain proof section
            HexBlobRow(
                label = "SHA-256 of plaintext",
                hex = contentHashHex,
                clipboard = clipboard,
            )
            HexBlobRow(
                label = "Ciphertext (${encryptedContentHex.length / 2} bytes)",
                hex = encryptedContentHex,
                clipboard = clipboard,
            )
            HexBlobRow(
                label = "Encrypted key (X25519 + AES-GCM, ${encryptedKeyHex.length / 2} bytes)",
                hex = encryptedKeyHex,
                clipboard = clipboard,
            )
        }
    }
}

@Composable
private fun HexBlobRow(
    label: String,
    hex: String,
    clipboard: android.content.ClipboardManager,
) {
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    GlowCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        val clip = android.content.ClipData.newPlainText(label, hex)
                        clipboard.setPrimaryClip(clip)
                        copied = true
                    },
                ) {
                    Text(if (copied) "Copied" else "Copy")  // v1.9.11: drop ✓ prefix
                }
            }
            Spacer(Modifier.height(8.dp))
            // Show full hex in monospace, wrapping naturally
            Text(
                text = hex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * UnlockByNoncePanel — V1 workaround for missing on-chain inbox index.
 *
 * Recipients paste the sender's base58 wallet address + the nonce (shared
 * out-of-band by the sender from their send-message success screen) and
 * tap Unlock. This calls unlock_message ix on-chain so the recipient can
 * decrypt + claim their reward. V2 will replace this with an auto-fetched
 * inbox once an on-chain indexer ships.
 */
@Composable
private fun UnlockByNoncePanel(
    onUnlock: (senderAddress: String, nonceText: String) -> Unit,
) {
    var senderAddress by remember { mutableStateOf("") }
    var nonceText by remember { mutableStateOf("") }

    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = Icons.Filled.Key,
                    color = Gold500,
                    size = 44.dp,
                    iconSize = 22.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "UNLOCK BY NONCE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "V1 workaround — paste the sender's address and nonce.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = senderAddress,
                onValueChange = { senderAddress = it },
                placeholder = {
                    Text(
                        "Sender's wallet address (base58)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold500,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = nonceText,
                onValueChange = { nonceText = it.filter { c -> c.isDigit() } },
                placeholder = {
                    Text(
                        "Nonce (number from sender's success screen)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Gold500,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )

            Spacer(Modifier.height(14.dp))

            GradientButton(
                text = "Unlock Whisper",
                icon = Icons.Filled.Lock,
                onClick = {
                    if (senderAddress.isNotBlank() && nonceText.isNotBlank()) {
                        onUnlock(senderAddress.trim(), nonceText.trim())
                    }
                },
                enabled = senderAddress.isNotBlank() && nonceText.isNotBlank(),
            )
        }
    }
}

/**
 * Shorten a base58 address to "4chars...4chars" for display.
 */
private fun shortAddress(base58: String): String {
    return if (base58.length > 8) {
        "${base58.take(4)}...${base58.takeLast(4)}"
    } else {
        base58
    }
}

/**
 * Convert a Unix timestamp (seconds) to a relative time string like "2h ago".
 */
private fun relativeTime(timestampSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val diff = nowSeconds - timestampSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}

/**
 * Convert a Unix timestamp (seconds) to a date string like "Dec 18, 14:22".
 */
private fun formatDate(timestampSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US)
    return sdf.format(java.util.Date(timestampSeconds * 1000))
}