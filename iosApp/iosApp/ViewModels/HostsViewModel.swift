import Foundation
import Observation
import Client

/// Outcome of scanning a pairing QR for a server already in the host list.
///
/// The scan matched an existing entry by TLS fingerprint, so nothing visible
/// changed — the row keeps its name and place. This drives the alert that says
/// so, which is the only chance to tell the user their new network was
/// recorded: the payoff is deferred to the next time they are back on the old
/// network, so without it the action looks like a no-op.
///
/// Mirrors the Android `RePairResult`.
///
/// - SeeAlso: `HostsViewModel.handlePairingUri(_:)`
struct RePairResult: Identifiable {
    /// The entry as saved, with the scanned addresses merged in.
    let entry: HostEntryLocal
    /// How many addresses the scan actually contributed; 0 when the code
    /// carried nothing the entry did not already know.
    let added: Int

    var id: String { entry.id }
}

/// Manages the hosts list and connection state. Observes the shared
/// `LocalRepository` (the single `local_state.json` store) for persistence and
/// wraps `ConnectionHolder` for WebSocket lifecycle.
///
/// The host list is mirrored from `LocalState.hosts` via a `FlowObserver`, and
/// every mutation (add/edit/delete, TOFU pin capture, re-pair) is written back
/// through the repository's suspend API. Mirrors the Android `HostsScreen`,
/// which observes the same shared repository.
@Observable
final class HostsViewModel {
    /// The saved hosts, mirrored from the repository's `LocalState.hosts`.
    var hosts: [HostEntryLocal] = []
    var connectingId: String?
    /// The in-flight connect, surfaced in `ConnectingSheet` rather than on the
    /// row: a walk can spend the full per-candidate budget on each dead
    /// address, which a row-sized spinner cannot explain. Nil for the demo,
    /// which is instant and needs no progress UI.
    var connectingEntry: HostEntryLocal?
    /// The addresses being tried, in order, so the progress UI can say
    /// "address 2 of 3".
    var connectingWalk: [Client.HostPort] = []
    /// The address the walk is trying right now, or nil before the first
    /// attempt is reported.
    var attemptingAddress: Client.HostPort?
    /// Backs Cancel — a three-address walk is over half a minute, which the
    /// user must be able to abort without force-quitting.
    private var connectTask: Task<Void, Never>?
    /// Contents of the failure alert, or `nil` when none is showing. Carries
    /// its own title so a refusal can be named as one — see the shared
    /// `Client.ConnectFailureText`.
    var errorAlert: Client.ConnectFailureText?
    /// Set when the latest connect attempt failed because the server's leaf
    /// cert no longer matches the stored pin. The view binds a dedicated
    /// alert to this so the user gets Re-pair / Forget / Cancel instead of
    /// the generic failure alert. Mirrors the `PinMismatchDialog` shown on
    /// Android.
    var pinMismatchEntry: HostEntryLocal?
    /// Set when a scan matched a host already in the list; the view binds an
    /// alert to this. See `RePairResult`.
    var rePairResult: RePairResult?

    /// Fallback alert title, used when no classified failure is showing.
    /// The strings themselves live in the shared `Client.ConnectFailureCopy`
    /// so iOS and Android cannot drift apart.
    static var failedTitle: String { Client.ConnectFailureCopy.shared.FAILED_TITLE }

    /// A connect failure awaiting presentation, or `nil`. See `raise(_:)` for
    /// why a failure ever has to wait.
    enum ConnectFailure {
        /// A titled failure, shown by the generic failure alert.
        case text(Client.ConnectFailureText)
        /// A TLS pin mismatch, shown by the dedicated Re-pair / Forget alert.
        case pinMismatch(HostEntryLocal)
    }

    /// A failure raised while `ConnectingSheet` was still on screen, held back
    /// until that sheet has finished dismissing. Flushed by
    /// `presentPendingFailure()` from the sheet's `onDismiss`.
    ///
    /// SwiftUI refuses to present an alert while another presentation is in
    /// progress and does **not** retry — it logs "Attempt to present ... while
    /// a presentation is in progress" and drops the alert on the floor. Setting
    /// `errorAlert` in the same main-actor turn that clears `connectingEntry`
    /// hit exactly that: the connect failure was diagnosed correctly and then
    /// silently discarded, leaving the user on an empty sessions list that
    /// claimed they had no tabs.
    private var pendingFailure: ConnectFailure?
    var waitingForApproval: Bool { ConnectionHolder.shared.pendingApproval }

