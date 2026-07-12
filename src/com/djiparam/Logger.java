package com.djiparam;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * App-wide file logger. Writes to the app's external files dir so logs can be pulled
 * over MTP (Android/data/com.djiparam/files/logs/ on the RC's internal storage), mirrors
 * to Logcat, and keeps a small in-memory ring for the on-screen log view.
 *
 * Log export ("О программе → Собрать логи") zips the whole logs/ dir into exports/.
 */
public final class Logger {

    private static final String TAG = "DjiParam";
    private static final int RING = 400;                 // lines kept for the UI

    private static File logDir;                          // <ext>/files/logs
    private static File exportDir;                       // <ext>/files/exports
    private static File logFile;                         // today's file
    private static PrintWriter out;
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static Runnable listener;                    // notified on every new line

    private Logger() {}

    public static synchronized void init(Context ctx) {
        if (out != null) return;
        File base = ctx.getExternalFilesDir(null);       // may be null very early
        if (base == null) base = ctx.getFilesDir();
        logDir = new File(base, "logs");
        exportDir = new File(base, "exports");
        logDir.mkdirs();
        exportDir.mkdirs();
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        logFile = new File(logDir, "app-" + day + ".log");
        try {
            out = new PrintWriter(new FileOutputStream(logFile, true), true);
        } catch (Exception e) {
            android.util.Log.e(TAG, "log file open failed", e);
        }
        i("==== session start ====");
        i("device=" + Build.MODEL + " (" + Build.DEVICE + ") android="
                + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT
                + " build=" + Build.DISPLAY);
    }

    public static void i(String msg) { write("I", msg); }
    public static void w(String msg) { write("W", msg); }
    public static void e(String msg) { write("E", msg); }

    public static void e(String msg, Throwable t) {
        write("E", msg + " :: " + t);
        android.util.Log.e(TAG, msg, t);
    }

    private static synchronized void write(String lvl, String msg) {
        String line = TS.format(new Date()) + " " + lvl + " " + msg;
        android.util.Log.println(
                "E".equals(lvl) ? android.util.Log.ERROR
                        : "W".equals(lvl) ? android.util.Log.WARN : android.util.Log.INFO,
                TAG, msg);
        if (out != null) out.println(line);
        ring.addLast(line);
        while (ring.size() > RING) ring.removeFirst();
        if (listener != null) listener.run();
    }

    /** Whole in-memory ring as a single string (newest last), for the on-screen log view. */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : ring) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void setListener(Runnable r) { listener = r; }

    public static File logDir() { return logDir; }
    public static File exportDir() { return exportDir; }

    /** Zip every file in logs/ into exports/djiparam-logs-<ts>.zip; returns the archive. */
    public static synchronized File zipLogs() throws Exception {
        if (out != null) out.flush();
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File zip = new File(exportDir, "djiparam-logs-" + ts + ".zip");
        File[] files = logDir != null ? logDir.listFiles() : null;
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            byte[] buf = new byte[8192];
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile()) continue;
                    z.putNextEntry(new ZipEntry(f.getName()));
                    try (FileInputStream in = new FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                    }
                    z.closeEntry();
                }
            }
        }
        i("logs zipped -> " + zip.getAbsolutePath());
        return zip;
    }
}
