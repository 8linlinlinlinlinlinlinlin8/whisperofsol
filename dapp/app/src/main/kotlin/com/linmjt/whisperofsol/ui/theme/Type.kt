package com.linmjt.whisperofsol.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * WhisperOfSol — Typography tokens
 *
 * Brand voice: warm, festive, trustworthy. We use:
 *   • Serif (FontFamily.Serif) for Display/Headline — evokes a holiday card
 *   • Sans  (FontFamily.SansSerif) for Title/Body/Label — clean UI legibility
 *
 * Sizes follow the Material 3 type scale (Display/Headline/Title/Body/Label)
 * but are slightly inflated for readability on a small phone screen.
 */

private val BrandSerif = FontFamily.Serif
private val BrandSans  = FontFamily.SansSerif

val WhisperOfSolTypography = Typography(

    // ─── Display — hero numbers, splash title ─────────────────────────
    displayLarge = TextStyle(
        fontFamily = BrandSerif,
        fontWeight = FontWeight.Bold,
        fontSize   = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = BrandSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = BrandSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 36.sp,
        lineHeight = 44.sp,
    ),

    // ─── Headline — section openers like "Treasury" / "Inbox" ──────────
    headlineLarge = TextStyle(
        fontFamily = BrandSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = BrandSerif,
        fontWeight = FontWeight.Medium,
        fontSize   = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = BrandSerif,
        fontWeight = FontWeight.Medium,
        fontSize   = 24.sp,
        lineHeight = 32.sp,
    ),

    // ─── Title — card titles, dialog headers ───────────────────────────
    titleLarge = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Medium,
        fontSize   = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    // ─── Body — paragraph / form labels ────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    // ─── Label — button text, chips, captions ──────────────────────────
    labelLarge = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = BrandSans,
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ─── Brand voice tokens — used by headline components ─────────────────
val HeroNumberStyle = TextStyle(
    fontFamily = BrandSerif,
    fontWeight = FontWeight.Bold,
    fontSize   = 32.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.5).sp,
)

val StatNumberStyle = TextStyle(
    fontFamily = BrandSerif,
    fontWeight = FontWeight.Bold,
    fontSize   = 28.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.3).sp,
)

val BrandTitleStyle = TextStyle(
    fontFamily = BrandSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize   = 28.sp,
    lineHeight = 36.sp,
)

val FestiveGreetingStyle = TextStyle(
    fontFamily = BrandSerif,
    fontWeight = FontWeight.Medium,
    fontSize   = 20.sp,
    lineHeight = 28.sp,
)