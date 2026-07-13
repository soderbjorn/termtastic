/**
 * An interactive weechat/irssi-style IRC channel rendered into a demo terminal
 * pane. Draws a nick-list column (ops `@`, voiced `+`), a scrolling
 * conversation area, and a bottom input line as full-screen ANSI frames on the
 * same snapshot/live-frame contract [DemoTerminalSession] uses, so the demo PTY
 * transports drive it unchanged.
 *
 * On a fixed timer it emits ambient chatter and IRC churn — joins (with `_`
 * nick-collision suffixing), parts, quits, `+v/-v` and `+o/-o` mode changes,
 * kicks, kickbans (`+b` mask → kick, with an occasional later `-b`), topic
 * changes, and `/me` actions. Anchor bots hold ops and do the kicking. Text the
 * demo user types is appended as their own line and a bot may answer per the
 * channel's responsiveness.
 *
 * RNG POLICY: every ambient decision is drawn from [ambientRng], seeded from
 * the channel's fixed [DemoIrcChannelSpec.seed], so the whole join/part/kick/
 * mode/chatter sequence plays out **identically on every load** — screenshots
 * and recordings match run to run. Only genuine user input (and the replies it
 * draws, from the separate [replyRng]) diverges from the seeded script. There
 * is no wall-clock or unseeded randomness anywhere in the engine; the on-screen
 * clock is a fabricated, deterministic counter.
 *
 * @param spec the channel's content and pacing.
 * @param scope the client's long-lived coroutine scope (ambient loop + input).
 * @see DemoIrcContent for the ported fixture pools
 * @see DemoServer for the wiring that creates one session per channel pane
 */
package se.soderbjorn.lunamux.client.demo

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** One conversation entry, coloured by [kind] when rendered. */
private enum class IrcLineKind { MESSAGE, SELF, ACTION, EVENT }

/**
 * One channel member. Bots make up the roster; the demo user is not a member
 * object (their nick is [DemoIrcContent.USER_NICK], rendered from the input
 * line and self messages).
 *
 * @property nick the member's current nick.
 * @property anchor `true` for the permanent ops that hold the channel and do
 *   the kicking/mode changes (they never part/quit and are never kicked).
 * @property op whether the member currently carries `@`.
 * @property voice whether the member currently carries `+`.
 */
private class IrcMember(
    val nick: String,
    val anchor: Boolean,
    var op: Boolean,
    var voice: Boolean,
)

/** One rendered conversation line. */
private class IrcLine(val kind: IrcLineKind, val text: String)

/**
 * The interactive IRC TUI session. See the file header for the RNG policy.
 */
