/**
 * Hosts/servers list screen for the Termtastic Android app.
 *
 * This is the app's landing screen. It displays the user's saved server
 * entries from [se.soderbjorn.termtastic.android.data.HostsRepository] and
 * provides add/edit/delete dialogs. Tapping a host initiates a WebSocket
 * connection via [se.soderbjorn.termtastic.android.net.ConnectionHolder] and,
 * on success, navigates to the [TreeScreen] overview.
 *
 * @see se.soderbjorn.termtastic.android.data.HostsRepository
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 * @see TreeScreen
 */
package se.soderbjorn.termtastic.android.ui

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.android.data.ConnectionRepository
import se.soderbjorn.termtastic.client.HostEntry
import se.soderbjorn.termtastic.android.data.HostsRepository
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.ServerUrl
import se.soderbjorn.termtastic.client.getOrCreateToken

/**
 * Sealed type representing the target of the host edit dialog.
 *
 * [Add] opens a blank form; [Edit] opens a pre-filled form for an existing entry.
 */
private sealed interface EditTarget {
    /** Indicates the dialog should create a new host entry. */
    data object Add : EditTarget
    /**
     * Indicates the dialog should edit an existing host entry.
     *
     * @property entry the host entry to edit.
     */
    data class Edit(val entry: HostEntry) : EditTarget
}

/**
 * Landing screen showing the user's saved server hosts.
 *
 * Provides add, edit, and delete functionality for host entries. Tapping a
 * host initiates a WebSocket connection to the Termtastic server; on success
 * the [onConnected] callback navigates to the tree overview.
 *
 * Shows a "Waiting for approval..." label when the server has a pending
 * device-approval dialog for this client.
 *
 * @param applicationContext Android application context for repository instantiation.
 * @param onConnected callback invoked after a successful connection to a host.
 * @see se.soderbjorn.termtastic.android.data.HostsRepository
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    applicationContext: Context,
    onConnected: () -> Unit,
) {
    val hostsRepo = remember { HostsRepository(applicationContext) }
    val connectionRepo = remember { ConnectionRepository(applicationContext) }
    val scope = rememberCoroutineScope()

    var hosts by remember { mutableStateOf<List<HostEntry>?>(null) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<HostEntry?>(null) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    val pendingApproval by (ConnectionHolder.pendingApproval
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        hostsRepo.hosts.collect { hosts = it }
    }

    Scaffold(
        containerColor = SidebarBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "HOSTS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = SidebarTextPrimary,
                            letterSpacing = 0.8.sp,
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = { editTarget = EditTarget.Add }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add host",
                            tint = SidebarTextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SidebarSurface,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SidebarBackground),
        ) {
            val list = hosts
            when {
                list == null -> Unit // first composition
                list.isEmpty() -> EmptyState(onAdd = { editTarget = EditTarget.Add })
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { entry ->
                        HostRow(
                            entry = entry,
                            connecting = connectingId == entry.id,
                            pendingApproval = pendingApproval && connectingId == entry.id,
                            enabled = connectingId == null,
                            onConnect = {
                                connectingId = entry.id
                                scope.launch {
                                    runCatching {
                                        val token = getOrCreateToken(connectionRepo.authTokenStore())
                                        ConnectionHolder.connect(
                                            serverUrl = ServerUrl(host = entry.host, port = entry.port),
                                            authToken = token,
                                        )
                                    }.onSuccess {
                                        connectingId = null
                                        onConnected()
                                    }.onFailure { e ->
                                        connectingId = null
                                        val msg = e.message ?: "Connection failed"
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = msg,
                                                actionLabel = "Dismiss",
                                            )
                                        }
                                    }
                                }
                            },
                            onEdit = { editTarget = EditTarget.Edit(entry) },
                            onDelete = { deleteTarget = entry },
                        )
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        val initial = (target as? EditTarget.Edit)?.entry
        HostEditDialog(
            initial = initial,
            onDismiss = { editTarget = null },
            onSave = { label, host, port ->
                scope.launch {
                    if (initial == null) {
                        hostsRepo.add(label, host, port)
                    } else {
                        hostsRepo.update(initial.copy(label = label, host = host, port = port))
                    }
                    editTarget = null
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        DeleteHostDialog(
            entry = entry,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    hostsRepo.delete(entry.id)
                    deleteTarget = null
                }
            },
        )
    }
}

/**
 * Placeholder UI shown when no hosts have been saved yet.
 *
 * Displays a friendly message and a button to add the first host.
 *
 * @param onAdd callback invoked when the "Add host" button is tapped.
 */
