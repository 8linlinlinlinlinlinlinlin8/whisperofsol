package com.linmjt.whisperofsol.ui.components

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.screens.QrScannerActivity

/**
 * v3.0.8: Reusable "Add a new address to book" dialog.
 *
 * Used by:
 *   - AddressBookSheet FAB (inline primary flow)
 *   - PostSendDialog "Save with nickname" action
 *
 * Fields:
 *   - Address (required, validates as non-blank — caller can do deeper
 *     base58 checks; we don't block save here to match the pre-v3.0.7
 *     permissive behavior, send will fail with the standard error if
 *     the address is bad)
 *   - Nickname (optional, 40-char limit)
 *   - QR scan button (trailing icon on Address field — launches
 *     [QrScannerActivity] via the same ActivityResultLauncher pattern
 *     used by the recipient field in SendWhisperScreen)
 *
 * On confirm, the dialog returns the (address, nickname) pair to the
 * caller via [onConfirm]. The caller is responsible for writing to
 * [com.linmjt.whisperofsol.ui.AddressBookPrefs].
 */
@Composable
fun AddAddressDialog(
    initialAddress: String = "",
    title: String = stringResource(R.string.addressbook_add_dialog_title),
    confirmLabel: String = stringResource(R.string.addressbook_save_action),
    onDismiss: () -> Unit,
    onConfirm: (address: String, nickname: String) -> Unit,
) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(initialAddress) }
    var nickname by remember { mutableStateOf("") }

    val qrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_ADDRESS)
            if (!scanned.isNullOrBlank()) address = scanned
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.addressbook_address_label)) },
                    placeholder = { Text(stringResource(R.string.addressbook_address_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = {
                                qrLauncher.launch(Intent(context, QrScannerActivity::class.java))
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.QrCode,
                                    contentDescription = stringResource(R.string.addressbook_qr_button),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.addressbook_qr_button))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { if (it.length <= 40) nickname = it },
                    label = { Text(stringResource(R.string.addressbook_nickname_label)) },
                    placeholder = { Text(stringResource(R.string.addressbook_nickname_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val addr = address.trim()
                    if (addr.isNotEmpty()) onConfirm(addr, nickname.trim())
                },
                enabled = address.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.addressbook_cancel))
            }
        },
    )
}
