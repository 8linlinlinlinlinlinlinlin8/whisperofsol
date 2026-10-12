package com.linmjt.whisperofsol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.ui.theme.BrandTitleStyle
import com.linmjt.whisperofsol.ui.theme.FestiveGreetingStyle
import com.linmjt.whisperofsol.ui.theme.GlowGold
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.Gold700
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * BrandLogo — Christmas sock + snowflake emblem.
 *
 * Hand-drawn in Compose Canvas (no Lottie / SVG dependencies). Renders at
 * any size via [size] parameter. Combines:
 *   • A red stocking (sock silhouette)
 *   • A snowflake halo (radial 6-fold snowflake)
 *   • A gold ring border
 *
 * Used by the splash/Wallet screen and top-of-app branding.
 */
@Composable
fun BrandLogo(
    size: Dp = 96.dp,
    showWordmark: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = GlowGold,
                    spotColor   = GlowGold,
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Gold500, Gold700, Red700),
                        center = Offset.Unspecified,
                        radius = size.value * 2f,
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawStockingAndSnowflake(this)
            }
        }
        if (showWordmark) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Whisper of Sol",
                style = BrandTitleStyle,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Snowflake — single decorative snowflake icon. Used in chips and dividers.
 */
@Composable
fun SnowflakeIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 24.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = this.size.minDimension / 2f * 0.78f

        // 6 radial arms
        for (i in 0 until 6) {
            val angle = i * (PI.toFloat() / 3f)
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = tint,
                start = Offset(cx, cy),
                end   = Offset(cx + dx * r, cy + dy * r),
                strokeWidth = 2f,
            )

            // Small barbs near the tip
            val bx = cx + dx * r * 0.7f
            val by = cy + dy * r * 0.7f
            val perpX = -dy
            val perpY = dx
            val barb = r * 0.18f
            drawLine(
                color = tint,
                start = Offset(bx, by),
                end   = Offset(bx + perpX * barb, by + perpY * barb),
                strokeWidth = 1.5f,
            )
        }
        drawCircle(
            color = tint,
            radius = r * 0.15f,
            center = Offset(cx, cy),
        )
    }
}

private fun drawStockingAndSnowflake(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    with(scope) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // ─── Snowflake (foreground) ──────────────────────────────────
        val flakeColor = Color.White
        val flakeR = h * 0.34f
        for (i in 0 until 6) {
            val angle = i * (PI.toFloat() / 3f)
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = flakeColor,
                start = Offset(cx, cy),
                end   = Offset(cx + dx * flakeR, cy + dy * flakeR),
                strokeWidth = 2.2f,
            )
            // barbs
            val tipX = cx + dx * flakeR
            val tipY = cy + dy * flakeR
            val perpX = -dy
            val perpY = dx
            drawLine(
                color = flakeColor,
                start = Offset(tipX - dx * flakeR * 0.25f + perpX * flakeR * 0.12f,
                              tipY - dy * flakeR * 0.25f + perpY * flakeR * 0.12f),
                end   = Offset(tipX, tipY),
                strokeWidth = 1.5f,
            )
            drawLine(
                color = flakeColor,
                start = Offset(tipX - dx * flakeR * 0.25f - perpX * flakeR * 0.12f,
                              tipY - dy * flakeR * 0.25f - perpY * flakeR * 0.12f),
                end   = Offset(tipX, tipY),
                strokeWidth = 1.5f,
            )
        }
        drawCircle(
            color = flakeColor,
            radius = flakeR * 0.14f,
            center = Offset(cx, cy),
        )
    }
}

/**
 * StockingBadge — small circular red stocking, used as wallet avatar.
 */
@Composable
fun StockingBadge(
    modifier: Modifier = Modifier,
    avatarSize: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(avatarSize)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = GlowGold,
                spotColor   = GlowGold,
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Red500, Red700),
                ),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(avatarSize * 0.65f)) {
            val cw = this.size.width
            val ch = this.size.height
            val sockPath = Path().apply {
                // Simple sock outline
                moveTo(cw * 0.25f, ch * 0.20f)
                lineTo(cw * 0.75f, ch * 0.20f)
                lineTo(cw * 0.75f, ch * 0.45f)
                lineTo(cw * 0.95f, ch * 0.55f)
                lineTo(cw * 0.85f, ch * 0.85f)
                lineTo(cw * 0.30f, ch * 0.85f)
                lineTo(cw * 0.25f, ch * 0.55f)
                close()
            }
            drawPath(sockPath, color = Color.White.copy(alpha = 0.92f))
            drawPath(
                sockPath,
                color = Gold500,
                style = Stroke(width = 2f),
            )
        }
    }
}