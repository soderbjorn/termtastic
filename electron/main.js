/**
 * @file Termtastic — Electron main process entry point.
 *
 * Responsibilities:
 * - Bootstraps the embedded Ktor server jar (or connects to an already-running
 *   instance) and creates the main BrowserWindow once the server is reachable.
 * - Enforces single-instance: a second launch refocuses the existing window.
 * - Registers a global hotkey (Ctrl+Alt+Cmd+Space) to summon the app from any
 *   context (other app, other Space).
 * - Builds the application menu (including a "Launch at Login" toggle on macOS).
 * - Handles macOS app-lifecycle conventions (keep alive on last window close,
 *   recreate window on dock click).
 */

const { app, BrowserWindow, Menu, globalShortcut, ipcMain, screen, session } = require("electron");
const path = require("path");
const fs = require("fs");
const net = require("net");
const { spawn } = require("child_process");

const APP_NAME = "Termtastic";
// Production port — mirrors shared/.../Constants.kt:SERVER_PORT. The dev server
// runs on 8083 (set by :server:run), so a packaged Termtastic on 8082 can keep
// running alongside developer iteration.
const PROD_PORT = 8082;
const URL_OVERRIDE = process.env.TERMTASTIC_URL || null;
const TARGET_URL = URL_OVERRIDE || `http://localhost:${PROD_PORT}`;

// Must run before `app.whenReady()` so the menu/about box pick up the name.
// NOTE: on macOS in *dev mode*, the system menu bar still reads CFBundleName
// from the unpackaged Electron.app's Info.plist, so the first menu may still
// say "Electron". The packaged build via electron-builder uses productName
// ("Termtastic") in its Info.plist and shows the right name there.
app.setName(APP_NAME);

// Global hotkey that summons the app from anywhere. Only fires while this
// process is alive — see the login-item toggle below for the "always
// available" story.
const SUMMON_ACCELERATOR = "Control+Alt+Command+Space";

// Single-instance lock: a second `open -a Termtastic` (from the dock, a
// Shortcut, etc.) should refocus the existing window rather than spin up a
// duplicate Electron + duplicate server bootstrap.
if (!app.requestSingleInstanceLock()) {
  app.quit();
  return;
}
app.on("second-instance", () => showAndFocus());

let mainWindow = null;

// --- Multi-window tracking --------------------------------------------------
//
// Every main BrowserWindow we create is recorded here, keyed by its
// client-assigned window id (UUID). Each renderer also receives its id via
// the URL query string (?window=<id>) so the Kotlin/JS side can namespace
// future per-window state to that id. The server owns the persistent list
// — see server/.../WindowRegistry.kt and the /api/windows REST surface.
//
// `mainWindow` (above) is kept in sync with the most recently focused window
// so existing single-window code paths (showAndFocus, setWindowBackgroundColor
// without a sender resolution, etc.) still do something sensible.
const mainWindows = new Map();

/**
 * Generate a fresh, opaque window id for a newly opened main window.
 *
 * @returns {string} a random identifier with no semantic meaning
 */
function makeWindowId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  // Node's `crypto` module is the fallback for Electron main contexts without
  // the global Web Crypto (very old builds). We only import it here because
  // it isn't needed on current Electron versions.
  return require("crypto").randomUUID();
}

// --- Window chrome preference (custom title bar toggle) ---------------------
//
// The `titleBarStyle` BrowserWindow option is immutable after creation, so we
// cache the user's choice locally and read it before building the window.
// The server is the source of truth (see `/api/ui-settings`), but we can't
// hit the server from the main process without an auth token, so the web
// renderer IPCs the current value down and we mirror it in userData.

/**
 * Absolute path to the JSON file that caches the user's window-chrome
 * preference between launches.
 *
 * @returns {string} Path under Electron's `userData`.
 */
function chromePrefsPath() {
  return path.join(app.getPath("userData"), "electron-chrome.json");
}

/**
 * Loads the cached window-chrome preferences from disk.
 *
 * @returns {{ customTitleBar: boolean }} Defaults to `{ customTitleBar: false }`
 *   (native title bar) when the file is missing or malformed.
 */
function loadChromePrefs() {
  try {
    const raw = fs.readFileSync(chromePrefsPath(), "utf8");
    const parsed = JSON.parse(raw);
    return { customTitleBar: parsed.customTitleBar === true };
  } catch (_) {
    return { customTitleBar: false };
  }
}

/**
 * Persists the window-chrome preferences to disk so the next launch honours
 * the user's choice without needing to fetch from the server.
 *
 * @param {{ customTitleBar: boolean }} prefs
 */
function saveChromePrefs(prefs) {
  try {
    fs.mkdirSync(path.dirname(chromePrefsPath()), { recursive: true });
    fs.writeFileSync(chromePrefsPath(), JSON.stringify(prefs));
  } catch (_) {
    // Cosmetic — worst case the next launch forgets the preference.
  }
}

let chromePrefs = loadChromePrefs();

// --- Window-registry mirror (for cold-start restore) -----------------------
//
// The server is the source of truth for the window registry (see
// server/.../WindowRegistry.kt), but at cold-start the Electron main
// process needs to know how many BrowserWindows to spawn *before* any
// renderer has loaded to hit the authed REST surface. We mirror the
// registry to a JSON file under `userData` every time a window is created
// or closed, and read it on startup. If the file is missing or malformed
// we fall back to spawning exactly one window.

/**
 * Absolute path to the JSON file that mirrors the server-side window
 * registry so Electron main can restore windows before any renderer runs.
 *
 * @returns {string} Path under Electron's `userData`.
 */
function windowsMirrorPath() {
  return path.join(app.getPath("userData"), "electron-windows.json");
}

