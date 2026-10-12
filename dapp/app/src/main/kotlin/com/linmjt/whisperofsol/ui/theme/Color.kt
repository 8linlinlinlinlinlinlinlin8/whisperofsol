package com.linmjt.whisperofsol.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WhisperOfSol — Primitive color tokens
 *
 * Three-layer token architecture:
 *   Primitive  (this file)  — raw palette scales
 *   Semantic   (Theme.kt)   — purpose-bound aliases (primary, surface…)
 *   Component  (ComponentDefaults.kt) — component-specific styling
 *
 * Every color used by the app MUST be defined here, then aliased in the
 * semantic layer. Direct hex literals in composables are a lint failure.
 *
 * Brand palette is built from:
 *   • Christmas Red  (CTA / error / festive)
 *   • Christmas Green (success / treasury / positive)
 *   • Christmas Gold  (tertiary / premium / jackpot)
 *   • Midnight Blue  (dark mode surface / depth)
 *   • Snow White     (light mode surface / on-dark)
 */

// ─── Christmas Red scale ─────────────────────────────────────────────
// Festive crimson, slightly warmer than Material Red500.
val Red50  = Color(0xFFFEF2F2)
val Red100 = Color(0xFFFEE2E2)
val Red200 = Color(0xFFFECACA)
val Red300 = Color(0xFFFCA5A5)
val Red400 = Color(0xFFF87171)
val Red500 = Color(0xFFEF4444)
val Red600 = Color(0xFFDC2626)
val Red700 = Color(0xFFB91C1C)   // brand primary red
val Red800 = Color(0xFF991B1B)
val Red900 = Color(0xFF7F1D1D)

// ─── Christmas Green scale ───────────────────────────────────────────
// Pine / holly green, deeper than Material Green700.
val Green50  = Color(0xFFF0FDF4)
val Green100 = Color(0xFFDCFCE7)
val Green200 = Color(0xFFBBF7D0)
val Green300 = Color(0xFF86EFAC)
val Green400 = Color(0xFF4ADE80)
val Green500 = Color(0xFF22C55E)   // success
val Green600 = Color(0xFF16A34A)
val Green700 = Color(0xFF15803D)   // brand secondary green
val Green800 = Color(0xFF166534)
val Green900 = Color(0xFF14532D)

// ─── Gold accent scale ───────────────────────────────────────────────
// Warm honey gold — used for tertiary (jackpot) and decorative accents.
val Gold100 = Color(0xFFFFFBEB)
val Gold200 = Color(0xFFFEF3C7)
val Gold300 = Color(0xFFFCD34D)   // bright gold
val Gold400 = Color(0xFFFBBF24)
val Gold500 = Color(0xFFF59E0B)   // brand tertiary gold
val Gold600 = Color(0xFFD97706)
val Gold700 = Color(0xFFB45309)

// ─── Midnight Blue scale (dark surfaces) ─────────────────────────────
// Deep indigo, evokes a Christmas Eve sky.
val Midnight50  = Color(0xFFEEF2FF)
val Midnight100 = Color(0xFFE0E7FF)
val Midnight200 = Color(0xFFC7D2FE)
val Midnight300 = Color(0xFFA5B4FC)
val Midnight400 = Color(0xFF818CF8)
val Midnight500 = Color(0xFF6366F1)
val Midnight600 = Color(0xFF4F46E5)
val Midnight700 = Color(0xFF312E81)   // dark surface variant
val Midnight800 = Color(0xFF1E1B4B)   // dark surface
val Midnight900 = Color(0xFF0F0A2E)   // deepest dark — backdrop

// ─── Snow White scale (light surfaces) ───────────────────────────────
// Cool grey-tinted whites for light mode surfaces.
val Snow50  = Color(0xFFF8FAFC)   // primary light surface
val Snow100 = Color(0xFFF1F5F9)   // surface variant
val Snow200 = Color(0xFFE2E8F0)   // outline (light)
val Snow300 = Color(0xFFCBD5E1)
val Snow400 = Color(0xFF94A3B8)
val Snow500 = Color(0xFF64748B)
val Snow600 = Color(0xFF475569)
val Snow700 = Color(0xFF334155)
val Snow800 = Color(0xFF1E293B)
val Snow900 = Color(0xFF0F172A)

// ─── Functional colors (semantic-grade, no scale) ────────────────────
// Christmas glow / shadow tints used by component tokens.
val GlowGold       = Color(0x66F59E0B)   // 40% gold — card glow
val GlowGoldStrong = Color(0x99F59E0B)   // 60% gold — focused glow
val GlowRed        = Color(0x4DB91C1C)   // 30% brand red shadow
val OverlayBlack   = Color(0x66000000)   // 40% scrim for hero banner
val Transparent    = Color(0x00000000)