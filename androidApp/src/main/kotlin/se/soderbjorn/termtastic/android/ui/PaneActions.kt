/**
 * Pane creation dialogs and utilities for the Termtastic Android app.
 *
 * Provides a multi-step dialog flow for creating new tabs and splitting
 * existing panes into terminals, file browsers, or git views. The flow is:
 * 1. [CreateKindPickerDialog] -- pick what to create (Tab, Terminal, File Browser, Git).
 * 2. [TabNameDialog] -- if Tab was chosen, enter a name.
 * 3. [PanePickerDialog] -- if a split type was chosen, pick the target pane.
 *
 * Used by [TreeScreen] via its "+" action button.
 *
 * @see TreeScreen
 * @see collectSplitTargets
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import se.soderbjorn.termtastic.WindowConfig

/**
 * The kinds of items a user can create from the central "+" button on [TreeScreen].
 *
 * [Tab] creates a new top-level tab; [Terminal], [FileBrowser], and [Git] split
 * an existing pane to add a sibling of the specified type.
 */
enum class CreateKind { Tab, Terminal, FileBrowser, Git }

/**
 * A leaf pane the user can pick as a split target in [PanePickerDialog].
 *
 * @property paneId the server-side identifier of the leaf pane.
 * @property title the human-readable title of the pane, shown in the picker list.
 */
data class SplitTarget(val paneId: String, val title: String)

/**
 * First step of the creation flow: pick what to create -- Tab, Terminal,
 * File Browser, or Git.
 *
 * Called from [TreeScreen] when the user taps the "+" action button.
 *
 * @param onDismiss callback to close the dialog without selecting anything.
 * @param onPick callback invoked with the chosen [CreateKind].
 */
@Composable
fun CreateKindPickerDialog(
    onDismiss: () -> Unit,
    onPick: (CreateKind) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create new") },
        text = {
            Column {
                CreateKindRow("Tab", "A new tab with a fresh terminal") {
                    onPick(CreateKind.Tab)
                }
                CreateKindRow("Terminal", "Split an existing pane") {
                    onPick(CreateKind.Terminal)
                }
                CreateKindRow("File Browser", "Split an existing pane") {
                    onPick(CreateKind.FileBrowser)
                }
                CreateKindRow("Git", "Split an existing pane") {
                    onPick(CreateKind.Git)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * A single selectable row inside [CreateKindPickerDialog].
 *
 * @param title the primary label (e.g. "Tab", "Terminal").
 * @param subtitle a short description shown below the title.
 * @param onClick callback invoked when the row is tapped.
 */
@Composable
private fun CreateKindRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Second step of the creation flow when [CreateKind.Tab] was chosen: prompts
 * the user to enter a name for the new tab.
 *
 * @param onDismiss callback to close the dialog without creating.
 * @param onConfirm callback invoked with the entered tab name.
 */
@Composable
fun TabNameDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tab") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tab name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Collects all leaf panes from the given [WindowConfig] as [SplitTarget] items.
 *
 * Walks every tab's panes and returns a flat list suitable for
 * [PanePickerDialog].
 *
 * @param config the current window configuration, or null if not yet received.
 * @return a list of split targets; empty if [config] is null.
 */
fun collectSplitTargets(config: WindowConfig?): List<SplitTarget> {
    if (config == null) return emptyList()
    val targets = mutableListOf<SplitTarget>()
    for (tab in config.tabs) {
        tab.panes.forEach { targets.add(SplitTarget(it.leaf.id, it.leaf.title)) }
    }
    return targets
}

/**
 * Second step of the creation flow for Terminal, File Browser, or Git: pick
 * which existing pane to split.
 *
 * Displays a scrollable list of available leaf panes. If no panes are
 * available, shows a message instead.
 *
 * @param kindLabel human-readable label for the type being created (e.g. "Terminal").
 * @param targets the available leaf panes to split.
 * @param onDismiss callback to close the dialog without selecting.
 * @param onPick callback invoked with the chosen pane's ID.
 */
@Composable
fun PanePickerDialog(
    kindLabel: String,
    targets: List<SplitTarget>,
    onDismiss: () -> Unit,
    onPick: (paneId: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $kindLabel") },
        text = {
            if (targets.isEmpty()) {
                Text("No panes available to split.")
            } else {
                Column {
                    Text(
                        "Which pane should it split?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(targets, key = { it.paneId }) { target ->
                            Text(
                                text = target.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(target.paneId) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