/**
 * Load the mirrored registry from disk.
 *
 * @returns {Array<{id:string,x:number,y:number,width:number,height:number,displayId?:string}>}
 *   list of previously-open windows, or empty on first launch / malformed file
 */
function loadWindowsMirror() {
  try {
    const raw = fs.readFileSync(windowsMirrorPath(), "utf8");
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((e) => e && typeof e.id === "string");
  } catch (_) {
    return [];
  }
}

/**
 * Clamp a persisted window rect onto a still-attached Electron display.
 *
 * If the user last left the window on a monitor that has since been
 * disconnected, restoring with the original `x`/`y` would place the
 * BrowserWindow offscreen and the user would never see it. This helper
 * first tries to find a display matching the persisted `displayId`; if
 * that display is gone, it re-centres the rectangle on the primary
 * display while preserving the window's size.
 *
 * `screen` requires `app.whenReady()` to have resolved, so this helper
 * is only safe to call from the restore path (which always runs after
 * the ready event).
 *
 * @param {{id:string,x:number,y:number,width:number,height:number,displayId?:string}} entry
 * @returns {{x:number,y:number,width:number,height:number}} bounds safe to pass to BrowserWindow
 */
function clampEntryToAttachedDisplay(entry) {
  const width = Math.max(320, Number.isFinite(entry.width) ? entry.width : 1280);
  const height = Math.max(240, Number.isFinite(entry.height) ? entry.height : 800);
  const x = Number.isFinite(entry.x) ? entry.x : null;
  const y = Number.isFinite(entry.y) ? entry.y : null;
  try {
    const displays = screen.getAllDisplays();
    // If we have a displayId hint, honour it first — it's the cheapest way
    // to land the window back on the intended monitor, and it survives
    // changing external-display arrangements that shift the global
    // coordinate system.
    if (entry.displayId) {
      const match = displays.find((d) => String(d.id) === String(entry.displayId));
      if (match) {
        // Keep the original (x, y) if they still land on that display;
        // otherwise re-centre the window within the display's workArea.
        const wa = match.workArea;
        const xOnDisplay = x !== null && x >= wa.x && x + width <= wa.x + wa.width;
        const yOnDisplay = y !== null && y >= wa.y && y + height <= wa.y + wa.height;
        if (xOnDisplay && yOnDisplay) return { x, y, width, height };
        return {
          x: Math.round(wa.x + (wa.width - width) / 2),
          y: Math.round(wa.y + (wa.height - height) / 2),
          width,
          height,
        };
      }
      // Fall through: persisted display is no longer attached.
    }
    // No hint, or hint stale. If (x,y) still live inside some attached
    // display, trust them. Otherwise re-centre on the primary display.
    if (x !== null && y !== null) {
      const covering = displays.find((d) =>
        x >= d.workArea.x &&
        y >= d.workArea.y &&
        x + width <= d.workArea.x + d.workArea.width &&
        y + height <= d.workArea.y + d.workArea.height
      );
      if (covering) return { x, y, width, height };
    }
    const primary = screen.getPrimaryDisplay();
    return {
      x: Math.round(primary.workArea.x + (primary.workArea.width - width) / 2),
      y: Math.round(primary.workArea.y + (primary.workArea.height - height) / 2),
      width,
      height,
    };
  } catch (_) {
    // `screen` unavailable — fall back to the persisted bounds verbatim
    // and let Electron clamp them if it must. This is the pre-monitor-
    // tracking behaviour.
    return {
      x: x !== null ? x : undefined,
      y: y !== null ? y : undefined,
      width,
      height,
    };
  }
}

/**
 * Persist the mirrored registry to disk. Called whenever a window is
 * created, moved, resized, or closed. The server-side registry receives
 * the same information via REST, but this local mirror is what cold-start
 * reads before any renderer has a chance to hit the authed endpoint.
 *
 * @param {Array<object>} entries
 */
function saveWindowsMirror(entries) {
  try {
    fs.mkdirSync(path.dirname(windowsMirrorPath()), { recursive: true });
    fs.writeFileSync(windowsMirrorPath(), JSON.stringify(entries));
  } catch (_) {
    // Cosmetic — worst case the next cold start forgets the extra windows
    // and opens exactly one.
  }
}

/**
 * Resolve the Electron display id a given bounds rectangle is anchored to.
 *
 * `screen.getDisplayMatching()` returns the Display whose bounds contain
 * most of the rectangle — the correct answer for restore-on-next-launch
 * because it snaps a window that the user last left on a secondary
 * monitor back to that same monitor, even if it's been disconnected and
 * reconnected. `screen` is only available after `app.whenReady()`, so
 * callers that run earlier than that should guard against it being
 * uninitialised (the helper returns `null` when `screen` throws).
 *
 * Falls back to `null` on any failure so a transient Electron error
 * doesn't wedge the lifecycle persist path.
 *
 * @param {{x:number,y:number,width:number,height:number}} bounds
 * @returns {string|null} the display id as a string, or null on failure
 */
function displayIdForBounds(bounds) {
  try {
    if (!bounds || !Number.isFinite(bounds.x) || !Number.isFinite(bounds.y)) return null;
    const d = screen.getDisplayMatching({
      x: bounds.x,
      y: bounds.y,
      width: Math.max(1, bounds.width || 1),
      height: Math.max(1, bounds.height || 1),
    });
    return d && typeof d.id !== "undefined" ? String(d.id) : null;
  } catch (_) {
    return null;
  }
}

/**
 * Serialise every currently-live BrowserWindow into the mirror file.
 *
 * Called on any lifecycle event (create/move/resize/close) so the mirror
 * is never more than one event stale.
 *
 * The persisted entry includes the Electron display id so cold-start
 * restore can clamp the window back onto the same physical monitor (at
 * least when that monitor is still attached).
 */
