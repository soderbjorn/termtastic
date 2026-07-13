/**
 * Pure fixture data for the demo-mode IRC channels of the DarknessIRC world:
 * the per-channel specs (topic rotation, message pools, member targets,
 * responsiveness, ambient pacing, and the fixed RNG seed), plus the shared
 * flavour pools (generic filler, directed replies, greetings, `/me` actions,
 * kick/part/quit reasons, op-grant lines) and the nick pool the join/part
 * churn draws from.
 *
 * This is a straight port of the pure content from the real DarknessIRC
 * project's `simulator/Content.kt` (the message pools, nick pool and event
 * flavour pools), trimmed to the three channels the demo shows (`#commodore`,
 * `#kotlin-multiplatform`, `#amiga`) and to `commonMain`-safe Kotlin so it compiles
 * for JS/iOS/Android. Nothing here is randomised at declaration time — the
 * randomness lives in [DemoIrcSession], which seeds a fixed [kotlin.random.Random]
 * per channel so the churn plays out identically on every load.
 *
 * @see DemoIrcSession for the engine that turns this data into an ANSI TUI
 * @see DemoFixtures for the World 2 pane layout that references these sessions
 */
package se.soderbjorn.lunamux.client.demo

/**
 * Everything needed to seed and drive one demo IRC channel session.
 *
 * Consumed by [DemoServer] (to construct the [DemoIrcSession] behind a channel
 * pane) and by [DemoIrcSession] itself (to seed its world and pick events).
 *
 * @property sessionId the fixture session id the channel pane attaches to.
 * @property channel the channel name (`"#commodore"`), shown in the title/input.
 * @property topics the topic strings the channel rotates between (index 0 is
 *   the starting topic; a topic-change event picks a different one).
 * @property messages the channel-specific ambient message pool.
 * @property targetMembers the roster size the channel seeds to and hovers around.
 * @property responsiveness probability that a bot answers when the demo user
 *   speaks (higher = chattier, quiet channels welcome newcomers more eagerly).
 * @property minDelayMs lower bound of the gap between ambient events.
 * @property maxDelayMs upper bound of the gap between ambient events.
 * @property seed the fixed RNG seed — the whole ambient script is a pure
 *   function of this, so screenshots/recordings match run to run.
 */
internal class DemoIrcChannelSpec(
    val sessionId: String,
    val channel: String,
    val topics: List<String>,
    val messages: List<String>,
    val targetMembers: Int,
    val responsiveness: Double,
    val minDelayMs: Long,
    val maxDelayMs: Long,
    val seed: Long,
)

/**
 * The static content pools for the demo IRC engine, ported from the real
 * DarknessIRC simulator. Exposed as one object so [DemoIrcSession] has a single
 * place to read from.
 */
internal object DemoIrcContent {

    /** The demo user's own nick, shown in the input line and self messages. */
    const val USER_NICK = "guest"

    /**
     * The three channel specs the DarknessIRC world shows, in pane order
     * (`#commodore`, `#kotlin-multiplatform`, `#amiga`). Looked up by [DemoServer] to
     * create one [DemoIrcSession] each.
     *
     * @return the ordered channel specs.
     */
    fun channelSpecs(): List<DemoIrcChannelSpec> = listOf(
        DemoIrcChannelSpec(
            sessionId = "dirc-s1",
            channel = "#commodore",
            topics = listOf(
                "Commodore 8-bit | C64/C128/VIC-20/PET | SID chip appreciation zone",
                "Commodore 8-bit | 1541 still spinning after 40 years | load\"*\",8,1",
                "Commodore 8-bit | PETSCII art wall welcome | no PC vs C64 wars",
            ),
            messages = COMMODORE_MESSAGES,
            targetMembers = 22,
            responsiveness = 0.35,
            minDelayMs = 2_200,
            maxDelayMs = 5_200,
            seed = 0x11_A9_10L,
        ),
        DemoIrcChannelSpec(
            sessionId = "dirc-s2",
            channel = "#kotlin-multiplatform",
            topics = listOf(
                "Kotlin Multiplatform | share code across JVM/JS/Native/Wasm | expect/actual talk",
                "Kotlin Multiplatform | Compose Multiplatform is production now | paste >3 lines to a pastebin",
            ),
            messages = KMP_MESSAGES,
            targetMembers = 20,
            responsiveness = 0.35,
            minDelayMs = 2_800,
            maxDelayMs = 6_200,
            seed = 0xC0_DE_5EL,
        ),
        DemoIrcChannelSpec(
            sessionId = "dirc-s3",
            channel = "#amiga",
            topics = listOf(
                "Amiga | Workbench, Kickstart, 68k & AGA talk | Guru Meditation survivors",
                "Amiga | AmigaOS 3.2 is real | Vampire & PiStorm accelerators welcome",
            ),
            messages = AMIGA_MESSAGES,
            targetMembers = 8,
            responsiveness = 0.85,
            minDelayMs = 4_500,
            maxDelayMs = 9_000,
            seed = 0x20_20_73L,
        ),
    )

