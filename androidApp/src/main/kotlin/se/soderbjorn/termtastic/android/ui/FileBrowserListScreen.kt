package se.soderbjorn.termtastic.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.termtastic.FileBrowserEntry
import se.soderbjorn.termtastic.android.net.ConnectionHolder

/**
 * One screen per directory: lists entries with directories first, then files.
 * Tapping a directory pushes another [FileBrowserListScreen]; tapping a file
 * pushes [FileBrowserContentScreen]. The server is told about every
 * navigated-into folder so the web client's tree reflects the same expansion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserListScreen(
    paneId: String,
    dirRelPath: String,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var entries by remember { mutableStateOf<List<FileBrowserEntry>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paneId, dirRelPath) {
        val list = runCatching { windowSocket.fileBrowserListDir(paneId, dirRelPath) }.getOrNull()
        entries = list ?: emptyList()
        if (list == null) errorMessage = "Failed to load directory"
        if (dirRelPath.isNotEmpty()) {
            // Keep the web client's persisted expansion set in sync with the
            // directories the mobile user has actually walked into.
            runCatching {
                windowSocket.fileBrowserSetExpanded(paneId, dirRelPath, true)
            }
        }
    }

    BackHandler { onBack() }

    val title = if (dirRelPath.isEmpty()) "FILES"
        else dirRelPath.substringAfterLast('/').ifEmpty { dirRelPath }

    Scaffold(
        containerColor = SidebarBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = SidebarTextPrimary,
                            letterSpacing = 0.8.sp,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
            val list = entries
            when {
                list == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = SidebarTextSecondary)
                }
                list.isEmpty() -> Text(
                    errorMessage ?: "Empty directory",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = SidebarTextSecondary,
                    ),
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.relPath }) { entry ->
                        FileBrowserRow(entry) {
                            if (entry.isDir) onOpenDir(entry.relPath)
                            else onOpenFile(entry.relPath)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserRow(entry: FileBrowserEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = entry.name
            }
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (entry.isDir) "\uD83D\uDCC1" else "\uD83D\uDCC4", // 📁 / 📄
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SidebarTextSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
