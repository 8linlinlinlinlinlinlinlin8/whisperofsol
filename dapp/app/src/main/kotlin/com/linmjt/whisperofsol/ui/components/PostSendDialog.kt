package com.linmjt.whisperofsol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.AddressBookPrefs

/**
 * v3.0.8: Post-send "Save this address?" dialog.
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
 * v3.0.8 design notes:
 *   - Uses [AlertDialog] (not bottom sheet) so the post-send state is
 *     clearly modal and the user makes an explicit choice.
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

    AlertDialog(
        onDismissRequest = {
            persistOptOut()
            onDismiss()
        },
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.postsend_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                persistOptOut()
                onSave(nickname.trim())
            }) {
                Text(stringResource(R.string.postsend_save))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                persistOptOut()
                onDismiss()
            }) {
                Text(stringResource(R.string.postsend_not_now))
            }
        },
    )
}
