/**
 * Terminal emulator screen for the Termtastic Android app.
 *
 * Hosts a full xterm-compatible terminal session rendered by a Termux
 * [com.termux.view.TerminalView]. Composes the four supporting helpers
 * extracted from this file:
 *  - [TerminalEmulatorHolder] — the externally-fed [TerminalSession]
 *    subclass + companion [TerminalEmulator] factory.
 *  - [TerminalThemeResolver] — palette resolution + emulator colour
 *    application.
 *  - [ImeHelperToolbar] — sticky modifier toolbar above the soft keyboard.
 *  - [SwipeInputBar] — gesture-typing input.
 *
 * Navigated to from [TreeScreen] when the user taps a terminal leaf
 * pane.
 *
 * @see TreeScreen
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 */
package se.soderbjorn.termtastic.android.ui

import se.soderbjorn.darkness.core.*

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.termtastic.WindowConfig
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import se.soderbjorn.termtastic.android.net.ConnectionHolder

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
 * Searches the [WindowConfig] pane tree for a leaf whose session ID
 * matches [sessionId] and returns its display title.
 */
private fun findLeafTitle(config: WindowConfig?, sessionId: String): String? {
    if (config == null) return null
    for (tab in config.tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.title }
    }
    return null
}

/**
 * A single-session terminal screen.
 *
 * @param sessionId the PTY session identifier to connect to on the server.
 * @param onBack callback invoked when the user navigates back to [TreeScreen].
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

    val emulatorDispatcher = remember {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

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

    var localGrid by remember(sessionId) {
        mutableStateOf<AndroidGridDims?>(null)
    }

    val applyingServerSize = remember(sessionId) { AtomicBoolean(false) }

    val terminalPalette = rememberTerminalPalette(client, sessionId)
    val bgComposeColor = Color(terminalPalette.terminal.bg)

    val session = remember(sessionId) {
        createExternalTerminalSession(
            scope = scope,
            emulatorDispatcher = emulatorDispatcher,
            terminalViewRef = terminalViewRef,
            applyingServerSize = applyingServerSize,
            ptySocket = ptySocket,
        )
    }

    val emulator = remember(sessionId) { createSyncedEmulator(session) }

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

    LaunchedEffect(sessionId) {
        ptySocket.ptySize.collect { size ->
            if (size == null) return@collect
            val (cols, rows) = size
            val view = terminalViewRef.value ?: return@collect
            val renderer = view.mRenderer ?: return@collect
            val cellW = renderer.fontWidth.toInt().coerceAtLeast(1)
            val cellH = renderer.fontLineSpacing.coerceAtLeast(1)
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
                    containerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
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
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
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
                            if (applyingServerSize.get()) return@setOnTerminalGridSizeChangedListener
                            val next = cols to rows
                            localGrid = AndroidGridDims(cols = cols, rows = rows)
                            if (next != lastSent) {
                                lastSent = next
                                scope.launch { runCatching { ptySocket.resize(cols, rows) } }
                            }
                        }
                        view.setTerminalViewClient(object : TerminalViewClient {
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
