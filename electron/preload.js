// Bridge a tiny slice of Electron's renderer-side API into the sandboxed
// page. Specifically: getPathForFile, which is the Electron 32+ replacement
// for the removed `File.path` property — used by the drag-and-drop handler
// in main.kt to turn a dropped File into an absolute filesystem path.
const { contextBridge, webUtils, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("electronApi", {
  getPathForFile: (file) => webUtils.getPathForFile(file),

  // --- Popout windows ------------------------------------------------------
  // Open a new OS window for a pane. The popout window loads the same web
  // app with ?popout=<paneId> so the renderer enters single-pane mode and
  // resolves the pane's content kind (terminal / markdown / git) from the
  // server config. The server (not Electron) tracks which panes are popped
  // out — see WindowState.popOutPane.
  popOutPane: (paneId, title) =>
    ipcRenderer.invoke("popout-pane", { paneId, title }),
  // Close a popout window programmatically (used by the "Dock" menu item
  // inside a popout window). The caller is responsible for sending the
  // dockPoppedOut command to the server first.
  closePopout: (paneId) => ipcRenderer.invoke("close-popout", paneId),
  // Register a callback for when a popout window is closed via the OS
  // close button (red X). The main window uses this to dock the pane back
  // into its tab so the PTY session isn't orphaned.
  onPopoutClosed: (callback) =>
    ipcRenderer.on("popout-closed", (_event, paneId) => callback(paneId)),
});
