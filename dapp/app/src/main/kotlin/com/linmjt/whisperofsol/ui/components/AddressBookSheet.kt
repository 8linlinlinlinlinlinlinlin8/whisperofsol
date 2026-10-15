package com.linmjt.whisperofsol.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.AddressBookEntry
import com.linmjt.whisperofsol.ui.AddressBookPrefs
import com.linmjt.whisperofsol.ui.AddressBookSort
import com.linmjt.whisperofsol.ui.AvatarPalette
import kotlinx.coroutines.launch

/**
 * v3.0.8: Exchange-style address book bottom sheet.
 *
 * Layout (top to bottom):
 *   - Title bar (Address Book + close)
 *   - Search field (real-time filter, debounce via direct onValueChange)
 *   - Sort chips: Recent / A-Z / Frequent
 *   - Scrollable list of entries (avatar + name + truncated address)
 *     - Tap row → onSelect (and close the sheet)
 *     - Trailing ⋮ menu → Delete / Rename
 *   - Empty state (no entries OR search miss)
 *   - FAB: + Add new (opens [AddAddressDialog])
 *
 * v3.0.8 design notes:
 *   - We use Material 3's [ModalBottomSheet] (skipPartiallyExpanded = true so
 *     the sheet always opens fully — easier reach to the FAB).
 *   - Sort change uses [AnimatedContent] crossfade for a subtle polish.
 *   - Delete uses Snackbar with [Undo] action (5 s) so accidental deletes
 *     are recoverable without a confirmation dialog.
 *   - Avatar color comes from [AddressBookEntry.avatarColorIndex], which
 *     is stable across sessions (hash of nickname).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookSheet(
    onDismiss: () -> Unit,
    onSelect: (AddressBookEntry) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Local mirror so the sheet re-renders immediately on add/remove.
    var entries by remember { mutableStateOf(AddressBookPrefs.getAll(context)) }
    var sort by remember { mutableStateOf(AddressBookSort.Recent) }
    var query by remember { mutableStateOf("") }

    // v3.0.8: Snackbar host for delete-with-undo, scoped to this sheet.
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.addressbook_deleted_message)
    val undoLabel = stringResource(R.string.addressbook_undo)

    // Dialog state for Add (FAB) and Rename (overflow menu)
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AddressBookEntry?>(null) }

    LaunchedEffect(sort, query) {
        // Re-read so the displayed list reflects current sort + filter
        // (cheap: parses ~5 KB JSON).
        entries = if (query.isBlank()) {
            AddressBookPrefs.getAllSorted(context, sort)
        } else {
            // Apply sort then search so order is stable within a search hit set.
            AddressBookPrefs.getAllSorted(context, sort)
                .filter { it.nickname.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 720.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ─── Title row ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.addressbook_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.addressbook_sheet_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.addressbook_close),
                    )
                }
            }

            // ─── Search field ──────────────────────────────────────────
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.addressbook_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // ─── Sort chips ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AddressBookSort.entries.forEach { s ->
                    AssistChip(
                        onClick = { sort = s },
                        label = {
                            Text(
                                text = when (s) {
                                    AddressBookSort.Recent       -> stringResource(R.string.addressbook_sort_recent)
                                    AddressBookSort.Alphabetical -> stringResource(R.string.addressbook_sort_az)
                                    AddressBookSort.Frequent     -> stringResource(R.string.addressbook_sort_frequent)
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        colors = if (s == sort)
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        else AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ─── List / empty state ────────────────────────────────────
            AnimatedContent(
                targetState = entries.isEmpty(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "sheet-content",
            ) { isEmpty ->
                if (isEmpty) {
                    EmptyState(
                        isSearchActive = query.isNotBlank(),
                        onAddNew = { showAddDialog = true },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = 80.dp), // leave room for FAB
                    ) {
                        items(entries, key = { it.address }) { entry ->
                            AddressRow(
                                entry = entry,
                                onTap = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSelect(entry)
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        if (!sheetState.isVisible) onDismiss()
                                    }
                                },
                                onRename = { renameTarget = entry },
                                onDelete = {
                                    AddressBookPrefs.remove(context, entry.address)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val removed = entry
                                    entries = if (query.isBlank())
                                        AddressBookPrefs.getAllSorted(context, sort)
                                    else
                                        AddressBookPrefs.getAllSorted(context, sort)
                                            .filter { it.nickname.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = deletedMessage,
                                            actionLabel = undoLabel,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            AddressBookPrefs.add(context, removed.address, removed.nickname)
                                            // recordUse stays untouched (undo only restores entry, not usage)
                                            entries = if (query.isBlank())
                                                AddressBookPrefs.getAllSorted(context, sort)
                                            else
                                                AddressBookPrefs.getAllSorted(context, sort)
                                                    .filter { it.nickname.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ─── Snackbar host (anchored to sheet bottom) ───────────────
        SnackbarHost(hostState = snackbarHostState) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                actionColor = MaterialTheme.colorScheme.inversePrimary,
            )
        }

        // ─── FAB ────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.addressbook_add_new)) },
                modifier = Modifier.align(Alignment.BottomEnd),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    // ─── Add dialog (FAB) ───────────────────────────────────────
    if (showAddDialog) {
        AddAddressDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { address, nickname ->
                AddressBookPrefs.add(context, address, nickname)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                entries = if (query.isBlank())
                    AddressBookPrefs.getAllSorted(context, sort)
                else
                    AddressBookPrefs.getAllSorted(context, sort)
                        .filter { it.nickname.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
                showAddDialog = false
            },
        )
    }

    // ─── Rename dialog (overflow menu) ──────────────────────────
    renameTarget?.let { target ->
        RenameDialog(
            initial = target.nickname,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                AddressBookPrefs.rename(context, target.address, newName)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                entries = if (query.isBlank())
                    AddressBookPrefs.getAllSorted(context, sort)
                else
                    AddressBookPrefs.getAllSorted(context, sort)
                        .filter { it.nickname.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
                renameTarget = null
            },
        )
    }
}

/**
 * Single row in the address book sheet.
 *
 * v3.0.8: Avatar uses [AddressBookEntry.avatarColorIndex] for a stable,
 * recognizable color. Tap the row to select; the trailing ⋮ opens a
 * dropdown with Rename / Delete.
 */
@Composable
private fun AddressRow(
    entry: AddressBookEntry,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val avatarColor = remember(entry.address) { Color(AvatarPalette[entry.avatarColorIndex()]) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle with first letter
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColor),
            contentAlignment = Alignment.Center,
        ) {
            val initial = (entry.nickname.ifBlank { entry.address }).firstOrNull()?.uppercase() ?: "•"
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayLabel(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.address.length >= 12)
                        "${entry.address.take(4)}…${entry.address.takeLast(4)}"
                    else entry.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.useCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.addressbook_use_count, entry.useCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.addressbook_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.addressbook_rename)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.addressbook_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(isSearchActive: Boolean, onAddNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = if (isSearchActive)
                stringResource(R.string.addressbook_empty_search)
            else
                stringResource(R.string.addressbook_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isSearchActive) {
            TextButton(onClick = onAddNew) {
                Text(stringResource(R.string.addressbook_empty_add_cta))
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.addressbook_rename_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 40) text = it },
                label = { Text(stringResource(R.string.addressbook_nickname_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.addressbook_cancel))
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onConfirm(text) }) {
                    Text(stringResource(R.string.addressbook_save_action))
                }
            }
        }
    }
}

// (clickable is imported from androidx.compose.foundation above)
