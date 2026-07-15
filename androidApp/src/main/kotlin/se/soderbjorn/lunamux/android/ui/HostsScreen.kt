/**
 * Hosts/servers list screen for the Lunamux Android app.
 *
 * This is the app's landing screen. It displays the user's saved server
 * entries from the shared [se.soderbjorn.lunamux.client.storage.LocalRepository]
 * (via [se.soderbjorn.lunamux.android.data.AppLocalRepository]) and provides
 * add/edit/delete dialogs. Tapping a host initiates a WebSocket connection via
 * [se.soderbjorn.lunamux.android.net.ConnectionHolder] and, on success,
 * navigates to the [TreeScreen] overview.
 *
 * The screen is also the QR pairing entry point: the top-bar scanner (and the
 * `lunamux://pair` deep link relayed through
 * [se.soderbjorn.lunamux.android.PendingPairingUri]) parses a
 * [se.soderbjorn.lunamux.PairingPayload], saves or updates the host entry,
 * and connects immediately — scan → connected, with no approval dialog.
 *
 * @see se.soderbjorn.lunamux.android.data.AppLocalRepository
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 * @see se.soderbjorn.lunamux.PairingPayload
 * @see TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.HostPort
import se.soderbjorn.lunamux.PairingPayload
import se.soderbjorn.lunamux.SERVER_TLS_PORT
import se.soderbjorn.lunamux.android.PendingPairingUri
import se.soderbjorn.lunamux.android.data.AppLocalRepository
import se.soderbjorn.lunamux.client.CandidateConnector
import se.soderbjorn.lunamux.client.HostEntry
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.android.net.NewsUpdatesController
import se.soderbjorn.lunamux.client.ServerUrl
import se.soderbjorn.lunamux.client.demo.DEMO_HOST
import se.soderbjorn.lunamux.client.viewmodel.ConnectFailureCopy

/**
 * Sentinel id used in the `connectingId` state for the built-in demo row —
 * it is not a persisted [HostEntry], so it needs its own marker to drive
 * the row's progress spinner and to disable the rest of the list while the
 * (instant) demo connection is being set up.
 */
private const val DEMO_ROW_ID = "builtin-demo"

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
 * Outcome of scanning a pairing QR for a server already in the host list —
 * what [RePairDialog] reports.
 *
 * @property entry the entry as saved, with the scanned addresses merged in.
 * @property added how many addresses the scan actually contributed; 0 when the
 *   code carried nothing the entry did not already know.
 */
private data class RePairResult(val entry: HostEntry, val added: Int)

/**
 * This endpoint the way a user would type it: the `:port` suffix appears only
 * when the port is *not* the one every Lunamux server listens on.
 *
 * The single formatting rule for every address this app shows — list rows, the
 * address picker, the connecting dialog, the cert-changed warning, and the host
 * editor. `:8443` on every row distinguishes nothing, so hiding it turns a
 * visible port into a signal ("this one is unusual") rather than decoration,
 * and makes the same address read identically wherever it appears.
 *
 * @return `host`, `host:port`, `[v6]`, or `[v6]:port`.
 * @see se.soderbjorn.lunamux.HostPort.toCandidateString
 */
private fun HostPort.display(): String = toCandidateString(defaultPort = SERVER_TLS_PORT)