    /** `#commodore` ambient message pool. */
    private val COMMODORE_MESSAGES = listOf(
        "finally recapped my breadbin C64, no more black screen at power on",
        "the 1541 drive is louder than my washing machine and I love it",
        "nothing beats the SID chip, that 6581 filter is pure magic",
        "8580 vs 6581 debate again? the 6581 filters are inconsistent and that's the charm",
        "typing in a type-in listing from a 1985 magazine, my fingers hurt already",
        "poke 53280,0 : poke 53281,0 — black borders, classic move",
        "just found my old VIC-20 in the loft, still boots with 3.5k free",
        "the PET 2001 chiclet keyboard is a crime against fingers but I respect it",
        "load\"$\",8 then list to see the directory, some habits never die",
        "SD2IEC changed my life, no more waiting 4 minutes to load a game",
        "anyone else still have the KERNAL ROM memory map memorized",
        "did a fastloader compare last night, JiffyDOS still wins",
        "demoscene on the 64 is unreal, they get more colors out of it every year",
        "sprite multiplexing to get 16 sprites on screen never stops being clever",
        "GEOS on a stock C64 was basically a Mac in 1986, wild for the time",
        "my C128 in CP/M mode is the most useless fun I've had all week",
        "cross-developing with a modern assembler and testing in VICE, best of both worlds",
        "raster interrupts are the whole game, once it clicks you can't unsee it",
        "cleaned 40 years of grime off the keycaps, looks factory fresh now",
        "the reset button mod on the cartridge port is essential, fight me",
        "datasette actually loaded first try today, framed the moment",
        "PETSCII art still holds up, those block graphics were ahead of their time",
    )

    /** `#kotlin-multiplatform` ambient message pool. */
    private val KMP_MESSAGES = listOf(
        "finally got one shared module building for android, ios and desktop, feels like magic",
        "expect/actual is elegant until you need three platform-specific implementations",
        "moved my networking layer to ktor and deleted both platform HTTP clients, great day",
        "Compose Multiplatform on iOS is genuinely usable now, not just a tech demo",
        "coroutines + Flow in commonMain means one viewmodel for every platform, love it",
        "the KMP gradle setup is 200 lines but once it compiles you never look at it again",
        "kotlinx.serialization in common code, no more hand-written JSON per platform",
        "SQLDelight gives me typesafe queries shared across all targets, chef's kiss",
        "wasm target is getting real, ran my shared logic in the browser today",
        "why does every KMP question on stackoverflow have an answer from 2021 that's obsolete now",
        "cinterop with an old C library was painful but it works and I feel powerful",
        "sharing the domain layer but keeping UI native is still the safest KMP bet imo",
        "the iOS framework export just worked first try, I'm suspicious",
        "kotlin/native memory model changes made everything so much simpler, no more freeze()",
        "koin vs kodein for DI in common code? go",
        "just replaced 3 duplicate validation implementations with one in commonMain",
        "hot reload in Compose Multiplatform desktop is criminally underrated",
        "the day I stopped writing the same model class twice I never looked back",
        "gradle configuration cache finally sped up my multi-target build, huge",
        "targeting jvm, js, native AND wasm from one codebase still feels like cheating",
        "actual class vs actual typealias, the eternal micro-decision",
        "TIL you can share ViewModels across platforms with the new lifecycle libs",
    )

    /** `#amiga` ambient message pool. */
    private val AMIGA_MESSAGES = listOf(
        "the Amiga boing ball demo still gives me chills 40 years on",
        "Workbench 3.1 vs 3.2, the new one is genuinely worth the upgrade",
        "just got a PiStorm running on my A500, it's stupidly fast now",
        "nothing sounds like a MOD tracker through Paula, four channels of joy",
        "Deluxe Paint IV is still a better pixel tool than half of what ships today",
        "the copper is the secret sauce, gradients for free every scanline",
        "blitter go brrr, the whole machine was designed around moving pixels",
        "recapped my A1200 and the video is crisp again, no more fuzzy colors",
        "Guru Meditation 8000 0003 again, my dodgy RAM expansion strikes",
        "WHDLoad turned my whole floppy collection into instant-load hard drive games",
        "hunting for a working Kickstart 3.1 ROM, the 2.04 is holding me back",
        "Lightwave rendered the Babylon 5 effects on Amigas, never forget",
        "the A500 chunky keyboard feel beats every modern board, don't @ me",
        "swapping disk 3 of 11 for the fourth time, ah the good old days",
        "Vampire V4 standalone is basically an Amiga from an alternate timeline",
        "Scala multimedia ran half the TV bug screens in the 90s on Amigas",
        "68030 with an FPU makes Imagine actually usable, worth every penny",
        "AGA's 256 colors felt infinite after years of the OCS 32-color palette",
        "still mapping my joystick muscle memory from Sensible Soccer",
        "the click-click of the internal floppy is pure nostalgia fuel",
        "octamed pushed Paula to 8 channels and I still don't know how",
        "booting straight to Workbench from CF card, no moving parts, bliss",
    )

