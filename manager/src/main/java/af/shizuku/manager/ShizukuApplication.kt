package af.shizuku.manager

import af.shizuku.manager.BuildConfig
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.Configuration
import com.topjohnwu.superuser.Shell
import android.content.Intent
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ThemeDelegateImpl
import af.shizuku.core.ui.ThemeDelegateManager
import af.shizuku.manager.utils.AppContextSettingsImpl
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.utils.ActivityLogSettingsImpl
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate
import rikka.shizuku.Shizuku
import timber.log.Timber
import af.shizuku.manager.di.appModule
import af.shizuku.manager.worker.RemoteDbSyncWorker
import android.os.UserManager
import com.airbnb.mvrx.Mavericks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * 白い熊 雫 Application class
 *
 * Initialization order:
 * 1. Static components (native libraries)
 * 2. Settings and managers
 * 3. State machine
 */
class ShizukuApplication : Application(), Configuration.Provider {

    companion object {
        lateinit var appContext: Context
            private set

        /** True only if libadb.so loaded successfully. ADB pairing features must check this. */
        var isAdbNativeAvailable: Boolean = false
            private set
    }

    // WorkManager's own internal background thread (e.g. ForceStopRunnable's Room queries) can
    // throw on an unrecoverable device condition (observed: full-disk SQLiteFullException, which
    // WorkManager converts to an IllegalStateException) entirely inside library code, with no
    // ShizukuPlus frames in the stack. Without a custom TaskExecutor to catch it here, that
    // reaches the process-wide uncaught-exception handler and kills the whole app.
    private val workManagerTaskExecutor: java.util.concurrent.Executor by lazy {
        val delegate = java.util.concurrent.Executors.newFixedThreadPool(4)
        java.util.concurrent.Executor { command ->
            delegate.execute {
                try {
                    command.run()
                } catch (t: Throwable) {
                    Timber.e(t, "WorkManager task threw on internal executor")
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .setTaskExecutor(workManagerTaskExecutor)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base
        // Initialize ShizukuSettings as early as possible
        ShizukuSettings.initialize(base)
    }

    /**
     * Initialize static components (native libraries, etc.)
     */
    private fun initializeStatics() {
        Timber.d("Initializing static components")

        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR).setTimeout(20))

        if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }

        if (atLeast30) {
            try {
                System.loadLibrary("adb")
                isAdbNativeAvailable = true
                Timber.d("Native library 'adb' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                // Log but do NOT rethrow — ADB pairing features degrade gracefully.
                // Common causes: SELinux policy on vendor ROMs, missing system dependency.
                Timber.e(e, "libadb.so failed to load — ADB pairing features disabled")
            }
        }
    }

    /**
     * Keeps AppIconCache honest: trims it under memory pressure (it's sized to 1/4 of max heap
     * and never shrinks on its own otherwise) and drops entries for apps that update/uninstall
     * so a changed icon doesn't keep showing the stale cached one.
     */
    private fun registerIconCacheMaintenance() {
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                af.shizuku.manager.utils.AppIconCache.trimMemory(level)
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java", ReplaceWith("onTrimMemory"))
            override fun onLowMemory() {
                af.shizuku.manager.utils.AppIconCache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        })

        val packageChangeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                af.shizuku.manager.utils.AppIconCache.invalidate(packageName)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Never unregistered — this is an Application-scoped singleton, same lifetime as the process.
        androidx.core.content.ContextCompat.registerReceiver(
            this, packageChangeReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Initialize settings and managers
     */
    private fun initializeManagers() {
        ActivityLogManager.initialize(this, ActivityLogSettingsImpl())
        af.shizuku.manager.database.ScriptSnippetManager.initialize(this)
        af.shizuku.manager.plugin.PlusFeatureRegistry.register(af.shizuku.manager.scripting.ScriptingFeatureModule)

        // Run auto-run snippets each time the Shizuku service transitions to RUNNING.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            ShizukuStateMachine.asFlow()
                .distinctUntilChanged()
                .filter { it == ShizukuStateMachine.State.RUNNING }
                .collect { af.shizuku.manager.database.ScriptSnippetManager.runAutoRunSnippets() }
        }

        // Redeploy the SU bridge dex whenever the server comes up, not just on app self-update
        // (#423 fix, `9dba4bd3`, only covered ACTION_MY_PACKAGE_REPLACED). If the server wasn't
        // running yet at that point, deployBridgeToTmp() silently no-ops with nothing to retry
        // it later — common on devices where an OEM freezer (Xiaomi/HyperOS, Samsung "Sleeping
        // apps") or a dropped wireless-ADB session delays the server past that moment. Cheap and
        // idempotent to just redeploy on every RUNNING transition.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            ShizukuStateMachine.asFlow()
                .distinctUntilChanged()
                .filter { it == ShizukuStateMachine.State.RUNNING && ShizukuSettings.isSuBridgeEnabled() }
                .collect {
                    try {
                        af.shizuku.manager.database.RootCompatHelper.deployBridgeToTmp(this@ShizukuApplication)
                    } catch (e: Exception) {
                        Timber.tag("ShizukuApplication").w(e, "SU bridge redeploy on service start skipped")
                    }
                }
        }
        AppContextManager.initialize(AppContextSettingsImpl())
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()

        // #429: one-time migration — push the legacy custom locale into AppCompat's
        // own per-app-language store so AppCompatDelegate.getApplicationLocales()
        // becomes authoritative going forward (system Settings > App Info > Language
        // integration on API 33+; AppLocalesStorageHelper-backed persistence below
        // it). AppActivity.attachBaseContext() re-syncs LocaleDelegate.defaultLocale
        // from AppCompatDelegate on every activity creation once this flag is set,
        // so AppCompatDelegate genuinely becomes the source of truth, not just a
        // parallel/secondary write MaterialActivity ignores. Runs exactly once, ever.
        if (!ShizukuSettings.hasMigratedToAppCompatLocales()) {
            val tag = ShizukuSettings.getRawLanguageTag() // KEY_LANGUAGE pref, may be null/"SYSTEM"
            if (!tag.isNullOrEmpty() && tag != "SYSTEM") {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            ShizukuSettings.setMigratedToAppCompatLocales()
        }

        // One-time sync so the "Start on boot" toggle (Settings > Behavior) reflects reality from
        // the first time anyone looks at it - see syncStartOnBootDefaultIfNeeded()'s doc comment.
        ShizukuSettings.syncStartOnBootDefaultIfNeeded(this)

        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())

        // Initialize Starter with context
        af.shizuku.manager.starter.Starter.initialize(this)

        if (ShizukuSettings.getWatchdog()) {
            WatchdogService.start(this)
            val userManagerWatchdog = getSystemService(Context.USER_SERVICE) as? UserManager
            if (userManagerWatchdog == null || userManagerWatchdog.isUserUnlocked) {
                try {
                    af.shizuku.manager.worker.WatchdogWorker.schedule(this)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to schedule WatchdogWorker in direct boot")
                }
                af.shizuku.manager.receiver.WatchdogAlarmReceiver.schedule(this)
            }
        }

        // AutomationService is intentionally not started here. Its two registered rules
        // (NetworkFirewallRule, AppSpecificProfileRule) are non-functional stubs with hardcoded
        // demo values; the real opt-in automation feature is pending (#6). ShizukuStateMachine
        // still dispatches ShizukuStateEvent through AutomationEngine as the future hook point.

        Shizuku.addLogListener { appName, packageName, action ->
            ActivityLogManager.log(appName, packageName, action)
        }


        // FORK: the periodic remote-database sync is NOT scheduled. Upstream enqueues a
        // 24-hourly WorkManager job that fetches app-context-db.json from the upstream author's
        // GitHub repo — a recurring, fully automatic call-out from this device that we do not
        // want. The worker class itself is also neutered (see RemoteDbSyncWorker), so a rebase
        // that restores this call still results in no network traffic.
        // This app sends nothing anywhere. See CLAUDE.md, "No phone-home".
    }

