/**
 * Shared network constants used by both the Ktor server and all client targets
 * (Electron, Android, iOS) to agree on connection endpoints.
 */
package se.soderbjorn.termtastic

/**
 * The TCP port the Ktor backend listens on. Clients use this to open HTTP and
 * WebSocket connections to the server. Defined here in the shared module so
 * every platform target resolves the same value at compile time.
 */
const val SERVER_PORT = 8082