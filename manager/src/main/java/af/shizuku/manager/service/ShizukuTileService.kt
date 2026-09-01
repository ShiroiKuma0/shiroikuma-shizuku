package af.shizuku.manager.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.MainActivity
// FORK FIX: upstream's 954064d3 uses R.string.tile_state_update_failed here without
// importing R, so their own master does not compile. Drop if upstream fixes it.
import af.shizuku.manager.R
import af.shizuku.manager.utils.ShizukuStateMachine

import androidx.work.WorkManager
import af.shizuku.manager.shiroikuma.ShiroikumaToast

class ShizukuTileService : TileService() {

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onStopListening() {
        super.onStopListening()
        ShizukuStateMachine.removeListener(stateListener)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStarting = state == ShizukuStateMachine.State.STARTING

        tile.state = when {
            isRunning -> Tile.STATE_ACTIVE
            isStarting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = when {
            isRunning -> "白い熊 雫: Active"
            isStarting -> "Starting..."
            else -> "白い熊 雫: Off"
        }
        tile.subtitle = when {
            isRunning -> "Running"
            isStarting -> "Please wait"
            else -> "Tap to Start"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStarting = state == ShizukuStateMachine.State.STARTING

        try {
            if (isRunning || isStarting) {
                // Stop Shizuku / cancel WADB worker
                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                updateTile()

                // Cancel worker
                WorkManager.getInstance(this).cancelUniqueWork("adb_start_worker")

                // Stop server if running
                kotlin.runCatching { rikka.shizuku.Shizuku.exit() }

                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                updateTile()
            } else {
                // Attempt to start silently if root is available
                if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    updateTile()
                    com.topjohnwu.superuser.Shell.cmd(af.shizuku.manager.starter.Starter.internalCommand)
                        .submit { result ->
                            // A successful starter only means the command ran; the binder lands a
                            // moment later, so keep STARTING and let the sticky binder listener
                            // promote it. A failed one has no binder coming — settle it now instead
                            // of leaving the tile stuck mid-transition.
                            if (result.isSuccess) ShizukuStateMachine.update()
                            else ShizukuStateMachine.settle()
                            updateTile()
                        }
                } else {
                    // Attempt to start WADB via Worker
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    updateTile()
                    af.shizuku.manager.worker.AdbStartWorker.enqueue(this)
                }
            }
        } catch (e: Exception) {
            ShiroikumaToast.show(this, getString(R.string.tile_state_update_failed, e.localizedMessage), Toast.LENGTH_SHORT)
        }
    }
}
