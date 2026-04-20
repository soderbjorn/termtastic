/**
 * @file Termtastic — Electron main process entry point.
 *
 * Responsibilities:
 * - Bootstraps the embedded Ktor server jar (or connects to an already-running
 *   instance) and creates the main BrowserWindow once the server is reachable.
 * - Enforces single-instance: a second launch refocuses the existing window.
 * - Registers a global hotkey (Ctrl+Alt+Cmd+Space) to summon the app from any
 *   context (other app, other Space).
 * - Manages popout pane windows: each popped-out terminal/pane gets its own
 *   OS-level BrowserWindow; closing it docks the pane back in the main window.
 * - Builds the application menu (including a "Launch at Login" toggle on macOS).
 * - Handles macOS app-lifecycle conventions (keep alive on last window close,
 *   recreate window on dock click).
 */

const { app, BrowserWindow, Menu, globalShortcut, ipcMain, session } = require("electron");
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

/** @type {Map<number, Electron.BrowserWindow>} screenIndex -> BrowserWindow */
const screenWindows = new Map();
/** Legacy alias: screen 0's window. Used in a few places that only care about
 *  the "primary" window (global shortcut, single-instance refocus). */
let mainWindow = null;

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
        {
          label: "New Window",
          accelerator: "CmdOrCtrl+Shift+N",
          click: () => createNewScreen(),
        },
        { type: "separator" },
        { role: "minimize" },
        { role: "zoom" },
        ...(isMac ? [{ type: "separator" }, { role: "front" }] : [{ role: "close" }]),
        ...buildScreenWindowEntries(),
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

/**
 * Builds the per-screen-window entries appended to the Window menu.
 *
 * macOS normally populates this list automatically via `role: 'windowMenu'`,
 * but that role can't be combined with our custom "New Window" item — so we
 * maintain the list by hand. The focused window gets a checkmark; clicking an
 * entry brings that window forward. {@link rebuildMenuOnWindowChanges} wires
 * the rebuild triggers so entries stay in sync with `screenWindows`.
 *
 * @returns {Array<Electron.MenuItemConstructorOptions>} Menu items to append
 *   (empty if no screen windows are open). Includes a leading separator when
 *   non-empty.
 */
function buildScreenWindowEntries() {
  const entries = [];
  const sorted = [...screenWindows.entries()].sort(([a], [b]) => a - b);
  for (const [idx, win] of sorted) {
    if (win.isDestroyed()) continue;
    const baseTitle = win.getTitle() || APP_NAME;
    const label = idx === 0 ? baseTitle : `${baseTitle} — Screen ${idx + 1}`;
    entries.push({
      label,
      type: "checkbox",
      checked: win.isFocused(),
      click: () => {
        if (win.isDestroyed()) return;
        if (win.isMinimized()) win.restore();
        win.show();
        win.focus();
      },
    });
  }
  return entries.length > 0 ? [{ type: "separator" }, ...entries] : [];
}

/**
 * Registers listeners on a screen window so the application menu stays in
 * sync with its title and focus state.
 *
 * Called from {@link createScreenWindow} right after a window is added to
 * `screenWindows`. The window's `closed` handler rebuilds the menu one last
 * time after the map entry is removed.
 *
 * @param {Electron.BrowserWindow} win - The screen window to track.
 */
function rebuildMenuOnWindowChanges(win) {
  const rebuild = () => buildAppMenu();
  win.on("focus", rebuild);
  win.on("blur", rebuild);
  win.webContents.on("page-title-updated", rebuild);
}

// --- Popout windows ---------------------------------------------------------
//
// A "popped out" pane lives in its own BrowserWindow that loads the same web
// app with ?popout=<paneId>. The renderer detects the query param and renders
// only that single pane (no tabs, no sidebar), waiting for the server config
// to resolve the pane's content kind (terminal / markdown / git). The server
// is authoritative about which panes are popped out — see
// WindowState.popOutPane / dockPoppedOut. Closing a popout window via the OS
// close button is signalled back to the main window so the pane gets docked
// back into the tree (otherwise the PTY would be orphaned).

