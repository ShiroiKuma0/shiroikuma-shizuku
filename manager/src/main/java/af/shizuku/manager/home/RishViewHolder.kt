package af.shizuku.manager.home

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeRishBinding
import af.shizuku.manager.shiroikuma.RishSetup
import af.shizuku.manager.shiroikuma.ShiroikumaToast
import af.shizuku.manager.shiroikuma.ShiroikumaViewTheme
import af.shizuku.manager.shiroikuma.showHouse
import af.shizuku.manager.utils.IconStyleHelper
import rikka.core.util.ClipboardUtils
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import java.text.DateFormat
import java.util.Date

/**
 * Home card that reports whether `rish` runs without a consent prompt, and hands over the single
 * command that makes it so.
 *
 * A **fixed** card sitting directly beneath the server-status card: it is setup state, not one of
 * the reorderable feature cards, and it is the first thing worth knowing after whether the server
 * is up.
 *
 * The green light is deliberately evidence-based — see [RishSetup.isWorking]. It turns green only
 * once a shell client has genuinely connected using the auth token, because the terminal's own
 * directory cannot be inspected without root, and a card that guessed would be worse than no card:
 * it would report success for a setup that still prompts on every command.
 */
class RishViewHolder(
    private val binding: HomeRishBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Any?> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeRishBinding.inflate(inflater, outer.cardContent, true)
            RishViewHolder(inner, outer)
        }
    }

    private val originalIcon = binding.icon.drawable

    init {
        binding.button1.setOnClickListener { copySetupCommand(it.context) }
        // The state colour IS the green light. Without this the home applier's body-text pass
        // repaints it on every layout, so "set up" and "not set up" render identically.
        ShiroikumaViewTheme.markColorOwned(binding.text2)
    }

    override fun onBind() {
        val context = itemView.context
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_rish")

        val terminal = RishSetup.installedTerminal(context)
        val summary = binding.text2

        when {
            terminal == null -> {
                summary.setText(R.string.home_rish_summary_no_terminal)
                summary.setTextColor(context.getColor(android.R.color.holo_red_light))
                binding.button1.isVisible = false
            }

            RishSetup.isWorking() -> {
                val at = RishSetup.lastConnectedAt()
                summary.text = if (at > 0) {
                    context.getString(
                        R.string.home_rish_summary_working_since,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(at))
                    )
                } else {
                    context.getString(R.string.home_rish_summary_working)
                }
                // Green rather than the house accent: this is the one place a semantic colour beats
                // palette consistency, since the accent is also ordinary body text and "working"
                // would then look identical to every other line on the screen.
                summary.setTextColor(context.getColor(android.R.color.holo_green_light))
                binding.button1.isVisible = true
                binding.button1.setText(R.string.home_rish_button_copy_again)
            }

            else -> {
                summary.setText(R.string.home_rish_summary_needs_setup)
                summary.setTextColor(context.getColor(android.R.color.holo_red_light))
                binding.button1.isVisible = true
                binding.button1.setText(R.string.home_rish_button_copy)
            }
        }
    }

    private fun copySetupCommand(context: Context) {
        val terminal = RishSetup.installedTerminal(context) ?: run {
            ShiroikumaToast.show(context, R.string.home_rish_summary_no_terminal)
            return
        }
        // Null only when the token cannot be encrypted; handing over a command in that state would
        // write a script that silently never authenticates, so say so instead.
        val command = RishSetup.buildSetupCommand(context, terminal) ?: run {
            ShiroikumaToast.show(context, R.string.home_automation_token_encrypt_failed)
            return
        }
        if (!ClipboardUtils.put(context, command)) {
            ShiroikumaToast.show(context, R.string.home_rish_copy_failed)
            return
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.home_rish_dialog_title)
            .setMessage(context.getString(R.string.home_rish_dialog_message, terminal))
            .setPositiveButton(android.R.string.ok, null)
            .showHouse()
    }

}
