package com.linmjt.whisperofsol.ui.screens

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.BuildConfig
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.GlowCard
import com.linmjt.whisperofsol.ui.components.IconBadge
import com.linmjt.whisperofsol.ui.theme.Gold500
import java.util.Locale

/**
 * SettingsScreen — v3.0.4, language switcher via SharedPreferences.
 *
 * v3.0.0 ~ v3.0.3 used AppCompatDelegate.setApplicationLocales() but every
 * combination we tried crashed on language switch (with or without manual
 * recreate, with or without AppCompatActivity). The crash was non-deterministic
 * — likely a race between setApplicationLocales' internal activity restart
 * and Compose's recomposition.
 *
 * v3.0.4 approach (simpler and more reliable):
 *   1. Save the chosen language to SharedPreferences ("app_language" key)
 *   2. MainActivity.attachBaseContext() reads the prefs and applies the locale
 *      via createConfigurationContext() — no recreate needed at startup
 *   3. SettingsScreen on click: save to prefs + call activity.recreate()
 *   4. Activity restarts, attachBaseContext applies the new locale, Compose
 *      picks up the new locale's stringResource() values
 *
 * This is the "old school" per-app locale approach (pre-AppCompat 1.6) and
 * is rock solid — no AppCompatDelegate quirks, no race conditions.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateAddressBook: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ChristmasBackground(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 56.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Top bar
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Language section
                item { LanguageSection() }

                // Address Book section (v3.0.7)
                item { AddressBookSection(onClick = onNavigateAddressBook) }

                // About section
                item { AboutSection() }
            }
        }
    }
}

@Composable
private fun LanguageSection() {
    val context = LocalContext.current

    // v3.0.5: Read current selection from LanguageState (in-memory
    // MutableState) instead of SharedPreferences. LanguageState is the
    // source of truth for the UI; SharedPreferences (LanguagePrefs) is the
    // persistent source of truth for app restart.
    val currentKey = com.linmjt.whisperofsol.ui.LanguageState.current.value ?: "system"

    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Filled.Language, color = Gold500)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_section_language),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_language_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 5 language options
            val options = listOf(
                LangOption("system",  R.string.settings_language_system),       // follow device
                LangOption("en",      R.string.settings_language_english),
                LangOption("zh-Hans", R.string.settings_language_zh_hans),
                LangOption("zh-Hant", R.string.settings_language_zh_hant),
                LangOption("ja",      R.string.settings_language_ja),
            )
            options.forEach { opt ->
                LanguageRadioRow(
                    label = stringResource(opt.labelRes),
                    selected = currentKey == opt.key,
                    onClick = {
                        // v3.0.6: in-place language switch (no recreate).
                        // 1. Update the activity's resources' Configuration
                        //    via updateConfiguration() — deprecated but
                        //    still works. This makes stringResource() return
                        //    the new locale's strings on the next call.
                        // 2. Save to prefs (persists for next app launch).
                        // 3. Update LanguageState — triggers a recomposition
                        //    that re-reads stringResource() everywhere.
                        applyLocaleToResources(context, opt.key)
                        LanguagePrefs.setLanguage(context, opt.key)
                        com.linmjt.whisperofsol.ui.LanguageState.current.value =
                            if (opt.key == "system") null else opt.key
                    },
                )
            }
        }
    }
}

private data class LangOption(val key: String, val labelRes: Int)

@Composable
private fun LanguageRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AboutSection() {
    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Filled.Info, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.settings_section_about),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.settings_version_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toLong(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_program_id_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_program_id_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * AddressBookSection (v3.0.7) — clickable card linking to the full
 * AddressBookScreen where users can view, edit, and delete locally
 * saved recipient addresses.
 */
@Composable
private fun AddressBookSection(onClick: () -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon = Icons.Filled.Book, color = Gold500)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.addressbook_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.addressbook_section_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(4.dp),
            )
        }
    }
}

/**
 * LanguagePrefs — SharedPreferences wrapper for the app's chosen language.
 *
 * Key: "app_language"
 * Value: BCP-47 language tag ("en", "zh-Hans", "zh-Hant", "ja") or null
 *        (null = follow device system locale).
 *
 * Used by:
 *   - SettingsScreen.onClick: writes the new choice and recreates the activity
 *   - MainActivity.attachBaseContext: reads the choice to apply the locale
 *     BEFORE any Compose code runs
 */
private object LanguagePrefs {
    private const val PREFS_NAME = "whisperofsol_settings"
    private const val KEY_LANGUAGE = "app_language"

    fun getLanguage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_LANGUAGE, null)
        return if (value.isNullOrEmpty()) null else value
    }

    fun setLanguage(context: Context, language: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (language.isNullOrEmpty() || language == "system") {
                remove(KEY_LANGUAGE)
            } else {
                putString(KEY_LANGUAGE, language)
            }
        }.apply()
    }
}

/**
 * Companion helper — exposed so MainActivity.onCreate can read the saved
 * language before [androidx.compose.runtime.Composition] starts. Kept here
 * (with the SettingsScreen) because the prefs key is defined in
 * [LanguagePrefs] above; if you move the prefs to a separate util, move
 * this helper too.
 */
internal fun readSavedLanguage(context: Context): String? = LanguagePrefs.getLanguage(context)

/**
 * v3.0.6: Apply the chosen language to the activity's resources IN PLACE
 * (no recreate). Uses [Resources.updateConfiguration] which is deprecated
 * since API 25 but still functional on modern Android — it's the only
 * way to change the activity's locale without [Activity.recreate].
 *
 * After this call, every `stringResource(R.string.xxx)` in the Compose
 * tree returns the new locale's strings. We also force-trigger a Compose
 * recomposition via the [com.linmjt.whisperofsol.ui.LanguageState] in-memory
 * state change (the caller does this right after).
 *
 * @param context typically the activity context (or any context whose
 *   resources share the same Configuration — they all do in a single app
 *   process, so this is safe).
 * @param key "system" to follow the device locale, otherwise a BCP-47 tag
 *   like "en", "zh-Hans", "zh-Hant", "ja".
 */
internal fun applyLocaleToResources(context: Context, key: String) {
    val locale: java.util.Locale = if (key == "system") {
        java.util.Locale.getDefault()
    } else {
        java.util.Locale.forLanguageTag(key)
    }
    java.util.Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    @Suppress("DEPRECATION")
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}