const popoutWindows = new Map(); // paneId -> BrowserWindow

ipcMain.handle("popout-pane", (_event, { paneId, title }) => {
  if (!paneId) return;
  const existing = popoutWindows.get(paneId);
  if (existing && !existing.isDestroyed()) {
    existing.focus();
    return;
  }
  const popout = new BrowserWindow({
    width: 720,
    height: 480,
    minWidth: 400,
    minHeight: 280,
    title: title || "Termtastic",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  const url = `${TARGET_URL}?popout=${encodeURIComponent(paneId)}`;
  popout.loadURL(url);
  popoutWindows.set(paneId, popout);

  popout.on("closed", () => {
    popoutWindows.delete(paneId);
    // User hit the red X (or the popout loaded a new URL). Tell all screen
    // windows to dock the pane back so the PTY isn't orphaned. Broadcast
    // to all screens since any of them may hold the tab containing this pane.
    for (const [, win] of screenWindows) {
      if (!win.isDestroyed()) {
        win.webContents.send("popout-closed", paneId);
      }
    }
  });
});

ipcMain.handle("close-popout", (_event, paneId) => {
  const win = popoutWindows.get(paneId);
  if (win && !win.isDestroyed()) win.close();
});

// ----------------------------------------------------------------------------

/**
 * Creates a BrowserWindow for a given screen index and loads the web app.
 *
 * The window is configured with context isolation and no Node integration
 * for security; renderer-side Electron APIs are exposed exclusively through
 * the preload script. A `did-fail-load` listener is attached to show a
 * user-friendly "server unreachable" page if the backend cannot be reached.
 *
 * @param {number} [screenIndex=0] - The screen index for multi-window support.
 * @param {{ x?: number, y?: number, width?: number, height?: number, displayId?: string }} [bounds]
 *   Optional saved window bounds from the server.
 * @returns {Electron.BrowserWindow} The created window.
 */
function createScreenWindow(screenIndex = 0, bounds) {
  const { screen } = require("electron");
  const opts = {
    width: bounds?.width || 1280,
    height: bounds?.height || 800,
    minWidth: 720,
    minHeight: 480,
    title: "Termtastic",
    icon: path.join(__dirname, "icons", "icon.png"),
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  };

  // Position the window at its saved location if bounds are provided.
  if (bounds && bounds.x != null && bounds.y != null) {
    // Validate that the saved position is within a connected display.
    const displays = screen.getAllDisplays();
    const target = bounds.displayId
      ? displays.find((d) => String(d.id) === String(bounds.displayId))
      : null;
    const display = target || screen.getDisplayNearestPoint({ x: bounds.x, y: bounds.y });
    const { x: dx, y: dy, width: dw, height: dh } = display.workArea;
    // Only apply saved position if it's at least partially on-screen.
    if (bounds.x + 100 > dx && bounds.x < dx + dw && bounds.y + 50 > dy && bounds.y < dy + dh) {
      opts.x = bounds.x;
      opts.y = bounds.y;
    }
  }

  const win = new BrowserWindow(opts);
  screenWindows.set(screenIndex, win);
  if (screenIndex === 0) mainWindow = win;
  rebuildMenuOnWindowChanges(win);
  buildAppMenu();

  const url = screenIndex === 0 ? TARGET_URL : `${TARGET_URL}?screen=${screenIndex}`;
  win.loadURL(url);

  win.webContents.on("did-fail-load", (_event, errorCode, errorDescription) => {
    if (errorCode === -3) return;
    if (screenIndex === 0) showUnreachable(errorDescription);
  });

  // Debounced window bounds persistence: relay move/resize events to the
  // renderer so it can send SetScreenBounds to the server via WebSocket.
  let boundsTimeout = null;
  const sendBounds = () => {
    if (win.isDestroyed()) return;
    const b = win.getBounds();
    const display = screen.getDisplayNearestPoint({ x: b.x, y: b.y });
    win.webContents.send("window-bounds-changed", {
      screenIndex,
      x: b.x,
      y: b.y,
      width: b.width,
      height: b.height,
      displayId: display ? String(display.id) : null,
    });
  };
  const debouncedBounds = () => {
    if (boundsTimeout) clearTimeout(boundsTimeout);
    boundsTimeout = setTimeout(sendBounds, 1000);
  };
  win.on("move", debouncedBounds);
  win.on("resize", debouncedBounds);

  win.on("closed", () => {
    screenWindows.delete(screenIndex);
    if (screenIndex === 0) mainWindow = null;
    buildAppMenu();
  });

  return win;
}

/**
 * Creates the primary (screen 0) BrowserWindow. Legacy wrapper around
 * {@link createScreenWindow} for backward compatibility with the startup
 * flow.
 *
 * Called by {@link ensureServerThenCreateWindow} once the server is confirmed
 * reachable (or on error, to display a diagnostic page), and by
 * {@link showAndFocus} when the window was previously closed on macOS.
 */
function createWindow() {
  createScreenWindow(0);
}

/**
 * Navigates the main window to the Termtastic web app URL.
 *
 * Separated from {@link createWindow} so the load can be retried independently
 * (e.g. after a server-unreachable error is resolved by the user).
 */
function loadApp() {
  mainWindow.loadURL(TARGET_URL);
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
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow();
    return;
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
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
  mainWindow.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
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
 * Fetches persisted screen states from the server and creates BrowserWindows
 * for any additional screens (index > 0) that were open on the last quit.
 *
 * Called after the primary (screen 0) window is created and the server is
 * confirmed reachable. Errors are silently ignored — the user can always
 * open additional windows manually.
 */
async function restoreAdditionalScreens() {
  try {
    const res = await net.fetch(`${TARGET_URL}/api/screen-states`);
    if (!res.ok) return;
    const screens = await res.json();
    if (!Array.isArray(screens)) return;
    for (const s of screens) {
      if (s.screenIndex === 0) continue;
      if (!s.open) continue;
      createScreenWindow(s.screenIndex, s.bounds || undefined);
    }
  } catch (_) {
    // Best effort — server may not support multi-window yet.
  }
}

async function ensureServerThenCreateWindow() {
  buildAppMenu();

  // Dev escape hatch: TERMTASTIC_URL bypasses all bootstrap and just loads the
  // user-provided URL. Used by `:electron:run` to talk to the dev server.
  if (URL_OVERRIDE) {
    createWindow();
    restoreAdditionalScreens();
    return;
  }

  // Already running? Reuse it. This is also what makes "relaunch the app
  // without losing PTY state" work after the first launch.
  if (await isPortListening(PROD_PORT)) {
    createWindow();
    restoreAdditionalScreens();
    return;
  }

  // Need to start the bundled jar.
  const jarPath = findServerJar();
  if (!jarPath) {
    // Dev electron with no jar staged — fall through and let the existing
    // did-fail-load handler render the "server unreachable" page.
    createWindow();
    return;
  }

  let spawned;
  try {
    spawned = spawnEmbeddedServer(jarPath);
  } catch (err) {
    createWindow();
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

  createWindow();

  if (result.kind === "port" && result.ok) {
    restoreAdditionalScreens();
  }

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

// --- Multi-window (screen) management ----------------------------------------

/**
 * Creates a new screen window with the next available screen index.
 *
 * Finds the lowest unused screen index (starting from 1, since 0 is the
 * primary window) and creates a BrowserWindow loading the app with
 * `?screen=N`. The server is notified via the renderer's WebSocket
 * connection (the renderer sends an OpenScreen command on load).
 */
function createNewScreen() {
  const usedIndices = new Set(screenWindows.keys());
  let nextIndex = 1;
  while (usedIndices.has(nextIndex)) nextIndex++;
  createScreenWindow(nextIndex);
}

// IPC: renderer asks which screen index its window was assigned.
ipcMain.handle("get-screen-index", (event) => {
  for (const [idx, win] of screenWindows) {
    if (!win.isDestroyed() && win.webContents === event.sender) return idx;
  }
  return 0;
});

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
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
    restoreAdditionalScreens();
  }
});
