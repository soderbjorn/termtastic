/**
 * Terminal emulator screen for the Termtastic Android app.
 *
 * Hosts a full xterm-compatible terminal session rendered by a Termux
 * [com.termux.view.TerminalView]. User keystrokes are forwarded to the
 * server over a [se.soderbjorn.termtastic.client.PtySocket] WebSocket, and
 * incoming PTY output is fed into a local [com.termux.terminal.TerminalEmulator]
 * for rendering. Also provides an IME helper toolbar for keys missing from
 * phone keyboards (Esc, Ctrl, Tab, arrows), a swipe-to-type input bar, and
 * pinch-to-zoom font resizing.
 *
 * Navigated to from [TreeScreen] when the user taps a terminal leaf pane.
 *
 * @see TreeScreen
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 */
package se.soderbjorn.termtastic.android.ui

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.termtastic.WindowConfig
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import se.soderbjorn.termtastic.Appearance
import se.soderbjorn.termtastic.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.ResolvedPalette
import se.soderbjorn.termtastic.recommendedThemes
import se.soderbjorn.termtastic.resolve
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.PtySocket
import se.soderbjorn.termtastic.client.UiSettings
import se.soderbjorn.termtastic.client.fetchUiSettings

// Accent colour for the terminal screen top bar. Previously hardcoded as
// warm amber; now derived from the semantic palette's accent.primary so it
// adapts to the selected theme.
/** Theme accent colour for the terminal screen top bar. */
private val HeaderAccent: Color
    @Composable @ReadOnlyComposable
    get() = SidebarAccent

/**
 * Local terminal grid metrics — cols/rows of the TerminalView's emulator.
 * Cached in Compose state and refreshed whenever the grid size changes,
 * so the Reformat button can re-assert the view's natural size.
 */
private data class AndroidGridDims(
    val cols: Int,
    val rows: Int,
)

/**
 * Searches the [WindowConfig] pane tree for a leaf whose session ID matches
 * [sessionId] and returns its display title.
 *
 * Used by [TerminalScreen] to show the server-computed pane title (e.g. a
 * prettified working directory) in the top bar instead of the raw session ID.
 *
 * @param config the current window configuration, or null if not yet received.
 * @param sessionId the PTY session identifier to search for.
 * @return the leaf title if found, or null.
 */
private fun findLeafTitle(config: WindowConfig?, sessionId: String): String? {
    if (config == null) return null
    for (tab in config.tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.title }
        tab.poppedOut.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.title }
    }
    return null
}