internal class DemoIrcSession internal constructor(
    private val spec: DemoIrcChannelSpec,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : DemoSession {

    override val sessionId: String get() = spec.sessionId

    /** Live output frames emitted after the snapshot. */
    private val live = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    /** Guards all mutable channel state and [lastFrame]. */
    private val lock = Mutex()

    /** Drives every ambient event — reseeded from [DemoIrcChannelSpec.seed]. */
    private var ambientRng = Random(spec.seed)

    /**
     * Drives replies to the demo user's typed lines. Kept SEPARATE from
     * [ambientRng] so that user input never perturbs the deterministic ambient
     * script — the ambient churn is identical whether or not the user types.
     */
    private val replyRng = Random(spec.seed xor 0x5DEECE66DL)

    /** The current roster, ops/voiced first when rendered. */
    private val members = mutableListOf<IrcMember>()

    /** Nicks currently "offline" — the pool joins draw from and parts return to. */
    private val offline = ArrayDeque<String>()

    /** Active `+b` masks awaiting an eventual `-b`, with the anchor that set them. */
    private val bans = ArrayDeque<Pair<String, String>>()

    /** Newest conversation lines last; capped so memory stays bounded. */
    private val lines = ArrayDeque<IrcLine>()

    /** The demo user's in-progress input line. */
    private val inputBuffer = StringBuilder()

    /** The current channel topic. */
    private var topic: String = spec.topics.first()

    /** Fabricated deterministic wall clock, in minutes past midnight. */
    private var clockMinutes = 14 * 60 + 2

    /** The last full frame, replayed as the snapshot to a new subscriber. */
    private var lastFrame: String = ""

    // -- live layout ---------------------------------------------------------
    // The frame reflows to the attached pane's terminal grid (see [resize]),
    // so the input line always rests on the pane's bottom row and the nick
    // list spans its full right edge. These start at the fallback grid for the
    // first snapshot and are overwritten by the first PTY resize.

    /** Conversation/nick-list height in rows (pane rows minus title, rule, input). */
    private var convoRows = DEFAULT_CONVO_ROWS

    /** Conversation column width in cells. */
    private var convoW = DEFAULT_CONVO_W

    /** Nick-list column width in cells. */
    private var nickW = DEFAULT_NICK_W

    /** Full frame width in cells: [convoW] + the " │ " gutter (3) + [nickW]. */
    private var totalW = DEFAULT_CONVO_W + 3 + DEFAULT_NICK_W

    /** Escape-sequence swallow state (arrow keys etc.): 0 none, 1 ESC, 2 CSI/SS3. */
    private var escState = 0

    /** Pending keystrokes, consumed in order by [inputJob]. */
    private val inputChannel = Channel<String>(Channel.UNLIMITED)

    /** The single input-consumer coroutine; cancelled by [close]. */
    private val inputJob: Job = scope.launch {
        for (chunk in inputChannel) for (ch in chunk) processChar(ch)
    }

    /** The ambient-event loop; cancelled by [close]/[restart]. */
    private var ambientJob: Job? = null

    init {
        seedWorld()
        lastFrame = renderFrame()
        startAmbient()
    }

    // -- DemoSession surface -------------------------------------------------

    override fun output(): Flow<ByteArray> = live.onSubscription {
        val snapshot = lock.withLock { lastFrame }
        if (snapshot.isNotEmpty()) emit(snapshot.encodeToByteArray())
    }

    override fun input(bytes: ByteArray) {
        inputChannel.trySend(bytes.decodeToString())
    }

    override fun inputText(text: String) {
        inputChannel.trySend(text)
    }

    /**
     * Reflow the channel to the pane's [cols] × [rows] terminal grid: the nick
     * column takes a quarter of the width (clamped), the conversation takes the
     * rest, and the conversation height is every row but the title, the rule and
     * the input line. Repaints only when the grid actually changed, so the churn
     * of a live drag-resize doesn't spam frames. Tiny grids (overview
     * miniatures) are ignored so a thumbnail can't collapse the real layout.
     */
    override suspend fun resize(cols: Int, rows: Int) {
        if (cols < 24 || rows < 6) return
        val nick = (cols / 4).coerceIn(10, 24)
        val convoWidth = (cols - 3 - nick).coerceAtLeast(16)
        val convoRowCount = (rows - 3).coerceAtLeast(4)
        if (cols == totalW && nick == nickW && convoWidth == convoW && convoRowCount == convoRows) return
        val frame = lock.withLock {
            totalW = cols
            nickW = nick
            convoW = convoWidth
            convoRows = convoRowCount
            lastFrame = renderFrame()
            lastFrame
        }
        // Clear the emulator's scrollback before the reflowed frame: shrinking the
        // xterm grid reflows the old (larger) frame's rows into scrollback, which —
        // with the viewport then parked up there — stranded the repaint mid-pane.
        // The prefix is NOT stored in lastFrame, so a fresh subscriber starts clean.
        live.emit(("$E[3J" + frame).encodeToByteArray())
    }

    override fun close() {
        inputChannel.close()
        inputJob.cancel()
        ambientJob?.cancel()
    }

    override suspend fun restart() {
        ambientJob?.cancel()
        val frame = lock.withLock {
            ambientRng = Random(spec.seed)
            members.clear()
            offline.clear()
            bans.clear()
            lines.clear()
            inputBuffer.clear()
            topic = spec.topics.first()
            clockMinutes = 14 * 60 + 2
            escState = 0
            seedWorld()
            lastFrame = renderFrame()
            lastFrame
        }
        // Clear the emulator's scrollback + screen so an attached client rewinds
        // before the fresh frame paints. The prefix is NOT stored in lastFrame:
        // a later subscriber starts from the clean frame and has nothing to erase.
        live.emit(("\u001b[3J\u001b[2J\u001b[H" + frame).encodeToByteArray())
        startAmbient()
    }

    // -- world seeding -------------------------------------------------------

    /**
     * Populate the roster, ops, voices, ban list and a few opening lines of
     * backlog. Runs single-threaded (from [init] or under [lock] in [restart]),
     * drawing entirely from [ambientRng] so the opening screen is identical
     * every load.
     */
    private fun seedWorld() {
        val shuffled = DemoIrcContent.nickPool.shuffled(ambientRng)
        val roster = shuffled.take(spec.targetMembers)
        offline.addAll(shuffled.drop(spec.targetMembers))
        val anchorCount = if (spec.targetMembers >= 15) 3 else 2
        roster.forEachIndexed { i, nick ->
            val anchor = i < anchorCount
            members.add(IrcMember(nick, anchor = anchor, op = anchor, voice = false))
        }
        // A few non-anchors start voiced.
        members.filter { !it.anchor }.shuffled(ambientRng).take(2 + ambientRng.nextInt(3))
            .forEach { it.voice = true }

        // Seed a short backlog so the pane opens mid-conversation, not empty.
        repeat(6) {
            val speaker = members.random(ambientRng)
            val text = if (ambientRng.nextDouble() < 0.8) spec.messages.random(ambientRng)
            else DemoIrcContent.genericMessages.random(ambientRng)
            addMessage(speaker, text)
            if (ambientRng.nextDouble() < 0.4) clockMinutes++
        }
    }

    // -- ambient loop --------------------------------------------------------

    /** Launch the seeded ambient-event loop; each iteration waits then repaints. */
    private fun startAmbient() {
        ambientJob = scope.launch {
            while (true) {
                val gap = spec.minDelayMs + ambientRng.nextLong(spec.maxDelayMs - spec.minDelayMs)
                delay(gap)
                mutateAndRepaint { ambientEvent() }
            }
        }
    }

    /**
     * Pick and apply one ambient event, mirroring the real simulator's event
     * mix (messages dominate; churn, modes, kicks and topic changes taper off).
     * Runs under [lock] via [mutateAndRepaint]; draws only from [ambientRng].
     */
    private fun ambientEvent() {
        // Occasionally lift an old ban first (the deferred `-b` after a kickban).
        if (bans.isNotEmpty() && ambientRng.nextDouble() < 0.25) {
            val (opNick, mask) = bans.removeFirst()
            val op = members.firstOrNull { it.nick == opNick && it.op } ?: members.firstOrNull { it.op }
            if (op != null) addEvent("— ${op.nick} sets mode -b $mask")
        }
        when (val r = ambientRng.nextDouble()) {
            in 0.00..0.50 -> eventMessage()
            in 0.50..0.66 -> eventReply()
            in 0.66..0.72 -> eventAction()
            in 0.72..0.82 -> eventJoin()
            in 0.82..0.90 -> eventLeave()
            in 0.90..0.95 -> eventMode()
            in 0.95..0.975 -> eventKick(ban = false)
            in 0.975..0.99 -> eventKick(ban = true)
            else -> eventTopic()
        }
        if (ambientRng.nextDouble() < 0.35) clockMinutes++
    }

    private fun eventMessage() {
        val bot = members.randomOrNull(ambientRng) ?: return
        val pool = if (ambientRng.nextDouble() < 0.8) spec.messages else DemoIrcContent.genericMessages
        addMessage(bot, pool.random(ambientRng))
    }

    private fun eventReply() {
        val last = lines.lastOrNull { it.kind == IrcLineKind.MESSAGE } ?: return eventMessage()
        val targetNick = lastSpeaker() ?: return eventMessage()
        val bot = members.filter { it.nick != targetNick }.randomOrNull(ambientRng) ?: return
        addMessage(bot, DemoIrcContent.replies.random(ambientRng).replace("{nick}", targetNick))
    }

    private fun eventAction() {
        val bot = members.randomOrNull(ambientRng) ?: return
        val other = members.filter { it != bot }.randomOrNull(ambientRng) ?: bot
        val text = DemoIrcContent.actions.random(ambientRng).replace("{nick}", other.nick)
        addLine(IrcLineKind.ACTION, "${clock()} · ${bot.nick} $text")
    }

    private fun eventJoin() {
        if (members.size >= spec.targetMembers + 6 || offline.isEmpty()) return
        val base = offline.removeAt(ambientRng.nextInt(offline.size))
        var nick = base
        while (members.any { it.nick == nick }) nick += "_" // nick-collision suffixing
        members.add(IrcMember(nick, anchor = false, op = false, voice = false))
        addEvent("» $nick has joined ${spec.channel}")
    }

    private fun eventLeave() {
        if (members.size <= spec.targetMembers - 4) return
        val bot = members.filter { !it.anchor }.randomOrNull(ambientRng) ?: return
        members.remove(bot)
        offline.addLast(bot.nick.trimEnd('_'))
        if (ambientRng.nextDouble() < 0.5) {
            addEvent("« ${bot.nick} has quit (${DemoIrcContent.quitReasons.random(ambientRng)})")
        } else {
            val why = DemoIrcContent.partMessages.random(ambientRng)
            addEvent("« ${bot.nick} has left ${spec.channel}" + if (why != null) " ($why)" else "")
        }
    }

    private fun eventMode() {
        val op = members.filter { it.op }.randomOrNull(ambientRng) ?: return
        val others = members.filter { it != op }
        when (ambientRng.nextDouble()) {
            in 0.00..0.40 -> others.filter { !it.voice && !it.op }.randomOrNull(ambientRng)?.let {
                it.voice = true
                addEvent("— ${op.nick} sets mode +v ${it.nick}")
            }
            in 0.40..0.60 -> others.filter { it.voice }.randomOrNull(ambientRng)?.let {
                it.voice = false
                addEvent("— ${op.nick} sets mode -v ${it.nick}")
            }
            in 0.60..0.85 -> others.filter { !it.op && !it.anchor }.randomOrNull(ambientRng)?.let {
                it.op = true
                addEvent("— ${op.nick} sets mode +o ${it.nick}")
            }
            else -> others.filter { it.op && !it.anchor }.randomOrNull(ambientRng)?.let {
                it.op = false
                addEvent("— ${op.nick} sets mode -o ${it.nick}")
            }
        }
    }

    private fun eventKick(ban: Boolean) {
        val op = members.filter { it.op && it.anchor }.randomOrNull(ambientRng) ?: return
        val victim = members.filter { !it.anchor && !it.op }.randomOrNull(ambientRng) ?: return
        val reason = DemoIrcContent.kickReasons.random(ambientRng)
        members.remove(victim)
        offline.addLast(victim.nick.trimEnd('_'))
        if (ban) {
            val mask = "${victim.nick}!*@*"
            bans.addLast(op.nick to mask)
            addEvent("⊘ ${op.nick} has kicked+banned ${victim.nick} from ${spec.channel} ($reason)   [+b $mask]")
        } else {
            addEvent("⊘ ${op.nick} has kicked ${victim.nick} from ${spec.channel} ($reason)")
        }
    }

    private fun eventTopic() {
        if (spec.topics.size < 2) return
        val op = members.filter { it.op }.randomOrNull(ambientRng) ?: return
        val next = spec.topics.filter { it != topic }.randomOrNull(ambientRng) ?: return
        topic = next
        addEvent("— ${op.nick} changes topic to: $next")
    }

    // -- reactions to the demo user -----------------------------------------

    /** Handle one input character through a minimal IRC input-line discipline. */
    private suspend fun processChar(ch: Char) {
        when (escState) {
            1 -> { escState = if (ch == '[' || ch == 'O') 2 else 0; return }
            2 -> { if (ch.code in 0x40..0x7e) escState = 0; return }
        }
        if (ch == '\u001b') { escState = 1; return }

        when (ch) {
            '\r', '\n' -> submitInput()
            '\u0003' -> mutateAndRepaint { inputBuffer.clear() } // Ctrl-C clears the line
            '\u007f', '\b' -> if (inputBuffer.isNotEmpty()) {
                mutateAndRepaint { inputBuffer.deleteAt(inputBuffer.length - 1) }
            }
            '\t' -> Unit
            else -> if (ch.code >= 0x20) mutateAndRepaint { inputBuffer.append(ch) }
        }
    }

    /**
     * Post the demo user's line as their own message, then maybe schedule a bot
     * reply (directly-addressed bots almost always answer; otherwise per the
     * channel's responsiveness). The reply nick is picked now, under [lock], so
     * a later roster change can't race it; the reply text is drawn from
     * [replyRng] so it never disturbs the ambient script.
     */
    private suspend fun submitInput() {
        val text = inputBuffer.toString().trim()
        inputBuffer.clear()
        if (text.isEmpty()) { mutateAndRepaint { } ; return }
        val replierNick = lock.withLock {
            addLine(IrcLineKind.SELF, "${clock()} ${DemoIrcContent.USER_NICK}> $text")
            lastFrame = renderFrame()
            pickReplier(text)
        }
        live.emit(lastFrame.encodeToByteArray())
        if (replierNick != null) scheduleReply(replierNick, text)
    }

    /** Choose a bot to answer [text] (or `null` for silence). Draws from [replyRng]. */
    private fun pickReplier(text: String): String? {
        val addressed = members.firstOrNull {
            it.nick.length >= 3 && text.contains(it.nick, ignoreCase = true)
        }
        if (addressed != null && replyRng.nextDouble() < 0.85) return addressed.nick
        if (replyRng.nextDouble() >= spec.responsiveness) return null
        return members.filter { it.nick != DemoIrcContent.USER_NICK }.randomOrNull(replyRng)?.nick
    }

    /** Post a directed reply from [nick] after a short human-like delay. */
    private fun scheduleReply(nick: String, ignoredUserText: String) {
        scope.launch {
            delay(1_500 + replyRng.nextLong(3_500))
            mutateAndRepaint {
                val bot = members.firstOrNull { it.nick == nick } ?: return@mutateAndRepaint
                val reply = DemoIrcContent.replies.random(replyRng)
                    .replace("{nick}", DemoIrcContent.USER_NICK)
                addMessage(bot, reply)
            }
        }
    }

    // -- line bookkeeping ----------------------------------------------------

    /** The nick of the newest plain message, or `null`. */
    private fun lastSpeaker(): String? =
        lines.lastOrNull { it.kind == IrcLineKind.MESSAGE }
            ?.text?.substringAfter("  ")?.substringBefore("  ")?.trimStart('@', '+', ' ')

    /** Append a plain channel message from [bot]. */
    private fun addMessage(bot: IrcMember, text: String) {
        val prefix = if (bot.op) "@" else if (bot.voice) "+" else " "
        addLine(IrcLineKind.MESSAGE, "${clock()}  $prefix${bot.nick}  $text")
    }

    /** Append an event (join/part/quit/mode/kick/topic) line. */
    private fun addEvent(text: String) = addLine(IrcLineKind.EVENT, "${clock()} $text")

    /** Append [text] as a [kind] line, capping the backlog to comfortably fill a tall pane. */
    private fun addLine(kind: IrcLineKind, text: String) {
        lines.addLast(IrcLine(kind, text))
        val cap = maxOf(80, convoRows + 20)
        while (lines.size > cap) lines.removeFirst()
    }

    /** The current fabricated clock as `HH:MM`. */
    private fun clock(): String {
        val h = (clockMinutes / 60) % 24
        val m = clockMinutes % 60
        return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
    }

    // -- rendering -----------------------------------------------------------

    /**
     * Mutate channel state under [lock], re-render the frame, and emit it as a
     * live full-screen redraw. The block runs on whichever coroutine called it
     * (ambient loop, input consumer, or a scheduled reply); [lock] serialises them.
     */
    private suspend fun mutateAndRepaint(block: () -> Unit) {
        val frame = lock.withLock {
            block()
            lastFrame = renderFrame()
            lastFrame
        }
        live.emit(frame.encodeToByteArray())
    }

    /**
     * Build the full ANSI screen: a title bar, the conversation/nick-list split,
     * a rule, and the input line. Each row ends with erase-to-EOL and the frame
     * homes the cursor and clears below, so a redraw repaints cleanly without a
     * full clear-screen flicker while typing.
     *
     * @return the ANSI frame string (cursor rests at the end of the input line).
     */
    private fun renderFrame(): String {
        val rows = ArrayList<String>(convoRows + 3)

        // Title bar: channel + topic on the left, member count on the right.
        val head = " ${spec.channel} — $topic"
        val count = "${members.size} ▸"
        val bar = fit(head, totalW - count.length - 1) + " " + count
        rows.add("$B$REV${fit(bar, totalW)}$R")

        // Conversation (left) beside the nick list (right).
        val convo = wrappedConvoRows()
        val nicks = nickRows()
        for (i in 0 until convoRows) {
            val left = colourConvo(convo.getOrNull(i))
            val right = nicks.getOrNull(i) ?: ""
            rows.add("$left $DIM│$R $right")
        }

        rows.add("$DIM${"─".repeat(totalW)}$R")
        rows.add("$DIM[${spec.channel}]$R $B${DemoIrcContent.USER_NICK}>$R ${inputBuffer}")

        val sb = StringBuilder()
        sb.append("$E[H")
        for (i in rows.indices) {
            sb.append("$E[").append(i + 1).append(";1H").append(rows[i]).append("$E[K")
        }
        sb.append("$E[J")
        return sb.toString()
    }

    /**
     * The conversation area's display rows: every backlog [IrcLine] **word-wrapped**
     * to [convoW] (so long messages spill onto continuation rows instead of being
     * cut off with an ellipsis), newest last, then the trailing [convoRows] of those
     * wrapped rows — what actually fits on screen. Each row keeps its source line's
     * [IrcLineKind] so [colourConvo] tints continuation rows like their message.
     */
    private fun wrappedConvoRows(): List<Pair<IrcLineKind, String>> {
        val all = ArrayList<Pair<IrcLineKind, String>>(lines.size)
        for (line in lines) {
            for (seg in wrapToWidth(line.text, convoW)) all.add(line.kind to seg)
        }
        val start = maxOf(0, all.size - convoRows)
        return all.subList(start, all.size)
    }

    /** Colour a conversation cell by its line kind and pad it to the convo width. */
    private fun colourConvo(cell: Pair<IrcLineKind, String>?): String {
        val text = fit(cell?.second ?: "", convoW)
        return when (cell?.first) {
            IrcLineKind.EVENT -> "$DIM$text$R"
            IrcLineKind.ACTION -> "$DIM$CYAN$text$R"
            IrcLineKind.SELF -> "$B$GRN$text$R"
            else -> text
        }
    }

    /**
     * Greedy word-wrap [s] into segments no wider than [w] columns, breaking on
     * spaces and hard-breaking any single word longer than [w]. Always returns at
     * least one segment (possibly empty) so an empty line still occupies a row.
     */
    private fun wrapToWidth(s: String, w: Int): List<String> {
        if (w < 1 || s.length <= w) return listOf(s)
        val out = ArrayList<String>()
        val line = StringBuilder()
        for (rawWord in s.split(' ')) {
            var word = rawWord
            // Hard-break a word that can't fit the full width on its own.
            while (word.length > w) {
                if (line.isNotEmpty()) { out.add(line.toString()); line.clear() }
                out.add(word.substring(0, w))
                word = word.substring(w)
            }
            val needed = if (line.isEmpty()) word.length else line.length + 1 + word.length
            if (needed > w) {
                out.add(line.toString()); line.clear(); line.append(word)
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
        }
        if (line.isNotEmpty() || out.isEmpty()) out.add(line.toString())
        return out
    }

    /** The nick-list column: ops (`@`) first, then voiced (`+`), then the rest. */
    private fun nickRows(): List<String> {
        val ordered = members.sortedWith(
            compareBy({ if (it.op) 0 else if (it.voice) 1 else 2 }, { it.nick.lowercase() }),
        )
        val rows = ArrayList<String>(convoRows)
        val shown = if (ordered.size > convoRows) convoRows - 1 else ordered.size
        for (i in 0 until shown) {
            val m = ordered[i]
            val sigil = if (m.op) "@" else if (m.voice) "+" else " "
            val cell = fit("$sigil${m.nick}", nickW)
            rows.add(if (m.op) "$B$cell$R" else if (m.voice) cell else "$DIM$cell$R")
        }
        if (ordered.size > convoRows) {
            rows.add("$DIM${fit("  +${ordered.size - shown} more", nickW)}$R")
        }
        return rows
    }

    /** Truncate [s] to [w] visible columns (with an ellipsis) or pad it to [w]. */
    private fun fit(s: String, w: Int): String =
        if (s.length > w) s.take(w - 1) + "…" else s.padEnd(w)

    private companion object {
        /** Fallback grid used for the first snapshot, before the pane reports its real size. */
        const val DEFAULT_CONVO_ROWS = 18
        const val DEFAULT_CONVO_W = 50
        const val DEFAULT_NICK_W = 18

        const val E = "\u001b"
        const val R = "$E[0m"
        const val B = "$E[1m"
        const val DIM = "$E[2m"
        const val REV = "$E[7m"
        const val GRN = "$E[32m"
        const val CYAN = "$E[36m"
    }
}
