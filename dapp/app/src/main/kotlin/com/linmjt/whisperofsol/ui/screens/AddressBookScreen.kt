package com.linmjt.whisperofsol.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.linmjt.whisperofsol.ui.AddressBookEntry
import com.linmjt.whisperofsol.ui.AddressBookPrefs
import com.linmjt.whisperofsol.ui.components.ChristmasBackground
import com.linmjt.whisperofsol.ui.components.GlowCard
import com.linmjt.whisperofsol.ui.components.IconBadge
import com.linmjt.whisperofsol.ui.components.SecondaryButton
import com.linmjt.whisperofsol.ui.theme.Gold500

/**
 * AddressBookScreen — v3.0.7.
 *
 * Lists locally saved addresses (nickname + base58) with two affordances:
 *   1. "Use this address" (returns to Send screen with recipient prefilled)
 *   2. Edit / Delete actions
 *
 * Empty state shows how to add entries (from Send screen). New entries
 * always arrive via the Send screen's "Save to address book" button —
 * this screen is read/delete/edit only.
 *
 * @param onBack           return to caller (Settings or Send, depending
 *                         on how it was reached)
 * @param onUseAddress     if non-null, tapping an entry's "Use" button
 *                         invokes this with the address and pops back.
 *                         When null, the "Use" button is hidden and the
 *                         screen is a read-only manager view.
 * @param initialAddOpen   if true, opens the "add new address" dialog
 *                         on first composition (used when entered via
 *                         Settings → "+ Add address")
 */
@Composable
fun AddressBookScreen(
    onBack: () -> Unit,
    onUseAddress: ((String) -> Unit)? = null,
    initialAddOpen: Boolean = false,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(AddressBookPrefs.getAll(context)) }
    var editing by remember { mutableStateOf<AddressBookEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(initialAddOpen) }

    // Re-read when this screen comes back into focus so new entries
    // added from Send screen show up without manual refresh.
    LaunchedEffect(Unit) {
        entries = AddressBookPrefs.getAll(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChristmasBackground(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 56.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            text = stringResource(R.string.addressbook_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.addressbook_save_dialog_title),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Count header
                if (entries.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.addressbook_count_format, entries.size
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                }

                // Empty state
                if (entries.isEmpty()) {
                    item {
                        EmptyState()
                    }
                }

                // Entries
                items(entries, key = { it.address }) { entry ->
                    AddressBookRow(
                        entry = entry,
                        onUse = if (onUseAddress != null) {
                            {
                                onUseAddress(entry.address)
                                onBack()
                            }
                        } else null,
                        onEdit = { editing = entry },
                        onDelete = {
                            AddressBookPrefs.remove(context, entry.address)
                            entries = AddressBookPrefs.getAll(context)
                        },
                    )
                }
            }
        }
    }

    // Edit dialog
    editing?.let { target ->
        EditAddressDialog(
            initial = target,
            onDismiss = { editing = null },
            onSave = { newAddr, newNick ->
                // Replace by remove + add (keeps cap, refreshes timestamp)
                AddressBookPrefs.remove(context, target.address)
                AddressBookPrefs.add(context, newAddr, newNick)
                entries = AddressBookPrefs.getAll(context)
                editing = null
            },
        )
    }

    // Add dialog
    if (showAddDialog) {
        EditAddressDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { newAddr, newNick ->
                AddressBookPrefs.add(context, newAddr, newNick)
                entries = AddressBookPrefs.getAll(context)
                showAddDialog = false
                Toast.makeText(
                    context,
                    context.getString(R.string.addressbook_added),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
}

@Composable
private fun EmptyState() {
    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(icon = Icons.Filled.Book, color = Gold500)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.addressbook_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.addressbook_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddressBookRow(
    entry: AddressBookEntry,
    onUse: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlowCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onUse != null) {
                    IconButton(onClick = onUse) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.addressbook_use),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.addressbook_edit),
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = stringResource(R.string.addressbook_delete),
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * EditAddressDialog — shared by edit-existing and add-new flows.
 * `initial` is null for add, non-null for edit (and pre-fills the fields).
 */
@Composable
private fun EditAddressDialog(
    initial: AddressBookEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var nickname by remember { mutableStateOf(initial?.nickname ?: "") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) stringResource(R.string.addressbook_save_dialog_title)
                else stringResource(R.string.addressbook_edit)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.addressbook_address_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    placeholder = {
                        Text("GxGs8Bi6iheHmnBdJn2teEtGrVrCzWGD6ecqHjMPt2Gr")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.addressbook_nickname_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { if (it.length <= 40) nickname = it },
                    placeholder = {
                        Text(text = stringResource(R.string.addressbook_nickname_hint))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (address.isBlank()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.addressbook_invalid_address),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    onSave(address, nickname)
                }
            }) {
                Text(stringResource(R.string.addressbook_save_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.addressbook_cancel))
            }
        },
    )
}