/**
 * A single-session terminal screen. Owns a [TerminalEmulator] fed from the
 * [PtySocket]'s byte flow, a [TerminalView] rendering the emulator, and
 * a sticky IME helper toolbar above the soft keyboard for Esc/Ctrl/Tab/arrows.
 *
 * @param sessionId the PTY session identifier to connect to on the server.
 * @param onBack callback invoked when the user navigates back to [TreeScreen].
 * @see TreeScreen
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    onBack: () -> Unit,
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        onBack()
        return
    }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Dedicated single-thread dispatcher for emulator state so we never
    // touch its internal grid from two threads at once.
    val emulatorDispatcher = remember {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    // Subscribe to the window config so we can show the server-computed
    // pane title (prettified cwd, or the user's custom name) in the top bar
    // instead of the raw session id. Matches the electron header style.
    val windowConfig by client.windowState.config.collectAsStateWithLifecycle()
    val headerTitle = remember(windowConfig, sessionId) {
        findLeafTitle(windowConfig, sessionId) ?: sessionId
    }

    val sessionStates by client.windowState.states.collectAsStateWithLifecycle()
    val paneState = sessionStates[sessionId]

    val ptySocket = remember(sessionId) { client.openPtySocket(sessionId) }
    val ctrlSticky = remember { mutableStateOf(false) }
    val shiftSticky = remember { mutableStateOf(false) }
    var swipeInputActive by remember { mutableStateOf(false) }
    var swipeText by remember { mutableStateOf("") }
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }

    // Latest local grid dimensions, updated by the TerminalView's
    // grid-size listener. Null until the view measures for the first time.
    var localGrid by remember(sessionId) {
        mutableStateOf<AndroidGridDims?>(null)
    }

    // Set while we're applying a server-pushed PTY size to the emulator.
    // The grid-size listener and the session's updateSize override both
    // check this flag before echoing the new dims back to the server —
    // without it, Android would re-send every server broadcast as a
    // regular Resize, clobbering a web Reformat within one round-trip.
    // Also gates writes to [localGrid] so it keeps reflecting the view's
    // *natural* cols/rows (needed by the Reformat button), not the
    // server-clamped value.
    val applyingServerSize = remember(sessionId) { AtomicBoolean(false) }

    // Mirror Electron's theme + dark/light preference, fetched once per
    // connect from the shared /api/ui-settings endpoint. Until it arrives we
    // leave the emulator on Termux's default palette.
    val centralSettings = LocalUiSettings.current
    var localSettings by remember(sessionId) { mutableStateOf<UiSettings?>(null) }
    LaunchedEffect(client, sessionId) {
        if (centralSettings == null) {
            localSettings = client.fetchUiSettings()
        }
    }
    val uiSettings = centralSettings ?: localSettings
    val systemIsDark = isSystemInDarkTheme()
    val defaultTheme = remember { recommendedThemes.first { it.name == DEFAULT_THEME_NAME } }
    val terminalPalette = remember(uiSettings, systemIsDark) {
        val theme = uiSettings?.sectionTheme("terminal") ?: defaultTheme
        val appearance = uiSettings?.appearance ?: Appearance.Auto
        theme.resolve(appearance, systemIsDark)
    }
    val bgComposeColor = Color(terminalPalette.terminal.bg)

    // A TerminalSession subclass that bypasses the JNI pty path: all user
    // input the view writes is forwarded to our ptySocket, and the view
    // renders our externally-fed emulator.
    val session = remember(sessionId) {
        object : TerminalSession(
            "/system/bin/sh",
            "/",
            emptyArray(),
            emptyArray(),
            8192,
            null,
        ) {
            private var externalEmulator: TerminalEmulator? = null

            fun setEmulator(e: TerminalEmulator) { externalEmulator = e }

            override fun getEmulator(): TerminalEmulator? = externalEmulator

            override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
                val e = externalEmulator
                if (e != null) {
                    // Serialise with onDraw (which also locks [e]) so a
                    // resize can't shrink mScreenRows mid-render, and with
                    // append on emulatorDispatcher so we don't crash
                    // setChar when mColumns changes underneath a write.
                    scope.launch(emulatorDispatcher) {
                        synchronized(e) {
                            runCatching { e.resize(columns, rows, cellWidthPixels, cellHeightPixels) }
                        }
                        terminalViewRef.value?.post { terminalViewRef.value?.invalidate() }
                    }
                }
                if (!applyingServerSize.get()) {
                    scope.launch { runCatching { ptySocket.resize(columns, rows) } }
                }
            }

            override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
                // no-op: emulator lifecycle is owned by the composable
            }

            override fun write(data: ByteArray, offset: Int, count: Int) {
                val copy = data.copyOfRange(offset, offset + count)
                scope.launch { ptySocket.send(copy) }
            }

            // TerminalSession's default implementations of these forward to
            // mClient, but we passed null for that (no JNI pty → no client
            // plumbing), so we must override all five or they'll NPE. They're
            // invoked from TerminalEmulator — even `reset()` inside the
            // emulator ctor calls onColorsChanged on the session.
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit

            override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
                val out = java.io.ByteArrayOutputStream(5)
                if (prependEscape) out.write(0x1b)
                when {
                    codePoint <= 0x7f -> out.write(codePoint)
                    codePoint <= 0x7ff -> {
                        out.write(0xc0 or (codePoint shr 6))
                        out.write(0x80 or (codePoint and 0x3f))
                    }
                    codePoint <= 0xffff -> {
                        out.write(0xe0 or (codePoint shr 12))
                        out.write(0x80 or ((codePoint shr 6) and 0x3f))
                        out.write(0x80 or (codePoint and 0x3f))
                    }
                    else -> {
                        out.write(0xf0 or (codePoint shr 18))
                        out.write(0x80 or ((codePoint shr 12) and 0x3f))
                        out.write(0x80 or ((codePoint shr 6) and 0x3f))
                        out.write(0x80 or (codePoint and 0x3f))
                    }
                }
                val bytes = out.toByteArray()
                scope.launch { ptySocket.send(bytes) }
            }
        }
    }

    val emulator = remember(sessionId) {
        TerminalEmulator(
            session,
            80,
            24,
            0,
            0,
            8192,
            null,
        ).also { session.setEmulator(it) }
    }

    // Pump incoming pty bytes into the emulator on the dedicated dispatcher,
    // then request a redraw on the main thread. The append is locked on
    // the emulator instance so it can't run concurrently with onDraw or a
    // resize — TerminalBuffer and TerminalEmulator are not thread-safe,
    // and a mid-append resize causes setChar to throw.
    LaunchedEffect(sessionId) {
        ptySocket.output.collect { chunk ->
            withContext(emulatorDispatcher) {
                synchronized(emulator) {
                    emulator.append(chunk, chunk.size)
                }
            }
            terminalViewRef.value?.post { terminalViewRef.value?.onScreenUpdated() }
        }
    }

    // React to the server-broadcast PTY size: the shell addresses cells
    // against this grid, so the emulator has to match or cursor-absolute
    // sequences land in the wrong row/column. When another client is
    // pinning the PTY narrower than our view's natural grid. The resize
    // goes through emulatorDispatcher + a lock on the emulator so it
    // serialises with both append and onDraw (TerminalView.onDraw also
    // acquires the same monitor).
    LaunchedEffect(sessionId) {
        ptySocket.ptySize.collect { size ->
            if (size == null) return@collect
            val (cols, rows) = size
            val view = terminalViewRef.value ?: return@collect
            val renderer = view.mRenderer ?: return@collect
            val cellW = renderer.fontWidth.toInt().coerceAtLeast(1)
            val cellH = renderer.fontLineSpacing.coerceAtLeast(1)
            // Suppress echo-back: any grid-size or updateSize callbacks
            // that fire as a result of this emulator.resize must NOT be
            // re-sent to the server, or we'd clobber whatever client just
            // pinned the PTY (web Reformat, another device, etc.). The
            // flag stays set through the view.post so it covers the
            // TerminalView measure that onScreenUpdated triggers, then a
            // second post clears it on the next frame.
            applyingServerSize.set(true)
            withContext(emulatorDispatcher) {
                synchronized(emulator) {
                    runCatching { emulator.resize(cols, rows, cellW, cellH) }
                }
            }
            view.post {
                view.onScreenUpdated()
                view.post { applyingServerSize.set(false) }
            }
        }
    }

    DisposableEffect(sessionId) {
        onDispose {
            ptySocket.closeDetached()
            emulatorDispatcher.close()
        }
    }

    // The activity declares configChanges=orientation|screenSize in the
    // manifest, so rotation does not recreate the activity and the
    // AndroidView's inner View is not automatically re-measured. Read
    // LocalConfiguration so this composable recomposes on rotation, then
    // poke the TerminalView to re-layout — that triggers onSizeChanged →
    // updateSize → emulator/pty resize, so Claude Code (and any other TUI)
    // sees the new grid.
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        terminalViewRef.value?.requestLayout()
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = HeaderAccent,
                        )
                    }
                },
                title = {
                    val waitAlpha = if (paneState == "waiting") {
                        val transition = rememberInfiniteTransition(label = "headerWaitPulse")
                        transition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.35f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 750),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "headerWaitAlpha",
                        ).value
                    } else 1f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.graphicsLayer { alpha = waitAlpha },
                    ) {
                        if (paneState == "working") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = HeaderAccent,
                                trackColor = HeaderAccent.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = headerTitle.uppercase(),
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = HeaderAccent,
                                letterSpacing = 0.6.sp,
                            ),
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { swipeInputActive = !swipeInputActive }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Swipe input",
                            tint = if (swipeInputActive) HeaderAccent else Color.Gray,
                        )
                    }
                    IconButton(onClick = {
                        val natural = localGrid
                        if (natural != null && natural.cols > 0 && natural.rows > 0) {
                            scope.launch {
                                runCatching { ptySocket.forceResize(natural.cols, natural.rows) }
                            }
                        }
                    }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Reformat",
                            tint = HeaderAccent,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(bgComposeColor),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize(),
                factory = { context ->
                    val view = TerminalView(context, null)
                    val currentTextSize = intArrayOf(30)
                    view.setTextSize(currentTextSize[0])
                    view.setTypeface(
                        runCatching { Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf") }
                            .getOrDefault(Typeface.MONOSPACE),
                    )
                    runCatching { Typeface.createFromAsset(context.assets, "fonts/Iosevka-Regular.ttf") }
                        .getOrNull()?.let { view.setFallbackTypeface(it) }
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                    var lastSent: Pair<Int, Int>? = null
                    view.setOnTerminalGridSizeChangedListener { cols, rows ->
                        // If this callback was triggered by us applying a
                        // server-pushed size, do NOT update localGrid (it
                        // must stay at the view's natural cols/rows so the
                        // Reformat button re-asserts the real size) and do
                        // NOT echo the dims back to the server (the very
                        // client that just pinned them would be undone).
                        if (applyingServerSize.get()) return@setOnTerminalGridSizeChangedListener
                        val next = cols to rows
                        localGrid = AndroidGridDims(cols = cols, rows = rows)
                        if (next != lastSent) {
                            lastSent = next
                            scope.launch { runCatching { ptySocket.resize(cols, rows) } }
                        }
                    }
                    view.setTerminalViewClient(object : TerminalViewClient {
                        // Pinch-to-zoom: TerminalView feeds us the cumulative
                        // scale factor since gesture start. Once it leaves a
                        // small deadzone we step the font size by one px and
                        // return 1f to reset the baseline so the next step
                        // requires another full deadzone of pinching.
                        override fun onScale(scale: Float): Float {
                            if (scale < 0.95f || scale > 1.05f) {
                                val step = if (scale > 1f) 1 else -1
                                val next = (currentTextSize[0] + step).coerceIn(14, 96)
                                if (next != currentTextSize[0]) {
                                    currentTextSize[0] = next
                                    view.setTextSize(next)
                                }
                                return 1f
                            }
                            return scale
                        }
                        override fun onSingleTapUp(e: android.view.MotionEvent?) {
                            view.requestFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                        }
                        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                        override fun shouldEnforceCharBasedInput(): Boolean = false
                        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                        override fun isTerminalViewSelected(): Boolean = true
                        override fun copyModeChanged(copyMode: Boolean) = Unit
                        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: com.termux.terminal.TerminalSession?): Boolean = false
                        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
                        override fun onLongPress(event: android.view.MotionEvent?): Boolean = false
                        override fun readControlKey(): Boolean = ctrlSticky.value
                        override fun readAltKey(): Boolean = false
                        override fun readShiftKey(): Boolean = shiftSticky.value
                        override fun readFnKey(): Boolean = false
                        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession?): Boolean = false
                        override fun onEmulatorSet() = Unit
                        override fun logError(tag: String?, message: String?) = Unit
                        override fun logWarn(tag: String?, message: String?) = Unit
                        override fun logInfo(tag: String?, message: String?) = Unit
                        override fun logDebug(tag: String?, message: String?) = Unit
                        override fun logVerbose(tag: String?, message: String?) = Unit
                        override fun logStackTraceWithMessage(tag: String?, message: String?, e: java.lang.Exception?) = Unit
                        override fun logStackTrace(tag: String?, e: java.lang.Exception?) = Unit
                    })
                    view.attachSession(session)
                    try {
                        val field = TerminalView::class.java.getDeclaredField("mEmulator")
                        field.isAccessible = true
                        field.set(view, emulator)
                    } catch (_: Throwable) {
                    }
                    applyTerminalColors(view, emulator, terminalPalette)
                    terminalViewRef.value = view
                    view
                },
                update = { view ->
                    applyTerminalColors(view, emulator, terminalPalette)
                    view.onScreenUpdated()
                },
            )

            }

            if (swipeInputActive) {
                SwipeInputBar(
                    text = swipeText,
                    onTextChange = { swipeText = it },
                    onSubmit = {
                        if (swipeText.isNotEmpty()) {
                            val bytes = (swipeText + "\r").toByteArray(Charsets.UTF_8)
                            scope.launch { ptySocket.send(bytes) }
                            swipeText = ""
                        }
                    },
                    palette = terminalPalette,
                )
            }

            ImeHelperToolbar(
                ctrlSticky = ctrlSticky.value,
                onCtrlToggle = { ctrlSticky.value = !ctrlSticky.value },
                shiftSticky = shiftSticky.value,
                onShiftToggle = { shiftSticky.value = !shiftSticky.value },
                onSend = { bytes ->
                    scope.launch { ptySocket.send(bytes) }
                },
                palette = terminalPalette,
            )
        }
    }
}

/**
 * Above-keyboard toolbar that synthesises escape sequences for the keys
 * that phone keyboards don't ship: Esc, Ctrl (sticky), Shift (sticky),
 * Tab, and the four arrows. Ctrl and Shift are modelled as sticky
 * modifiers — tap to arm, tap again to unarm — which the next
 * [TerminalView] key event can read via [TerminalViewClient.readControlKey]
 * and [TerminalViewClient.readShiftKey].
 *
 * @param palette the resolved theme palette for deriving toolbar colours.
 */
