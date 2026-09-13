package moe.shizuku.privileged.api;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Proxy ContentProvider at authority "moe.shizuku.privileged.api.shizuku".
 *
 * Shizuku API v11+ apps retrieve the Shizuku binder by calling
 * ContentResolver.call() on this authority. Without it, only apps using the
 * legacy broadcast path (REQUEST_BINDER) can connect — v11+ apps silently fail.
 *
 * Forwards all call() invocations to the real ShizukuManagerProvider in the
 * 白い熊 雫 manager at authority "shiroikuma.shizuku.shizuku".
 *
 * FORK: the authority is {@code <applicationId>.shizuku} and this module cannot read the
 * manager's BuildConfig, so it is spelled out here — upstream spells out its own app id in the
 * same place. {@code ComponentNameContractTest} in {@code :manager} reads this literal back and
 * fails the build if it stops matching {@code APP_ID}; a wrong authority here forwards every
 * modern stock-API client into nothing, with no error anywhere.
 */
public class ShizukuProviderProxy extends ContentProvider {

    private static final String REAL_AUTHORITY = "shiroikuma.shizuku.shizuku";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            Uri realUri = Uri.parse("content://" + REAL_AUTHORITY);
            return getContext().getContentResolver().call(realUri, method, arg, extras);
        } catch (Exception e) {
            return null;
        }
    }

    // Shizuku only uses call() — stubs satisfy ContentProvider's abstract contract.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