function persistWindowsMirror() {
  const out = [];
  for (const [id, win] of mainWindows) {
    if (win.isDestroyed()) continue;
    const b = win.getBounds();
    out.push({
      id,
      x: b.x,
      y: b.y,
      width: b.width,
      height: b.height,
      displayId: displayIdForBounds(b),
    });
  }
  saveWindowsMirror(out);
}

// --- Embedded server bootstrap ----------------------------------------------
//
// On launch we want the packaged Termtastic to "just work":
//   1. If a server is already listening on PROD_PORT, reuse it.
//   2. Otherwise spawn the bundled server jar (detached) and wait for it.
//   3. On quit we deliberately do NOT kill the spawned server — `detached: true`
//      + `child.unref()` lets it outlive this process so PTY sessions survive
//      a window close.
//
// TERMTASTIC_URL overrides everything: it's the dev escape hatch (`:electron:run`
// sets it to the dev server on 8083) and skips all of this logic.

/**
 * Checks whether a TCP port is currently accepting connections.
 *
 * Used by the server bootstrap logic to detect if the Ktor backend is already
 * running before attempting to spawn a new instance.
 *
 * @param {number} port - TCP port number to probe.
 * @param {string} [host="127.0.0.1"] - Host address to connect to.
 * @param {number} [timeoutMs=250] - Socket timeout in milliseconds before
 *   giving up on a single probe.
 * @returns {Promise<boolean>} Resolves to `true` if a connection was
 *   established, `false` on timeout or error.
 */
function isPortListening(port, host = "127.0.0.1", timeoutMs = 250) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    let settled = false;
    const done = (result) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(result);
    };
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => done(true));
    socket.once("timeout", () => done(false));
    socket.once("error", () => done(false));
    socket.connect(port, host);
  });
}

/**
 * Polls a TCP port until it starts accepting connections or a deadline expires.
 *
 * Called by {@link ensureServerThenCreateWindow} after spawning the embedded
 * server jar, to block window creation until the backend is ready to serve
 * HTTP requests.
 *
 * @param {number} port - TCP port number to poll.
 * @param {number} [timeoutMs=30000] - Maximum time to wait in milliseconds.
 * @returns {Promise<boolean>} Resolves to `true` if the port became reachable
 *   within the deadline, `false` otherwise.
 */
async function waitForPort(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await isPortListening(port)) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return false;
}

/**
 * Locates the bundled Ktor server fat-jar on disk.
 *
 * In packaged (electron-builder) builds the jar is placed in
 * `Contents/Resources/server.jar` via `extraResources`. In dev mode
 * (`:electron:run`) it lives at `./resources/server.jar` relative to this
 * script.
 *
 * Called by {@link ensureServerThenCreateWindow} when no server is already
 * listening on {@link PROD_PORT}.
 *
 * @returns {string|null} Absolute path to the jar, or `null` if not found.
 */