/**
 * Landing screen showing the user's saved server hosts.
 *
 * Provides add, edit, and delete functionality for host entries. Tapping a
 * host initiates a WebSocket connection to the Lunamux server; on success
 * the [onConnected] callback navigates to the tree overview.
 *
 * Shows a "Waiting for approval..." label when the server has a pending
 * device-approval dialog for this client.
 *
 * @param applicationContext Android application context, forwarded to the
 *   [se.soderbjorn.lunamux.android.net.NewsUpdatesController].
 * @param onConnected callback invoked after a successful connection to a host.
 * @param onOpenNews callback invoked when the toolbar bell is tapped; opens the
 *   "News & Updates" screen.
 * @see se.soderbjorn.lunamux.android.data.AppLocalRepository
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    applicationContext: Context,
    onConnected: () -> Unit,
    onOpenNews: () -> Unit,
) {
    val repository = remember { AppLocalRepository.instance }
    val scope = rememberCoroutineScope()

    // Null while local_state.json is still hydrating (render nothing rather than
    // flashing the empty state), then the persisted host list.
    val localState by repository.state.collectAsState()
    val hosts = localState?.hosts
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    // Set when a scan matched a host we already had; drives RePairDialog.
    var rePairResult by remember { mutableStateOf<RePairResult?>(null) }
    var deleteTarget by remember { mutableStateOf<HostEntry?>(null) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    // Triggered when ConnectionHolder.connect throws a pin-mismatch — the
    // server's cert no longer matches what we pinned. Re-pair clears the
    // stored pin and runs first-connect again; Forget removes the host.
    var pinMismatchEntry by remember { mutableStateOf<HostEntry?>(null) }
    val pendingApproval by (ConnectionHolder.pendingApproval
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // The in-flight connect, surfaced in ConnectingDialog rather than on the
    // row: a walk can spend 12s on each dead address, which a row-sized
    // spinner cannot explain. Null for the demo, which is instant and needs no
    // dialog. `connectingWalk` is the list being tried, so the dialog can say
    // "address 2 of 3"; `connectJob` backs Cancel.
    var connectingEntry by remember { mutableStateOf<HostEntry?>(null) }
    var connectingWalk by remember { mutableStateOf<List<HostPort>>(emptyList()) }
    var connectingAddress by remember { mutableStateOf<HostPort?>(null) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    // Set when a long-press asks which address to use; only offered for entries
    // that actually have a choice.
    var addressPickerTarget by remember { mutableStateOf<HostEntry?>(null) }

    // Shared news/update checker — drives the toolbar bell's visibility (shown
    // only when there is news or an available update).
    val newsUpdatesVm = remember { NewsUpdatesController.ensureStarted(applicationContext) }
    val newsUpdatesState by newsUpdatesVm.stateFlow.collectAsState()

    // Connect to a saved host entry: walks its addresses in order (a paired
    // entry carries every address the server advertised) and, on success,
    // persists the winner in one write — winning address promoted to the head
    // of the list, TOFU pin captured if this was a pinless first connect,
    // spent pairing token cleared. Failures produce connectivity-aware
    // messages instead of a generic timeout.
    // Connect using an explicit address instead of the usual walk — the
    // long-press picker's path. `null` means "walk the entry's addresses in
    // order", which is what tapping the row does.
    val clearConnecting: () -> Unit = {
        connectingId = null
        connectingEntry = null
        connectingWalk = emptyList()
        connectingAddress = null
        connectJob = null
    }
    val connectToEntryUsing: (HostEntry, HostPort?) -> Unit = { entry, only ->
        connectingId = entry.id
        connectingEntry = entry
        connectingWalk = only?.let { listOf(it) } ?: entry.addresses
        connectingAddress = null
        connectJob = scope.launch {
            runCatching {
                val token = repository.getOrCreateAuthToken()
                val connection = ConnectionHolder.connectMulti(
                    addresses = connectingWalk,
                    authToken = token,
                    pinnedFingerprintHex = entry.pinnedFingerprintHex,
                    pairingToken = entry.pairingToken,
                    // Name what we're waiting on: a dead address costs 12s of
                    // otherwise mute spinner, which reads as a hang.
                    onAttempt = { connectingAddress = it },
                )
                val pin = entry.pinnedFingerprintHex
                    ?: connection.client.observedFingerprint.value
                // The address that answered leads the walk next time. Note this
                // promotes into `entry.addresses` even on the `only` path, so a
                // picked address is remembered like any other winner.
                val updated = entry.promoting(connection.endpoint).copy(
                    pinnedFingerprintHex = pin,
                    pairingToken = null,
                )
                if (updated != entry) repository.updateHost(updated)
            }.onSuccess {
                clearConnecting()
                onConnected()
            }.onFailure { e ->
                clearConnecting()
                when {
                    // The user pressed Cancel; they already know. Saying
                    // "couldn't reach the Mac" would blame the network for
                    // something they did on purpose.
                    e is CancellationException -> Unit
                    ConnectionHolder.isPinMismatch(e) -> pinMismatchEntry = entry
                    else -> {
                        // Classification and copy are shared with iOS — see
                        // ConnectFailureCopy. The snackbar has no title field,
                        // so render both parts as one line.
                        val msg = ConnectFailureCopy.classify(
                            throwable = e,
                            rawMessage = e.message,
                            deviceNoun = "phone",
                        ).oneLine
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = msg,
                                actionLabel = "Dismiss",
                                duration = SnackbarDuration.Long,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Tapping a host: walk its addresses in order, asking the user nothing. */
    val connectToEntry: (HostEntry) -> Unit = { entry -> connectToEntryUsing(entry, null) }

    // Handle a scanned QR / deep-linked pairing URI: parse, dedupe against
    // existing entries (same server = same cert fingerprint or overlapping
    // endpoints — the re-pair path also refreshes a rotated cert's pin), save,
    // and connect straight away.
    val handlePairingUri: (String) -> Unit = { uri ->
        val payload = PairingPayload.parse(uri)
        if (payload == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "That doesn't look like a Lunamux pairing code",
                    actionLabel = "Dismiss",
                    duration = SnackbarDuration.Long,
                )
            }
        } else {
            scope.launch {
                // Identity is the TLS cert and nothing else. An address is not
                // a machine: 192.168.1.5 is whoever's Wi-Fi you are on, so
                // matching on endpoint overlap would merge a colleague's Mac
                // into your entry and repin it. The cert can't collide, is
                // generated once with a 10-year life (CertStore), and follows
                // the machine between networks — which is exactly the case
                // re-pairing exists for. A manually-added entry matches too
                // once it has captured its TOFU pin, since that is the same
                // cert. The cost is that a manual entry that has never
                // connected has no pin and forks a duplicate; that entry has
                // no history worth keeping anyway.
                val existing = repository.ensureLoaded().hosts.firstOrNull { host ->
                    host.pinnedFingerprintHex == payload.fingerprintHex
                }
                if (existing != null) {
                    // Augment, don't replace: re-pairing at home must not cost
                    // the entry its work addresses. The scanned set leads, so
                    // the network the user is standing on is tried first. See
                    // mergeAddresses.
                    val merged = HostEntry.mergeAddresses(payload.candidates, existing.addresses)
                    // Count what actually landed, not what the QR offered: the
                    // merge caps its result, so some of the fresh addresses may
                    // not have made it in.
                    val added = merged.count { it !in existing.addresses }
                    val updated = existing.copy(
                        addresses = merged,
                        pinnedFingerprintHex = payload.fingerprintHex,
                        pairingToken = payload.token,
                    )
                    repository.updateHost(updated)
                    // Deliberately no auto-connect here, unlike a first pairing.
                    // Re-pairing a known host changes nothing you can see — same
                    // label, same row — and connecting navigates away before any
                    // confirmation could be read, so the one moment the user
                    // could learn what happened would be spent. The dialog is
                    // that moment, and it offers the connect the scan implied.
                    rePairResult = RePairResult(updated, added)
                } else {
                    val entry = repository.addPairedHost(
                        label = payload.serverName ?: "Paired server",
                        addresses = payload.candidates,
                        pinnedFingerprintHex = payload.fingerprintHex,
                        pairingToken = payload.token,
                    )
                    // A brand-new host keeps the scan → connected promise: the
                    // new row in the list is its own confirmation.
                    connectToEntry(entry)
                }
            }
        }
    }

    // QR scanner: the Google code scanner supplies its own UI and runs the
    // camera inside a Play Services process, so there is no CAMERA permission
    // and no runtime prompt to handle here — startScan() goes straight to the
    // viewfinder. It needs Play Services, this APK's only such dependency;
    // where that is missing or the module can't be fetched, the failure
    // listener explains it and manual add-host still works.
    val scanner = remember {
        GmsBarcodeScanning.getClient(
            applicationContext,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val startScan: () -> Unit = {
        scanner.startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(handlePairingUri) }
            // Cancellation is just the user backing out; say nothing.
            .addOnCanceledListener { }
            .addOnFailureListener { e ->
                Log.w("HostsScreen", "code scanner unavailable", e)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Couldn't open the scanner. Add the server's address " +
                            "manually with +, or check Google Play services.",
                        actionLabel = "Dismiss",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
    }

    // Pairing deep link (system camera / browser → lunamux://pair): the
    // activity posts it into PendingPairingUri; consume() clears the slot so
    // recompositions and config changes can't pair twice.
    val pendingPairing by PendingPairingUri.uri.collectAsState()
    LaunchedEffect(pendingPairing) {
        if (pendingPairing != null) {
            PendingPairingUri.consume()?.let(handlePairingUri)
        }
    }

    // Connect to the built-in demo "server": the magic demo host makes the
    // shared client run against its in-process simulation, so this never
    // touches the network and completes instantly. No auth, no TLS pin, no
    // saved host entry.
    val connectDemo: () -> Unit = {
        connectingId = DEMO_ROW_ID
        scope.launch {
            runCatching {
                ConnectionHolder.connect(
                    serverUrl = ServerUrl(host = DEMO_HOST, port = 0),
                    authToken = "demo",
                )
            }.onSuccess {
                connectingId = null
                onConnected()
            }.onFailure { e ->
                connectingId = null
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = e.message ?: "Demo failed to start",
                        actionLabel = "Dismiss",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
        }
    }

    // Collapsing large title — expands to a tall "Servers" header at rest and
    // shrinks to an inline bar as the list scrolls, mirroring the iOS hosts
    // screen. The behaviour is wired to the Scaffold via nestedScroll below.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // This screen renders before any server connection exists, so no
    // server-driven theme has been fetched yet — but the sidebar palette falls
    // back to the default theme, so we use its black background and themed text
    // here (rather than the Material 3 surface) to match the Sessions screen.
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SidebarBackground,
        // Themed, like every other surface on this screen. The stock Snackbar
        // paints itself from the Material colour scheme, which this app does
        // not theme — so it arrived in default lavender/purple against the
        // sidebar palette.
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SidebarSurface,
                    contentColor = SidebarTextPrimary,
                    actionColor = SidebarAccent,
                )
            }
        },
        topBar = {
            LargeTopAppBar(
                title = { Text("Servers") },
                actions = {
                    NewsBellButton(
                        onClick = onOpenNews,
                        shouldPulse = newsUpdatesState.hasNews,
                        muted = !newsUpdatesState.hasContent,
                    )
                    IconButton(onClick = startScan) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = "Scan QR code",
                            tint = SidebarAccent,
                        )
                    }
                    IconButton(onClick = { editTarget = EditTarget.Add }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add Server",
                            tint = SidebarAccent,
                        )
                    }
                    // Shared info menu → support forum, website, legal pages.
                    // Mirrored in the Sessions top bar so both primary screens
                    // expose the same links from the same place.
                    AboutMenu()
                },
                // Keep the bar the sidebar colour in both expanded and collapsed
                // states so the title region never flashes the default surface
                // tint as it scrolls — matching the Sessions screen's top bar.
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = SidebarBackground,
                    scrolledContainerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val list = hosts
                when {
                    list == null -> Unit // first composition
                    list.isEmpty() -> EmptyState(
                        onScan = startScan,
                        onAdd = { editTarget = EditTarget.Add },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { entry ->
                            HostRow(
                                entry = entry,
                                enabled = connectingId == null,
                                onConnect = { connectToEntry(entry) },
                                onPickAddress = { addressPickerTarget = entry },
                                onEdit = { editTarget = EditTarget.Edit(entry) },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
            // Discreet, always-visible entry into the built-in demo, pinned to
            // the bottom of the screen below both the empty state and the host
            // list so it never competes with the user's own servers.
            DemoFooter(
                connecting = connectingId == DEMO_ROW_ID,
                enabled = connectingId == null,
                onConnect = connectDemo,
            )
        }
    }

    connectingEntry?.let { entry ->
        ConnectingDialog(
            entry = entry,
            walk = connectingWalk,
            current = connectingAddress,
            pendingApproval = pendingApproval,
            // Cancelling the job unwinds CandidateConnector, which tears down
            // the in-flight attempt; the failure path treats it as silent.
            onCancel = { connectJob?.cancel() },
        )
    }

    editTarget?.let { target ->
        val initial = (target as? EditTarget.Edit)?.entry
        HostEditDialog(
            initial = initial,
            onDismiss = { editTarget = null },
            onSave = { label, addresses ->
                scope.launch {
                    if (initial == null) {
                        repository.addHost(label, addresses)
                    } else {
                        repository.updateHost(initial.copy(label = label, addresses = addresses))
                    }
                    editTarget = null
                }
            },
        )
    }

    addressPickerTarget?.let { entry ->
        AddressPickerDialog(
            entry = entry,
            onDismiss = { addressPickerTarget = null },
            onPick = { address ->
                addressPickerTarget = null
                connectToEntryUsing(entry, address)
            },
        )
    }

    rePairResult?.let { result ->
        RePairDialog(
            result = result,
            onDismiss = { rePairResult = null },
            onConnect = {
                rePairResult = null
                connectToEntry(result.entry)
            },
        )
    }

    deleteTarget?.let { entry ->
        DeleteHostDialog(
            entry = entry,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    repository.deleteHost(entry.id)
                    deleteTarget = null
                }
            },
        )
    }

    pinMismatchEntry?.let { entry ->
        PinMismatchDialog(
            entry = entry,
            onDismiss = { pinMismatchEntry = null },
            onRepair = {
                scope.launch {
                    // Clear the stored pin so the next tap runs first-connect
                    // (capture mode + server ApprovalDialog) again.
                    repository.updateHost(entry.copy(pinnedFingerprintHex = null))
                    pinMismatchEntry = null
                }
            },
            onForget = {
                scope.launch {
                    repository.deleteHost(entry.id)
                    pinMismatchEntry = null
                }
            },
        )
    }
}

