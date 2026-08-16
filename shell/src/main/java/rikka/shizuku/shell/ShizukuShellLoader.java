package rikka.shizuku.shell;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.Os;
import android.text.TextUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import dalvik.system.BaseDexClassLoader;
import rikka.hidden.compat.PackageManagerApis;
import stub.dalvik.system.VMRuntimeHidden;

public class ShizukuShellLoader {

    private static final Logger LOGGER = Logger.getLogger("ShizukuShellLoader");

    // Injected from gradle.properties APP_ID at build time, never spelled out here. This process
    // is spawned fresh by the rish/plus scripts in the CALLING app's UID, so it cannot read the
    // server's runtime-resolved MANAGER_APPLICATION_ID and must know its own manager's id up front.
    // Upstream lists two literals because one server binary serves two applicationIds; this fork
    // never builds the Drop-In flavor, so its id is fixed at build time and the probe below is only
    // a safety net. Injection is what stops a rename or an upstream rewrite silently un-fixing it.
    private static final String PLUS_APPLICATION_ID = BuildConfig.MANAGER_APPLICATION_ID;
    private static final String DROPIN_APPLICATION_ID = "moe.shizuku.privileged.api";

    private static String[] args;
    private static String callingPackage;
    private static Handler handler;
    private static Runnable timeoutCallback;
    private static Runnable waitingNoticeCallback;

    // Transaction code the manager uses to ask us to prove our uid. Code 1 is the binder handoff
    // itself, so the challenge is the next one up. Must agree exactly with
    // VerifiedBinderRequestReceiver.TRANSACTION_IDENTITY_CHALLENGE.
    //
    // ⛔ A HAND-COPIED DEX GOES STALE. This loader runs from "$BASEDIR"/rish_shizuku.dex — a COPY
    // beside the rish script, NOT the copy inside the installed APK — so an app update does not
    // update it, and a manager that has the challenge can be talking to a loader that does not.
    // The script RishSetup generates re-extracts this dex from the APK whenever the APK path
    // changes, which fixes it for any setup done through the app; a hand-made rish is still on its
    // own. So every change here must degrade gracefully on the manager side.
    private static final int TRANSACTION_IDENTITY_CHALLENGE = 2;

    // Fork: the verified-uid entry point. Sending this instead of the public action is what lets an
    // already-granted shell client skip the consent prompt; the manager still falls back to asking
    // whenever the challenge fails or no grant exists yet, so this is never weaker than the public
    // path, only quieter. Suffix must match VerifiedBinderRequestReceiver.ACTION_SUFFIX.
    private static final String ACTION_REQUEST_BINDER_VERIFIED_SUFFIX = ".intent.action.REQUEST_BINDER_VERIFIED";
    private static final String ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";

