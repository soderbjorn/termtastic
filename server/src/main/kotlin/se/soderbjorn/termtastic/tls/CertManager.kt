/**
 * Self-signed TLS keystore lifecycle for Termtastic.
 *
 * On first server start, generates an RSA-2048 self-signed certificate with
 * SAN entries for `localhost`, `127.0.0.1`, and `::1`, persists it as a PKCS12
 * keystore at `AppPaths.tlsDir()/server.p12`, and stores the random keystore
 * password next to it (`keystore.pass`, file mode 0600 on POSIX). On subsequent
 * starts, the existing keystore is loaded.
 *
 * This is the trust anchor pinned by native clients (Android, iOS) via SHA-256
 * fingerprint comparison after a TOFU first-connect. Web/Electron browsers
 * trust it via the standard "click-through self-signed warning" interstitial,
 * with Electron's loopback-accept handler skipping the warning for the bundled
 * app.
 *
 * Called by [Application.main] just before the Ktor `embeddedServer` is built.
 *
 * @see AppPaths.tlsDir
 */
package se.soderbjorn.termtastic.tls

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.AppPaths
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bundle returned by [CertManager.ensureKeystore] containing everything the
 * Ktor `sslConnector` needs to bind a TLS listener, plus the SHA-256 fingerprint
 * of the leaf certificate (logged at startup so users / scripts can verify
 * what their pinned clients should be locked to).
 *
 * @property keystore the loaded PKCS12 [KeyStore] containing the cert + private key.
 * @property alias the alias under which the keypair is stored.
 * @property password the in-memory password chars used for both the keystore
 *   and the private key entry. Treated as a secret; do not log.
 * @property sha256Fingerprint lowercase hex SHA-256 of the leaf certificate's
 *   DER encoding. 64 characters, no separators.
 */
data class KeyStoreBundle(
    val keystore: KeyStore,
    val alias: String,
    val password: CharArray,
    val sha256Fingerprint: String,
)

/**
 * Loads the existing TLS keystore from `AppPaths.tlsDir()` or generates a
 * fresh self-signed one if missing or corrupt.
 *
 * Called once per server boot before the Ktor `sslConnector` is wired up.
 */
object CertManager {

    private val log = LoggerFactory.getLogger(CertManager::class.java)
    private const val ALIAS = "termtastic"
    private const val KEYSTORE_FILE = "server.p12"
    private const val PASSWORD_FILE = "keystore.pass"

    /**
     * Ensure a usable keystore exists on disk and return its in-memory form.
     *
     * Behaviour:
     *  - If `tls/server.p12` and `tls/keystore.pass` both exist and the keystore
     *    loads successfully, returns it as-is.
     *  - Otherwise generates a fresh RSA-2048 self-signed cert (SAN list:
     *    `localhost`, `127.0.0.1`, `::1`; validity 10 years), writes both files,
     *    and returns the new bundle.
     *
     * Regeneration is the only "rotation" mechanism: deleting the keystore and
     * restarting the server forces every pinned client to re-pair. This is by
     * design — pinning detects the change exactly because the cert is new.
     *
     * @return the loaded or freshly generated [KeyStoreBundle].
     */
    fun ensureKeystore(): KeyStoreBundle {
        val dir = AppPaths.tlsDir().apply { mkdirs() }
        val ksFile = dir.resolve(KEYSTORE_FILE)
        val pwFile = dir.resolve(PASSWORD_FILE)

        if (ksFile.isFile && pwFile.isFile) {
            runCatching {
                val pw = pwFile.readText().trim().toCharArray()
                val ks = KeyStore.getInstance("PKCS12").apply {
                    ksFile.inputStream().use { load(it, pw) }
                }
                return KeyStoreBundle(ks, ALIAS, pw, computeFingerprint(ks))
                    .also { logFingerprint(it, regenerated = false) }
            }.onFailure {
                log.warn("Failed to load existing keystore at ${ksFile.absolutePath}; regenerating.", it)
            }
        }

        val pw = randomPasswordChars()
        val ks = buildKeyStore {
            certificate(ALIAS) {
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = 2048
                password = String(pw)
                domains = listOf("localhost", "127.0.0.1", "::1")
                daysValid = 3650
            }
        }

        FileOutputStream(ksFile).use { ks.store(it, pw) }
        pwFile.writeText(String(pw))
        applyPosixSecretMode(pwFile.toPath())
        applyPosixSecretMode(ksFile.toPath())

        return KeyStoreBundle(ks, ALIAS, pw, computeFingerprint(ks))
            .also { logFingerprint(it, regenerated = true) }
    }

    private fun computeFingerprint(ks: KeyStore): String {
        val cert = ks.getCertificate(ALIAS)
            ?: error("Keystore is missing alias '$ALIAS' — cannot derive fingerprint.")
        val der = cert.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomPasswordChars(): CharArray {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    private fun applyPosixSecretMode(path: java.nio.file.Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        // Silently ignore on non-POSIX (Windows) — file ACLs there are best-effort.
    }

    private fun logFingerprint(bundle: KeyStoreBundle, regenerated: Boolean) {
        val verb = if (regenerated) "Generated" else "Loaded"
        log.info("$verb TLS keystore. SHA-256 fingerprint: ${bundle.sha256Fingerprint}")
    }
}
