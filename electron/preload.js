/**
 * @file Termtastic — Electron preload script (context bridge).
 *
 * Runs in a sandboxed renderer context with `contextIsolation: true`. Exposes
 * a minimal `window.electronApi` object to the web app via Electron's
 * contextBridge, giving the Kotlin/JS frontend access to:
 *
 * - **getPathForFile** -- resolves a dragged-and-dropped `File` object to its
 *   absolute filesystem path (Electron 32+ replacement for the removed
 *   `File.path` property).
 * - **Popout window management** -- open, close, and listen for closure of
 *   per-pane OS windows that live outside the main BrowserWindow.
 *
 * No Node.js APIs are leaked to the renderer beyond what is listed here.
 */

const { contextBridge, webUtils, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronApi", {
  /**
   * Resolves a drag-and-dropped `File` object to its absolute filesystem path.
   *
   * Replacement for the removed `File.path` property (Electron 32+). Used by
   * the Kotlin/JS drag-and-drop handler to obtain the real path of a dropped
   * file so the server can open it.
   *
   * @param {File} file - A DOM `File` from a drop event's `DataTransfer`.
   * @returns {string} Absolute path on the local filesystem.
   */
  getPathForFile: (file) => webUtils.getPathForFile(file),

  // --- Popout windows ------------------------------------------------------

  /**
   * Opens a new OS-level BrowserWindow for a single pane.
   *
   * The popout window loads the same web app with `?popout=<paneId>` so the
   * renderer enters single-pane mode and resolves the pane's content kind
   * (terminal / markdown / git) from the server config. The server (not
   * Electron) tracks which panes are popped out -- see
   * `WindowState.popOutPane`.
   *
   * If a popout for this paneId already exists, it is focused instead.
   *
   * @param {string} paneId - Unique identifier of the pane to pop out.
   * @param {string} [title] - Window title; defaults to "Termtastic".
   * @returns {Promise<void>}
   */
  popOutPane: (paneId, title) =>
    ipcRenderer.invoke("popout-pane", { paneId, title }),

  /**
   * Closes a popout window programmatically.
   *
   * Used by the "Dock" menu item inside a popout window. The caller is
   * responsible for sending the `dockPoppedOut` command to the server first
   * so the pane is reattached to the main layout.
   *
   * @param {string} paneId - Identifier of the popout window to close.
   * @returns {Promise<void>}
   */
  closePopout: (paneId) => ipcRenderer.invoke("close-popout", paneId),

  /**
   * Registers a callback for when a popout window is closed via the OS close
   * button (red X / Cmd+W).
   *
   * The main window uses this to send a `dockPoppedOut` command to the server
   * so the pane is returned to its tab and the PTY session is not orphaned.
   *
   * @param {function(string): void} callback - Invoked with the `paneId` of
   *   the closed popout window.
   */
  onPopoutClosed: (callback) =>
    ipcRenderer.on("popout-closed", (_event, paneId) => callback(paneId)),

  // --- Window chrome theming ----------------------------------------------

  /**
   * Tints the current BrowserWindow's background colour so the native macOS
   * title bar (which is translucent by default) picks up the theme colour.
   *
   * Called by the Kotlin/JS renderer from `applyAppearanceClass` whenever
   * the theme or appearance changes. The main process resolves which window
   * to paint from the IPC sender, so the same call works for the main
   * window and for popout windows without passing a window id.
   *
   * @param {string} color - CSS hex colour like `#002b36` (no alpha).
   * @returns {Promise<void>}
   */
  setWindowBackgroundColor: (color) =>
    ipcRenderer.invoke("set-window-background-color", color),

  // --- Custom title bar toggle ---------------------------------------------

  /**
   * Toggles the custom (themed) title bar on the Electron main window.
   *
   * The title bar style (`titleBarStyle`) is a window creation option in
   * Electron and cannot be mutated on an existing BrowserWindow, so the
   * main process destroys the current window and creates a new one with the
   * requested style. The server keeps all state (PTYs, layout, settings), so
   * the reload is purely visual.
   *
   * The value is cached locally in `userData/electron-chrome.json` so the
   * next cold start honours the preference without a round-trip to the
   * server.
   *
   * Called by the Kotlin/JS renderer when the user toggles the setting in
   * the Settings panel.
   *
   * @param {boolean} enabled - `true` to hide the native title bar and
   *   render the themed window chrome, `false` to show the native OS
   *   title bar.
   * @returns {Promise<void>}
   */
  setElectronCustomTitleBar: (enabled) =>
    ipcRenderer.invoke("set-custom-title-bar", enabled),
});
