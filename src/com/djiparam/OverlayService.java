package com.djiparam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Floating control overlay that sits ON TOP of DJI Fly — same trick as the fcc bundle's gesture app
 * (com.djifcc.labdron.gesture / OverlayService): a TYPE_APPLICATION_OVERLAY window with FLAG_NOT_FOCUSABLE,
 * so DJI Fly keeps focus/video and is never minimised. A small draggable handle (≡) expands a panel of
 * blind write-only toggles; each button fires Duml.writeOnceCoexist (0xE3 on 40008, no reader) — the path
 * we confirmed coexists with a running DJI Fly. NEVER reads (no 40007), so Fly's video mirror is untouched.
 *
 * Param indices are resolved by MainActivity (from the detected/cached model) and passed via the intent.
 */
public final class OverlayService extends Service {

    private WindowManager wm;
    private View handle;
    private View panel;
    private final Duml duml = new Duml();   // write-only; start() is never called → no 40007 reader
    private final Handler main = new Handler(Looper.getMainLooper());

    private String[] titles, onLabels, offLabels, types;
    private int[] indices;
    private long[] onVals, offVals;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent it, int flags, int startId) {
        if (it != null && it.hasExtra("titles")) {
            titles    = it.getStringArrayExtra("titles");
            indices   = it.getIntArrayExtra("indices");
            onVals    = it.getLongArrayExtra("onVals");
            offVals   = it.getLongArrayExtra("offVals");
            types     = it.getStringArrayExtra("types");
            onLabels  = it.getStringArrayExtra("onLabels");
            offLabels = it.getStringArrayExtra("offLabels");
        }
        startForeground(7, buildNotif());
        if (wm == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            addHandle();
            Logger.i("[overlay] started");
        }
        return START_STICKY;
    }

    private Notification buildNotif() {
        String ch = "djiparam_overlay";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "Оверлей", NotificationManager.IMPORTANCE_MIN);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("DjiParam — оверлей")
                .setContentText("Тумблеры поверх DJI Fly (40008)")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true).build();
    }

    private WindowManager.LayoutParams olp(int w, int h, int gravity) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        return p;
    }

    private void addHandle() {
        TextView t = new TextView(this);
        t.setText("≡");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pill(0xCC3A3A40));   // серый, скруглённые углы (как кнопки приложения)
        int sz = dp(54);
        WindowManager.LayoutParams p = olp(sz, sz, Gravity.TOP | Gravity.LEFT);
        p.x = 0; p.y = dp(110);
        t.setOnTouchListener(new DragTap(p, this::togglePanel));
        handle = t;
        wm.addView(t, p);
    }

    private void togglePanel() {
        if (panel != null) { try { wm.removeView(panel); } catch (Throwable ignore) {} panel = null; return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xB00A0A0B);
        col.setPadding(dp(8), dp(6), dp(8), dp(6));

        int n = titles != null ? titles.length : 0;
        for (int i = 0; i < n; i++) {
            final int idx = indices[i];
            final long on = onVals[i], off = offVals[i];
            final String ty = types[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));
            Button onB = pillBtn(titles[i] + " on", 0x99808080, idx, ty, on);
            Button offB = pillBtn(titles[i] + " off", 0x99484848, idx, ty, off);
            LinearLayout.LayoutParams lo = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lo.rightMargin = dp(4);
            LinearLayout.LayoutParams lf = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lf.leftMargin = dp(4);
            row.addView(onB, lo);
            row.addView(offB, lf);
            col.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, dp(6), 0, 0);

        Button close = new Button(this);
        close.setText("Свернуть");
        close.setAllCaps(false);
        close.setSingleLine(true);
        close.setTextSize(12);
        close.setMinWidth(0); close.setMinimumWidth(0);
        close.setPadding(dp(2), dp(6), dp(2), dp(6));
        close.setTextColor(0xFF2E9BFF);
        close.setBackgroundColor(0x00000000);
        close.setOnClickListener(v -> togglePanel());
        foot.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button app = new Button(this);
        app.setText("Открыть");
        app.setAllCaps(false);
        app.setSingleLine(true);
        app.setTextSize(12);
        app.setMinWidth(0); app.setMinimumWidth(0);
        app.setPadding(dp(2), dp(6), dp(2), dp(6));
        app.setTextColor(0xFF2E9BFF);
        app.setBackgroundColor(0x00000000);
        app.setOnClickListener(v -> {
            togglePanel();
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            } catch (Throwable t) { Logger.w("[overlay] open app: " + t); }
        });
        foot.addView(app, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button remove = new Button(this);
        remove.setText("Закрыть");
        remove.setAllCaps(false);
        remove.setSingleLine(true);
        remove.setTextSize(12);
        remove.setMinWidth(0); remove.setMinimumWidth(0);
        remove.setPadding(dp(2), dp(6), dp(2), dp(6));
        remove.setTextColor(0xFFFF453A);
        remove.setBackgroundColor(0x00000000);
        remove.setOnClickListener(v -> { togglePanel(); stopSelf(); });
        foot.addView(remove, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        col.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams p = olp(dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        p.x = dp(54); p.y = dp(84);
        panel = col;
        wm.addView(col, p);
    }

    /** Filled rounded pill button (app style), white text; fires a write-only 40008 inject. */
    private Button pillBtn(String text, int fill, int idx, String type, long val) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(15);
        b.setTextColor(0xF0FFFFFF);
        b.setBackground(pill(fill));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setOnClickListener(v -> {
            Logger.i("[overlay] tap '" + text + "' idx=" + idx + " val=" + val + " type=" + type + " -> 40008");
            new Thread(() -> {
                final boolean ok = duml.writeOnceCoexist(0, idx, type, val);
                Logger.i("[overlay] tap '" + text + "' sent=" + ok);
                main.post(() -> Toast.makeText(this, (ok ? "→ " : "✗ ") + text, Toast.LENGTH_SHORT).show());
            }).start();
        });
        return b;
    }

    private android.graphics.drawable.GradientDrawable pill(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(8));
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onDestroy() {
        super.onDestroy();
        try { if (panel != null) wm.removeView(panel); } catch (Throwable ignore) {}
        try { if (handle != null) wm.removeView(handle); } catch (Throwable ignore) {}
        Logger.i("[overlay] stopped");
    }

    /** Draggable handle: move on drag, fire onTap on a clean tap (no drag). */
    private final class DragTap implements View.OnTouchListener {
        final WindowManager.LayoutParams p;
        final Runnable onTap;
        float downX, downY; int startX, startY; boolean moved;
        DragTap(WindowManager.LayoutParams p, Runnable onTap) { this.p = p; this.onTap = onTap; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); startX = p.x; startY = p.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int nx = (int) (startX + e.getRawX() - downX);
                    int ny = (int) (startY + e.getRawY() - downY);
                    if (Math.abs(e.getRawX() - downX) > dp(6) || Math.abs(e.getRawY() - downY) > dp(6)) moved = true;
                    p.x = nx; p.y = ny;
                    try { wm.updateViewLayout(v, p); } catch (Throwable ignore) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && onTap != null) onTap.run();
                    return true;
            }
            return false;
        }
    }
}
