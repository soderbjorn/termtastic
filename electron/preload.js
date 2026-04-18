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
});