@Composable
private fun ImeHelperToolbar(
    ctrlSticky: Boolean,
    onCtrlToggle: () -> Unit,
    shiftSticky: Boolean,
    onShiftToggle: () -> Unit,
    onSend: (ByteArray) -> Unit,
    palette: ResolvedPalette,
) {
    val toolbarBg = Color(palette.surface.sunken)
    val dividerColor = Color(palette.border.subtle)
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(toolbarBg)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(modifier = Modifier.width(4.dp))
        ToolbarKey("Ctrl", sticky = true, active = ctrlSticky, palette = palette) { onCtrlToggle() }
        ToolbarKey("Shift", sticky = true, active = shiftSticky, palette = palette) { onShiftToggle() }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = 10.dp)
                .background(dividerColor),
        )
        ToolbarKey("Enter", palette = palette) { onSend(byteArrayOf(0x0d)) }
        ToolbarKey("Esc", palette = palette) { onSend(byteArrayOf(0x1b)) }
        ToolbarKey("Tab", palette = palette) {
            if (shiftSticky) {
                onSend("\u001b[Z".toByteArray(Charsets.UTF_8))
                onShiftToggle()
            } else {
                onSend(byteArrayOf(0x09))
            }
        }
        ToolbarKey("↑", palette = palette) { onSend("\u001b[A".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("↓", palette = palette) { onSend("\u001b[B".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("→", palette = palette) { onSend("\u001b[C".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("←", palette = palette) { onSend("\u001b[D".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("Home", palette = palette) { onSend("\u001b[H".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("End", palette = palette) { onSend("\u001b[F".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("PgUp", palette = palette) { onSend("\u001b[5~".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("PgDn", palette = palette) { onSend("\u001b[6~".toByteArray(Charsets.UTF_8)) }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

/**
 * Visible text field for gesture-typing (Android swipe-to-write). Sits
 * between the terminal and the IME toolbar when active. A standard TextField
 * allows the keyboard to offer swipe suggestions, unlike Termux's raw key
 * capture in TerminalView.
 *
 * @param palette the resolved theme palette for deriving input bar colours.
 */
@Composable
private fun SwipeInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    palette: ResolvedPalette,
) {
    val barBg = Color(palette.surface.raised)
    val inputBg = Color(palette.surface.sunken)
    val accentColor = Color(palette.accent.primary)
    val textColor = Color(palette.text.primary)
    val placeholderColor = Color(palette.text.tertiary)
    val onAccentColor = Color(palette.accent.onPrimary)
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    "Type or swipe here\u2026",
                    color = placeholderColor,
                    fontSize = 14.sp,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = textColor,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = inputBg,
                unfocusedContainerColor = inputBg,
                cursorColor = accentColor,
                focusedIndicatorColor = accentColor,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(6.dp),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accentColor)
                .clickable { onSubmit() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "\u23CE",
                fontSize = 18.sp,
                color = onAccentColor,
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * Paint the terminal with the resolved theme palette. Mutates the emulator's
 * default foreground/background and cursor colour indices (what SGR 0 /
 * "default" resolves to) so existing rows repaint on the next
 * onScreenUpdated(), and sets the view's own background so the letterbox
 * around the text grid matches.
 *
 * @param view     the Termux [TerminalView] to update.
 * @param emulator the [TerminalEmulator] whose colour table is mutated.
 * @param palette  the fully resolved semantic palette for the terminal section.
 */
private fun applyTerminalColors(
    view: TerminalView,
    emulator: TerminalEmulator,
    palette: ResolvedPalette,
) {
    val fg = palette.terminal.fg.toInt()
    val bg = palette.terminal.bg.toInt()
    val cursor = palette.terminal.cursor.toInt()
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cursor
    view.setBackgroundColor(bg)
}

/**
 * A single key button in the [ImeHelperToolbar].
 *
 * Renders as a rounded-rectangle pill with haptic feedback on tap. Sticky
 * keys (Ctrl, Shift) toggle between armed (blue) and unarmed states.
 *
 * @param label the text displayed on the key.
 * @param sticky true for modifier keys that toggle on/off.
 * @param active true when a sticky modifier is currently armed.
 * @param palette the resolved theme palette for deriving key colours.
 * @param onClick callback invoked when the key is tapped.
 */
@Composable
private fun ToolbarKey(
    label: String,
    sticky: Boolean = false,
    active: Boolean = false,
    palette: ResolvedPalette,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(6.dp)
    val accentColor = Color(palette.accent.primary)
    val keyBg = Color(palette.surface.raised)
    val stickyBg = Color(palette.surface.sunken)
    val borderColor = Color(palette.border.`default`)
    val bg = when {
        sticky && active -> accentColor
        sticky -> stickyBg
        else -> keyBg
    }
    val textColor = when {
        sticky && active -> Color(palette.accent.onPrimary)
        sticky -> Color(palette.text.secondary)
        else -> Color(palette.text.primary)
    }
    val baseModifier = Modifier
        .padding(vertical = 6.dp)
        .fillMaxHeight()
        .clip(shape)
        .background(bg, shape)
    val borderedModifier = if (sticky && !active) {
        baseModifier.border(1.dp, borderColor, shape)
    } else {
        baseModifier
    }
    Box(
        modifier = borderedModifier
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = textColor,
                fontWeight = if (sticky) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}
