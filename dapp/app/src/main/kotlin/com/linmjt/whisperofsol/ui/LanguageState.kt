package com.linmjt.whisperofsol.ui

import androidx.compose.runtime.mutableStateOf

/**
 * LanguageState — v3.0.5: in-memory source of truth for the chosen language.
 *
 * Why a singleton with [mutableStateOf]?
 *   - We need to trigger a Compose recomposition when the user picks a new
 *     language, so the entire UI re-reads [androidx.compose.ui.res.stringResource]
 *     with the new locale.
 *   - The MainActivity's [androidx.compose.runtime.Composable] reads
 *     `current.value` inside `setContent {}`. When the value changes,
 *     Compose recomposes, the new [android.content.Context] is created via
 *     [android.content.Context.createConfigurationContext], and a
 *     [androidx.compose.runtime.CompositionLocalProvider] propagates the new
 *     [androidx.compose.ui.platform.LocalContext] to all child Composables.
 *   - No activity recreate() needed → no app "close → reopen" UX.
 *
 * Persistence:
 *   - The persistent source of truth is [LanguagePrefs] (SharedPreferences).
 *   - On app launch, MainActivity.onCreate reads from LanguagePrefs and
 *     sets `current.value` to the saved choice.
 *   - On language change, SettingsScreen writes to LanguagePrefs AND sets
 *     `current.value` to the new choice (triggers recomposition).
 *
 * Value:
 *   - null = follow system locale
 *   - non-null = BCP-47 tag (e.g. "en", "zh-Hans", "zh-Hant", "ja")
 */
object LanguageState {
    val current = mutableStateOf<String?>(null)
}