    override fun onCreate() {
        ThemeDelegateManager.setDelegate(ThemeDelegateImpl())
        // FORK: install the live 白い熊 雫 theme provider BEFORE any Activity composes, so the very
        // first frame already carries the user's own colours and typeface rather than the static
        // overlay's defaults. See ShiroikumaTheme.
        af.shizuku.manager.shiroikuma.ShiroikumaTheme.install(this)
        // Every DialogFragment gets the house black fill + yellow border automatically. Material's
        // dialog builder overrides the themed window background, and there is no stroke attribute to
        // set, so this is the only way short of editing ~40 files. See ShiroikumaDialogs.
        af.shizuku.manager.shiroikuma.ShiroikumaDialogs.installGlobalStyling(this)
        super.onCreate()

        // 0. Initialize Timber
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Prewarm root check on a background thread early to avoid main thread delays/ANRs
        af.shizuku.manager.utils.EnvironmentUtils.prewarmAsync()

        // 1. Run security check
        if (af.shizuku.manager.security.SecurityGuard.isTampered()) {
            Timber.e("Security violation: Environment tampered!")
            // Optionally: crash or notify user
        }

        // 2. Register persistent crash handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(af.shizuku.manager.utils.CrashHandler(this, defaultHandler))

        // 3. Initialize Mavericks and Koin
        Mavericks.initialize(this)
        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@ShizukuApplication)
            modules(appModule)
        }

        // 2. Initialize static components FIRST to ensure HiddenApiBypass is active
        try {
            initializeStatics()
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize static components")
            if (e is Error) throw e
        }

        // 4. Strict mode for debugging (DEBUG only)
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .penaltyFlashScreen()
                    .build()
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        registerIconCacheMaintenance()

        // 5. Initialize settings and managers
        try {
            initializeManagers()
            if (ShizukuSettings.getWatchdog() && ShizukuSettings.isLiveActivityEnabled()) {
                try {
                    // startForegroundService() is required on API 26+ to start from background;
                    // plain startService() throws BackgroundServiceStartNotAllowedException on API 31+.
                    val liveServiceIntent = Intent(this, af.shizuku.manager.service.ShizukuLiveService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(liveServiceIntent)
                    } else {
                        startService(liveServiceIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ShizukuApplication", "Failed to start ShizukuLiveService", e)
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize managers")
            if (e is Error) throw e
        }

        // 6. Update state machine
        try {
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Failed to update state machine")
        }

        Timber.d("白い熊 雫 ${BuildConfig.VERSION_NAME} initialization complete")
    }
}
