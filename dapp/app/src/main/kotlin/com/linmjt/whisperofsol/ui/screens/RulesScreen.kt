package com.linmjt.whisperofsol.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.GlowCard
import com.linmjt.whisperofsol.ui.components.HeroBanner
import com.linmjt.whisperofsol.ui.components.IconBadge
import com.linmjt.whisperofsol.ui.theme.GlowGold
import com.linmjt.whisperofsol.ui.theme.Gold100
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.Gold700
import com.linmjt.whisperofsol.ui.theme.Green500
import com.linmjt.whisperofsol.ui.theme.Green700
import com.linmjt.whisperofsol.ui.theme.HeroCardShape
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700
import kotlinx.coroutines.launch

/**
 * RulesScreen — Christmas lottery rules, probability table, and Trust Layer.
 *
 * Stage 1.5 layout (top-to-bottom):
 *   1. Top bar with back + title
 *   2. Hero banner "Christmas Lottery"
 *   3. Probability table — 5 tiers (consolation → mystery jackpot)
 *   4. Expected value card (R = Σ p · r, computed from the table)
 *   5. Trust Layer (NEW in Stage 1.5):
 *      • Program ID card — copy + view on Solana Explorer
 *      • Treasury Balance card — mock lamports + view treasury PDA
 *      • Provably Fair block — short copy
 *      • Source Verified badge — links to GitHub repo
 *
 * The 5 probability cards and the EV card live in the same LazyColumn so
 * the 5th prize ("Mystery Jackpot") is naturally visible just above the
 * fold; a fold-out indicator appears once the user starts scrolling.
 */
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    // v1.9.12: pass treasuryLamports so the Mystery Jackpot tier can
    // compute its dynamic reward (50% of Treasury) instead of a stale
    // hardcoded 0.3 SOL value.
    treasuryLamports: Long? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // v1.9.12: re-build when treasuryLamports changes so the Mystery
    // Jackpot row's reward auto-updates. STABLE tiers stay constant.
    val tiers = remember(treasuryLamports) { LotteryTiers.all(treasuryLamports) }

    // v1.9.13: removed expectedValueSol (was used by ExpectedValueCard, now removed).

    // True once user scrolls past the first screen — drives the "↓ 1 more"
    // fold-out chip that hints at the 5th prize sitting just below.
    val showScrollHint by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 }
    }

    ChristmasBackground(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            ) {
                // ─── Top bar ─────────────────────────────────────────
                item {
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
                                text = "Lottery Rules",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "Probability table from chain",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // ─── Hero banner ─────────────────────────────────────
                item {
                    HeroBanner(
                        title = "Christmas Lottery",  // v1.9.11: drop 🎰 emoji prefix
                        subtitle = "Every unlock has a chance to win",
                        icon = Icons.Filled.Casino,    // v1.9.11: was EmojiEvents
                    )
                }

                // ─── Probability cards ───────────────────────────────
                items(tiers.size) { idx ->
                    val tier = tiers[idx]
                    RewardTier(
                        name  = tier.name,
                        icon  = tier.icon,
                        probability = tier.probabilityPercent.toFloat(),
                        reward  = formatReward(tier.rewardSol),
                        color  = tierColor(tier.tierColor),
                        highlighted = tier.highlighted,
                    )
                }

                // v1.9.13: removed ExpectedValueCard — R = Σ p·r is mathematically
                // correct but the "PROFITABLE" badge was misleading (unlock is
                // free, so R > 0 is trivially true). The probability table above
                // already shows everything the user needs.

                // ─── Trust Layer (Stage 1.5) ──────────────────────────
                item {
                    TrustLayerSection(
                        // v1.9.13: pass real Treasury balance
                        treasuryLamports = treasuryLamports,
                        onOpenTreasury = {
                            openUrl(
                                context,
                                "https://explorer.solana.com/address/$TREASURY_PDA?cluster=devnet"
                            )
                        },
                        // v1.9.13: removed onOpenSource — the GitHub repo is
                        // private (anonymous 404), and the "Source Verified" card
                        // text was wrong ("on Solana Explorer" but actually linked
                        // to GitHub). Whole card removed.
                    )
                }
            }

            // ─── Fold-out scroll hint ────────────────────────────
            // Stage 1.5 fix: when the 5th prize is just below the fold,
            // surface a soft "↓ scroll for more" pill. Once the user
            // scrolls it auto-hides (driven by [showScrollHint]).
            if (showScrollHint) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(4)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "1 more prize below",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/**
 * Tier table — single source of truth.
 *
 * Keep this in sync with the Anchor program (lib.rs):
 *   PROB_*  constants are basis points (out of 10_000).
 *   REWARD_* are lamports.
 *
 * The Expected Value card multiplies probability × reward at runtime —
 * no hard-coded "R = 0.00141 SOL" anywhere.
 */
private enum class TierColor { Red500, Gold500, Green500, Red700, Tertiary }

private data class LotteryTier(
    val name: String,
    // v1.9.11: was `emoji: String` with hardcoded 🎄/🎁/🌟/🎅/✨.
    // Now an ImageVector (Material Icon) for a formal product look.
    val icon: ImageVector,
    // v1.9.12: was `probabilityFraction: Double` (0.0–1.0) with magic
    // 0.7000 / 0.2500 / 0.0400 / 0.0090 numbers. Renamed to bps (basis
    // points, 0-10000) to mirror the on-chain program exactly:
    //   PROB_COMFORT  = 7000
    //   PROB_SMALL    = 2500
    //   PROB_MEDIUM   =  400
    //   PROB_LARGE    =   90
    //   (jackpot)     =   10
    val probabilityBps: Int,                // 0–10000
    val rewardSol: Double,                   // SOL
    val tierColor: TierColor,
    val highlighted: Boolean = false,
) {
    val probabilityFraction: Double get() = probabilityBps / 10_000.0
    val probabilityPercent: Double get() = probabilityBps / 100.0  // 0.01% units
}

@Composable
private fun tierColor(t: TierColor): Color = when (t) {
    TierColor.Red500   -> MaterialTheme.colorScheme.primary
    TierColor.Gold500  -> Gold500
    TierColor.Green500 -> Green500
    TierColor.Red700   -> Red700
    TierColor.Tertiary -> MaterialTheme.colorScheme.tertiary
}

private fun formatReward(sol: Double): String = when {
    sol >= 1.0   -> "%.2f SOL".format(sol)
    sol >= 0.01  -> "%.3f SOL".format(sol)
    else         -> "%.4f SOL".format(sol)
}

// v1.9.12: tier probabilities and (non-jackpot) rewards must match the
// on-chain program exactly. Source of truth:
//   src/android/pda/programs/pda/src/lib.rs
//     PROB_COMFORT, PROB_SMALL, PROB_MEDIUM, PROB_LARGE
//     REWARD_COMFORT, REWARD_SMALL, REWARD_MEDIUM, REWARD_LARGE
//     CHRISTMAS_BONUS_BPS (jackpot = 50% of current Treasury when active)
//
// If you change the on-chain values, update this list too. The on-chain
// program does NOT expose tier table via a query instruction yet, so the
// Android side keeps a static mirror (drift risk — see SOP in MEMORY.md).
private object LotteryTiers {
    // Static tiers — probability and reward are constants on-chain.
    val STATIC: List<LotteryTier> = listOf(
        // bps values mirror src/android/pda/programs/pda/src/lib.rs.
        // reward values are in lamports (1 SOL = 1_000_000_000 lamports)
        // — convert to SOL for display via the `lamports` helper below.
        LotteryTier("Consolation",  Icons.Filled.CardGiftcard, 7000, lamports(  300_000), TierColor.Red500),   // 70.00% → 0.0003 SOL
        LotteryTier("Small",        Icons.Filled.Star,         2500, lamports(1_000_000), TierColor.Gold500),  // 25.00% → 0.001 SOL
        LotteryTier("Medium",       Icons.Filled.Park,         400,  lamports(5_000_000), TierColor.Green500), //  4.00% → 0.005 SOL
        LotteryTier("Large",        Icons.Filled.Face,          90, lamports(50_000_000), TierColor.Red700),   //  0.90% → 0.05 SOL
        // 9990-9999 (0.10%) reserved for the Mystery Jackpot below.
    )

    /**
     * Mystery Jackpot — DYNAMIC. Reward = 50% of current Treasury lamports.
     * Only pays out when the authority has activated christmas_mode on-chain
     * (otherwise this bucket falls through to the "no reward" miss).
     *
     * @param treasuryLamports current Treasury balance in lamports
     * @return a LotteryTier with the dynamic reward computed
     */
    fun mysteryJackpot(treasuryLamports: Long?): LotteryTier {
        val rewardLamports = if (treasuryLamports != null) treasuryLamports / 2 else 0L
        return LotteryTier(
            name = "Mystery Jackpot",
            icon = Icons.Filled.AutoAwesome,
            // 0.10% probability: bps bucket 9990-9999 of 10000.
            probabilityBps = 10,
            // v1.9.12: was hardcoded 0.3 SOL — WRONG. The on-chain reward
            // is dynamic (treasury / 2). rewardSol is in SOL for display.
            rewardSol = lamports(rewardLamports),
            tierColor = TierColor.Tertiary,
            highlighted = true,
        )
    }

    /** Convert lamports → SOL (Double) for the display field. */
    private fun lamports(l: Long): Double = l / 1_000_000_000.0

    /** Convenience: full list with the dynamic jackpot appended. */
    fun all(treasuryLamports: Long?): List<LotteryTier> =
        STATIC + listOf(mysteryJackpot(treasuryLamports))
}

private const val TREASURY_PDA = "4k4Uu9dAXczWGexPwyHFh7m62PHHGBXJ58ej34JKajHc"  // v1.9.0: PDA derived from new programId CCTfaFA...
// v1.9.13: removed MOCK_TREASURY_SOL — was the source of the
// "12.345 SOL" landmine on the Treasury Balance card. Now real value
// comes from HomeUiState.treasuryLamports (fetched via the Solana RPC).
// v2.0.1: removed PROGRAM_ID const + Program ID card — user feedback
// was that showing it on the Lottery Rules screen was clutter without
// added value (anyone curious can find it on the Treasury card's
// "View Treasury on Explorer" link in the related accounts tab).

/**
 * Trust Layer — 1 card under the probability table.
 *
 * v2.0.1: Program ID card removed (user feedback: not needed on this
 * screen, gave it clutter without value). Now only the Treasury Balance
 * card remains as the on-chain proof that the lottery is real.
 */
@Composable
private fun TrustLayerSection(
    onOpenTreasury: () -> Unit,
    // v1.9.13: real on-chain Treasury balance (was MOCK_TREASURY_SOL)
    treasuryLamports: Long? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Treasury Balance card (v1.9.13: real on-chain value) ─
        // v1.9.12 had this showing MOCK_TREASURY_SOL = "12.345 SOL" with
        // a TODO comment. v1.9.13: real value comes from
        // uiState.treasuryLamports (fetched on connect via
        // whisperService.getTreasuryLamports()).
        GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        icon = Icons.Filled.AccountBalanceWallet,
                        color = Gold500,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TREASURY BALANCE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                        // v1.9.13: real value from chain. Falls back to
                        // a "Loading..." chip when not yet fetched.
                        if (treasuryLamports != null) {
                            val sol = treasuryLamports.toDouble() / 1_000_000_000.0
                            Text(
                                text = "%.4f SOL".format(sol),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Live from on-chain Treasury PDA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = "Loading...",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Gold500.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onOpenTreasury() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = Gold500,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "View Treasury on Explorer",
                        style = MaterialTheme.typography.labelLarge,
                        color = Gold500,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}



// v2.0.1: removed shortProgramId() — was only used by the now-deleted
// Program ID card.

// v1.9.13: removed ExpectedValueCard — R = Σ p·r is mathematically
// correct but the "PROFITABLE" badge was misleading (unlock is free,
// so R > 0 is trivially true). Probability table above already shows
// everything the user needs.

@Composable
private fun RewardTier(
    name: String,
    // v1.9.11: was `emoji: String` rendered as Text(emoji, fontSize=32.sp).
    // Now takes ImageVector so we render a proper Material icon at 32dp.
    icon: ImageVector,
    probability: Float,
    reward: String,
    color: Color,
    highlighted: Boolean = false,
) {
    val cardColor = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surface
    GlowCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        backgroundColor = cardColor,
        elevation = if (highlighted) 12.dp else 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = color,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                // Probability bar
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(probability.coerceAtLeast(0.01f))
                            .height(8.dp)
                            .background(color = color, shape = RoundedCornerShape(4.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .weight((100f - probability).coerceAtLeast(0.01f))
                            .height(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatPercent(probability.toDouble())} chance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "REWARD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = reward,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ProbabilityChart(bars: List<Pair<String, Float>>) {
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val w = size.width
        val h = size.height
        val maxBarH = h * 0.78f
        val padding = 16f
        val chartH = h - padding * 2
        val chartW = w - padding * 2
        val barCount = bars.size
        val gap = 8f
        val barW = (chartW - gap * (barCount - 1)) / barCount
        val maxValue = bars.maxOf { it.second }.coerceAtLeast(0.0001f)

        // Grid baseline
        drawLine(
            color = gridColor,
            start = Offset(padding, h - padding),
            end   = Offset(w - padding, h - padding),
            strokeWidth = 1.5f,
        )

        bars.forEachIndexed { i, (_, value) ->
            val ratio = (value / maxValue).coerceIn(0f, 1f)
            val barH = maxBarH * ratio.coerceAtLeast(0.04f)
            val x = padding + i * (barW + gap)
            val y = h - padding - barH

            drawRect(
                color = barColor.copy(alpha = 0.95f),
                topLeft = Offset(x, y),
                size = Size(barW, barH),
            )
            drawRect(
                color = barColor.copy(alpha = 0.5f),
                topLeft = Offset(x, h - padding - barH * 0.35f),
                size = Size(barW, barH * 0.35f),
            )
            drawRect(
                color = Gold500.copy(alpha = 0.9f),
                topLeft = Offset(x, y),
                size = Size(barW, 4f),
            )
            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

private fun formatPercent(v: Double): String = when {
    v >= 1.0  -> "${v.toInt()}%"
    else      -> "%.2f%%".format(v)
}

private fun formatPercentShort(v: Double): String = when {
    v >= 1.0  -> "${v.toInt()}"
    else      -> "%.2f".format(v)
}
