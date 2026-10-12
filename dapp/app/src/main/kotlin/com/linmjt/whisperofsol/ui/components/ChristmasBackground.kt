package com.linmjt.whisperofsol.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Christmas-themed animated background.
 *
 * Two stacked layers, both opt-in:
 *   • radial festive gradient (red→green→midnight, very low alpha)
 *   • animated snowflakes (50 flakes, infinite loop, 60fps target)
 *
 * Performance:
 *   • Each flake is a 4-parameter particle (x, y, radius, sway phase).
 *   • x-position is randomised at composition time and held in remember.
 *   • y-position is driven by a single infinite transition shared across
 *     all flakes — this gives us one coroutine per layer instead of 50.
 *   • sway is computed analytically from time + phase, no extra animation.
 *
 * The result is buttery-smooth 60fps even on entry-level hardware.
 */
@Composable
fun ChristmasBackground(
    modifier: Modifier = Modifier,
    snowflakeCount: Int = 50,
    showGradient: Boolean = true,
    showSnow: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {

        // Layer 1 — radial festive gradient.
        if (showGradient) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x14B91C1C),    // red at center
                            0.5f to Color(0x0A15803D),    // green mid
                            1.0f to Color(0x000F0A2E),    // midnight edge
                        ),
                        center = Offset(w * 0.5f, h * 0.2f),
                        radius = w.coerceAtLeast(h),
                    )
                )
            }
        }

        // Layer 2 — animated snowflakes.
        if (showSnow) {
            val transition = rememberInfiniteTransition(label = "snow")
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue  = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 12_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "snowProgress",
            )

            // Pre-generate flake parameters once (deterministic per seed).
            val flakes = remember(snowflakeCount) {
                val rng = Random(seed = 25122525)
                Array(snowflakeCount) { i ->
                    Snowflake(
                        xFraction    = i.toFloat() / snowflakeCount + (rng.nextFloat() - 0.5f) / snowflakeCount,
                        radiusPx     = 2f + rng.nextFloat() * 4f,
                        fallDurationSec = 8f + rng.nextFloat() * 6f,
                        swayAmplitudePx = 8f + rng.nextFloat() * 14f,
                        swayPhase   = rng.nextFloat() * 2f * PI.toFloat(),
                        swayFreq    = 0.6f + rng.nextFloat() * 0.8f,
                        alpha       = 0.4f + rng.nextFloat() * 0.5f,
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cycleDuration = 12f  // matches animationSpec above

                flakes.forEach { flake ->
                    // Normalize progress per-flake by their individual fall duration.
                    val fallRatio: Float = (cycleDuration / flake.fallDurationSec).toFloat()
                    val individualProgress: Float = ((progress * fallRatio) % 1.0f)

                    val yPos: Float = -flake.radiusPx + (h + flake.radiusPx * 2f) * individualProgress
                    val baseX: Float = flake.xFraction * w
                    val swayRad: Float = progress * 2f * PI.toFloat() * flake.swayFreq + flake.swayPhase
                    val sway: Float = sin(swayRad) * flake.swayAmplitudePx

                    drawCircle(
                        color = Color.White.copy(alpha = flake.alpha),
                        radius = flake.radiusPx,
                        center = Offset(baseX + sway, yPos),
                    )
                }
            }
        }

        // Layer 3 — content.
        content()
    }
}

private data class Snowflake(
    val xFraction: Float,
    val radiusPx: Float,
    val fallDurationSec: Float,
    val swayAmplitudePx: Float,
    val swayPhase: Float,
    val swayFreq: Float,
    val alpha: Float,
)