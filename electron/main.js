const { app, BrowserWindow, Menu, globalShortcut, ipcMain } = require("electron");
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

async function waitForPort(port, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await isPortListening(port)) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return false;
}

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

// Returns the path to the server log file. The log directory is created lazily
// on first call. Electron's `app.getPath("logs")` resolves to:
//   macOS:   ~/Library/Logs/Termtastic/
//   Linux:   ~/.config/Termtastic/logs/
//   Windows: %APPDATA%/Termtastic/logs/
function serverLogPath() {
  const logsDir = app.getPath("logs");
  fs.mkdirSync(logsDir, { recursive: true });
  return path.join(logsDir, "server.log");
}

// Returns { spawnError, logPath } — a promise that resolves to an Error if the
// child fails to launch (e.g. java not found), or stays pending forever on
// success. We race this against waitForPort to surface a friendly error fast
// instead of hanging on the 30 s timeout.
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
  javaArgs.push("-jar", jarPath);

  // Pipe server stdout+stderr to a log file. We open an fd and hand it to the
  // child via stdio so the OS keeps the descriptor alive even after Electron
  // exits (the server is detached and outlives us).
  const logPath = serverLogPath();
  const logFd = fs.openSync(logPath, "a");
  const child = spawn(java, javaArgs, {
    detached: true,
    stdio: ["ignore", logFd, logFd],
    env: { ...process.env, TERMTASTIC_PORT: String(PROD_PORT) },
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

function isLoginItemEnabled() {
  try {
    return app.getLoginItemSettings().openAtLogin === true;
  } catch (_) {
    return false;
  }
}

function toggleLoginItem() {
  const next = !isLoginItemEnabled();
  // openAsHidden keeps the window from popping up at login — the app sits
  // idle in the background until the global hotkey fires.
  app.setLoginItemSettings({ openAtLogin: next, openAsHidden: true });
  buildAppMenu(); // refresh checkbox
}

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
        { role: "minimize" },
        { role: "zoom" },
        ...(isMac ? [{ type: "separator" }, { role: "front" }] : [{ role: "close" }]),
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
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
    // User hit the red X (or the popout loaded a new URL). Tell the main
    // window to dock the pane back so the PTY isn't orphaned. If the main
    // window itself is gone (app quitting), skip the send.
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("popout-closed", paneId);
    }
  });
});

ipcMain.handle("close-popout", (_event, paneId) => {
  const win = popoutWindows.get(paneId);
  if (win && !win.isDestroyed()) win.close();
});

// ----------------------------------------------------------------------------

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 720,
    minHeight: 480,
    title: "Termtastic",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  loadApp();

  mainWindow.webContents.on("did-fail-load", (_event, errorCode, errorDescription) => {
    // -3 is ABORTED (e.g. an in-flight load was replaced) — ignore.
    if (errorCode === -3) return;
    showUnreachable(errorDescription);
  });
}

function loadApp() {
  mainWindow.loadURL(TARGET_URL);
}

// Bring Termtastic to the foreground from any context: global hotkey,
// second-instance launch, dock click. Handles the "window was closed but app
// is still alive" case (macOS) by recreating it.
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

async function ensureServerThenCreateWindow() {
  buildAppMenu();

  // Dev escape hatch: TERMTASTIC_URL bypasses all bootstrap and just loads the
  // user-provided URL. Used by `:electron:run` to talk to the dev server.
  if (URL_OVERRIDE) {
    createWindow();
    return;
  }

  // Already running? Reuse it. This is also what makes "relaunch the app
  // without losing PTY state" work after the first launch.
  if (await isPortListening(PROD_PORT)) {
    createWindow();
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

function registerGlobalShortcut() {
  const ok = globalShortcut.register(SUMMON_ACCELERATOR, showAndFocus);
  if (!ok) {
    console.warn(`Failed to register global shortcut: ${SUMMON_ACCELERATOR}`);
  }
}

app.whenReady().then(async () => {
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
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
