package af.shizuku.manager.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import af.shizuku.manager.R
import af.shizuku.manager.shiroikuma.ShiroikumaToast

class DhizukuAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        ShiroikumaToast.show(context, R.string.dhizuku_device_owner_enabled, Toast.LENGTH_SHORT)
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(context, DhizukuAdminReceiver::class.java)
            dpm.setBackupServiceEnabled(componentName, true)
        } catch (e: Exception) {
            // Ignored, may not be device owner yet or method not available
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        ShiroikumaToast.show(context, R.string.dhizuku_device_owner_disabled, Toast.LENGTH_SHORT)
    }
}
