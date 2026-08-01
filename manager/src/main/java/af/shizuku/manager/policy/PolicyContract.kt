package af.shizuku.manager.policy

/**
 * The wire contract of the device-policy API, in one place, so the provider, the UI and the note
 * handed to the 白い熊 応用管理 chat can never drift apart.
 *
 * The authority is `${applicationId}.policy` — declared that way in the manifest and resolved to
 * `shiroikuma.shizuku.policy`. It is a **new** authority on purpose: `DhizukuProvider`'s authority
 * is the public Dhizuku one that third-party Dhizuku clients bind, and this private contract does
 * not belong in it.
 */
object PolicyContract {

    /** Only for documentation and the `content call` line below — never for addressing. */
    const val AUTHORITY_SUFFIX = ".policy"

    // Methods
    const val METHOD_STATUS = "status"
    const val METHOD_SET_ACCESSIBILITY_BLOCKED = "set_accessibility_blocked"
    const val METHOD_SET_USER_CONTROL_DISABLED = "set_user_control_disabled"
    const val METHOD_SET_USER_RESTRICTION = "set_user_restriction"
    const val METHOD_SET_ALWAYS_ON_VPN = "set_always_on_vpn"
    const val METHOD_SET_CAMERA_DISABLED = "set_camera_disabled"
    const val METHOD_CLEAR_ALL_LOCKS = "clear_all_locks"

    // Extras in
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_BLOCKED = "blocked"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_KEY = "key"
    const val EXTRA_LOCKDOWN = "lockdown"

    // Extras out
    const val EXTRA_IS_DEVICE_OWNER = "is_device_owner"
    const val EXTRA_API_LEVEL = "api_level"
    const val EXTRA_DELEGATED_SCOPES = "delegated_scopes"
    const val EXTRA_REFUSED_RESTRICTIONS = "refused_restrictions"
    const val EXTRA_STEPS = "steps"
    const val EXTRA_STEPS_FAILED = "steps_failed"

    // Errors
    const val ERROR_NOT_AUTHORIZED = "not-authorized"
    const val ERROR_NOT_DEVICE_OWNER = "not-device-owner"
    const val ERROR_UNKNOWN_METHOD = "unknown-method"
    const val ERROR_MISSING_ARG = "missing-argument"
}