    /// Sentinel id used in `connectingId` for the built-in demo row, which
    /// is not a persisted host entry. Drives the row's progress spinner and
    /// disables the rest of the list during the (instant) demo connect.
    static let demoConnectingId = "builtin-demo"

    private let repository = AppRepository.shared
    private let flowObserver = Client.FlowObserver()

    init() {
        // Mirror the persisted host list into `hosts`. The value is `LocalState?`
        // (nil until hydration completes); an empty list is published until then.
        flowObserver.observe(flow: repository.state) { [weak self] value in
            let state = value as? Client.LocalState
            let mapped = (state?.hosts ?? []).map { HostEntryLocal(from: $0) }
            DispatchQueue.main.async { self?.hosts = mapped }
        }
    }

    deinit {
        flowObserver.clear()
    }

    /// Connect to the built-in demo "server": the magic demo host makes the
    /// shared client run against its in-process simulation, so this never
    /// touches the network and completes instantly. No auth, no TLS pin, no
    /// saved host entry.
    func connectDemo() {
        connectingId = Self.demoConnectingId
        errorAlert = nil
        Task {
            do {
                let serverUrl = Client.ServerUrl(host: DemoModeKt.DEMO_HOST, port: 0)
                try await ConnectionHolder.shared.connect(
                    serverUrl: serverUrl,
                    authToken: "demo",
                    pinnedFingerprintHex: nil
                )
                // Demo settings resolve to the stock defaults; setting them
                // anyway keeps every colour accessor on the same path as a
                // real connection.
                if let client = ConnectionHolder.shared.client {
                    Palette.config = try? await client.fetchThemeConfig()
                }
                await MainActor.run { connectingId = nil }
            } catch {
                await MainActor.run {
                    raise(.text(Client.ConnectFailureText(
                        title: Self.failedTitle,
                        message: error.localizedDescription
                    )))
                }
            }
        }
    }

    /// Connect to a saved host entry: walks its addresses in order (a paired
    /// entry carries every address the server advertised) and, on success,
    /// persists the winner in one write — winning address promoted to the head
    /// of the list, TOFU pin captured if this was a pinless first connect,
    /// spent pairing token cleared. Mirrors the Android `connectToEntry`
    /// lambda.
    ///
    /// - Parameters:
    ///   - entry: the host to connect to.
    ///   - only: connect using just this address instead of walking them all —
    ///     the long-press picker's path. `nil` for the normal walk.
    func connect(entry: HostEntryLocal, only: Client.HostPort? = nil) {
        let walk = only.map { [$0] } ?? entry.addresses
        connectingId = entry.id
        connectingEntry = entry
        connectingWalk = walk
        attemptingAddress = nil
        errorAlert = nil
        connectTask = Task {
            do {
                let token = try await repository.getOrCreateAuthToken()
                let connection = try await ConnectionHolder.shared.connectMulti(
                    addresses: walk,
                    authToken: token,
                    pinnedFingerprintHex: entry.pinnedFingerprintHex,
                    pairingToken: entry.pairingToken,
                    onAttempt: { [weak self] address in
                        Task { @MainActor in self?.attemptingAddress = address }
                    }
                )
                // TOFU: on a pinless first connect, keep whatever fingerprint
                // the handshake observed so the next connect runs in
                // strict-verify mode.
                let pin = entry.pinnedFingerprintHex
                    ?? (connection.client.observedFingerprint.value as? String)
                // The address that answered leads the walk next time. Note this
                // promotes into `entry.addresses` even on the `only:` path, so
                // a picked address is remembered like any other winner.
                var updated = entry.promoting(connection.endpoint)
                updated.pinnedFingerprintHex = pin
                updated.pairingToken = nil
                if updated != entry {
                    try? await repository.updateHost(entry: updated.toShared())
                }
                // Fetch the user's theme settings so all views use the
                // selected theme from the start. Palette.settings is a
                // static var read by all colour accessors.
                if let client = ConnectionHolder.shared.client {
                    Palette.config = try? await client.fetchThemeConfig()
                }
                await MainActor.run { clearConnecting() }
            } catch {
                await MainActor.run {
                    // The user pressed Cancel; they already know. Saying
                    // "couldn't reach the Mac" would blame the network for
                    // something they did on purpose.
                    if error is CancellationError || Task.isCancelled {
                        clearConnecting()
                        return
                    }
                    if ConnectionHolder.shared.lastPinMismatch {
                        raise(.pinMismatch(entry))
                    } else {
                        raise(.text(Self.connectFailureText(error)))
                    }
                }
            }
        }
    }

