package com.djiparam;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * DJI Fly helper for the RC 2.
 *
 * We do NOT auto-detect a running DJI Fly: an untrusted app on this locked RC can't see
 * a backgrounded process (/proc is hidepid=2, UsageStats reports only the foreground app,
 * dumpsys needs system). Instead the UI shows a small warning + a "Стоп DJI Fly" button
 * that opens the system App-Info screen, where the user taps "Остановить" (real stop =
 * force-stop; killBackgroundProcesses is a no-op on this home process).
 */
public final class DjiFly {

    /** Any DJI Fly version, newest first. */
    public static final String[] CANDIDATES = { "dji.go.v6", "dji.go.v5" };

    private DjiFly() {}

    /** First installed DJI Fly package, or null if none is installed. */
    public static String installedPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : CANDIDATES) {
            try { pm.getPackageInfo(pkg, 0); return pkg; }
            catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    /** Open the App-Info screen for the given DJI Fly package (user taps "Остановить"). */
    public static void openAppInfo(Context ctx, String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); }
        catch (Exception e) { Logger.e("open app-info failed for " + pkg, e); }
    }
}