    private static final Binder receiverBinder = new Binder() {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                IBinder binder = data.readStrongBinder();

                String sourceDir = data.readString();
                if (binder != null) {
                    handler.post(() -> onBinderReceived(binder, sourceDir));
                } else {
                    LOGGER.severe("Server is not running");
                    System.exit(1);
                }
                return true;
            }
            if (code == TRANSACTION_IDENTITY_CHALLENGE) {
                // The manager cannot learn who we are from the broadcast — Intent extras carry no
                // verified sender identity, which is exactly why upstream's "pre-authorized" fast
                // path was a privilege escalation (c7c9f6c8). So it hands us a binder of its own
                // plus a nonce and asks us to call back: on THAT transaction the kernel tells it
                // our real uid, and it can honour an existing grant without prompting.
                //
                // Answered inline on this binder thread rather than posted to the handler: the
                // challenge can arrive before main() reaches Looper.loop(), and a queued reply
                // would then miss the manager's timeout window.
                IBinder identityBinder = data.readStrongBinder();
                String nonce = data.readString();
                if (identityBinder != null) {
                    Parcel out = Parcel.obtain();
                    try {
                        out.writeString(nonce);
                        identityBinder.transact(1, out, null, IBinder.FLAG_ONEWAY);
                    } catch (Throwable tr) {
                        // Non-fatal: the manager falls back to asking the user for consent.
                        LOGGER.warning("Failed to answer identity challenge: " + tr);
                    } finally {
                        out.recycle();
                    }
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // This process is spawned fresh via app_process by the "rish"/"plus" shell scripts, in the
    // calling app's own UID - it has no way to see the server's runtime-resolved
    // ServerConstants.MANAGER_APPLICATION_ID, so it must independently work out which flavor is
    // actually installed. Same class of bug as #371's ServiceStarter.kt fix: a hardcoded
    // upstream's own manager id here means REQUEST_BINDER never reaches anyone on a Drop-In-only
    // install, since Intent.setPackage() silently drops the broadcast when that package isn't
    // present (rish then just times out after 15s with a misleading "connection may be blocked"
    // message).
    private static String resolveManagerPackageName() {
        if (PackageManagerApis.getApplicationInfoNoThrow(PLUS_APPLICATION_ID, 0, 0) != null) {
            return PLUS_APPLICATION_ID;
        }
        if (PackageManagerApis.getApplicationInfoNoThrow(DROPIN_APPLICATION_ID, 0, 0) != null) {
            return DROPIN_APPLICATION_ID;
        }
        return PLUS_APPLICATION_ID;
    }

    private static void requestForBinder() throws RemoteException {
        Bundle data = new Bundle();
        data.putBinder("binder", receiverBinder);

        String authToken = System.getenv("SHIZUKU_TOKEN");

        String managerPackage = resolveManagerPackageName();
        // ALWAYS the verified action when talking to our own manager, token or not. Routing a
        // token-carrying request to the public action instead — which this did — means only the
        // token is ever checked: the identity challenge never runs, so a stored per-uid grant is
        // never consulted, and a user who answers the consent prompt is asked again on the very
        // next command because their answer has no path that reads it. VerifiedBinderRequestReceiver
        // checks the token FIRST (no round trip, so no latency cost), then the challenge, and only
        // then hands over to the public consent flow. The token still travels in the extras for
        // that last hop.
        //
        // Only OUR manager declares the verified receiver. The Drop-In flavor is never built by
        // this fork, but resolveManagerPackageName() can still land on that id if something else
        // occupies it — such a manager gets the public action, which every manager understands.
        String action = PLUS_APPLICATION_ID.equals(managerPackage)
                ? managerPackage + ACTION_REQUEST_BINDER_VERIFIED_SUFFIX
                : ACTION_REQUEST_BINDER;

        Intent intent = new Intent(action)
                .setPackage(managerPackage)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("data", data);

        if (!TextUtils.isEmpty(authToken)) {
            intent.putExtra("auth", authToken);
        }

        if (!TextUtils.isEmpty(callingPackage)) {
            intent.putExtra("callingPackage", callingPackage);
        }
        // Always include the UID. Receivers use this when PackageManager.getApplicationInfo()
        // is unavailable (e.g. classic rish_shizuku.dex omits callingPackage, or PM lookup
        // fails). This UID is authoritative: this process runs as the caller's UID.
        intent.putExtra("callingUid", Os.getuid());

        IBinder amBinder = ServiceManager.getService("activity");
        IActivityManager am;
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder);
        } else {
            am = ActivityManagerNative.asInterface(amBinder);
        }

        try {
            am.broadcastIntent(null, intent, null, null, 0, null, null,
                    null, -1, null, true, false, 0);
        } catch (Throwable e) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
                    || !Objects.equals(e.getMessage(), "Calling application did not provide package name")) {
                throw e;
            }

            LOGGER.warning("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity");

            Intent baseActivityIntent = new Intent(ACTION_REQUEST_BINDER)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("data", data);

            if (!TextUtils.isEmpty(authToken)) {
                baseActivityIntent.putExtra("auth", authToken);
            }

            Intent activityIntent = Intent.createChooser(
                    baseActivityIntent,
                    "Request binder from Shizuku"
            );