@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = SidebarTextSecondary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No hosts yet",
            style = MaterialTheme.typography.titleMedium,
            color = SidebarTextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Add a server to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = SidebarTextSecondary.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onAdd) {
            Text("Add host")
        }
    }
}

/**
 * A single row in the hosts list showing the label, host:port, and an
 * overflow menu for edit/delete actions.
 *
 * When a connection attempt is in progress, the overflow menu is replaced
 * by a progress spinner and optional "Waiting for approval" text.
 *
 * @param entry the host entry to render.
 * @param connecting true while a connection attempt is in progress for this entry.
 * @param pendingApproval true when the server is showing a device-approval dialog.
 * @param enabled false to disable tap interactions (while another host is connecting).
 * @param onConnect callback invoked when the row is tapped to connect.
 * @param onEdit callback invoked from the overflow menu to edit this entry.
 * @param onDelete callback invoked from the overflow menu to delete this entry.
 */
@Composable
private fun HostRow(
    entry: HostEntry,
    connecting: Boolean,
    pendingApproval: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onConnect)
            .semantics {
                role = Role.Button
                contentDescription = "${entry.label}, ${entry.host}:${entry.port}"
            }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalGlyph()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = SidebarTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.host}:${entry.port}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SidebarTextSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (connecting) {
            if (pendingApproval) {
                Text(
                    "Waiting for approval\u2026",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SidebarTextSecondary,
                    ),
                )
                Spacer(Modifier.width(6.dp))
            }
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = SidebarTextSecondary,
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Host options",
                        tint = SidebarTextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Dialog for adding or editing a host entry.
 *
 * Presents text fields for label, host, and port. The Save button is
 * disabled until all fields contain valid input.
 *
 * @param initial the existing entry to edit, or null when adding a new one.
 * @param onDismiss callback to close the dialog without saving.
 * @param onSave callback with the validated label, host, and port values.
 */
@Composable
private fun HostEditDialog(
    initial: HostEntry?,
    onDismiss: () -> Unit,
    onSave: (label: String, host: String, port: Int) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }

    val parsedPort = port.toIntOrNull()
    val canSave = label.isNotBlank() && host.isNotBlank() && parsedPort != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text(if (initial == null) "Add host" else "Edit host") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (parsedPort != null && canSave) onSave(label.trim(), host.trim(), parsedPort) },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Confirmation dialog shown before deleting a host entry.
 *
 * @param entry the host entry about to be deleted.
 * @param onDismiss callback to close the dialog without deleting.
 * @param onConfirm callback to proceed with deletion.
 */
@Composable
private fun DeleteHostDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Delete host?") },
        text = { Text("\"${entry.label}\" will be removed from this device.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Small 16dp terminal-pane glyph, inlined from TreeScreen's PaneIcon (non-floating variant). */
@Composable
private fun TerminalGlyph() {
    val tint = SidebarTextSecondary.copy(alpha = 0.7f)
    Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = "Terminal" }) {
        val w = size.width
        val px = w / 16f
        val stroke = Stroke(
            width = 1.3f * px,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(1f * px, 2f * px),
            size = Size(14f * px, 12f * px),
            cornerRadius = CornerRadius(1.5f * px, 1.5f * px),
            style = stroke,
        )
        val chevron = Path().apply {
            moveTo(4f * px, 7f * px)
            lineTo(6f * px, 5f * px)
            lineTo(4f * px, 3f * px)
        }
        drawPath(chevron, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(7f * px, 7f * px),
            end = Offset(11f * px, 7f * px),
            strokeWidth = 1.2f * px,
            cap = StrokeCap.Round,
        )
    }
}