function findServerJar() {
  // In packaged builds the jar is shipped via electron-builder `extraResources`,
  // which lands it at Contents/Resources/server.jar (i.e. process.resourcesPath)
  // — outside app.asar, where `java -jar` can actually read it. In dev (gradle
  // :electron:run) the jar lives next to main.js under ./resources/.
  const candidates = app.isPackaged
    ? [path.join(process.resourcesPath, "server.jar")]
    : [path.join(__dirname, "resources", "server.jar")];
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

/**
 * Resolves the absolute path to a usable `java` binary.
 *
 * GUI-launched apps on macOS do not inherit the user's shell PATH, so Java
 * installed via Homebrew, SDKMAN, or asdf would be invisible to a bare
 * `spawn("java")`. This function probes, in order:
 *   1. `$JAVA_HOME/bin/java`
 *   2. `/usr/libexec/java_home` (macOS only)
 *   3. Well-known install locations (Homebrew, Temurin, system packages)
 *   4. Falls back to the bare binary name, relying on PATH as a last resort.
 *
 * Called by {@link spawnEmbeddedServer}.
 *
 * @returns {string} Absolute path to the `java` binary, or the bare name
 *   `"java"` / `"java.exe"` if no candidate was found on disk.
 */
function resolveJavaBinary() {
  // GUI launches on macOS (Finder, dock) don't inherit shell PATH, so a Java
  // installed via Homebrew / asdf / SDKMAN is invisible to a naked spawn("java").
  // Probe the obvious places: JAVA_HOME, then `/usr/libexec/java_home` on
  // macOS, then a curated list of well-known install roots, then PATH as
  // last resort.
  const binName = process.platform === "win32" ? "java.exe" : "java";

  if (process.env.JAVA_HOME) {
    const c = path.join(process.env.JAVA_HOME, "bin", binName);
    if (fs.existsSync(c)) return c;
  }

  if (process.platform === "darwin") {
    try {
      const { execFileSync } = require("child_process");
      const home = execFileSync("/usr/libexec/java_home", { encoding: "utf8" }).trim();
      if (home) {
        const c = path.join(home, "bin", "java");
        if (fs.existsSync(c)) return c;
      }
    } catch (_) {
      // java_home exits non-zero when no JDK is registered — fall through.
    }
  }

  const wellKnown = [];
  if (process.platform === "darwin") {
    wellKnown.push(
      "/opt/homebrew/opt/openjdk/bin/java",
      "/opt/homebrew/opt/openjdk@21/bin/java",
      "/opt/homebrew/opt/openjdk@17/bin/java",
      "/usr/local/opt/openjdk/bin/java",
      "/usr/local/opt/openjdk@21/bin/java",
      "/usr/local/opt/openjdk@17/bin/java",
      "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java",
      "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java",
    );
  } else if (process.platform === "linux") {
    wellKnown.push(
      "/usr/lib/jvm/default-java/bin/java",
      "/usr/lib/jvm/java-21-openjdk/bin/java",
      "/usr/lib/jvm/java-17-openjdk/bin/java",
      "/usr/bin/java",
    );
  }
  for (const c of wellKnown) {
    if (fs.existsSync(c)) return c;
  }

  return binName; // last-ditch: rely on PATH
}

/**
 * Returns the absolute path to the server log file, creating the log
 * directory if it does not already exist.
 *
 * The log directory is resolved via Electron's `app.getPath("logs")`:
 * - macOS:   `~/Library/Logs/Termtastic/`
 * - Linux:   `~/.config/Termtastic/logs/`
 * - Windows: `%APPDATA%/Termtastic/logs/`
 *
 * Called by {@link spawnEmbeddedServer} to set up stdout/stderr redirection
 * for the spawned JVM process.
 *
 * @returns {string} Absolute path to `server.log`.
 */
function serverLogPath() {
  const logsDir = app.getPath("logs");
  fs.mkdirSync(logsDir, { recursive: true });
  return path.join(logsDir, "server.log");
}

/**
 * Spawns the embedded Ktor server as a detached child process.
 *
 * The child is deliberately detached and unref'd so it outlives the Electron
 * process -- this lets PTY sessions survive a window close. Server
 * stdout/stderr is redirected to a log file (see {@link serverLogPath}).
 *
 * Called by {@link ensureServerThenCreateWindow} when no server is already
 * listening. The caller races the returned `spawnError` promise against
 * {@link waitForPort} to surface a friendly error quickly instead of hanging
 * for the full 30-second timeout.
 *
 * @param {string} jarPath - Absolute path to the server fat-jar.
 * @returns {{ java: string, spawnError: Promise<Error>, logPath: string }}
 *   `java` is the resolved binary path (useful for error messages),
 *   `spawnError` resolves if/when the child emits an error or exits non-zero
 *   (stays pending on success), and `logPath` is the server log location.
 */
function spawnEmbeddedServer(jarPath) {
  const java = resolveJavaBinary();
  // On macOS, the embedded JVM would normally pop a dock icon the first time
  // it opens the device-approval Swing dialog. -Dapple.awt.UIElement=true
  // tells the JVM to behave like a background agent (LSUIElement), so the
  // dialog still appears but no extra dock tile shows up next to Termtastic's
  // own. No-op on Windows/Linux.
  const javaArgs = [];
  if (process.platform === "darwin") {
    javaArgs.push("-Dapple.awt.UIElement=true");
  }
  javaArgs.push(`-Dtermtastic.port=${PROD_PORT}`, "-jar", jarPath);

  // Pipe server stdout+stderr to a log file. We open an fd and hand it to the
  // child via stdio so the OS keeps the descriptor alive even after Electron
  // exits (the server is detached and outlives us).
  const logPath = serverLogPath();
  const logFd = fs.openSync(logPath, "a");
  const child = spawn(java, javaArgs, {
    detached: true,
    stdio: ["ignore", logFd, logFd],
  });
  // Close our copy of the fd — the spawned process has its own.
  fs.closeSync(logFd);
  // Detach from this process group entirely. Combined with `detached: true`
  // above, the JVM survives Electron quitting — which is exactly the contract
  // we want for "don't kill the server on quit".
  child.unref();

  const spawnError = new Promise((resolve) => {
    child.once("error", (err) => resolve(err));
    child.once("exit", (code) => {
      if (code !== 0 && code !== null) {
        resolve(new Error(`Embedded server exited with code ${code}`));
      }
    });
  });
  return { java, spawnError, logPath };
}

// ----------------------------------------------------------------------------

/**
 * Checks whether the app is configured to launch at OS login.
 *
 * Used by {@link buildAppMenu} to set the "Launch at Login" checkbox state,
 * and by {@link toggleLoginItem} to determine the next toggle value.
 *
 * @returns {boolean} `true` if the app is registered as a login item.
 */
function isLoginItemEnabled() {
  try {
    return app.getLoginItemSettings().openAtLogin === true;
  } catch (_) {
    return false;
  }
}

/**
 * Toggles the "Launch at Login" OS setting and rebuilds the app menu to
 * reflect the new state.
 *
 * Triggered by the user clicking the "Launch at Login" checkbox in the
 * application menu. When enabled, the app starts hidden (`openAsHidden`)
 * so it waits silently for the global hotkey.
 */
function toggleLoginItem() {
  const next = !isLoginItemEnabled();
  // openAsHidden keeps the window from popping up at login — the app sits
  // idle in the background until the global hotkey fires.
  app.setLoginItemSettings({ openAtLogin: next, openAsHidden: true });
  buildAppMenu(); // refresh checkbox
}

/**
 * Constructs and installs the application menu bar.
 *
 * On macOS the first submenu is the app menu, containing About, the "Launch
 * at Login" toggle, hide/unhide, and quit. All platforms get Edit, View, and
 * Window menus with standard roles.
 *
 * Called once at startup by {@link ensureServerThenCreateWindow} and again by
 * {@link toggleLoginItem} whenever the login-item checkbox changes.
 */
function buildAppMenu() {
  const isMac = process.platform === "darwin";
  const template = [
    ...(isMac
      ? [
          {
            label: APP_NAME,
            submenu: [
              { role: "about" },
              { type: "separator" },
              {
                label: "Launch at Login",
                type: "checkbox",
                checked: isLoginItemEnabled(),
                click: toggleLoginItem,
              },
              { type: "separator" },
              { role: "hide" },
              { role: "hideOthers" },
              { role: "unhide" },
              { type: "separator" },
              { role: "quit" },
            ],
          },
        ]
      : []),
    {
      // The File menu is Electron-only — the web build has no equivalent
      // affordance and issue #18 deliberately scopes the "additional main
      // window" feature to the packaged app. Windows/Linux get the same
      // submenu as macOS; the accelerators follow platform conventions
      // (Cmd+N on macOS, Ctrl+N elsewhere).
      label: "File",
      submenu: [
        {
          label: "New Window",
          accelerator: "CmdOrCtrl+N",
          click: () => {
            // Open an additional main window with a fresh window id. The
            // renderer reads `?window=<id>` from the URL and routes every
            // piece of per-window state — tabs, theme, sidebar widths —
            // through the server under that id, so a new window comes up
            // with its own clean slate (tabs default to "Tab 1", theme
            // to the project default) until the user customises it.
            const win = createWindow();
            win.focus();
          },
        },
        { type: "separator" },
        {
          // "Close Window" on both platforms — maps to `close` (not
          // `quit`) so Cmd/Ctrl+W closes just the focused window, and
          // the remaining windows keep running. On macOS this mirrors
          // the Safari/Finder convention; on Windows/Linux it matches
          // the typical browser File-menu shape.
          label: "Close Window",
          accelerator: "CmdOrCtrl+W",
          role: "close",
        },
        ...(isMac
          ? []
          : [
              { type: "separator" },
              // Non-Mac platforms keep Quit in the File menu per
              // convention (Mac puts it in the app menu).
              { role: "quit" },
            ]),
      ],
    },
    {
      label: "Edit",
      submenu: [
        { role: "undo" },
        { role: "redo" },
        { type: "separator" },
        { role: "cut" },
        { role: "copy" },
        { role: "paste" },
        { role: "selectAll" },
      ],
    },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { role: "forceReload" },
        { role: "toggleDevTools" },
        { type: "separator" },
        { role: "resetZoom" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { type: "separator" },
        { role: "togglefullscreen" },
      ],
    },
    {
      label: "Window",
      submenu: [
        { role: "minimize" },
        { role: "zoom" },
        ...(isMac ? [{ type: "separator" }, { role: "front" }] : [{ role: "close" }]),
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// --- Window chrome theming --------------------------------------------------
//
// The renderer computes the active theme's titlebar colour from the resolved
// palette (ResolvedPalette.Chrome.titlebar) and sends it here whenever the
// theme or appearance changes. We paint it via BrowserWindow.setBackgroundColor.
//
// On macOS the *default* native title bar is opaque and system-drawn — it
// ignores the window's backgroundColor entirely. For the theme tint to be
// visible, the main window is created with titleBarStyle: "hiddenInset":
// the native bar is hidden, the window background shows through where it
// used to be, and the traffic lights stay natively positioned on top. See
// createWindow().

/**
 * IPC handler: tints the sender's BrowserWindow background to the given CSS
 * colour string so the native macOS title bar adopts the theme colour.
 *
 * Called by the web renderer from `applyAppearanceClass` whenever the theme
 * or appearance changes. The colour is the `--t-chrome-titlebar` value from
 * the resolved palette.
 *
 * @param {Electron.IpcMainEvent} event  IPC event (sender identifies window).
 * @param {string} color                  Hex colour string like `#002b36`.
 */
ipcMain.handle("set-window-background-color", (event, color) => {
  if (typeof color !== "string" || !color) return;
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win && !win.isDestroyed()) win.setBackgroundColor(color);
});

/**
 * IPC handler: reply with the Electron display id the sender's
 * BrowserWindow currently lives on. Consumed by the Kotlin/JS
 * `WindowRegistryClient` so server-persisted registry entries record the
 * monitor a window was last seen on; the next cold start uses that hint
 * to place the window back on the same physical display.
 *
 * @param {Electron.IpcMainEvent} event  IPC event (sender identifies window).
 * @returns {string|null}               display id, or null on failure.
 */
ipcMain.handle("get-current-window-display-id", (event) => {
  try {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win || win.isDestroyed()) return null;
    return displayIdForBounds(win.getBounds());
  } catch (_) {
    return null;
  }
});

/**
 * IPC handler: toggles whether the main BrowserWindow renders the themed
 * (custom) title bar in place of the native OS one. Because `titleBarStyle`
 * is immutable after window creation, we destroy the current main window and
 * rebuild it with the new style. The server keeps every bit of user-facing
 * state (PTY sessions, layout, focus, settings), so the reload is
 * visual-only.
 *
 * Called by the Kotlin/JS renderer when the user toggles the setting in the
 * Settings panel, and once at startup when the renderer reconciles the
 * cached electron-side value with the server-side source of truth.
 *
 * @param {Electron.IpcMainEvent} _event
 * @param {boolean} enabled  `true` → themed chrome, `false` → native title bar.
 */
ipcMain.handle("set-custom-title-bar", (event, enabled) => {
  const next = enabled === true;
  if (next === chromePrefs.customTitleBar) return;
  chromePrefs = { ...chromePrefs, customTitleBar: next };
  saveChromePrefs(chromePrefs);
  // Recreate every open BrowserWindow with the new titleBarStyle. The server
  // owns every piece of user-facing state (PTY sessions, layout, settings)
  // so destroying and respawning the windows is purely visual. Each new
  // window keeps the same window id so the renderer's per-window state
  // namespacing survives the reload.
  const snapshot = Array.from(mainWindows.entries()).map(([id, win]) => ({
    id,
    bounds: !win.isDestroyed() ? win.getBounds() : null,
  }));
  // Identify the sender so we can raise its replacement at the end.
  const senderWin = BrowserWindow.fromWebContents(event.sender);
  const senderId = senderWin && senderWin.__termtasticWindowId;
  // Destroy first, recreate second — `titleBarStyle` is an immutable
  // construction option so we can't mutate in place.
  for (const [, win] of mainWindows) {
    if (!win.isDestroyed()) win.destroy();
  }
  mainWindows.clear();
  mainWindow = null;
  let reborn = null;
  for (const entry of snapshot) {
    const w = createWindow({ windowId: entry.id, bounds: entry.bounds || undefined });
    if (entry.id === senderId) reborn = w;
  }
  if (reborn) reborn.focus();
});

// ----------------------------------------------------------------------------

/**
 * Append or replace the `window` query parameter on a URL.
 *
 * The Kotlin/JS renderer reads this parameter on boot to know which window
 * id it is running inside. Passing it via the URL (rather than, say, via
 * an IPC round-trip) means the id is present on the very first paint.
 *
 * @param {string} baseUrl - the URL to augment
 * @param {string} windowId - the window id to attach
 * @returns {string} the URL with `window=<id>` set in the query string
 */
function urlWithWindowId(baseUrl, windowId) {
  try {
    const u = new URL(baseUrl);
    u.searchParams.set("window", windowId);
    return u.toString();
  } catch (_) {
    // Fallback for non-standard URLs (the dev-mode `data:` unreachable page
    // already lives elsewhere; this path is only hit for regular http URLs).
    const sep = baseUrl.includes("?") ? "&" : "?";
    return `${baseUrl}${sep}window=${encodeURIComponent(windowId)}`;
  }
}

/**
 * Creates a main application BrowserWindow and loads the web app.
 *
 * The window is configured with context isolation and no Node integration
 * for security; renderer-side Electron APIs are exposed exclusively through
 * the preload script. A `did-fail-load` listener is attached to show a
 * user-friendly "server unreachable" page if the backend cannot be reached.
 *
 * Each window carries a unique [windowId] (auto-generated if the caller
 * doesn't supply one) that is appended to the URL as `?window=<id>` so the
 * renderer can namespace future per-window state to that id. The window is
 * registered in {@link mainWindows}, and {@link mainWindow} is updated to
 * point at the newly created one — so legacy single-window code paths (the
 * global-hotkey showAndFocus, the "server unreachable" error page, etc.)
 * continue to target the most recently created window.
 *
 * Called by {@link ensureServerThenCreateWindow} once the server is confirmed
 * reachable (or on error, to display a diagnostic page), by
 * {@link showAndFocus} when every window was previously closed on macOS,
 * and by the `File > New Window` menu entry which spawns an additional
 * top-level BrowserWindow.
 *
 * @param {object} [opts]
 * @param {string} [opts.windowId] - explicit id for this window (auto-
 *   generated when omitted)
 * @param {{x:number,y:number,width:number,height:number}} [opts.bounds] -
 *   screen geometry to restore; omitted for the default 1280x800 centre
 * @returns {Electron.BrowserWindow}
 */
function createWindow(opts = {}) {
  const windowId = opts.windowId || makeWindowId();
  const bounds = opts.bounds && Number.isFinite(opts.bounds.width) && Number.isFinite(opts.bounds.height)
    ? opts.bounds
    : null;

  const browserOptions = {
    width: bounds?.width ?? 1280,
    height: bounds?.height ?? 800,
    minWidth: 720,
    minHeight: 480,
    title: "Termtastic",
    icon: path.join(__dirname, "icons", "icon.png"),
    // Hide the native macOS title bar (in favour of the themed chrome) when
    // `chromePrefs.customTitleBar` is true. The `titleBarStyle` option is
    // immutable after creation, so toggling the setting destroys and
    // recreates this window (see the `set-custom-title-bar` IPC handler).
    titleBarStyle: chromePrefs.customTitleBar ? "hiddenInset" : "default",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  };
  if (bounds && Number.isFinite(bounds.x) && Number.isFinite(bounds.y)) {
    browserOptions.x = bounds.x;
    browserOptions.y = bounds.y;
  }

  const win = new BrowserWindow(browserOptions);
  mainWindow = win;
  mainWindows.set(windowId, win);

  // Stash the window id on the BrowserWindow itself (non-enumerable-ish
  // via a plain property) so IPC handlers that receive an event.sender
  // can map back to the id without consulting mainWindows.
  win.__termtasticWindowId = windowId;

  loadApp(win, windowId);

  win.webContents.on("did-fail-load", (_event, errorCode, errorDescription) => {
    // -3 is ABORTED (e.g. an in-flight load was replaced) — ignore.
    if (errorCode === -3) return;
    showUnreachableOn(win, errorDescription);
  });

  // Track focus so `mainWindow` keeps pointing at whatever the user just
  // interacted with. Matters for the global hotkey and the "raise the app"
  // paths that predate multi-window and operate on the primary reference.
  win.on("focus", () => {
    mainWindow = win;
  });

  // Drop the window from our map when it's closed. The registry-side
  // cleanup (DELETE /api/windows/<id>) is done from the renderer on
  // `beforeunload` where it still has the auth token in scope; here we
  // just keep the local mirror (used for cold-start restore) in sync.
  win.on("closed", () => {
    mainWindows.delete(windowId);
    if (mainWindow === win) {
      mainWindow = mainWindows.values().next().value || null;
    }
    persistWindowsMirror();
  });

  // Keep the mirror up to date as the user moves/resizes windows. The
  // server-side registry is updated independently from the renderer (it
  // owns the auth token); the mirror is what the main process reads on
  // the *next* cold start to know how many BrowserWindows to spawn.
  win.on("move", persistWindowsMirror);
  win.on("resize", persistWindowsMirror);
  persistWindowsMirror();

  return win;
}

/**
 * Navigates a main BrowserWindow to the Termtastic web app URL, tagged with
 * its window id so the renderer knows which window it is running inside.
 *
 * Separated from {@link createWindow} so the load can be retried independently
 * (e.g. after a server-unreachable error is resolved by the user).
 *
 * @param {Electron.BrowserWindow} [win] - target window; defaults to the
 *   most recently created window ({@link mainWindow})
 * @param {string} [windowId] - explicit window id; defaults to the id stored
 *   on `win` by {@link createWindow}
 */
function loadApp(win, windowId) {
  const target = win || mainWindow;
  if (!target) return;
  const id = windowId || target.__termtasticWindowId;
  target.loadURL(id ? urlWithWindowId(TARGET_URL, id) : TARGET_URL);
}

/**
 * Brings Termtastic to the foreground from any context.
 *
 * Handles multiple scenarios: the window may be minimized, hidden behind
 * other apps, on another macOS Space, or already closed (macOS keeps the
 * process alive). In the latter case the window is recreated.
 *
 * Invoked by the global hotkey handler, the `second-instance` event (when
 * the user relaunches the app), and the macOS `activate` event (dock click).
 */
function showAndFocus() {
  // With multi-window support the "summon the app" gesture has to raise
  // every main window (so the user gets back whatever they had open), not
  // just `mainWindow`. The last-focused one gets focused last so it wins
  // the Space-level activation.
  const candidates = Array.from(mainWindows.values()).filter((w) => w && !w.isDestroyed());
  if (candidates.length === 0) {
    createWindow();
    return;
  }
  const last = mainWindow && !mainWindow.isDestroyed() ? mainWindow : candidates[candidates.length - 1];
  for (const w of candidates) {
    if (w === last) continue;
    if (w.isMinimized()) w.restore();
    w.show();
  }
  if (last.isMinimized()) last.restore();
  last.show();
  last.focus();
  // On macOS, focusing a BrowserWindow isn't enough to pull the app to the
  // front from another Space / another active app. `app.focus({ steal: true })`
  // is the macOS-only escape hatch that does.
  if (process.platform === "darwin") app.focus({ steal: true });
}

/**
 * Replaces the main window's content with a styled "server unreachable"
 * error page.
 *
 * The page includes the target URL, an optional hint (e.g. how to install
 * Java), a retry button, and the raw error description. Rendered as an
 * inline `data:` URL so no server connectivity is required.
 *
 * Called by {@link createWindow}'s `did-fail-load` handler and by
 * {@link ensureServerThenCreateWindow} when the embedded server fails to
 * start or times out.
 *
 * @param {string} [errorDescription] - Technical error string shown in
 *   small grey text at the bottom of the card.
 * @param {string} [hint] - HTML string with a user-facing suggestion.
 *   Defaults to a "Start the server with `./gradlew :server:run`" message.
 */
function showUnreachable(errorDescription, hint) {
  showUnreachableOn(mainWindow, errorDescription, hint);
}

/**
 * Variant of {@link showUnreachable} that targets an explicit window.
 *
 * Needed now that multiple BrowserWindows can be alive — each window's own
 * `did-fail-load` listener wants to render the error page into itself,
 * not into whichever window happens to be {@link mainWindow} right now.
 *
 * @param {Electron.BrowserWindow} win
 * @param {string} [errorDescription]
 * @param {string} [hint]
 */
function showUnreachableOn(win, errorDescription, hint) {
  if (!win || win.isDestroyed()) return;
  const hintHtml = hint
    ? `<p>${hint}</p>`
    : `<p>Start the server with:</p><p><code>./gradlew :server:run</code></p>`;
  const html = `
    <!doctype html>
    <html>
      <head>
        <meta charset="utf-8" />
        <title>Termtastic — server unreachable</title>
        <style>
          body {
            font-family: -apple-system, system-ui, sans-serif;
            background: #1e1e1e;
            color: #e0e0e0;
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100vh;
            margin: 0;
          }
          .card {
            max-width: 520px;
            padding: 32px;
            background: #2a2a2a;
            border-radius: 8px;
            box-shadow: 0 4px 16px rgba(0,0,0,0.4);
          }
          h1 { margin-top: 0; font-size: 18px; }
          code {
            background: #111;
            padding: 2px 6px;
            border-radius: 4px;
            font-size: 13px;
          }
          button {
            margin-top: 16px;
            padding: 8px 16px;
            background: #0a84ff;
            color: white;
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
          }
          button:hover { background: #006fe0; }
          .err { color: #888; font-size: 12px; margin-top: 12px; }
        </style>
      </head>
      <body>
        <div class="card">
          <h1>Can't reach the Termtastic server</h1>
          <p>Tried to load <code>${TARGET_URL}</code>.</p>
          ${hintHtml}
          <button onclick="location.reload()">Retry</button>
          <div class="err">${errorDescription || ""}</div>
        </div>
      </body>
    </html>
  `;
  win.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
}

/**
 * Orchestrates the full startup sequence: menu setup, server bootstrap, and
 * window creation.
 *
 * The logic follows this decision tree:
 * 1. If `TERMTASTIC_URL` is set (dev mode), skip server management entirely
 *    and load that URL.
 * 2. If `PROD_PORT` is already listening, reuse the existing server (supports
 *    app restarts without killing PTY sessions).
 * 3. Otherwise, locate and spawn the bundled server jar, then race
 *    {@link waitForPort} against a spawn-error promise so a missing JDK
 *    surfaces immediately rather than hanging for 30 seconds.
 *
 * Called once from the `app.whenReady()` handler.
 *
 * @returns {Promise<void>}
 */
/**
 * Open every window listed in the local mirror, or a single fresh window
 * when the mirror is empty (first launch, corrupt file, etc.).
 *
 * Called by {@link ensureServerThenCreateWindow} for each branch (dev URL,
 * reused server, freshly spawned server, missing jar). Always returns at
 * least one window so downstream callers can unconditionally talk to
 * `mainWindow` for error-page rendering.
 *
 * @returns {Electron.BrowserWindow} the last-created window (the one the
 *   rest of the startup path treats as the "primary" for error UI)
 */
function restoreOrCreateWindows() {
  const mirror = loadWindowsMirror();
  if (mirror.length === 0) {
    return createWindow();
  }
  let last = null;
  for (const entry of mirror) {
    // Clamp to a still-attached display. Without this, a window last
    // seen on a second monitor that has since been unplugged would open
    // offscreen and be invisible to the user.
    const bounds = clampEntryToAttachedDisplay(entry);
    last = createWindow({
      windowId: entry.id,
      bounds,
    });
  }
  return last;
}

async function ensureServerThenCreateWindow() {
  buildAppMenu();

  // Dev escape hatch: TERMTASTIC_URL bypasses all bootstrap and just loads the
  // user-provided URL. Used by `:electron:run` to talk to the dev server.
  if (URL_OVERRIDE) {
    restoreOrCreateWindows();
    return;
  }

  // Already running? Reuse it. This is also what makes "relaunch the app
  // without losing PTY state" work after the first launch.
  if (await isPortListening(PROD_PORT)) {
    restoreOrCreateWindows();
    return;
  }

  // Need to start the bundled jar.
  const jarPath = findServerJar();
  if (!jarPath) {
    // Dev electron with no jar staged — fall through and let the existing
    // did-fail-load handler render the "server unreachable" page.
    restoreOrCreateWindows();
    return;
  }

  let spawned;
  try {
    spawned = spawnEmbeddedServer(jarPath);
  } catch (err) {
    restoreOrCreateWindows();
    showUnreachable(
      String(err && err.message ? err.message : err),
      `Couldn't launch the embedded server. Make sure Java 17+ is installed (Homebrew: <code>brew install openjdk</code>) and discoverable via <code>JAVA_HOME</code> or <code>/usr/libexec/java_home</code>.`
    );
    return;
  }

  // Race the port poll against an early child error so we don't hang 30 s on
  // a missing JDK.
  const result = await Promise.race([
    waitForPort(PROD_PORT, 30000).then((ok) => ({ kind: "port", ok })),
    spawned.spawnError.then((err) => ({ kind: "error", err })),
  ]);

  restoreOrCreateWindows();

  if (result.kind === "error") {
    showUnreachable(
      `${result.err.message} (tried java at: ${spawned.java})`,
      `Couldn't launch the embedded server. Make sure Java 17+ is installed (Homebrew: <code>brew install openjdk</code>) and discoverable via <code>JAVA_HOME</code> or <code>/usr/libexec/java_home</code>.`
    );
  } else if (!result.ok) {
    const logHint = spawned.logPath
      ? `Check the server log at <code>${spawned.logPath}</code> for details.`
      : `Check Console.app for crashes from the bundled <code>java -jar server.jar</code> process.`;
    showUnreachable(
      `Timed out waiting for the embedded server on port ${PROD_PORT}.`,
      `The embedded server didn't come up in time. ${logHint}`
    );
  }
}

/**
 * Registers the global keyboard shortcut that summons the app from any
 * context (even when another app is focused).
 *
 * The accelerator is {@link SUMMON_ACCELERATOR} (`Ctrl+Alt+Cmd+Space`).
 * If registration fails (e.g. another app already owns the combo), a
 * warning is logged to stderr.
 *
 * Called once from the `app.whenReady()` handler. The shortcut is
 * automatically unregistered in the `will-quit` handler.
 */
function registerGlobalShortcut() {
  const ok = globalShortcut.register(SUMMON_ACCELERATOR, showAndFocus);
  if (!ok) {
    console.warn(`Failed to register global shortcut: ${SUMMON_ACCELERATOR}`);
  }
}

app.whenReady().then(async () => {
  // Override the Dock icon on macOS so that dev-mode runs show the Termtastic
  // icon instead of the generic Electron icon. (Packaged builds get this from
  // the embedded .icns, but the Dock API is the only way to set it at runtime.)
  // Must run after app.whenReady() — dock APIs are unavailable before that.
  if (process.platform === "darwin" && app.dock) {
    try {
      // In packaged builds __dirname is inside app.asar where icons/ doesn't
      // exist — fall back to the .icns shipped by electron-builder in Resources.
      const devIcon = path.join(__dirname, "icons", "icon.png");
      const packagedIcon = path.join(process.resourcesPath, "icon.icns");
      const iconPath = app.isPackaged ? packagedIcon : devIcon;
      app.dock.setIcon(iconPath);
    } catch (_) {
      // Cosmetic — never let a missing icon abort startup.
    }
  }

  // Grant notification permissions to the app's own origin. Without this,
  // Electron denies Notification.requestPermission() by default and the
  // renderer never gets the browser permission prompt.
  session.defaultSession.setPermissionRequestHandler((_webContents, permission, callback) => {
    callback(permission === "notifications");
  });
  session.defaultSession.setPermissionCheckHandler((_webContents, permission) => {
    return permission === "notifications";
  });

  await ensureServerThenCreateWindow();
  registerGlobalShortcut();
});

app.on("will-quit", () => {
  globalShortcut.unregisterAll();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("activate", () => {
  // Dock-click on macOS after all windows were closed. Restore the last
  // known set so the user gets back whatever they had open, not just a
  // single fresh window.
  if (BrowserWindow.getAllWindows().length === 0) restoreOrCreateWindows();
});