    /// Abort the in-flight connect. Cancelling the task unwinds the shared
    /// `CandidateConnector`, which tears down the attempt it was waiting on;
    /// `disconnect()` additionally drops a socket that phase 2 had already
    /// published, which cancellation alone would leave live.
    ///
    /// Main-actor isolated because `ConnectionHolder.disconnect()` is, and the
    /// only caller is the Cancel button.
    @MainActor
    func cancelConnect() {
        connectTask?.cancel()
        ConnectionHolder.shared.disconnect()
        clearConnecting()
    }

    /// Drop every trace of an in-flight connect, dismissing the progress UI.
    private func clearConnecting() {
        connectingId = nil
        connectingEntry = nil
        connectingWalk = []
        attemptingAddress = nil
        connectTask = nil
    }

    /// Surface a connect failure, tearing down the progress UI first.
    ///
    /// When `ConnectingSheet` is up (every real host connect), the alert cannot
    /// be presented in the same turn that dismisses it, so the failure is
    /// stashed in `pendingFailure` and the sheet's `onDismiss` presents it once
    /// the screen is free. When no sheet is up (the demo path, which has no
    /// progress UI) there is nothing to wait for and it presents immediately.
    ///
    /// Called from every connect catch block. Use this rather than assigning
    /// `errorAlert` / `pinMismatchEntry` directly, so no failure can be lost
    /// to a presentation conflict again.
    ///
    /// - Parameter failure: what to tell the user.
    /// - SeeAlso: `presentPendingFailure()`
    private func raise(_ failure: ConnectFailure) {
        let sheetWasUp = connectingEntry != nil
        clearConnecting()
        if sheetWasUp {
            pendingFailure = failure
        } else {
            present(failure)
        }
    }

    /// Bind `failure` to the alert state the view observes.
    private func present(_ failure: ConnectFailure) {
        switch failure {
        case .text(let text): errorAlert = text
        case .pinMismatch(let entry): pinMismatchEntry = entry
        }
    }

    /// Present a failure that `raise(_:)` held back while the progress sheet
    /// was still dismissing. Called from `ConnectingSheet`'s `onDismiss`, which
    /// fires after the dismissal completes — the point at which presenting an
    /// alert is safe. No-op when the connect succeeded or was cancelled.
    ///
    /// - SeeAlso: `raise(_:)`
    func presentPendingFailure() {
        guard let failure = pendingFailure else { return }
        pendingFailure = nil
        present(failure)
    }

    /// Handle a scanned QR / deep-linked pairing URI: parse, dedupe against
    /// existing entries, save, and connect straight away. Mirrors the Android
    /// `handlePairingUri` lambda.
    ///
    /// Invalid input is expected, not exceptional — the scanner reports any QR
    /// it decodes, and the deep link is reachable from any app that can open a
    /// URL — so a non-payload just raises the same friendly message Android
    /// shows.
    ///
    /// - Parameter uri: the raw `lunamux://pair?...` string.
    func handlePairingUri(_ uri: String) {
        guard let payload = Client.PairingPayload.companion.parse(uri: uri),
              !payload.candidates.isEmpty else {
            errorAlert = Client.ConnectFailureText(
                title: Self.failedTitle,
                message: "That doesn't look like a Lunamux pairing code"
            )
            return
        }
        Task {
            // Identity is the TLS cert and nothing else. An address is not a
            // machine: 192.168.1.5 is whoever's Wi-Fi you are on, so matching
            // on endpoint overlap would merge a colleague's Mac into your entry
            // and repin it. The cert can't collide, is generated once with a
            // 10-year life (CertStore), and follows the machine between
            // networks — which is exactly the case re-pairing exists for. A
            // manually-added entry matches too once it has captured its TOFU
            // pin, since that is the same cert. The cost is that a manual entry
            // that has never connected has no pin and forks a duplicate; that
            // entry has no history worth keeping anyway.
            let existing = try? await repository.ensureLoaded().hosts.first { host in
                host.pinnedFingerprintHex == payload.fingerprintHex
            }

            if let existing {
                var updated = HostEntryLocal(from: existing)
                updated.pinnedFingerprintHex = payload.fingerprintHex
                // Augment, don't replace: re-pairing at home must not cost the
                // entry its work addresses. The scanned set leads, so the
                // network the user is standing on is tried first. See
                // HostEntry.mergeAddresses.
                let merged = Client.HostEntry.companion.mergeAddresses(
                    fresh: payload.candidates,
                    existing: existing.addresses
                )
                // Count what actually landed, not what the QR offered: the merge
                // caps its result, so some fresh addresses may not have made it in.
                let added = merged.filter { !existing.addresses.contains($0) }.count
                updated.addresses = merged
                updated.pairingToken = payload.token
                try? await repository.updateHost(entry: updated.toShared())
                // Deliberately no auto-connect here, unlike a first pairing.
                // Re-pairing a known host changes nothing you can see — same
                // label, same row — and connecting navigates away before any
                // confirmation could be read, so the one moment the user could
                // learn what happened would be spent. Mirrors the Android
                // RePairDialog.
                await MainActor.run { rePairResult = RePairResult(entry: updated, added: added) }
            } else {
                let created = try? await repository.addPairedHost(
                    label: payload.serverName ?? "Paired server",
                    addresses: payload.candidates,
                    pinnedFingerprintHex: payload.fingerprintHex,
                    pairingToken: payload.token
                )
                guard let created else {
                    await MainActor.run {
                        errorAlert = Client.ConnectFailureText(
                            title: Self.failedTitle,
                            message: "Couldn't save the paired server"
                        )
                    }
                    return
                }
                // A brand-new host keeps the scan → connected promise: the new
                // row in the list is its own confirmation.
                await MainActor.run { connect(entry: HostEntryLocal(from: created)) }
            }
        }
    }