            am.startActivityAsUser(null, callingPackage, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000);
        }
    }

    private static void onBinderReceived(IBinder binder, String sourceDir) {
        if (timeoutCallback != null) {
            handler.removeCallbacks(timeoutCallback);
        }
        // Cancelled before it can print: on an authorized path the binder is here in milliseconds,
        // and the "waiting for authorization" line would otherwise precede every single command.
        if (waitingNoticeCallback != null) {
            handler.removeCallbacks(waitingNoticeCallback);
        }

        var base = sourceDir.substring(0, sourceDir.lastIndexOf('/'));
        String librarySearchPath = base + "/lib/" + VMRuntimeHidden.getRuntime().vmInstructionSet();
        String systemLibrarySearchPath = System.getProperty("java.library.path");
        if (!TextUtils.isEmpty(systemLibrarySearchPath)) {
            librarySearchPath += File.pathSeparatorChar + systemLibrarySearchPath;
        }

        try {
            var classLoader = new BaseDexClassLoader(sourceDir, null, librarySearchPath, ClassLoader.getSystemClassLoader());
            String className = "plus".equals(System.getProperty("shizuku.cmd")) 
                ? "af.shizuku.manager.shell.PlusShell" 
                : "af.shizuku.manager.shell.Shell";
            Class<?> cls = classLoader.loadClass(className);
            cls.getDeclaredMethod("main", String[].class, String.class, IBinder.class, Handler.class)
                    .invoke(null, args, callingPackage, binder, handler);
        } catch (ClassNotFoundException tr) {
            abort("Class not found. Make sure you have Shizuku v12.0.0 or above installed.: " + tr, tr);
        } catch (Throwable tr) {
            // invoke() wraps the target method's own exceptions in InvocationTargetException,
            // whose toString()/message carry nothing useful - unwrap to the real cause.
            Throwable cause = (tr instanceof InvocationTargetException && tr.getCause() != null) ? tr.getCause() : tr;
            abort("Failed to load shell class: " + cause, cause);
        }
    }

    public static void main(String[] args) {
        ShizukuShellLoader.args = args;

        String packageName;
        var pkg = PackageManagerApis.getPackagesForUidNoThrow(Os.getuid());
        if (pkg.size() == 1) {
            packageName = pkg.get(0);
        } else {
            packageName = System.getenv("RISH_APPLICATION_ID");
            if (TextUtils.isEmpty(packageName) || "PKG".equals(packageName)) {
                abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)");
                System.exit(1);
            }
        }

        ShizukuShellLoader.callingPackage = packageName;

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        handler = new Handler(Looper.getMainLooper());

        try {
            requestForBinder();
        } catch (Throwable tr) {
            abort("Failed to request binder: " + tr, tr);
        }

        // The 90s failure-path budget below covers a genuine wait for a human to notice and tap the
        // consent notification, but prints nothing while it waits - which reads identically to a
        // hang for a setup that is actually broken. So this line still exists; it is just DEFERRED.
        //
        // Printed unconditionally (as it was) it lands in front of every single command, including
        // the authorized fast paths where the binder arrives in milliseconds and nobody is being
        // asked for anything - noise on every prompt, which is exactly what a working setup must not
        // produce. onBinderReceived cancels it, so it only ever appears when the wait is real.
        waitingNoticeCallback = () ->
                System.err.println("Waiting for 白い熊 雫 authorization... check your notifications.");
        handler.postDelayed(waitingNoticeCallback, 1500);

        timeoutCallback = () -> abort(
                String.format(
                        "Request timeout. The connection between the current app (%1$s) and Shizuku app may be blocked by your system. " +
                                "Please disable all battery optimization features for both current app (%1$s) and Shizuku app.",
                        packageName)
        );
        // 15s was sized for the consent dialog launching directly (see the commit that introduced
        // this value). Since then, the dialog is routed through a notification the user has to
        // notice, open, and tap first to dodge Android's background-activity-launch restrictions
        // (#377) - that extra human step can easily eat the whole budget on its own, so a fully
        // successful, on-time consent grant can still race this timer and lose (#377, still timing
        // out after the notification/consent flow itself works). 90s gives real margin for that
        // flow; onBinderReceived() above cancels this the moment the binder actually arrives, so a
        // fast path isn't slowed down, only the failure path waits longer before giving up.
        handler.postDelayed(timeoutCallback, 90000);

        Looper.loop();
        System.exit(0);
    }

    private static void abort(String message) {
        System.err.println(message);
        LOGGER.severe(message);
        System.exit(1);
    }

    private static void abort(String message, Throwable tr) {
        System.err.println(message);
        tr.printStackTrace();
        LOGGER.log(Level.SEVERE, message, tr);
        System.exit(1);
    }
}
