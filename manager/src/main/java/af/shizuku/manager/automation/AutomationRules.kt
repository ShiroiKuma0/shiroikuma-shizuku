package af.shizuku.manager.automation

import android.content.Context
import android.net.wifi.WifiInfo
import android.os.Build
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

// Calls service.updatePlusFeatureEnabled(key, value) off the main thread.
// Must not be called from the main thread — getService() involves IPC.
private fun syncFeatureToServer(key: String, value: Boolean) {
    try {
        val binder = Shizuku.getBinder() ?: return
        val service = IShizukuService.Stub.asInterface(binder)
        service.updatePlusFeatureEnabled(key, value)
    } catch (e: Exception) {
        Timber.tag("AutomationRules").w(e, "syncFeatureToServer($key=$value) failed")
    }
}

/**
 * Enables or disables the Binder Firewall based on whether the current WiFi SSID is in
 * the user-configured trusted-networks list. The firewall is enabled on untrusted networks
 * (defensive default) and disabled on trusted ones to avoid blocking known-safe callers.
 */
class NetworkFirewallRule : AutomationRule {
    override val name: String = "Network Firewall Rule"
    private var isSafeNetwork: Boolean = false

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
        if (event !is NetworkEvent) return false
        val trustedNetworks = ShizukuSettings.getAutomationTrustedNetworks()
        // An empty trusted list means the user hasn't configured this rule — treat as inactive.
        if (trustedNetworks.isEmpty()) return false

        // ssid is null when SSID detection is unavailable. Null → untrusted.
        val isCurrentlySafe = event.isWifiConnected &&
            event.ssid != null &&
            trustedNetworks.contains(event.ssid)

        return if (isCurrentlySafe != isSafeNetwork) {
            isSafeNetwork = isCurrentlySafe
            true
        } else {
            false
        }
    }

    override fun execute(context: Context) {
        val enable = !isSafeNetwork
        Timber.tag("AutomationRules").i(
            "NetworkFirewallRule: %s → binder_firewall=%b",
            if (isSafeNetwork) "trusted network" else "untrusted network", enable
        )
        ShizukuSettings.setBinderFirewallEnabled(enable)
        syncFeatureToServer("binder_firewall", enable)
    }
}

/**
 * Applies per-app settings profiles when the foreground app changes.
 *
 * Profiles are stored as a JSON object in ShizukuSettings (key = package name, value = object
 * with optional boolean fields). Example structure persisted by the settings UI:
 *   { "com.example.bank": { "binder_firewall": true }, "com.example.game": { "binder_firewall": false } }
 *
 * When an app without an explicit profile comes to the foreground, all managed features are
 * restored to the user's baseline preferences (the non-automation ShizukuSettings values).
 */
class AppSpecificProfileRule : AutomationRule {
    override val name: String = "App Profile Rule"
    private var currentApp: String? = null
    // Snapshot of the user's binder_firewall setting before the first profile override.
    // Null means no profile has been applied yet (baseline not captured).
    private var baselineBinderFirewall: Boolean? = null

    override fun evaluate(event: AutomationEvent, context: Context): Boolean {
        if (event !is ForegroundAppEvent) return false
        val json = ShizukuSettings.getAutomationAppProfilesJson()
        // No profiles configured — treat as inactive.
        if (json == "{}" || json.length <= 2) return false

        return if (event.packageName != currentApp) {
            currentApp = event.packageName
            true
        } else {
            false
        }
    }

    override fun execute(context: Context) {
        val app = currentApp ?: return
        val json = ShizukuSettings.getAutomationAppProfilesJson()

        // Capture the user's baseline before the first override so restoreDefaults() can undo it.
        if (baselineBinderFirewall == null) {
            baselineBinderFirewall = ShizukuSettings.isBinderFirewallEnabled()
        }

        // Minimal JSON parsing without pulling in a full JSON library.
        // Looks for the package-name key and extracts the nested object.
        val profileJson = extractProfileJson(json, app)
        if (profileJson != null) {
            Timber.tag("AutomationRules").i("AppProfileRule: applying profile for %s", app)
            applyProfile(profileJson)
        } else {
            Timber.tag("AutomationRules").i("AppProfileRule: restoring defaults (no profile for %s)", app)
            restoreDefaults()
        }
    }

    private fun applyProfile(profileJson: String) {
        val binderFirewall = extractBooleanField(profileJson, "binder_firewall")
        if (binderFirewall != null) {
            ShizukuSettings.setBinderFirewallEnabled(binderFirewall)
            syncFeatureToServer("binder_firewall", binderFirewall)
        }
    }

    private fun restoreDefaults() {
        val baseline = baselineBinderFirewall ?: return // No baseline yet — nothing to restore.
        Timber.tag("AutomationRules").d("AppProfileRule: restoring baseline binder_firewall=%b", baseline)
        ShizukuSettings.setBinderFirewallEnabled(baseline)
        syncFeatureToServer("binder_firewall", baseline)
    }

    // Extracts the value of a top-level string key from minimal JSON.
    // Returns the nested JSON object string, or null if not found.
    private fun extractProfileJson(json: String, key: String): String? {
        val escaped = "\"${key.replace("\"", "\\\"")}\""
        val idx = json.indexOf(escaped)
        if (idx < 0) return null
        val colonIdx = json.indexOf(':', idx + escaped.length)
        if (colonIdx < 0) return null
        val start = json.indexOf('{', colonIdx)
        if (start < 0) return null
        var depth = 0
        for (i in start until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun extractBooleanField(json: String, key: String): Boolean? {
        val escaped = "\"${key.replace("\"", "\\\"")}\""
        val idx = json.indexOf(escaped)
        if (idx < 0) return null
        val colonIdx = json.indexOf(':', idx + escaped.length)
        if (colonIdx < 0) return null
        val rest = json.substring(colonIdx + 1).trimStart()
        return when {
            rest.startsWith("true") -> true
            rest.startsWith("false") -> false
            else -> null
        }
    }
}