/**
 * Placeholder UI shown when no hosts have been saved yet.
 *
 * Pairing by QR is the primary path (scan → connected, no typing, no
 * approval dialog); manual entry is demoted to a secondary action. The entry
 * point into the built-in demo lives in the bottom-pinned [DemoFooter], which
 * is shown in this state too, so first-time users can still explore the app
 * without owning a server.
 *
 * @param onScan callback invoked when the "Scan QR code" button is tapped.
 * @param onAdd callback invoked when the secondary "Add manually" action is tapped.
 */
@Composable
private fun EmptyState(
    onScan: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = SidebarTextSecondary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No servers yet",
            style = MaterialTheme.typography.titleMedium,
            color = SidebarTextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "On your Mac, in Lunamux, go to \"Settings > Server & Security… > Devices\" " +
                "and tick \"Allow connections from other devices\" and then press " +
                "\"Pair via QR Code\" - and then scan the code here.",
            style = MaterialTheme.typography.bodyMedium,
            color = SidebarTextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onScan,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SidebarAccent,
                contentColor = SidebarBackground,
            ),
        ) {
            Text("Scan QR code")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onAdd,
            colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
        ) {
            Text("Add manually")
        }
    }
}

/**
 * Discreet, bottom-pinned entry into the built-in demo. Tapping it connects to
 * the in-process demo simulation (see the magic [DEMO_HOST] handling in the
 * shared client) — instant, offline, and stateless, so it needs no edit/delete
 * affordances.
 *
 * Rendered as a single muted row beneath a hairline divider, staying out of the
 * way of the user's own servers. External links (support forum, website, legal
 * pages) now live in the top bar's [AboutMenu] rather than here, keeping this
 * footer to the one demo affordance.
 *
 * @param connecting true while the demo connection is being set up.
 * @param enabled false while another host is connecting.
 * @param onConnect callback invoked when the footer is tapped.
 * @see AboutMenu
 */
