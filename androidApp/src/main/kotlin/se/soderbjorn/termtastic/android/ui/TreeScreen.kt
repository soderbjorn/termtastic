/**
 * Tree overview screen for the Termtastic Android app.
 *
 * Displays the server's window layout as a flat list of tab headers and leaf
 * pane rows, mirroring the sidebar in the web/Electron client. Each leaf is
 * annotated with a type icon (terminal, file browser, git, floating, or empty)
 * and a status indicator (spinner for "working", opacity pulse for "waiting")
 * reflecting the server-pushed session state. Tapping a leaf navigates to [TerminalScreen], [FileBrowserListScreen],
 * or [GitListScreen] depending on the pane type.
 *
 * Also hosts the "+" button that launches the [CreateKindPickerDialog] flow
 * from [PaneActions] for creating new tabs and split panes.
 *
 * @see TerminalScreen
 * @see FileBrowserListScreen
 * @see GitListScreen
 * @see PaneActions
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.TerminalContent
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.addSiblingFileBrowser
import se.soderbjorn.termtastic.client.addSiblingGit
import se.soderbjorn.termtastic.client.addSiblingTerminal
import se.soderbjorn.termtastic.client.addTab

// Palette tokens live in SidebarPalette.kt — shared with MarkdownListScreen.

/**
 * Sealed hierarchy representing the two kinds of rows in the tree list.
 *
 * [TabHeader] is a section header for a tab; [Leaf] is a clickable pane row.
 */
private sealed class TreeRow {
    /**
     * Section header for a tab, showing the tab title and an aggregate state dot.
     *
     * @property tabId the server-side tab identifier.
     * @property title the display title of the tab.
     * @property aggregateState "working", "waiting", or null -- the worst-case state
     *   across all leaves in this tab.
     */
    data class TabHeader(
        val tabId: String,
        val title: String,
        val aggregateState: String?,
    ) : TreeRow()

    /**
     * A leaf pane row representing a terminal, file browser, git pane, or empty placeholder.
     *
     * @property paneId the server-side pane identifier.
     * @property sessionId the PTY session identifier (used to open [TerminalScreen]).
     * @property title the display title of the pane.
     * @property kind the type of content this pane holds.
     * @property floating true if the pane is in a floating window.
     */
    data class Leaf(
        val paneId: String,
        val sessionId: String,
        val title: String,
        val kind: LeafKind,
        val floating: Boolean,
    ) : TreeRow()
}

/** Discriminator for the type of content a leaf pane holds. */
private enum class LeafKind { TERMINAL, FILE_BROWSER, GIT, EMPTY }

/**
 * Intermediate data holder used during tree flattening before being
 * converted to [TreeRow.Leaf] rows.
 */
private data class CollectedLeaf(
    val paneId: String,
    val sessionId: String,
    val title: String,
    val kind: LeafKind,
    val floating: Boolean,
)

/**
 * Flattens the hierarchical [WindowConfig] into a linear list of [TreeRow] items
 * suitable for a [LazyColumn].
 *
 * For each tab, collects all leaves, computes an aggregate state dot
 * (waiting > working > null), and emits a [TreeRow.TabHeader] followed by
 * [TreeRow.Leaf] rows.
 *
 * @param config the current window configuration from the server.
 * @param states map of session ID to state string ("working", "waiting", or null).
 * @return ordered list of tree rows.
 */
private fun flatten(config: WindowConfig, states: Map<String, String?>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    for (tab in config.tabs) {
        val leaves = mutableListOf<CollectedLeaf>()
        for (p in tab.panes) addLeaf(p.leaf, floating = false, out = leaves)

        // "waiting" wins over "working" — matches updateStateIndicators() in WebState.kt.
        var tabState: String? = null
        for (leaf in leaves) {
            when (states[leaf.sessionId]) {
                "waiting" -> { tabState = "waiting"; break }
                "working" -> if (tabState != "working") tabState = "working"
            }
        }

        rows.add(TreeRow.TabHeader(tab.id, tab.title, tabState))
        for (leaf in leaves) {
            rows.add(
                TreeRow.Leaf(
                    paneId = leaf.paneId,
                    sessionId = leaf.sessionId,
                    title = leaf.title,
                    kind = leaf.kind,
                    floating = leaf.floating,
                )
            )
        }
    }
    return rows
}

