package com.linmjt.whisperofsol.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * WhisperOfSol — Shape tokens (component layer)
 *
 * Material 3 default shapes are 4dp / 8dp / 12dp / 16dp — too sharp for a
 * premium festive feel. We override with a friendlier 16/20/24/28 scale so
 * cards and buttons feel like rounded ornaments, cutting corners.
 */

val WhisperOfSolShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(16.dp),    // inputs, chips
    medium     = RoundedCornerShape(20.dp),    // inline cards, dialogs
    large      = RoundedCornerShape(24.dp),    // primary cards
    extraLarge = RoundedCornerShape(28.dp),    // hero banner, FAB
)

// ─── Brand-specific component shapes (single source of truth) ────────
val HeroCardShape   = RoundedCornerShape(28.dp)
val BigButtonShape  = RoundedCornerShape(24.dp)
val InlineCardShape = RoundedCornerShape(20.dp)
val ChipShape       = RoundedCornerShape(16.dp)
val SnowflakeShape  = RoundedCornerShape(50)   // perfectly circular flake chip