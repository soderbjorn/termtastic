/**
 * "Create" bottom sheet for the Lunamux Android Sessions screen.
 *
 * This file contains [CreateSheet], the Material3 `ModalBottomSheet` opened by
 * the Sessions top bar's "+" button. It gathers every "create a new …" action
 * into one place: a new tab, new panes (terminal / file browser / git) for the
 * active overview tab, and a new world. World creation lives here — rather than
 * inside the world-switcher dropdown — so that dropdown is purely for switching
 * between and managing existing worlds.
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A bottom sheet listing every "create new …" action for the Sessions screen.
 *
 * Opened from [TreeScreen]'s top-bar "+" button. Always offers "New tab" and
 * "New world"; the three pane creators (terminal / file browser / git) are
 * shown only when [showPaneOptions] is true — i.e. in the overview view mode,
 * where there is an active tab to add the pane to. Each row dismisses the sheet
 * via its callback (the caller flips the sheet's visibility flag) before
 * performing the action or opening a follow-up name dialog.
 *
 * @param showPaneOptions whether to include the per-pane creators (terminal /
 *   file browser / git); typically `viewMode == OVERVIEW`.
 * @param onNewTab called when "New tab" is chosen.
 * @param onNewPane called with the wire kind (`"terminal"`, `"fileBrowser"`,
 *   or `"git"`) when a pane creator is chosen.
 * @param onNewWorld called when "New world" is chosen.
 * @param onDismiss called when the sheet is dismissed (swipe / scrim tap).
 * @see TreeScreen
 * @see PaneIcon
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSheet(
    showPaneOptions: Boolean,
    onNewTab: () -> Unit,
    onNewPane: (kind: String) -> Unit,
    onNewWorld: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SidebarSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Create",
                color = SidebarTextBright,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            CreateRow(
                label = "New tab",
                onClick = onNewTab,
            ) {
                // Decorative — the "New tab" label is the row's accessible name.
                PlusIcon(contentDescription = "", tint = SidebarTextPrimary)
            }
            if (showPaneOptions) {
                // Separate "New tab" from the pane creators. In list mode
                // (showPaneOptions == false) the divider before "New workspace"
                // below already provides the one separator under "New tab".
                HorizontalDivider(
                    color = SidebarBorder,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                CreateRow(
                    label = "New terminal",
                    onClick = { onNewPane("terminal") },
                ) { PaneIcon(kind = LeafKind.TERMINAL, floating = false, sizeDp = 20) }
                CreateRow(
                    label = "New file browser",
                    onClick = { onNewPane("fileBrowser") },
                ) { PaneIcon(kind = LeafKind.FILE_BROWSER, floating = false, sizeDp = 20) }
                CreateRow(
                    label = "New git",
                    onClick = { onNewPane("git") },
                ) { PaneIcon(kind = LeafKind.GIT, floating = false, sizeDp = 20) }
            }
            HorizontalDivider(
                color = SidebarBorder,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            CreateRow(
                label = "New workspace",
                onClick = onNewWorld,
            ) {
                Icon(
                    Icons.Outlined.Public,
                    contentDescription = null,
                    tint = SidebarTextPrimary,
                )
            }
        }
    }
}

/**
 * One tappable row in the [CreateSheet]: a leading [icon] followed by a text
 * [label]. Rendered as a full-width clickable [Row] so the whole strip is the
 * tap target, matching the sheet's other rows.
 *
 * @param label the row's visible (and accessible) name.
 * @param onClick invoked when the row is tapped.
 * @param icon the leading glyph, drawn in a fixed 24.dp box.
 */
@Composable
private fun CreateRow(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(20.dp))
        Text(label, color = SidebarTextPrimary, fontSize = 16.sp)
    }
}
