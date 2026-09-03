package af.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import af.shizuku.manager.R
import af.shizuku.manager.backup.AppBackupActivity
import af.shizuku.manager.databinding.HomeAppBackupItemBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class AppBackupViewHolder(private val binding: HomeAppBackupItemBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeAppBackupItemBinding.inflate(inflater, outer.cardContent, true)
            AppBackupViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
        root.applySpringTouch()
    }

    private val originalIcon = binding.icon.drawable

    private inline val title get() = binding.text1
    private inline val summary get() = binding.text2
    private inline val iconView get() = binding.icon

    override fun onBind() {
        val context = itemView.context
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_app_backup")
        if (!data.isRunning) {
            itemView.isEnabled = false
            title.setText(R.string.home_backup_title)
            summary.text = context.getString(
                R.string.home_status_service_not_running,
                context.getString(R.string.app_name)
            )
        } else {
            itemView.isEnabled = true
            title.setText(R.string.home_backup_title)
            summary.setText(R.string.home_backup_summary)
        }
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, AppBackupActivity::class.java),
            iconView,
            "icon_backup"
        )
    }
}