/**
 * Converts a single [LeafNode] into a [CollectedLeaf] and appends it to [out].
 *
 * Determines the [LeafKind] from the leaf's content type.
 *
 * @param leaf the leaf node to convert.
 * @param floating whether this leaf is inside a floating window.
 * @param out accumulator list for collected leaves.
 */
private fun addLeaf(leaf: LeafNode, floating: Boolean, out: MutableList<CollectedLeaf>) {
    val kind = when (leaf.content) {
        is TerminalContent, null -> LeafKind.TERMINAL
        is FileBrowserContent -> LeafKind.FILE_BROWSER
        is GitContent -> LeafKind.GIT
    }
    out.add(
        CollectedLeaf(
            paneId = leaf.id,
            sessionId = leaf.sessionId,
            title = leaf.title,
            kind = kind,
            floating = floating,
        )
    )
}

/**
 * Tree overview screen showing the server's window layout.
 *
 * Renders a flat list of tab headers and leaf pane rows, each with a type icon
 * and optional pulsing state dot. Tapping a leaf navigates to the appropriate
 * screen based on its [LeafKind]. The "+" button in the top bar launches the
 * [CreateKindPickerDialog] flow for adding tabs or splitting panes.
 *
 * The back/disconnect button tears down the connection via [ConnectionHolder]
 * and returns to [HostsScreen].
 *
 * @param onOpenTerminal callback invoked with a session ID to open a terminal.
 * @param onOpenFileBrowser callback invoked with a pane ID to open the file browser.
 * @param onOpenGit callback invoked with a pane ID to open the git view.
 * @param onDisconnect callback invoked when the user disconnects from the server.
 * @see TerminalScreen
 * @see FileBrowserListScreen
 * @see GitListScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(
    onOpenTerminal: (sessionId: String) -> Unit,
    onOpenFileBrowser: (paneId: String) -> Unit,
    onOpenGit: (paneId: String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        onDisconnect()
        return
    }

    val config by client.windowState.config.collectAsStateWithLifecycle()
    val states by client.windowState.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // --- Creation dialog state ---
    var showCreatePicker by remember { mutableStateOf(false) }
    var showTabName by remember { mutableStateOf(false) }
    // e.g. "terminal" / "fileBrowser" / "git" — which kind to create next to
    // the picked pane.
    var showPanePicker by remember { mutableStateOf<String?>(null) }

    if (showCreatePicker) {
        CreateKindPickerDialog(
            onDismiss = { showCreatePicker = false },
            onPick = { kind ->
                showCreatePicker = false
                when (kind) {
                    CreateKind.Tab -> showTabName = true
                    CreateKind.Terminal -> showPanePicker = "terminal"
                    CreateKind.FileBrowser -> showPanePicker = "fileBrowser"
                    CreateKind.Git -> showPanePicker = "git"
                }
            },
        )
    }
    if (showTabName) {
        TabNameDialog(
            onDismiss = { showTabName = false },
            onConfirm = { name ->
                showTabName = false
                val socket = ConnectionHolder.windowSocket() ?: return@TabNameDialog
                scope.launch { addTab(client, socket, name) }
            },
        )
    }
    showPanePicker?.let { kind ->
        val targets = collectSplitTargets(config)
        PanePickerDialog(
            kindLabel = when (kind) {
                "terminal" -> "Terminal"
                "fileBrowser" -> "File Browser"
                "git" -> "Git"
                else -> kind.replaceFirstChar { it.uppercase() }
            },
            targets = targets,
            onDismiss = { showPanePicker = null },
            onPick = { paneId ->
                showPanePicker = null
                val socket = ConnectionHolder.windowSocket() ?: return@PanePickerDialog
                val cfgSnapshot = config
                scope.launch {
                    when (kind) {
                        "terminal" -> addSiblingTerminal(socket, paneId, cfgSnapshot)
                        "fileBrowser" -> addSiblingFileBrowser(socket, paneId, cfgSnapshot)
                        "git" -> addSiblingGit(socket, paneId, cfgSnapshot)
                    }
                }
            },
        )
    }

    Scaffold(
        containerColor = SidebarBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { ConnectionHolder.disconnect() }
                        onDisconnect()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Disconnect",
                            tint = SidebarTextSecondary,
                        )
                    }
                },
                title = { Text("Termtastic", color = SidebarTextPrimary) },
                actions = {
                    IconButton(onClick = { showCreatePicker = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Create new",
                            tint = SidebarTextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
            )
        },
    ) { paddingValues ->
        val cfg = config
        if (cfg == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connecting…", color = SidebarTextSecondary)
            }
            return@Scaffold
        }

        val rows = flatten(cfg, states)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            items(rows, key = { row ->
                when (row) {
                    is TreeRow.TabHeader -> "tab-${row.tabId}"
                    is TreeRow.Leaf -> "leaf-${row.paneId}"
                }
            }) { row ->
                when (row) {
                    is TreeRow.TabHeader -> TabHeaderRow(row)
                    is TreeRow.Leaf -> LeafRow(
                        row = row,
                        state = states[row.sessionId],
                        onClick = {
                            when (row.kind) {
                                LeafKind.TERMINAL -> onOpenTerminal(row.sessionId)
                                LeafKind.FILE_BROWSER -> onOpenFileBrowser(row.paneId)
                                LeafKind.GIT -> onOpenGit(row.paneId)
                                LeafKind.EMPTY -> {} // undecided pane — no action
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Renders a tab section header row with the tab title in uppercase and an
 * aggregate state dot.
 *
 * @param row the tab header data to render.
 */
