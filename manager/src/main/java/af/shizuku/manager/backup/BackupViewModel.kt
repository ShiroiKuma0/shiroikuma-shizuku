package af.shizuku.manager.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuPlusAPI
import af.shizuku.manager.utils.ShizukuStateMachine
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class BackupViewModel : ViewModel() {

    data class AppEntry(
        val packageName: String,
        val versionName: String,
        val isSystem: Boolean,
        val allowBackup: Boolean
    )

    sealed class UiState {
        object Loading : UiState()
        data class Loaded(val apps: List<AppEntry>) : UiState()
        data class Error(val msg: String) : UiState()
        object ServiceNotRunning : UiState()
    }

    sealed class BackupEvent {
        data class BackupComplete(val pkg: String, val path: String) : BackupEvent()
        data class FreezeChanged(val pkg: String, val nowFrozen: Boolean) : BackupEvent()
        data class Failure(val msg: String) : BackupEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    private val _events = MutableSharedFlow<BackupEvent>()
    val events: SharedFlow<BackupEvent> = _events

    // Packages currently being backed up — drives per-row busy state in the adapter.
    private val _busyPackages = MutableStateFlow<Set<String>>(emptySet())
    val busyPackages: StateFlow<Set<String>> = _busyPackages

    fun loadApps(includeSystem: Boolean = false) {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            if (ShizukuStateMachine.get() != ShizukuStateMachine.State.RUNNING) {
                _state.value = UiState.ServiceNotRunning
                return@launch
            }
            try {
                val bundles = ShizukuPlusAPI.BackupRestorePlus.listInstalledPackages(includeSystem)
                val entries = bundles
                    .mapNotNull { b ->
                        val pkg = b.getString("packageName") ?: return@mapNotNull null
                        AppEntry(
                            packageName = pkg,
                            versionName = b.getString("versionName") ?: "",
                            isSystem = b.getBoolean("isSystem"),
                            allowBackup = b.getBoolean("allowBackup")
                        )
                    }
                    .sortedBy { it.packageName }
                _state.value = UiState.Loaded(entries)
            } catch (e: Exception) {
                Timber.e(e, "loadApps failed")
                _state.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun backupAppData(entry: AppEntry, outputDir: File) {
        val pkg = entry.packageName
        if (pkg in _busyPackages.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busyPackages.value = _busyPackages.value + pkg
            try {
                val pkgDir = File(outputDir, pkg).also { it.mkdirs() }

                ShizukuPlusAPI.BackupRestorePlus.forceStop(pkg)

                val prepared = ShizukuPlusAPI.ApkPatcher.prepareTempDebug(pkg)
                if (!prepared) {
                    _events.emit(BackupEvent.Failure("Could not enable debug mode for $pkg. App may have a split APK or integrity check that blocks reinstall."))
                    return@launch
                }

                val dataPfd = ShizukuPlusAPI.ApkPatcher.streamDataDir(pkg)
                if (dataPfd != null) {
                    val dataFile = File(pkgDir, "data.tar.gz")
                    dataPfd.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(dataFile).use { input.copyTo(it) }
                        }
                    }
                }

                val extPfd = ShizukuPlusAPI.BackupRestorePlus.backupExternalData(pkg)
                if (extPfd != null) {
                    val extFile = File(pkgDir, "external.tar.gz")
                    extPfd.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            FileOutputStream(extFile).use { input.copyTo(it) }
                        }
                    }
                }

                ShizukuPlusAPI.ApkPatcher.restoreOriginal(pkg)
                _events.emit(BackupEvent.BackupComplete(pkg, pkgDir.absolutePath))
            } catch (e: Exception) {
                Timber.e(e, "Backup failed for $pkg")
                // Best-effort restore so the app isn't left in temp-debug state.
                try { ShizukuPlusAPI.ApkPatcher.restoreOriginal(pkg) } catch (ex: Exception) {
                    Timber.w(ex, "restoreOriginal also failed for $pkg")
                }
                _events.emit(BackupEvent.Failure("Backup failed for $pkg: ${e.message}"))
            } finally {
                _busyPackages.value = _busyPackages.value - pkg
            }
        }
    }

    fun toggleFreeze(entry: AppEntry) {
        val pkg = entry.packageName
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wasFrozen = ShizukuPlusAPI.BackupRestorePlus.isAppFrozen(pkg)
                val nowFrozen = if (wasFrozen) {
                    ShizukuPlusAPI.BackupRestorePlus.unfreezeApp(pkg)
                    false
                } else {
                    ShizukuPlusAPI.BackupRestorePlus.freezeApp(pkg)
                    true
                }
                _events.emit(BackupEvent.FreezeChanged(pkg, nowFrozen))
            } catch (e: Exception) {
                Timber.e(e, "toggleFreeze failed for $pkg")
                _events.emit(BackupEvent.Failure("Freeze toggle failed: ${e.message}"))
            }
        }
    }
}
