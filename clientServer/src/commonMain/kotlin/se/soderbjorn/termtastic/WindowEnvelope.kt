package se.soderbjorn.termtastic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/**
 * Envelopes pushed over the `/window` websocket from server → client. Each
 * message is a JSON Text frame serialized with [windowJson]. The polymorphic
 * discriminator is `"type"` here — different from the `"kind"` discriminator
 * [windowJson] uses globally for the nested `PaneNode` / `LeafContent`
 * hierarchies. We override per-class with [JsonClassDiscriminator] so the
 * outer envelope still serializes as `{"type":"config", …}` while nested
 * `config.tabs[…].root` panes stay on `"kind":"leaf" | "split"`. That
 * preserves byte-for-byte compatibility with the legacy wire format the
 * hand-built `buildJsonObject { put("type", …) }` server code produced, and
 * with the web client's `dynamic`-path incoming parser that still reads
 * `parsed.type`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WindowEnvelope {
    @Serializable
    @SerialName("config")
    data class Config(val config: WindowConfig) : WindowEnvelope()

    @Serializable
    @SerialName("state")
    data class State(val states: Map<String, String?>) : WindowEnvelope()

    @Serializable
    @SerialName("fileBrowserDir")
    data class FileBrowserDir(
        val paneId: String,
        val dirRelPath: String,
        val entries: List<FileBrowserEntry>,
    ) : WindowEnvelope()

    @Serializable
    @SerialName("fileBrowserContent")
    data class FileBrowserContentMsg(
        val paneId: String,
        val relPath: String,
        val kind: FileContentKind,
        /** Rendered or highlighted HTML. Empty when [kind] is [FileContentKind.Binary]. */
        val html: String,
        val language: String? = null,
    ) : WindowEnvelope()

    @Serializable
    @SerialName("fileBrowserError")
    data class FileBrowserError(
        val paneId: String,
        val message: String,
    ) : WindowEnvelope()

    @Serializable
    @SerialName("pendingApproval")
    data class PendingApproval(val message: String) : WindowEnvelope()

    @Serializable
    @SerialName("uiSettings")
    data class UiSettings(val settings: JsonObject) : WindowEnvelope()

    @Serializable
    @SerialName("claudeUsage")
    data class ClaudeUsage(val usage: ClaudeUsageData?) : WindowEnvelope()

    @Serializable
    @SerialName("gitList")
    data class GitList(
        val paneId: String,
        val entries: List<GitFileEntry>,
    ) : WindowEnvelope()

    @Serializable
    @SerialName("gitDiff")
    data class GitDiffResult(
        val paneId: String,
        val filePath: String,
        val hunks: List<DiffHunk>,
        /** Detected language for syntax highlighting, or null. */
        val language: String? = null,
        /** Full old file content (for split mode). */
        val oldContent: String? = null,
        /** Full new file content (for split mode). */
        val newContent: String? = null,
    ) : WindowEnvelope()

    @Serializable
    @SerialName("gitError")
    data class GitError(
        val paneId: String,
        val message: String,
    ) : WindowEnvelope()
}

@Serializable
data class ClaudeUsageData(
    val sessionPercent: Int,
    val sessionResetTime: String,
    val weeklyAllPercent: Int,
    val weeklyAllResetTime: String,
    val weeklySonnetPercent: Int,
    val extraUsageEnabled: Boolean,
    val extraUsageInfo: String?,
    /** ISO-8601 instant when the data was fetched from the Claude CLI. */
    val fetchedAt: String = "",
)

@Serializable
data class FileBrowserEntry(
    val name: String,
    val relPath: String,
    val isDir: Boolean,
    /** File size in bytes. 0 for directories. */
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
)

@Serializable
enum class FileContentKind { Markdown, Text, Binary }

@Serializable
data class GitFileEntry(
    val filePath: String,
    val status: GitFileStatus,
    val directory: String,
)

@Serializable
enum class GitFileStatus { Added, Modified, Deleted, Renamed, Untracked }

@Serializable
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

@Serializable
data class DiffLine(
    val type: DiffLineType,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null,
    val content: String,
)

@Serializable
enum class DiffLineType { Context, Addition, Deletion }