@Composable
private fun TabHeaderRow(row: TreeRow.TabHeader) {
    WaitingPulse(state = row.aggregateState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.title.uppercase(),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = SidebarTextPrimary,
                    letterSpacing = 0.5.sp,
                ),
            )
            StateSpinner(state = row.aggregateState, sizeDp = 12, leadingSpacer = 7)
        }
    }
}

/**
 * Renders a clickable leaf pane row with a type icon, spinner, and title.
 *
 * @param row the leaf pane data to render.
 * @param state the current state string ("working", "waiting", or null).
 * @param onClick callback invoked when the row is tapped to open the pane.
 */
@Composable
private fun LeafRow(
    row: TreeRow.Leaf,
    state: String?,
    onClick: () -> Unit,
) {
    WaitingPulse(state = state) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "${row.title}, ${row.kind.name.lowercase()}"
                }
                .padding(start = 32.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaneIcon(kind = row.kind, floating = row.floating)
            Spacer(Modifier.width(8.dp))
            StateSpinner(state = state, sizeDp = 12, trailingSpacer = 7)
            Text(
                text = row.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = SidebarTextSecondary,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * Small spinning indicator shown when the session state is "working".
 * Matches the web client spinner CSS.
 */
@Composable
private fun StateSpinner(
    state: String?,
    sizeDp: Int,
    leadingSpacer: Int = 0,
    trailingSpacer: Int = 0,
) {
    if (state != "working") return
    if (leadingSpacer > 0) Spacer(Modifier.width(leadingSpacer.dp))
    CircularProgressIndicator(
        modifier = Modifier
            .size(sizeDp.dp)
            .semantics { stateDescription = "working" },
        strokeWidth = 2.dp,
        color = SidebarTextPrimary,
        trackColor = SidebarTextSecondary.copy(alpha = 0.3f),
    )
    if (trailingSpacer > 0) Spacer(Modifier.width(trailingSpacer.dp))
}

/**
 * Wraps [content] in a pulsating opacity animation when [state] is "waiting",
 * drawing attention to panes that need user input.
 */
@Composable
private fun WaitingPulse(state: String?, content: @Composable () -> Unit) {
    if (state == "waiting") {
        val transition = rememberInfiniteTransition(label = "waitPulse")
        val alpha = transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waitAlpha",
        ).value
        Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }) { content() }
    } else {
        content()
    }
}

/**
 * 14dp icon mirroring the SVGs in main/web/.../main.kt sidebar:
 *  - terminal: rounded rect with a chevron + line ("terminal prompt")
 *  - markdown: document with folded corner and two text lines
 *  - empty: dashed rounded rect placeholder
 *  - floating: rounded rect with a center line and a small taskbar
 */
