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

  // --- Window chrome theming ----------------------------------------------

  /**
   * Tints the current BrowserWindow's background colour so the native macOS
   * title bar (which is translucent by default) picks up the theme colour.
   *
   * Called by the Kotlin/JS renderer from `applyAppearanceClass` whenever
   * the theme or appearance changes. The main process resolves which window
   * to paint from the IPC sender.
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
