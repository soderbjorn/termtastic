/**
 * Shared copy and classification for connect failures, so the hosts screen on
 * every platform explains a failed connect the same way.
 *
 * This exists because the classification had drifted into being written twice —
 * once in Swift (`HostsViewModel`) and once in Kotlin (`HostsScreen`) — with the
 * marker phrase, the titles and three message strings duplicated across the two.
 * Any wording fix had to be made in both, and a fix applied to only one silently
 * diverged the platforms.
 *
 * The duplication was avoidable because the connect runs entirely in shared
 * code, which therefore already knows what went wrong: [CandidateConnector]
 * knows when no endpoint answered, and [WindowSocket.awaitInitialConfig] knows
 * when the server refused the device. Both once discarded that knowledge into
 * exception prose that each platform then pattern-matched back out. They now
 * throw [ServerUnreachableException] and [DeviceAuthRejectedException], so this
 * classifier is a plain dispatch on type and the UI layers just render it.
 *
 * @see ConnectFailureText
 * @see ConnectFailureCopy.classify
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.client.CandidateConnector
import se.soderbjorn.lunamux.client.DeviceAuthRejectedException
import se.soderbjorn.lunamux.client.ServerUnreachableException
import se.soderbjorn.lunamux.client.WindowSocket

/**
 * A connect failure rendered for the user: a short title naming the outcome and
 * a body explaining the cause and, where one exists, the fix.
 *
 * Both fields are carried together because they must agree — a server that
 * refused us is a different event from one we could not reach, and a title that
 * contradicts its own body is worse than no title.
 *
 * @property title   short outcome, e.g. [ConnectFailureCopy.REFUSED_TITLE].
 *   Rendered as the alert title on platforms that have one (iOS).
 * @property message body text; never repeats [title], so the two can be
 *   concatenated without reading redundantly. See [oneLine].
 */
data class ConnectFailureText(
    val title: String,
    val message: String,
) {
    /**
     * [title] and [message] as a single string, for platforms whose failure UI
     * has no title field (Android shows these in a snackbar).
     *
     * Safe to concatenate precisely because [message] never restates [title].
     *
     * @return e.g. "Connection refused. The server turned this device away. …"
     */
    val oneLine: String get() = "$title. $message"
}

/**
 * Classifies a connect failure and owns every user-facing string for one.
 *
 * Caller context: the hosts screen's connect-failure path on both platforms
 * (`HostsViewModel.raise` on iOS, `HostsScreen`'s `onFailure` on Android),
 * for every failure except a TLS pin mismatch — that has its own dedicated
 * Re-pair / Forget dialog on both platforms and never reaches here.
 */
object ConnectFailureCopy {
    /**
     * Title for a connect that never got a usable answer — unreachable server,
     * timeout, transport error. Retrying, or fixing the network, may help.
     */
    const val FAILED_TITLE: String = "Connection failed"

    /**
     * Title for a connect the server *answered and turned away*. Deliberately
     * distinct from [FAILED_TITLE]: the network is fine and retrying will not
     * help, so calling this a "failure" sends the user off to debug their Wi-Fi
     * when the fix is on the machine running the server.
     */
    const val REFUSED_TITLE: String = "Connection refused"

    /**
     * Turn a connect failure into the title and message to show the user.
     *
     * Deliberately does not consider the device's transport. Mobile data used to
     * get its own "you can't reach a LAN host from here" message, but that is
     * not true — a VPN reaches the Mac over cellular perfectly well — and a
     * message that blames the connection reads as though the app refused to try.
     * It never refused: the connect is always attempted, and this only ever runs
     * once one has already failed. The same reachability advice covers every
     * transport.
     *
     * A pin mismatch never reaches here: both platforms route
     * [CandidateConnector.isPinMismatch] to a dedicated Re-pair / Forget dialog
     * first.
     *
     * @param throwable   the connect failure, or `null` when the caller has no
     *   Kotlin throwable to offer. iOS passes the `KotlinException` unwrapped
     *   from the bridged `NSError`, which is absent for failures raised in Swift
     *   itself (e.g. its own connect timeout) — hence nullable.
     * @param rawMessage  developer-facing text for the failure
     *   (`Throwable.message` on Android, `Error.localizedDescription` on iOS),
     *   used verbatim only when [throwable] is not one of the known cases.
     * @param deviceNoun  what to call the user's device in the reachability
     *   advice — "iPhone" on iOS, "phone" on Android.
     * @return the title and message to render.
     */
    fun classify(
        throwable: Throwable?,
        rawMessage: String?,
        deviceNoun: String,
    ): ConnectFailureText {
        // The server answered and turned us away. Its own text is developer-
        // facing ("…before sending a config… check the server's log for…"), so
        // say instead what the user can actually do about it.
        if (throwable.anyInChain { it is DeviceAuthRejectedException }) {
            return ConnectFailureText(
                title = REFUSED_TITLE,
                message = "The server turned this device away. On your Mac, in Lunamux, go to " +
                    "\"Settings > Server & Security… > Devices\" to re-pair or approve this device.",
            )
        }
        // Reachability advice only when nothing answered at all. A failure that
        // reached the server must never be blamed on the network.
        if (throwable.anyInChain { it is ServerUnreachableException }) {
            return ConnectFailureText(
                title = FAILED_TITLE,
                message = "Couldn't reach the Lunamux server. Make sure this $deviceNoun is on " +
                    "the same Wi-Fi network as your computer, or on a VPN that can reach it.",
            )
        }
        return ConnectFailureText(
            title = FAILED_TITLE,
            message = rawMessage?.takeIf { it.isNotBlank() }
                ?: "The connection failed for an unknown reason.",
        )
    }

    /**
     * Whether this throwable or any link in its cause chain satisfies
     * [predicate]. Walks the chain rather than testing the top frame alone
     * because a failure may be wrapped in transit (Ktor and the coroutine
     * machinery both do this), and it is depth-capped for the same reason
     * [CandidateConnector.isPinMismatch] is: a self-referencing `cause` must
     * not spin forever.
     *
     * @param predicate the test to apply to each link.
     * @return `true` if any link matches; `false` for a `null` receiver.
     */
    private inline fun Throwable?.anyInChain(predicate: (Throwable) -> Boolean): Boolean {
        var c: Throwable? = this
        var depth = 0
        while (c != null && depth < 16) {
            if (predicate(c)) return true
            c = c.cause
            depth++
        }
        return false
    }
}