@Composable
private fun DemoFooter(
    connecting: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = SidebarTextSecondary.copy(alpha = 0.2f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Live demo — the footer's sole affordance.
            Row(
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onConnect)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Try the live demo, no server needed"
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = SidebarTextSecondary,
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SidebarTextSecondary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Try the live demo",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SidebarTextSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A single row in the hosts list showing the label, its preferred address, and
 * an overflow menu holding every row action.
 *
 * Deliberately carries no connect progress: a walk can spend 12s on each dead
 * address, and a row-sized spinner cannot say which address it is on or how
 * long is left. That belongs to [ConnectingDialog], which covers this row
 * anyway while it is up.
 *
 * @param entry the host entry to render.
 * @param enabled false to disable tap interactions (while another host is connecting).
 * @param onConnect callback invoked when the row is tapped to connect.
 * @param onPickAddress invoked from the overflow menu to choose one address to
 *   connect with. Offered for every host, including one-address ones: an item
 *   that comes and goes by row reads as a bug, and the picker is also the only
 *   place the full address list can be seen before committing to a connect.
 * @param onEdit callback invoked from the overflow menu to edit this entry.
 * @param onDelete callback invoked from the overflow menu to delete this entry.
 * @see ConnectingDialog
 */
@Composable
private fun HostRow(
    entry: HostEntry,
    enabled: Boolean,
    onConnect: () -> Unit,
    onPickAddress: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tap connects, and that is all the row does. The address picker
            // used to hang off a long-press, which put the one action with no
            // other route to it behind an invisible gesture; it lives in the
            // overflow menu now, like everything else.
            .clickable(enabled = enabled, onClick = onConnect)
            .semantics {
                role = Role.Button
                contentDescription = "${entry.label}, ${entry.primary?.display() ?: "no address"}"
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
            // The preferred address — the one a tap tries first. Which address
            // is being tried *during* a connect is ConnectingDialog's job.
            Text(
                text = entry.primary?.display() ?: "No address",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SidebarTextSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Server options",
                    tint = SidebarTextSecondary,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Connect using…") },
                    onClick = {
                        menuOpen = false
                        onPickAddress()
                    },
                )
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

/**
 * Modal progress dialog for an in-flight connect.
 *
 * A connect is not a moment: the walk tries each address in turn and spends up
 * to [CandidateConnector.DEFAULT_PER_CANDIDATE_TIMEOUT_MS] on every one that
 * does not answer, so a host with three stale addresses is over half a minute
 * of waiting. A row-sized spinner cannot carry that \u2014 it cannot say which
 * address is being tried, how many are left, or how long this one has before
 * the walk moves on. This dialog says all three, and offers a way out that
 * isn't force-quitting the app.
 *
 * Dismissed by the caller the moment the connect resolves: on success the
 * screen navigates to the tree, on failure a snackbar or the cert-changed
 * dialog takes over.
 *
 * @param entry the host being connected to; named in the title.
 * @param walk the addresses being tried, in order \u2014 the basis for "address 2
 *   of 3". A one-element walk (the long-press picker's path) drops the
 *   counter, since there is no sequence to place it in.
 * @param current the address being tried right now, or null before the first
 *   attempt is reported.
 * @param pendingApproval true once a server has been reached and is showing
 *   its device-approval dialog. Phase 1 is over by then, so the per-address
 *   countdown is meaningless and the bar goes indeterminate.
 * @param onCancel invoked to abort the connect.
 * @see CandidateConnector.connectFirstReachable
 */
@Composable
private fun ConnectingDialog(
    entry: HostEntry,
    walk: List<HostPort>,
    current: HostPort?,
    pendingApproval: Boolean,
    onCancel: () -> Unit,
) {
    val budgetSeconds = (CandidateConnector.DEFAULT_PER_CANDIDATE_TIMEOUT_MS / 1000).toInt()
    // Steps once a second rather than sweeping smoothly. The bar's whole job is
    // to show time running out on this address, and a ticking bar reads as
    // elapsed seconds where a smooth one reads as generic "busy" motion. Keyed
    // on `current`, so restarting is also the signal that the walk moved on —
    // and at 1fps this dialog costs nothing to keep on screen.
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(current) {
        elapsed = 0
        if (current != null) {
            while (elapsed < budgetSeconds) {
                delay(1_000)
                elapsed++
            }
        }
    }
    val index = walk.indexOf(current)

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Connecting to ${entry.label}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    pendingApproval -> {
                        Text("Waiting for approval\u2026", color = SidebarTextPrimary)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = SidebarAccent,
                            trackColor = SidebarProgressTrack,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Approve this device in Lunamux on your Mac to finish connecting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SidebarTextSecondary,
                        )
                    }
                    current == null -> {
                        Text("Starting\u2026", color = SidebarTextPrimary)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = SidebarAccent,
                            trackColor = SidebarProgressTrack,
                        )
                    }
                    else -> {
                        Text(
                            "Trying ${current.display()}",
                            color = SidebarTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { elapsed / budgetSeconds.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = SidebarAccent,
                            trackColor = SidebarProgressTrack,
                            // Material 3 draws a dot at the track end by
                            // default. It marks "100%" on a bar whose end is
                            // already obvious, and here it reads as a stray
                            // artefact sitting past the track.
                            drawStopIndicator = {},
                        )
                        if (walk.size > 1 && index >= 0) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Address ${index + 1} of ${walk.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SidebarTextSecondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Long-press escape hatch: connect to [entry] using one specific address
 * rather than walking them.
 *
 * Tapping a host is the normal path and asks nothing — it tries the preferred
 * address, falls back through the rest, and promotes whatever answered. That
 * walk costs up to 12s per dead address, though, and the phone cannot know
 * which network it is on. When the user does know, this skips straight to the
 * right one. Deliberately not the default: it is an override for the case the
 * automatic path handles slowly, not a question worth asking on every connect.
 *
 * Only reachable for entries with more than one address, so it never offers a
 * choice of one.
 *
 * @param entry the host to connect to.
 * @param onDismiss invoked when the user backs out without picking.
 * @param onPick invoked with the chosen endpoint.
 */
@Composable
private fun AddressPickerDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onPick: (HostPort) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Connect using") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                entry.addresses.forEachIndexed { index, address ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(address) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = address.display(),
                            color = SidebarTextPrimary,
                        )
                        // Inline rather than a sentence underneath: the head of
                        // the list only needs marking, not explaining, and a
                        // two-line gloss on one row made the list read as
                        // though the rows were unevenly grouped.
                        if (index == 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "(last used)",
                                color = SidebarTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Reports what scanning a QR for an already-known server did.
 *
 * The scan matched an existing entry by TLS fingerprint, so nothing visible
 * changed — the row keeps its name and place. This is the only chance to tell
 * the user their new network was recorded, which is the whole point of
 * re-pairing somewhere else: the payoff is deferred to the next time they are
 * back on the old network, so without this the action looks like a no-op.
 *
 * Offers the connect that the scan implied, so skipping the auto-connect costs
 * nothing.
 *
 * @param result the merged entry and how many addresses it gained.
 * @param onDismiss invoked when the user closes without connecting.
 * @param onConnect invoked to connect to the merged entry.
 */
@Composable
private fun RePairDialog(
    result: RePairResult,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("${result.entry.label} updated") },
        text = {
            Text(
                when (result.added) {
                    0 ->
                        "This code carried no addresses that ${result.entry.label} " +
                            "didn't already know, so nothing changed."
                    1 ->
                        "1 new address was added. ${result.entry.label} can now be " +
                            "reached on this network as well as the ones it already knew."
                    else ->
                        "${result.added} new addresses were added. ${result.entry.label} " +
                            "can now be reached on this network as well as the ones it " +
                            "already knew."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConnect,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Done") }
        },
    )
}

/**
 * One editable address row.
 *
 * Carries its own identity rather than leaning on its list index: rows move and
 * get deleted, and keying the UI on the index would hand a moved row a new
 * identity — losing focus and animating the wrong row.
 *
 * @property id stable identity for the row across reorders.
 * @property text the row's raw contents, as typed.
 */
private data class AddressField(val id: Long, val text: String)

/**
 * Width of an address row's trailing `⋮` menu — Material's 48dp minimum touch
 * target, stated here rather than left implicit because the Name field reserves
 * the same gutter to line its right edge up with the address fields above the
 * rows. Both uses read this, so they cannot drift apart.
 *
 * @see AddressRowMenu
 */
private val AddressRowMenuWidth = 48.dp

/**
 * Bottom sheet for adding or editing a host entry.
 *
 * A name, then one ordered list of addresses: the list *is* the connect walk,
 * top-first, so reordering it is how the user says which network to try first.
 * There is no separate "host" field above a list of backups — the head of the
 * list is the preferred address (see [HostEntry.addresses]).
 *
 * Each row takes `host` or `host:port`; the port suffix is hidden when it is
 * the one every server listens on, so the common row reads as a bare host. The
 * Save button is disabled until the name is set and every non-blank row parses.
 *
 * A sheet rather than an `AlertDialog`, matching the iOS `HostEditSheet` down to
 * the copy: this is a form with a growing list in it, and a dialog box that
 * shrink-wraps its content fought both the list and the keyboard.
 *
 * @param initial the existing entry to edit, or null when adding a new one.
 * @param onDismiss callback to close the sheet without saving.
 * @param onSave callback with the validated label and the ordered addresses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditDialog(
    initial: HostEntry?,
    onDismiss: () -> Unit,
    onSave: (label: String, addresses: List<HostPort>) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var nextId by remember { mutableStateOf(0L) }
    // Held as text rather than parsed HostPorts because a half-typed row
    // ("192.168.1." on the way to an IP) is not parseable and must not be
    // dropped mid-edit; the parse happens once, on save.
    var addresses by remember {
        val rows = (initial?.addresses ?: emptyList()).map {
            AddressField(nextId++, it.display())
        }
        // A new host opens with one blank row: this list is the only place an
        // address can be typed, so starting empty would present a form with
        // nothing to fill in.
        mutableStateOf(rows.ifEmpty { listOf(AddressField(nextId++, "")) })
    }

    /** Blank rows are mid-edit, not wrong; only a non-blank bad row is flagged. */
    fun parse(text: String): HostPort? = text.trim()
        .takeIf { it.isNotEmpty() }
        ?.let { HostPort.parseCandidate(it, SERVER_TLS_PORT) }

    val filled = addresses.filter { it.text.isNotBlank() }
    val parsed = filled.mapNotNull { parse(it.text) }
    // A non-blank row that will not parse blocks the save rather than being
    // dropped — silently losing an address the user believes they saved is
    // worse than making them fix it.
    val canSave = label.isNotBlank() && filled.isNotEmpty() && parsed.size == filled.size

    // What the form looked like when it opened, so an outside-tap can tell
    // "changed my mind" from "about to lose work". Compared as text, not parsed
    // addresses: a half-typed row is a change worth protecting even though it
    // would not save.
    val initialLabel = remember { initial?.label ?: "" }
    val initialRows = remember {
        (initial?.addresses ?: emptyList()).map { it.display() }
    }
    val dirty = label != initialLabel || addresses.map { it.text } != initialRows
    var confirmDiscard by remember { mutableStateOf(false) }
    // Partially-expanded allowed, so the sheet opens at a comfortable height
    // and can be dragged up to cover the screen — matching the iOS sheet's
    // medium/large detents. A host with a dozen addresses needs the room.
    val sheetState = rememberModalBottomSheetState()
    val sheetScope = rememberCoroutineScope()

    ModalBottomSheet(
        // Tapping the scrim or swiping down used to discard silently. It still
        // discards when there is nothing to lose; once the form has been
        // touched it asks first, because a stray tap beside a sheet should not
        // be able to destroy work the user cannot get back.
        onDismissRequest = { if (dirty) confirmDiscard = true else onDismiss() },
        sheetState = sheetState,
        containerColor = SidebarSurface,
        contentColor = SidebarTextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The address list plus a raised keyboard runs past the sheet;
                // imePadding keeps the focused row above the keyboard rather
                // than under it.
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            // Cancel / title / Save across the top, mirroring the iOS sheet's
            // navigation bar — on a sheet there is no dialog button row to put
            // them in, and burying Save at the bottom of a scrolling list would
            // hide it behind the keyboard.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { if (dirty) confirmDiscard = true else onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
                ) { Text("Cancel") }
                Text(
                    text = if (initial == null) "Add Server" else "Edit Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = SidebarTextPrimary,
                )
                TextButton(
                    onClick = { if (canSave) onSave(label.trim(), parsed) },
                    enabled = canSave,
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
                ) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
            // Scrolls: with a paired host's saved addresses the content runs
            // past a phone's sheet height.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Name") },
                        supportingText = { Text("A name for this server, shown in the server list.") },
                        singleLine = true,
                        colors = themedTextFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    // Reserve the gutter the address rows spend on their ⋮ menu,
                    // so every field in the sheet ends on the same line.
                    Spacer(Modifier.width(AddressRowMenuWidth))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Addresses",
                    style = MaterialTheme.typography.titleSmall,
                    color = SidebarTextPrimary,
                )
                // Both rules the rows need, said where the list starts rather
                // than in a paragraph below it. Kept identical to the iOS sheet.
                Text(
                    "Tried in order, top first. Port $SERVER_TLS_PORT unless you type \"host:port\".",
                    color = SidebarTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                addresses.forEachIndexed { index, row ->
                    val invalid = row.text.isNotBlank() && parse(row.text) == null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = row.text,
                            onValueChange = { edited ->
                                addresses = addresses.toMutableList()
                                    .also { it[index] = row.copy(text = edited) }
                            },
                            placeholder = { Text("host or host:port") },
                            isError = invalid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = themedTextFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        AddressRowMenu(
                            index = index,
                            count = addresses.size,
                            onMove = { offset ->
                                val target = index + offset
                                if (target in addresses.indices) {
                                    addresses = addresses.toMutableList()
                                        .also { java.util.Collections.swap(it, index, target) }
                                }
                            },
                            onDelete = {
                                addresses = addresses.filterIndexed { i, _ -> i != index }
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                TextButton(
                    onClick = { addresses = addresses + AddressField(nextId++, "") },
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add address")
                }
            }
        }
    }

    if (confirmDiscard) {
        DiscardChangesDialog(
            onKeepEditing = {
                confirmDiscard = false
                // A swipe-down animates the sheet away *before* onDismissRequest
                // fires, so by now it is already hidden — keeping it in the
                // composition is not enough to bring it back. Re-show it, or
                // "Keep editing" would dismiss the prompt onto an empty screen
                // with the form gone and no way back to it. (A scrim tap leaves
                // the sheet up, where this is a no-op.)
                sheetScope.launch { sheetState.show() }
            },
            onDiscard = {
                confirmDiscard = false
                onDismiss()
            },
        )
    }
}

/**
 * Asks before throwing away unsaved edits to a host.
 *
 * Only reached from an *accidental* dismissal — a tap on the scrim beside the
 * sheet, or a swipe down — and only when the form has actually been touched.
 * The Cancel button does not come through here: pressing it is an explicit
 * "throw this away", and confirming an intent the user just stated is noise.
 *
 * @param onKeepEditing invoked to close the prompt and return to the sheet.
 * @param onDiscard invoked to abandon the edits and close the sheet.
 */
@Composable
private fun DiscardChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Discard changes?") },
        text = { Text("Your edits to this server won't be saved.") },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Discard") }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepEditing,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Keep editing") }
        },
    )
}

