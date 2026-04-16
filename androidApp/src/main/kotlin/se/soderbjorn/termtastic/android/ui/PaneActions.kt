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

/** The things a user can create from the central "+" button. */
enum class CreateKind { Tab, Terminal, FileBrowser, Git }

/** A leaf pane the user can pick as a split target. */
data class SplitTarget(val paneId: String, val title: String)

/**
 * First step: pick what to create — Tab, Terminal, File Browser, or Git.
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
 * Second step for Tab: enter a name.
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
 * Collect all leaf panes from [config] as split targets.
 */
fun collectSplitTargets(config: WindowConfig?): List<SplitTarget> {
    if (config == null) return emptyList()
    val targets = mutableListOf<SplitTarget>()
    fun walk(node: se.soderbjorn.termtastic.PaneNode?) {
        when (node) {
            is se.soderbjorn.termtastic.LeafNode -> targets.add(SplitTarget(node.id, node.title))
            is se.soderbjorn.termtastic.SplitNode -> { walk(node.first); walk(node.second) }
            null -> Unit
        }
    }
    for (tab in config.tabs) {
        walk(tab.root)
        tab.floating.forEach { targets.add(SplitTarget(it.leaf.id, it.leaf.title)) }
        tab.poppedOut.forEach { targets.add(SplitTarget(it.leaf.id, it.leaf.title)) }
    }
    return targets
}

/**
 * Second step for Terminal/FileBrowser: pick which pane to split.
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