    /// Classify a connect failure into the alert's title and message.
    ///
    /// The connect itself runs in shared code, so shared code already knows what
    /// went wrong and throws a typed failure for each case. Both the rules and
    /// every string they can produce therefore live in
    /// `Client.ConnectFailureCopy`, and Android renders identical wording from
    /// identical rules. All this wrapper does is unwrap the Kotlin throwable
    /// that Kotlin/Native boxes inside the bridged `NSError` — the same move
    /// `ConnectionHolder.isPinMismatch(_:)` makes for the same reason.
    ///
    /// `KotlinException` is absent for failures raised in Swift itself (the
    /// connect timeout in `ConnectionHolder.withTimeout`); `classify` takes the
    /// throwable as optional and falls back to the message for exactly that.
    ///
    /// - Parameter error: the connect failure (non-pin-mismatch).
    /// - Returns: the title and message for the alert.
    /// - SeeAlso: `Client.ConnectFailureCopy.classify`
    private static func connectFailureText(_ error: Error) -> Client.ConnectFailureText {
        let thrown = (error as NSError).userInfo["KotlinException"] as? Client.KotlinThrowable
        return Client.ConnectFailureCopy.shared.classify(
            throwable: thrown,
            rawMessage: error.localizedDescription,
            deviceNoun: "iPhone"
        )
    }

    /// Save a manually-typed host. The edit sheet guarantees at least one
    /// address, which the shared `addHost` requires.
    ///
    /// - Parameters:
    ///   - label: user-visible display name.
    ///   - addresses: the endpoints to try, in the order the user arranged them.
    func addHost(label: String, addresses: [Client.HostPort]) {
        Task { try? await repository.addHost(label: label, addresses: addresses) }
    }

    func updateHost(_ entry: HostEntryLocal) {
        Task { try? await repository.updateHost(entry: entry.toShared()) }
    }

    func deleteHost(id: String) {
        Task { try? await repository.deleteHost(id: id) }
    }

    /// Clear the stored pin so the next connect attempt re-runs the TOFU
    /// capture and re-fires the server's `DeviceAuth.ApprovalDialog`.
    /// Triggered from the cert-changed alert's "Re-pair" button when the
    /// user has decided the new certificate is legitimate (server was
    /// reinstalled, key rolled, etc.).
    func repairPin(_ entry: HostEntryLocal) {
        var updated = entry
        updated.pinnedFingerprintHex = nil
        Task { try? await repository.updateHost(entry: updated.toShared()) }
        pinMismatchEntry = nil
    }

    /// Delete the host entry from the cert-changed alert's "Forget" button.
    /// Distinct from `deleteHost(id:)` only in that it also clears the
    /// alert state.
    func forgetHost(_ entry: HostEntryLocal) {
        Task { try? await repository.deleteHost(id: entry.id) }
        pinMismatchEntry = nil
    }
}
