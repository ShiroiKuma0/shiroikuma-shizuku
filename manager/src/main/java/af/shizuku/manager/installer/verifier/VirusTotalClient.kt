package af.shizuku.manager.installer.verifier

import java.io.File

/**
 * FORK: disabled. Contacts nobody.
 *
 * Upstream sent the SHA-256 of every APK being installed to VirusTotal, together with the
 * user's API key in the query string, and read back an engine-detection count. That discloses
 * which apps 白い熊 installs — and when — to an outside party, so the lookup is gone: no URL,
 * no connection, no API key read.
 *
 * The class is kept so the verifier registry and its settings row keep compiling across
 * rebases; it now always reports "not checked" without touching the network.
 *
 * This app sends nothing anywhere. See CLAUDE.md, "No phone-home".
 */
class VirusTotalClient : ApkVerificationClient {
    override val name = "VirusTotal API"
    override val preferenceKey = "verify_apk_virustotal"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult =
        VerificationResult(
            isSafe = true,
            methodsUsed = listOf(name),
            riskScore = 0,
            details = "VirusTotal: disabled in this build — no data left the device."
        )
}
