package com.linmjt.whisperofsol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.AddressBookPrefs

/**
 * v3.0.9: Post-send "Save this address?" dialog.
 *
 * UX:
 *   - Triggered when a send completes successfully and the recipient
 *     address is NOT already in the address book.
 *   - Offers two actions: "Not now" (dismiss) and "Save" (with optional
 *     nickname filled inline).
 *   - **"Don't ask me again" checkbox at the bottom** — when checked,
 *     persists [AddressBookPrefs.setPostSendPromptDisabled] = true so
 *     this dialog won't appear again until app data is cleared
 *     (intentional: clearing app data is a "fresh start" signal that
 *     should re-enable every prompt).
 *
 * v3.0.9 (bug fix 2026-10-13): switched from `AlertDialog` to a manual
 * `Dialog` with a scrim-blocking outer Box and `dismissOnClickOutside =
 * false`. The previous AlertDialog had `dismissOnClickOutside = true` by
 * default, so a tap on the scrim dismissed the dialog AND the same tap
 * "passed through" to the underlying screen, triggering a button there
 * (e.g. tapping the Send button on the Inbox page). With the new
 * implementation, the scrim's Box has a no-op `clickable` modifier that
 * consumes the tap before it can reach anything below.
 *
 * v3.0.8 design notes:
 *   - The address preview is monospaced and truncated to first 4 / last 4
 *     chars to keep the dialog compact.
 *   - The nickname field is inline (no nested dialog) to keep the
 *     interaction single-layer.
 */
@Composable
fun PostSendDialog(
    recipientAddress: String,
    onDismiss: () -> Unit,
    onSave: (nickname: String) -> Unit,
) {
    val context = LocalContext.current
    var nickname by remember { mutableStateOf("") }
    var dontAskAgain by remember { mutableStateOf(false) }

    fun persistOptOut() {
        if (dontAskAgain) AddressBookPrefs.setPostSendPromptDisabled(context, true)
    }

    Dialog(
        onDismissRequest = {
            // Hard-disabled by `dismissOnClickOutside = false` below, so
            // this only fires on back-press / system dismiss.
            persistOptOut()
            onDismiss()
        },
        properties = DialogProperties(
            // KEY: prevents the scrim-tap "pass-through" bug where the
            // dialog dismisses AND the same tap registers on the screen
            // below. User must explicitly tap a button to close.
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // v3.0.9: full-screen scrim Box that blocks all underlying touches.
        // The clickable modifier on the scrim consumes the event so it
        // never reaches the page behind the dialog window.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    // The outer scrim also opts out of the dialog's
                    // dismiss-on-outside-tap behavior. We want absolutely
                    // no tap to bubble back to the underlying activity.
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The dialog content sits in its own Surface so the clickable
            // scrim around it doesn't intercept taps meant for the
            // dialog's own buttons.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.postsend_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.postsend_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Address preview (truncated)
                    Text(
                        text = if (recipientAddress.length >= 12)
                            "${recipientAddress.take(4)}…${recipientAddress.takeLast(4)}"
                        else recipientAddress,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // Inline nickname field (optional)
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { if (it.length <= 40) nickname = it },
                        label = { Text(stringResource(R.string.addressbook_nickname_label)) },
                        placeholder = { Text(stringResource(R.string.addressbook_nickname_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Don't-ask-again checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            onCheckedChange = { dontAskAgain = it },
                        )
                        Text(
                            text = stringResource(R.string.postsend_dont_ask),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    // Buttons row (Not now + Save)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            persistOptOut()
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.postsend_not_now))
                        }
                        TextButton(onClick = {
                            persistOptOut()
                            onSave(nickname.trim())
                        }) {
                            Text(stringResource(R.string.postsend_save))
                        }
                    }
                }
            }
        }
    }
}