    /** Off-topic filler that fits any channel. */
    val genericMessages = listOf(
        "brb coffee",
        "back",
        "lol",
        "anyone alive in here?",
        "quiet day today huh",
        "ok that's wild",
        "hah, nice",
        "hmm",
        "can confirm",
        "big mood",
        "reading scrollback... eventful morning I see",
        "afk a bit, meeting",
    )

    /** Replies aimed at a specific nick — `{nick}` is substituted. */
    val replies = listOf(
        "{nick}: yeah exactly",
        "{nick}: hah",
        "{nick}: nah, I don't buy it",
        "{nick}: source?",
        "{nick}: this",
        "{nick}: same",
        "{nick}: been there",
        "{nick}: rip",
        "{nick}: what do you mean?",
        "{nick}: oh nice",
        "{nick}: +1",
        "{nick}: depends tbh",
        "true, {nick}",
        "good point {nick}",
        "wait what, {nick}?",
        "{nick}: try turning it off and on again",
        "{nick}: bold claim",
        "{nick}: yeah I saw that too",
        "{nick}: strongly agree",
        "{nick}: screenshot or it didn't happen",
    )

    /** Greetings for a human joining a quiet channel — `{nick}` is substituted. */
    val greetings = listOf(
        "hey {nick}",
        "o/ {nick}",
        "welcome {nick}",
        "hi {nick}",
        "{nick}: welcome, read the topic :)",
        "yo {nick}",
    )

    /** CTCP ACTION texts (`/me`) — `{nick}` may be a random channel member. */
    val actions = listOf(
        "stretches",
        "yawns",
        "goes to grab more coffee",
        "is back",
        "sighs",
        "shrugs",
        "waves",
        "facepalms",
        "pokes {nick}",
        "hands {nick} a coffee",
        "nods slowly",
    )

    /** Reasons an op gives when kicking (or kickbanning). */
    val kickReasons = listOf(
        "flooding",
        "spam",
        "take it elsewhere",
        "we don't do that here",
        "enough",
        "read the topic",
        "cool it",
        "last warning was the last warning",
        "bye",
    )

    /** Quit messages shown when a nick leaves the network entirely. */
    val quitReasons = listOf(
        "Quit: leaving",
        "Ping timeout: 246 seconds",
        "Ping timeout: 265 seconds",
        "Read error: Connection reset by peer",
        "Quit: WeeChat 4.2.1",
        "Remote host closed the connection",
        "Quit: sleep",
        "Quit: ZZZzzz…",
        "Excess Flood",
    )

    /** PART reasons; `null` means no reason was given. */
    val partMessages = listOf(null, null, null, "brb", "lunch", "gotta run", "later folks", "afk")

    /** What an op says when granting a privilege the demo user asked for. */
    val grantResponses = listOf(
        "there you go",
        "done",
        "sure",
        "{nick}: done",
        "np",
        "here you go {nick}",
        "use it wisely {nick}",
        "alright",
    )

    /** The nick pool joins/parts draw from (a trimmed slice of the real pool). */
    val nickPool = listOf(
        "quartz", "drifter42", "toastman", "eth0", "mango_", "kernelpanic", "juniper", "bitwise",
        "moonpie", "circuitry", "plasmatic", "n8dawg", "tessellate", "zephyr_", "pixelbyte",
        "combustible", "ratatosk", "fennec", "wintermute", "voidptr", "hexadecimal", "smokestack",
        "gluon", "cobalt_", "flatline", "ionosphere", "jackdaw", "krypton_", "lodestone", "mothman",
        "nullroute", "oxidize", "papercut", "quibble", "rustbucket", "sixpence", "tinfoil", "umlaut",
        "vantablack", "xylem", "yonder_", "zugzwang", "cloudberry", "eventide", "farsight", "gigawatt",
        "halogen", "inkwell", "jetstream", "lampshade", "mudlark", "nightowl_", "pinecone", "riverbed",
        "stargazer", "tumbleweed", "waypoint", "copperwire", "foxglove", "tinderbox", "harbinger",
    )
}