/**
 * Per-row overflow menu in [HostEditDialog]: move this address up or down the
 * walk, or delete it.
 *
 * A menu rather than three inline buttons because the row's text field needs
 * the width — on a phone dialog, up/down/delete side by side leave the address
 * itself unreadably narrow.
 *
 * Sentence-cased ("Move up") where the iOS menu is title-cased ("Move Up").
 * That is deliberate, not drift: Material specifies sentence case for buttons
 * and menu items, the HIG specifies title case. Matching the two platforms to
 * each other would put one of them out of spec.
 *
 * @param index this row's position.
 * @param count total rows, used to disable "Move down" on the last one.
 * @param onMove invoked with -1 (up) or +1 (down).
 * @param onDelete invoked to remove this row.
 */
@Composable
private fun AddressRowMenu(
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Address ${index + 1} options",
                tint = SidebarTextSecondary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Move up") },
                enabled = index > 0,
                onClick = {
                    open = false
                    onMove(-1)
                },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                enabled = index < count - 1,
                onClick = {
                    open = false
                    onMove(1)
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * Dialog shown when the server's TLS leaf certificate no longer matches the
 * fingerprint pinned during the first successful connect. This is either a
 * benign event (server was reinstalled / regenerated its cert) or evidence
 * of an active MITM attempt.
 *
 * Three exits:
 *  - **Re-pair**: clears [HostEntry.pinnedFingerprintHex] so the next tap
 *    runs first-connect (TOFU capture + server `ApprovalDialog`) again.
 *  - **Forget**: deletes the host entry.
 *  - **Cancel**: dismisses without changing anything; user can retry later.
 *
 * @param entry the host entry whose pin mismatched.
 * @param onDismiss invoked when the user picks Cancel or taps outside.
 * @param onRepair invoked when the user picks Re-pair.
 * @param onForget invoked when the user picks Forget.
 */
@Composable
private fun PinMismatchDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onRepair: () -> Unit,
    onForget: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Server certificate changed") },
        text = {
            Text(
                "The server at \"${entry.label}\" " +
                    "(${entry.primary?.display() ?: "no address"}) is " +
                    "presenting a different certificate than the one you paired with. " +
                    "This could mean the server was reinstalled, or someone is trying " +
                    "to intercept your connection.\n\n" +
                    "Re-pair if you trust the new certificate; Forget to remove the server.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onRepair,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Re-pair") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onForget,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
                ) { Text("Cancel") }
            }
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
        title = { Text("Delete server?") },
        text = { Text("\"${entry.label}\" will be removed from this device.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
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
