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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.termtastic.GitFileEntry
import se.soderbjorn.termtastic.GitFileStatus
import se.soderbjorn.termtastic.android.net.ConnectionHolder

// Palette tokens live in SidebarPalette.kt — shared with TreeScreen.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitListScreen(
    paneId: String,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var entries by remember { mutableStateOf<List<GitFileEntry>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paneId) {
        val list = runCatching { windowSocket.gitList(paneId) }.getOrNull()
        entries = list ?: emptyList()
        if (list == null) errorMessage = "Failed to load git status"
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = SidebarBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "GIT",
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
                    errorMessage ?: "No changed files",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = SidebarTextSecondary,
                    ),
                    modifier = Modifier.padding(16.dp),
                )
                else -> {
                    val groups = remember(list) { groupByDirectory(list) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        groups.forEach { (directory, items) ->
                            item(key = "section:$directory") {
                                GitSectionHeader(directory)
                            }
                            items(items, key = { "file:${it.filePath}" }) { entry ->
                                GitFileRow(entry) { onOpenFile(entry.filePath) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitSectionHeader(directory: String) {
    Text(
        text = if (directory.isEmpty()) "(ROOT)" else directory.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            color = SidebarTextPrimary,
            letterSpacing = 0.5.sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun GitFileRow(entry: GitFileEntry, onClick: () -> Unit) {
    val fileName = entry.filePath.substringAfterLast('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$fileName (${entry.status.name})"
            }
            .padding(start = 32.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(entry.status)
        Spacer(Modifier.width(8.dp))
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SidebarTextSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.filePath,
            style = MaterialTheme.typography.labelSmall.copy(
                color = SidebarTextSecondary.copy(alpha = 0.6f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Colored single-letter badge indicating git status:
 *  M = yellow, A = green, D = red, R = blue, U = gray.
 */
@Composable
private fun StatusBadge(status: GitFileStatus) {
    val (letter, color) = when (status) {
        GitFileStatus.Modified  -> "M" to Color(0xFFFFD60A)
        GitFileStatus.Added     -> "A" to Color(0xFF32D74B)
        GitFileStatus.Deleted   -> "D" to Color(0xFFFF453A)
        GitFileStatus.Renamed   -> "R" to Color(0xFF64D2FF)
        GitFileStatus.Untracked -> "U" to Color(0xFF8E8E93)
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * Group entries by their [GitFileEntry.directory] field, preserving order.
 */
private fun groupByDirectory(entries: List<GitFileEntry>): LinkedHashMap<String, MutableList<GitFileEntry>> {
    val groups = LinkedHashMap<String, MutableList<GitFileEntry>>()
    for (entry in entries) {
        groups.getOrPut(entry.directory) { mutableListOf() }.add(entry)
    }
    return groups
}
