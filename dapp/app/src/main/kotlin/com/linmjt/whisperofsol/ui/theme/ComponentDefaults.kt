package com.linmjt.whisperofsol.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * WhisperOfSol — Component token layer
 *
 * Centralizes component-level defaults so every Card / Button across the
 * app inherits the same shadow, padding, and elevation. Anything visual
 * (radius, elevation, spacing) used by a single component should be
 * defined here as a token, not hardcoded in the composable body.
 */

object WhisperOfSolCardDefaults {

    /** Default hero card — 24dp radius, level-2 elevation, gold-tinted glow. */
    @Composable
    fun hero(
        container: Color = MaterialTheme.colorScheme.surfaceVariant,
        content:   Color = MaterialTheme.colorScheme.onSurface,
    ): CardColors = CardDefaults.cardColors(
        containerColor = container,
        contentColor   = content,
    )

    /** Inline summary card — lighter, 1dp elevation, no glow. */
    @Composable
    fun inline(
        container: Color = MaterialTheme.colorScheme.surface,
        content:   Color = MaterialTheme.colorScheme.onSurface,
    ): CardColors = CardDefaults.cardColors(
        containerColor = container,
        contentColor   = content,
    )

    /** Press feedback / emphasis card. */
    val ElevatedElevation: Dp = 6.dp
    val DefaultElevation:  Dp = 2.dp
    val FlatElevation:     Dp = 0.dp
}

object WhisperOfSolButtonDefaults {

    /** Outlined border — used by secondary actions. */
    val primaryOutline: BorderStroke
        @Composable
        get() = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)

    /** Sizes — taller than Material default for tappable comfort. */
    val PrimaryHeight:   Dp = 56.dp
    val SecondaryHeight: Dp = 48.dp
    val HorizontalPad:   Dp = 24.dp
}