@Composable
private fun PaneIcon(kind: LeafKind, floating: Boolean) {
    val tint = SidebarTextSecondary.copy(alpha = if (floating) 0.9f else 0.6f)
    val desc = when {
        kind == LeafKind.FILE_BROWSER -> "File browser pane"
        kind == LeafKind.GIT -> "Git pane"
        kind == LeafKind.EMPTY -> "Undecided pane"
        floating -> "Floating pane"
        else -> "Pane"
    }
    Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = desc }) {
        val w = size.width
        val px = w / 16f
        val stroke = Stroke(
            width = 1.3f * px,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when {
            kind == LeafKind.FILE_BROWSER -> {
                // Document with folded corner and two text lines
                val doc = Path().apply {
                    moveTo(3f * px, 1.75f * px)
                    lineTo(9.5f * px, 1.75f * px)
                    lineTo(13f * px, 5.25f * px)
                    lineTo(13f * px, 14.25f * px)
                    cubicTo(13f * px, 14.53f * px, 12.78f * px, 14.75f * px, 12.5f * px, 14.75f * px)
                    lineTo(3f * px, 14.75f * px)
                    cubicTo(2.72f * px, 14.75f * px, 2.5f * px, 14.53f * px, 2.5f * px, 14.25f * px)
                    lineTo(2.5f * px, 2.25f * px)
                    cubicTo(2.5f * px, 1.97f * px, 2.72f * px, 1.75f * px, 3f * px, 1.75f * px)
                    close()
                }
                drawPath(doc, color = tint, style = stroke)
                // Fold
                val fold = Path().apply {
                    moveTo(9.25f * px, 1.75f * px)
                    lineTo(9.25f * px, 5.25f * px)
                    lineTo(13f * px, 5.25f * px)
                }
                drawPath(fold, color = tint, style = stroke)
                // Text lines
                drawLine(tint, Offset(5f * px, 8.5f * px), Offset(10.5f * px, 8.5f * px), 1.2f * px, StrokeCap.Round)
                drawLine(tint, Offset(5f * px, 11f * px), Offset(10.5f * px, 11f * px), 1.2f * px, StrokeCap.Round)
            }
            kind == LeafKind.GIT -> {
                // Git branch icon: vertical line with two branches and three circles
                val r = 1.5f * px
                // Main vertical line
                drawLine(tint, Offset(8f * px, 3f * px), Offset(8f * px, 13f * px), 1.3f * px, StrokeCap.Round)
                // Branch line going top-right
                val branch1 = Path().apply {
                    moveTo(8f * px, 5f * px)
                    lineTo(11f * px, 3f * px)
                }
                drawPath(branch1, color = tint, style = stroke)
                // Branch line going bottom-right
                val branch2 = Path().apply {
                    moveTo(8f * px, 10f * px)
                    lineTo(11f * px, 12f * px)
                }
                drawPath(branch2, color = tint, style = stroke)
                // Circle at top of main line
                drawCircle(tint, radius = r, center = Offset(8f * px, 3f * px), style = stroke)
                // Circle at branch endpoint top-right
                drawCircle(tint, radius = r, center = Offset(11f * px, 3f * px), style = stroke)
                // Circle at bottom of main line
                drawCircle(tint, radius = r, center = Offset(8f * px, 13f * px), style = stroke)
            }
            kind == LeafKind.EMPTY -> {
                // Dashed rounded rect placeholder
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(2f * px, 2f * px),
                    size = Size(12f * px, 12f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * px, 1.5f * px),
                    style = Stroke(
                        width = 1.3f * px,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(2.5f * px, 2f * px), 0f,
                        ),
                    ),
                )
            }
            floating -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(2f * px, 1f * px),
                    size = Size(12f * px, 10f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * px, 1.5f * px),
                    style = stroke,
                )
                drawLine(tint, Offset(4f * px, 8f * px), Offset(10f * px, 8f * px), 1.2f * px, StrokeCap.Round)
                drawRoundRect(
                    color = tint.copy(alpha = tint.alpha * 0.5f),
                    topLeft = Offset(4f * px, 13f * px),
                    size = Size(8f * px, 1.2f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.6f * px, 0.6f * px),
                )
            }
            else -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(1f * px, 2f * px),
                    size = Size(14f * px, 12f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * px, 1.5f * px),
                    style = stroke,
                )
                val chevron = Path().apply {
                    moveTo(4f * px, 7f * px)
                    lineTo(6f * px, 5f * px)
                    lineTo(4f * px, 3f * px)
                }
                drawPath(chevron, color = tint, style = stroke)
                drawLine(tint, Offset(7f * px, 7f * px), Offset(11f * px, 7f * px), 1.2f * px, StrokeCap.Round)
            }
        }
    }
}
