package com.djiparam;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DJI Param — skeleton.
 *
 * Tabs: "Быстрые параметры" (quick toggles), "Все параметры" (full list — поиск + чтение по тапу),
 * "О программе" (app info + log export). Управление соединением вынесено в верхнюю панель:
 * индикатор ●/подключение, отключение и «Стоп DJI Fly» живут на уровне переключения вкладок,
 * а не внутри страниц.
 */
public class MainActivity extends Activity {

    // DJI Fly-style dark palette
    private static final int BG      = 0xFF0A0A0B;
    private static final int ROW_BG  = 0xFF161619;
    private static final int TXT     = 0xFFFFFFFF;
    private static final int TXT_SUB = 0xFF8E8E93;
    private static final int ACCENT  = 0xFF2E9BFF;
    private static final int DIVIDER = 0xFF2A2A2E;
    private static final int GREEN   = 0xFF34C759;
    private static final int RED     = 0xFFFF453A;

    private static final int TAB_QUICK = 0;
    private static final int TAB_ALL   = 1;
    private static final int TAB_ABOUT = 2;
    private static final String[] TABS = { "Быстрые параметры", "Все параметры", "О программе" };

    /**
     * A quick toggle backed by a real FC parameter. Indices are the Lito X1 (wa151) defaults —
     * they differ per model, so we always verify {@link #name} via get_info before trusting the
     * index and never write when the on-board name doesn't match (CLAUDE.md §10).
     */
    private static final class QuickParam {
        final String title, name, type, desc;
        final String[] aliases;               // accepted on-board names (board may report short OR full path)
        final int table, index;
        final long onValue, offValue;
        final String onLabel, offLabel;
        QuickParam(String title, String name, String[] aliases, int table, int index, String type,
                   long onValue, String onLabel, long offValue, String offLabel, String desc) {
            this.title = title; this.name = name; this.aliases = aliases;
            this.table = table; this.index = index;
            this.type = type; this.onValue = onValue; this.onLabel = onLabel;
            this.offValue = offValue; this.offLabel = offLabel; this.desc = desc;
        }
        /**
         * The board reports the name as "short" or "short|g_config.full.path". Match a segment
         * EXACTLY (case-insensitive) against an alias. Exact only — substring matching wrongly
         * accepted "enable" for "gps_enable" and the different-enum "fswitch_selection_b" for
         * "fswitch_selection", defeating the write-mismatch guard.
         */
        boolean matches(String boardName) {
            if (boardName == null || boardName.isEmpty()) return false;
            for (String seg : boardName.split("\\|")) {
                String s = seg.trim().toLowerCase();
                for (String a : aliases) if (s.equals(a.toLowerCase())) return true;
            }
            return false;
        }
    }

    // Confirmed FLYC toggles on the RC 2 (table 0). See CLAUDE.md §2 "Известные тумблеры".
    // Aliases = both the short name and the full g_config path seen in the .dhv2params dumps.
    private static final QuickParam[] QUICK = {
        new QuickParam("Режим ATTI", "fswitch_selection",
                new String[]{ "fswitch_selection", "g_config.control.control_mode[0]" },
                0, 146, "U8", 3, "ATTI", 12, "обычный",
                "Назначение позиции переключателя режимов: ATTI (полёт без стабилизации по позиции, "
                + "снос по ветру) вместо обычного. На части прошивок переназначение не срабатывает."),
        new QuickParam("Передние LED", "forearm_led_ctrl",
                new String[]{ "forearm_led_ctrl", "g_config.misc_cfg.forearm_lamp_ctrl" },
                0, 23, "U8", 239, "вкл", 0, "выкл",
                "Передние (лучевые) огни: 239 = включены, 0 = выключены. Отключение может нарушать "
                + "требования к огням в вашей юрисдикции."),
        new QuickParam("GPS", "gps_enable",
                new String[]{ "gps_enable" },
                0, 650, "U8", 1, "вкл", 0, "выкл",
                "Приём GNSS (GPS/ГЛОНАСС и т.д.). Выключение переводит удержание позиции на "
                + "визуальные датчики — риск сноса; только для отладки на земле."),
        new QuickParam("VPS (визуальное позиционирование)", "vps_func_en",
                new String[]{ "vps_func_en" },
                0, 1314, "U8", 1, "вкл", 0, "выкл",
                "Система визуального позиционирования (нижние камеры/ToF) для удержания и посадки без "
                + "GNSS. Выключение снижает точность зависания и посадки у земли."),
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Duml duml = new Duml();
    private volatile boolean connecting = false;
    private volatile String connPhase = "";   // текст фазы на кнопке: «Подключение…» / «Чтение…»
    private volatile boolean paramsBusy = false;   // reading/writing a quick param

    // Latest read state, keyed by param index. Populated on connect, refreshed after each write.
    private final java.util.HashMap<Integer, Long> qpValue = new java.util.HashMap<>();   // current value
    private final java.util.HashMap<Integer, String> qpName = new java.util.HashMap<>();  // name from get_info
    private final java.util.HashMap<Integer, String> qpDefault = new java.util.HashMap<>(); // default from get_info

    // ---- per-model catalog (assets/quick_index.json): resolve quick params by NAME → index per model.
    // Indices differ wildly per model (gps_enable: 53 on Neo 2 … 1403 on Flip), so the quick toggles
    // can't use one fixed index — we look each name up in the catalog of the active model.
    private static final class ModelCat {
        final String name; final int table; final String catalog;  // assets file with the full param table
        final long crc, count;                                   // firmware-table CRC + slot count (0xE0)
        final List<String> codenames = new ArrayList<>();        // acModel values that auto-select this
        final java.util.HashMap<String, Integer> idx = new java.util.HashMap<>();  // param name → index (quick)
        ModelCat(String name, int table, String catalog, long crc, long count) {
            this.name = name; this.table = table; this.catalog = catalog; this.crc = crc; this.count = count;
        }
    }
    private final List<ModelCat> models = new ArrayList<>();
    private boolean modelsLoaded = false;
    private String manualModel = null;   // null = авто-определение (CRC 0xE0 → codename)
    private volatile long boardCrc = 0, boardCount = 0;   // from 0xE0 on connect; identifies the model

    // ---- tab "Все параметры": bundled table, searchable, grouped, value read on tap ----
    /** One row of the bundled index→name→type table (assets/params.json, from the litox1 dump). */
    private static final class P {
        final String name, full, type, cat, desc;
        final int table, index;
        final double min, max, def;
        final boolean fav;
        P(String name, String full, int table, int index, String type, double min, double max,
          double def, String cat, boolean fav, String desc) {
            this.name = name; this.full = full; this.table = table; this.index = index;
            this.type = type; this.min = min; this.max = max; this.def = def;
            this.cat = cat; this.fav = fav; this.desc = desc;
        }
        boolean isFloat() { return "F32".equals(type) || "F64".equals(type); }
        /** A 0/1 parameter → render as a switch like the quick toggles. */
        boolean isBool() { return !isFloat() && min == 0 && max == 1; }
    }

    // "Часто используемые" pseudo-category (shown first) + the report's category order.
    private static final String FAV_CAT = "★ Часто используемые";
    private static final String ALL_CATS = "Все категории";
    private static final String[] CAT_ORDER = {
        "Режимы и управляемость",
        "Ограничения полёта и регуляторные",
        "RTH, взлёт, посадка, failsafe",
        "Обход препятствий и vision",
        "Батарея и питание",
        "Навигация и GNSS",
        "RC, радио, свет, интерфейсы",
        "Стабилизация и фильтры",
        "Моторы, ESC, силовая",
        "IMU, компас, калибровка",
        "Геометрия датчиков (модель)",
        "Калибровка экземпляра / bias",
        "Подвес и камера",
        "Диагностика и симулятор",
        "Прочие внутренние",
    };
    // Short "что включает" blurb per section (from DJI_PARAMS_REPORT_RU.md §Категории параметров).
    private static final java.util.HashMap<String, String> CAT_DESC = new java.util.HashMap<>();
    static {
        CAT_DESC.put(FAV_CAT, "Что чаще всего меняют: скорость, высота, режимы, RTH, свет, GPS");
        CAT_DESC.put("Режимы и управляемость", "Normal/Sport/Tripod/Cine, скорости, наклон, expo, rc_scale, торможение");
        CAT_DESC.put("Ограничения полёта и регуляторные", "высота, радиус, CE/C0/C1, RID, GEO и региональные флаги");
        CAT_DESC.put("RTH, взлёт, посадка, failsafe", "высота/скорость RTH, homing, потеря связи, посадочные состояния");
        CAT_DESC.put("Обход препятствий и vision", "visual sensing, VPS/MVO, ToF, LiDAR, obstacle avoidance");
        CAT_DESC.put("Батарея и питание", "пороги заряда/напряжения, smart battery, аутентификация батареи");
        CAT_DESC.put("Навигация и GNSS", "GPS/Galileo/BeiDou, RTK, waypoint и координатные источники");
        CAT_DESC.put("RC, радио, свет, интерфейсы", "RC/SBUS/SDR, LED, антенны, интерфейсные флаги");
        CAT_DESC.put("Стабилизация и фильтры", "notch/LPF, gain, feed-forward, авто-тюнинг, компенсация вибраций");
        CAT_DESC.put("Моторы, ESC, силовая", "ESC, моторы, пропеллеры, mixer, idle, actuator");
        CAT_DESC.put("IMU, компас, калибровка", "IMU/gyro/accel health, команды и пороги калибровки");
        CAT_DESC.put("Геометрия датчиков (модель)", "координаты и ориентация IMU/GPS/антенн/RTK/UWB/LiDAR");
        CAT_DESC.put("Калибровка экземпляра / bias", "bias IMU, состояние компаса, температура/готовность — не переносить");
        CAT_DESC.put("Подвес и камера", "gimbal, camera, panorama и центрирование подвеса");
        CAT_DESC.put("Диагностика и симулятор", "тестовые, factory/debug, fault/history, runtime status");
        CAT_DESC.put("Прочие внутренние", "поля, назначение которых нельзя надёжно вывести из имени");
    }

    private List<P> table;                                     // loaded from the active model's catalog
    private String loadedCatalog = null;                       // assets file currently in `table`
    private final java.util.HashMap<Integer, P> tableByKey = new java.util.HashMap<>();   // key -> P
    private final List<Object> rows = new ArrayList<>();       // display list: String (header) | P (param)
    private final java.util.HashMap<Integer, String> allText = new java.util.HashMap<>(); // key -> display value
    private final java.util.HashMap<Integer, String> allDefault = new java.util.HashMap<>(); // key -> get_info default
    private final java.util.HashMap<Integer, Integer> allBool = new java.util.HashMap<>(); // key -> 0/1 (bool params)
    private final java.util.HashSet<Integer> allReading = new java.util.HashSet<>();       // keys being read now
    private final java.util.HashSet<Integer> expanded = new java.util.HashSet<>();         // keys expanded (show desc)
    private final java.util.LinkedHashSet<Integer> starred = new java.util.LinkedHashSet<>(); // ★ pinned to quick tab

    // single gentle reader for tap-triggered value reads (never sweep — churn kills the DUML router, §4)
    private final java.util.LinkedHashSet<Integer> loadQueue = new java.util.LinkedHashSet<>();
    private volatile boolean workerRunning = false;
    private long lastListRefresh = 0;

    private String allFilter = "";
    private String allCat = ALL_CATS;                          // current category filter
    private ListView allList;
    private ParamAdapter allAdapter;

    private static int key(int table, int index) { return (table << 16) | (index & 0xFFFF); }

    // ---- starred params (pinned to the quick tab), persisted in SharedPreferences ----
    private void loadStarred() {
        starred.clear();
        java.util.Set<String> s = getSharedPreferences("djiparam", MODE_PRIVATE)
                .getStringSet("starred", java.util.Collections.<String>emptySet());
        for (String v : s) { try { starred.add(Integer.parseInt(v)); } catch (Exception ignore) {} }
    }
    private void saveStarred() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (Integer k : starred) s.add(String.valueOf(k));
        getSharedPreferences("djiparam", MODE_PRIVATE).edit().putStringSet("starred", s).apply();
    }
    private void toggleStar(int k) {
        if (!starred.remove(k)) starred.add(k);
        saveStarred();
    }

    private FrameLayout root;
    private TextView logView;
    private float d;
    private int currentTab = TAB_QUICK;
    private boolean autoConnectPending = false;   // test hook: am start ... --ez autoconnect true

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        Logger.init(this);
        d = getResources().getDisplayMetrics().density;
        loadStarred();

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        // no auto-connect: the user connects explicitly via the Подключить button (test hook only)
        autoConnectPending = getIntent() != null && getIntent().getBooleanExtra("autoconnect", false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        render();
        if (autoConnectPending && !connecting) { autoConnectPending = false; connect(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Logger.setListener(null);
        stopLoading();               // stop the all-params worker before releasing the channel
        duml.stop();                 // never overlap DJI Fly — release the DUML feeds
    }

    // ================= rendering =================

    private void render() {
        root.removeAllViews();
        Logger.setListener(null);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.addView(buildTopBar());     // connection controls + tabs on one top bar

        if (currentTab == TAB_ALL) {
            buildAll(col);              // search box + recycling ListView (952 rows, low-RAM)
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(b, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (currentTab == TAB_ABOUT) buildAbout(b);
            else buildQuick(b);
            col.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Top bar = connection cluster (left) + tabs. The cluster holds a Подключить/Отключить button
     * (status shown by its colour) and a "Стоп Fly" button — proper finger-sized targets, not the
     * old tiny icons. Everything that used to sit inside the param pages lives here.
     */
    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF000000);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        boolean running = duml.running();
        boolean up = duml.isUp();
        boolean connected = up && !duml.acModel().isEmpty();

        // Подключить / Отключить — colour = status (green connected, red link-only, accent connecting)
        int connColor = connecting ? ACCENT : connected ? GREEN : running ? RED : 0xFF3A3A40;
        // текст = действие по нажатию: во время подключения — фаза, иначе «Отключить»/«Подключить».
        // Состояние (подключено/линк-без-борта) передаёт цвет кнопки, а не текст.
        String connText;
        if (connecting) connText = connPhase == null || connPhase.isEmpty() ? "Подключение…" : connPhase;
        else if (running) connText = "Отключить";
        else connText = "Подключить";
        Button conn = new Button(this);
        conn.setText(connText);
        conn.setAllCaps(false);
        conn.setTextSize(14);
        conn.setTextColor(TXT);
        conn.setTypeface(Typeface.DEFAULT_BOLD);
        conn.setBackground(pill(connColor));
        conn.setPadding(dp(16), dp(7), dp(16), dp(7));
        conn.setOnClickListener(v -> {
            if (connecting) return;
            if (duml.running()) disconnect(); else connect();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(10); clp.rightMargin = dp(6);
        bar.addView(conn, clp);

        // Модель — авто-определение по кодовому имени борта, с ручным выбором. Индексы параметров
        // у каждой модели свои, поэтому от выбора зависит, куда пишут быстрые тумблеры.
        loadModelsIfNeeded();
        Button mdl = new Button(this);
        mdl.setText("Модель: " + modelLabel() + "  ▾");
        mdl.setAllCaps(false);
        mdl.setTextSize(13);
        mdl.setTextColor(TXT);
        mdl.setBackground(pillOutline(DIVIDER));
        mdl.setPadding(dp(12), dp(7), dp(12), dp(7));
        mdl.setOnClickListener(v -> showModelMenu(mdl));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.rightMargin = dp(6);
        bar.addView(mdl, mlp);

        // Стоп DJI Fly — opens App-Info (user taps "Остановить"); only if Fly is installed
        String pkg = DjiFly.installedPackage(this);
        if (pkg != null) {
            final String p = pkg;
            Button stop = new Button(this);
            stop.setText("Стоп Fly");
            stop.setAllCaps(false);
            stop.setTextSize(14);
            stop.setTextColor(RED);
            stop.setBackground(pillOutline(RED));
            stop.setPadding(dp(14), dp(7), dp(14), dp(7));
            stop.setOnClickListener(v -> {
                Logger.i("stop DJI Fly -> App Info " + p);
                DjiFly.openAppInfo(this, p);
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = dp(8);
            bar.addView(stop, slp);
        }

        // thin separator between the cluster and the tabs
        View sep = new View(this);
        sep.setBackgroundColor(DIVIDER);
        bar.addView(sep, new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(24)));

        // tabs fill the rest
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            boolean sel = (i == currentTab);
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            Button tb = new Button(this);
            tb.setText(TABS[i]);
            tb.setAllCaps(false);
            tb.setTextSize(14);
            tb.setTextColor(sel ? ACCENT : TXT_SUB);
            tb.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tb.setBackgroundColor(0x00000000);
            tb.setGravity(Gravity.CENTER);
            tb.setPadding(dp(4), dp(10), dp(4), dp(8));
            tb.setOnClickListener(v -> { currentTab = idx; render(); });
            tab.addView(tb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View ul = new View(this);
            ul.setBackgroundColor(sel ? ACCENT : DIVIDER);
            tab.addView(ul, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            bar.addView(tab, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private void connect() {
        connecting = true;
        connPhase = "Подключение…";
        qpValue.clear();
        qpName.clear();
        qpDefault.clear();
        boardCrc = 0; boardCount = 0;
        Logger.i("connect: starting DUML session");
        render();
        new Thread(() -> {
            duml.start();
            // ожидание по факту: идём дальше, как только линк поднялся и пойман кодовое имя борта
            // (обычно <300 мс), но не дольше 1500 мс — на случай, если broadcast опаздывает.
            long deadline = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < deadline
                    && !(duml.isUp() && !duml.acModel().isEmpty())) {
                sleepMs(50);
            }
            // firmware-table CRC (0xE0) — this identifies the model reliably for auto-select
            long[] ti = duml.tableInfo(0, 1200);
            if (ti != null) { boardCrc = ti[0]; boardCount = ti[1]; }
            Logger.i("connect: up=" + duml.isUp() + " ac=" + duml.acModel()
                    + " sn=" + duml.acSerial() + " rc=" + duml.rcModel()
                    + " crc=" + Long.toHexString(boardCrc) + " count=" + boardCount);
            connPhase = "Чтение…";       // фаза чтения быстрых параметров
            ui.post(this::render);
            readQuickParams();           // pull current toggle state (verify names, read values)
            connecting = false;
            connPhase = "";
            ui.post(this::render);
        }).start();
    }

    /** Close the DUML session and drop cached values — the ● goes grey (CLAUDE.md §4: free the sockets). */
    private void disconnect() {
        Logger.i("disconnect: stopping DUML session");
        stopLoading();
        duml.stop();
        qpValue.clear();
        qpName.clear();
        qpDefault.clear();
        boardCrc = 0; boardCount = 0;
        allText.clear();
        allBool.clear();
        allReading.clear();
        toast("Дрон отключён");
        render();
    }

    /**
     * Serially read each quick param on the busy 40007 stream. get_info (name check) is best-effort:
     * on this FC it often doesn't answer, so we don't depend on it — a successful read_value already
     * proves the index is a real param. Both are retried once (reads are timing-sensitive here).
     */
    // ---- per-model catalog resolution ----

    private void loadModelsIfNeeded() {
        if (modelsLoaded) return;
        modelsLoaded = true;
        try {
            InputStream in = getAssets().open("quick_index.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int nb;
            while ((nb = in.read(buf)) > 0) bo.write(buf, 0, nb);
            in.close();
            JSONObject root = new JSONObject(bo.toString("UTF-8"));
            JSONArray ms = root.getJSONArray("models");
            for (int i = 0; i < ms.length(); i++) {
                JSONObject mo = ms.getJSONObject(i);
                long crc = 0;
                try { crc = Long.parseLong(mo.optString("crc", "0"), 16); } catch (Exception ignore) {}
                ModelCat mc = new ModelCat(mo.getString("name"), mo.optInt("table", 0),
                        mo.optString("catalog", "params.json"), crc, mo.optLong("count", 0));
                JSONArray cn = mo.optJSONArray("codenames");
                for (int j = 0; cn != null && j < cn.length(); j++) mc.codenames.add(cn.getString(j).toLowerCase());
                JSONObject idx = mo.getJSONObject("idx");
                java.util.Iterator<String> it = idx.keys();
                while (it.hasNext()) { String k = it.next(); mc.idx.put(k, idx.getInt(k)); }
                models.add(mc);
            }
            Logger.i("[model] loaded " + models.size() + " catalogs");
        } catch (Exception e) { Logger.w("[model] load quick_index.json: " + e); }
    }

    /** Active catalog: manual choice if set, else auto by aircraft codename, else null (unknown). */
    private ModelCat effectiveModel() {
        if (manualModel != null) { loadModelsIfNeeded(); for (ModelCat m : models) if (m.name.equals(manualModel)) return m; }
        return detectedModel();
    }
    private ModelCat detectedModel() {
        loadModelsIfNeeded();
        // 1. firmware-table CRC (0xE0) — reliable. count breaks the wa150/wa151 shared-CRC tie.
        if (boardCrc != 0) {
            ModelCat hit = null; int n = 0;
            for (ModelCat m : models) if (m.crc != 0 && m.crc == boardCrc) { hit = m; n++; }
            if (n == 1) return hit;
            if (n > 1) { for (ModelCat m : models) if (m.crc == boardCrc && m.count == boardCount) return m; }
        }
        // 2. fallback: aircraft codename (a label, less reliable than CRC)
        String ac = duml.acModel();
        if (ac != null && !ac.isEmpty()) {
            String acl = ac.toLowerCase();
            for (ModelCat m : models) for (String c : m.codenames) if (acl.equals(c)) return m;
        }
        return null;
    }
    /** On-board index of a quick param for the active model, or -1 if unknown/unavailable. */
    private int idxOf(QuickParam q) {
        ModelCat m = effectiveModel();
        if (m == null) return -1;
        Integer i = m.idx.get(q.name);
        return i == null ? -1 : i;
    }
    private String modelLabel() {
        ModelCat m = effectiveModel();
        String base = m != null ? m.name : "не определена";
        return manualModel == null ? base + " · авто" : base;
    }
    private void showModelMenu(View anchor) {
        loadModelsIfNeeded();
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        ModelCat det = detectedModel();
        pm.getMenu().add(0, 0, 0, "Авто" + (det != null ? " (" + det.name + ")" : " (не определена)"));
        for (int i = 0; i < models.size(); i++) pm.getMenu().add(0, i + 1, i + 1, models.get(i).name);
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            String prev = manualModel;
            manualModel = (id == 0) ? null : models.get(id - 1).name;
            if (!java.util.Objects.equals(prev, manualModel)) onModelChanged();
            return true;
        });
        pm.show();
    }
    /** Model changed → indices/catalog differ, drop all cached reads and the loaded table, then re-read. */
    private void onModelChanged() {
        qpValue.clear(); qpName.clear(); qpDefault.clear();
        stopLoading();                       // cancel in-flight all-params reads (old model's indices)
        table = null; loadedCatalog = null; tableByKey.clear(); rows.clear();
        allText.clear(); allBool.clear(); allDefault.clear(); allReading.clear(); expanded.clear();
        Logger.i("[model] -> " + (manualModel == null ? "авто" : manualModel));
        render();                            // rebuilds; buildAll/loadTableIfNeeded pulls the new catalog
        if (duml.isUp()) new Thread(this::readQuickParams).start();
    }

    private void readQuickParams() {
        if (!duml.isUp()) return;
        loadTableIfNeeded();
        loadModelsIfNeeded();
        int n = 0;
        for (QuickParam q : QUICK) {
            if (!duml.isUp()) break;
            int ix = idxOf(q);
            if (ix < 0) continue;                      // нет в каталоге активной модели — пропускаем
            connPhase = "Чтение " + (++n) + "…";
            ui.post(this::render);
            // one read_value (proves the index) + one getInfoRaw (name + default in a single reply).
            // NOTE: use getInfoRaw, NOT getInfoName — the latter's 6-byte payload is silently ignored
            // by this FC and always times out (that was the quick-tab slowness).
            Long v = duml.readParam(q.table, ix, q.type, 1000);
            if (v != null) qpValue.put(ix, v);
            sleepMs(QP_PACE_MS);                       // разнести запросы во времени: вплотную роутер роняет ответы
            byte[] info = duml.getInfoRaw(q.table, ix, 800);
            String name = infoName(info);
            if (name != null && !name.isEmpty()) qpName.put(ix, name);
            if (!qpDefault.containsKey(ix)) {
                String def = infoDefault(info, q.type);
                if (def != null) qpDefault.put(ix, def);
            }
            Logger.i("[qp] " + q.name + " idx=" + ix + " name=" + name + " value=" + v);
            sleepMs(QP_PACE_MS);
        }
        // also read the user-starred params so the quick tab shows their current values
        for (Integer k : starred) {
            if (!duml.isUp()) break;
            connPhase = "Чтение " + (++n) + "…";
            ui.post(this::render);
            P p = tableByKey.get(k);
            if (p != null) readOne(p, k);
            sleepMs(QP_PACE_MS);
        }
    }

    /** Pause between quick-tab DUML requests — spacing them out stops the router dropping every 2nd reply. */
    private static final int QP_PACE_MS = 150;

    // ---- tab: quick params ----

    private void buildQuick(LinearLayout b) {
        boolean connected = duml.isUp() && !duml.acModel().isEmpty();
        ModelCat model = effectiveModel();
        addSectionCaption(b, "БЫСТРЫЕ ПАРАМЕТРЫ");
        if (!connected) {
            addNote(b, "Подключитесь к дрону — нажмите индикатор ● в левом верхнем углу. "
                    + "Приложение прочитает текущие значения и позволит их переключить.");
        } else if (model == null) {
            addNote(b, "Модель дрона не определена автоматически. Выберите её кнопкой «Модель» вверху — "
                    + "у каждой модели свои индексы параметров, поэтому без выбора тумблеры недоступны.");
        }

        for (final QuickParam q : QUICK) {
            int ix = idxOf(q);
            String seenName = ix < 0 ? null : qpName.get(ix);   // null if get_info didn't answer
            Long cur = ix < 0 ? null : qpValue.get(ix);
            boolean haveName = seenName != null && !seenName.isEmpty();
            boolean nameMismatch = haveName && !q.matches(seenName);   // get_info answered a DIFFERENT name

            String status;
            if (!connected) {
                status = q.name + " · нет данных";
            } else if (ix < 0) {
                status = q.name + " · нет в каталоге выбранной модели";
            } else if (cur == null) {
                status = q.name + " · не прочитан (нет ответа)";
            } else if (nameMismatch) {
                status = "⚠ на этой прошивке idx " + ix + " = " + seenName + " — запись заблокирована";
            } else {
                String now = cur == q.onValue ? q.onLabel
                        : cur == q.offValue ? q.offLabel : String.valueOf(cur);
                String def = qpDefault.get(ix);
                status = q.name + " · сейчас: " + now + (def != null ? " · по умолч. " + def : "")
                        + (haveName ? "" : " · имя не проверено");
            }
            String sub = q.desc + "\n" + status;   // description first, then live status line

            Switch sw = addSwitchRow(b, q.title, sub);
            sw.setChecked(cur != null && cur == q.onValue);   // set before attaching listener — no callback
            // editable once we have a real value and get_info hasn't positively contradicted the name
            boolean editable = connected && ix >= 0 && cur != null && !nameMismatch && !paramsBusy;
            sw.setEnabled(editable);
            if (editable) {
                sw.setOnCheckedChangeListener((v, checked) -> onQuickToggle(q, checked));
            }
        }

        // user-starred params (★ pinned from the "Все параметры" tab)
        if (!starred.isEmpty()) {
            loadTableIfNeeded();
            addSectionCaption(b, "МОИ ПАРАМЕТРЫ (★)");
            for (Integer k : starred) {
                P p = tableByKey.get(k);
                if (p != null) addStarredRow(b, p);
            }
        }
    }

    /** A pinned param on the quick tab: value + read/refresh, switch (bool) or edit field (numeric). */
    private void addStarredRow(LinearLayout parent, final P p) {
        final int k = key(p.table, p.index);
        boolean connected = duml.isUp();
        boolean reading = allReading.contains(k);
        String vtext = allText.get(k);

        LinearLayout row = newRow();
        row.setOrientation(LinearLayout.VERTICAL);

        // title line: name (fills) + unstar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(p.name); title.setTextColor(TXT); title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView unstar = new TextView(this);
        unstar.setText("★"); unstar.setTextColor(0xFFFFCC00); unstar.setTextSize(16);
        unstar.setPadding(dp(10), 0, 0, 0);
        unstar.setOnClickListener(v -> { toggleStar(k); render(); });
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(unstar);
        row.addView(top);

        String defv = allDefault.get(k);
        String meta = p.type + (p.isFloat() || (p.min == 0 && p.max == 0) ? ""
                : "  ·  " + fmtFloat(p.min) + "…" + fmtFloat(p.max))
                + (defv != null ? "  ·  по умолч. " + defv : "")
                + "  ·  " + (reading ? "чтение…" : vtext == null ? "нет данных" : "значение: " + vtext);
        TextView sub = new TextView(this);
        sub.setText(meta); sub.setTextColor(TXT_SUB); sub.setTextSize(12); sub.setPadding(0, dp(2), 0, dp(4));
        row.addView(sub);

        // controls: read/refresh + (switch | edit field)
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        Button read = new Button(this);
        read.setText(vtext == null ? "Читать" : "Обновить");
        read.setAllCaps(false); read.setTextSize(13); read.setTextColor(ACCENT);
        read.setBackgroundColor(0x00000000); read.setPadding(0, dp(2), dp(16), dp(2));
        read.setEnabled(connected && !paramsBusy);
        read.setOnClickListener(v -> requestRead(k, true));
        ctl.addView(read);

        if (p.isBool()) {
            Integer bv = allBool.get(k);
            boolean loaded = bv != null;
            Switch sw = new Switch(this);
            int[][] st = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, 0xFFCACACF}));
            sw.setTrackTintList(new ColorStateList(st, new int[]{0x882E9BFF, 0x33FFFFFF}));
            sw.setChecked(loaded && bv == 1);
            sw.setEnabled(loaded && connected && !paramsBusy);
            sw.setOnCheckedChangeListener((v, checked) -> onAllToggle(p, checked, sw));
            ctl.addView(sw);
        } else {
            final EditText edit = new EditText(this);
            edit.setTextColor(TXT); edit.setTextSize(14);
            edit.setHint("значение"); edit.setHintTextColor(TXT_SUB);
            edit.setSingleLine(true); edit.setBackground(pillOutline(DIVIDER));
            edit.setPadding(dp(10), dp(6), dp(10), dp(6));
            edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | (p.isFloat() ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
            if (vtext != null) edit.setText(vtext);
            edit.setEnabled(connected && !paramsBusy);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            elp.leftMargin = dp(8); elp.rightMargin = dp(8);
            ctl.addView(edit, elp);
            Button write = new Button(this);
            write.setText("Записать"); write.setAllCaps(false); write.setTextSize(14);
            write.setTextColor(TXT); write.setTypeface(Typeface.DEFAULT_BOLD);
            write.setBackground(pill(connected && !paramsBusy ? ACCENT : 0xFF3A3A40));
            write.setPadding(dp(16), dp(6), dp(16), dp(6));
            write.setEnabled(connected && !paramsBusy);
            write.setOnClickListener(v -> onAllWrite(p, edit.getText().toString()));
            ctl.addView(write);
        }
        row.addView(ctl);
        parent.addView(row);
        addDivider(parent);
    }

    /** Write a quick param (re-verifying the name first), read it back, then refresh the UI. */
    private void onQuickToggle(QuickParam q, boolean checked) {
        if (paramsBusy) return;
        final int ix = idxOf(q);
        if (ix < 0) { toast("Параметр недоступен для выбранной модели"); render(); return; }
        paramsBusy = true;
        final long target = checked ? q.onValue : q.offValue;
        Logger.i("[qp] toggle " + q.name + " idx=" + ix + " -> " + target);
        render();                                   // disables all switches while busy
        toast(q.title + ": запись " + target + "…");
        new Thread(() -> {
            // re-verify the on-board name right before writing — but only BLOCK on a positive
            // mismatch. Use getInfoRaw (working 4-byte payload); a null just means "unverified",
            // not "wrong param" — same rule as the display gate in buildQuick.
            String name = infoName(duml.getInfoRaw(q.table, ix, 900));
            boolean mismatch = name != null && !name.isEmpty() && !q.matches(name);
            if (mismatch) {
                Logger.w("[qp] name mismatch, abort write: idx=" + ix + " name=" + name);
                ui.post(() -> toast("Отмена: имя параметра не совпало (" + name + ")"));
            } else {
                final long status = duml.writeParam(q.table, ix, q.type, target, 1600);
                final Long rb = duml.readParam(q.table, ix, q.type, 1400);
                if (rb != null) qpValue.put(ix, rb);
                final boolean ok = status == 0 && rb != null && rb == target;
                ui.post(() -> toast(q.title + (ok
                        ? " · записано (" + rb + ")"
                        : " · ошибка (status=" + status + ", читаем " + rb + ")")));
            }
            paramsBusy = false;
            ui.post(this::render);
        }).start();
    }

    // ---- tab: all params — search + category filter, grouped, compact rows, tap to expand ----

    private void buildAll(LinearLayout col) {
        loadTableIfNeeded();

        // header line: search box (fills) + category selector (right)
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setBackgroundColor(ROW_BG);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), dp(2), dp(8), dp(2));

        TextView icon = new TextView(this);
        icon.setText("🔍");
        icon.setTextSize(14);
        icon.setTextColor(TXT_SUB);
        icon.setPadding(0, 0, dp(8), 0);
        searchRow.addView(icon);

        final EditText search = new EditText(this);
        search.setHint("Поиск: имя или описание…");
        search.setHintTextColor(TXT_SUB);
        search.setTextColor(TXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackgroundColor(0x00000000);
        search.setPadding(0, dp(8), 0, dp(8));
        search.setText(allFilter);
        search.setSelection(allFilter.length());
        search.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { allFilter = e.toString(); buildRows(); }
        });
        // fullscreen/immersive hides the nav bar → no system "down" key; the Готово key must close the IME
        search.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(tv); return true;
            }
            return false;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView clear = new TextView(this);
        clear.setText("✕");
        clear.setTextSize(16);
        clear.setTextColor(TXT_SUB);
        clear.setPadding(dp(8), dp(8), dp(10), dp(8));
        clear.setOnClickListener(v -> search.setText(""));
        searchRow.addView(clear);

        // category spinner (white text on dark). Options: favorites, all, then each category.
        final List<String> cats = new ArrayList<>();
        cats.add(FAV_CAT);
        cats.add(ALL_CATS);
        for (String c : CAT_ORDER) cats.add(c);
        Spinner spin = new Spinner(this);
        ArrayAdapter<String> sa = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, cats) {
            @Override public View getView(int p, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(p, cv, parent);
                t.setTextColor(TXT); t.setTextSize(13); t.setGravity(Gravity.END);
                return t;
            }
            @Override public View getDropDownView(int p, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(p, cv, parent);
                t.setTextColor(0xFF000000); t.setPadding(dp(12), dp(10), dp(12), dp(10));
                return t;
            }
        };
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spin.setAdapter(sa);
        int sel = cats.indexOf(allCat);
        spin.setSelection(sel < 0 ? cats.indexOf(ALL_CATS) : sel);
        spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                String chosen = cats.get(position);
                if (!chosen.equals(allCat)) { allCat = chosen; buildRows(); }
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        searchRow.addView(spin, new LinearLayout.LayoutParams(dp(140),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(searchRow);
        addDivider(col);

        // recycling list — the only viable way to show 952 rows on this low-RAM RC
        allList = new ListView(this);
        allList.setBackgroundColor(BG);
        allList.setDivider(new ColorDrawable(DIVIDER));
        allList.setDividerHeight(Math.max(1, dp(1) / 2));
        allList.setCacheColorHint(BG);
        if (allAdapter == null) allAdapter = new ParamAdapter();
        buildRows();                                // populate the grouped display list
        allList.setAdapter(allAdapter);
        // touching the list dismisses the search keyboard (no nav bar to do it in immersive mode)
        allList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView v, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL && search.hasFocus()) hideKeyboard(search);
            }
            public void onScroll(android.widget.AbsListView v, int a, int b, int c) {}
        });
        col.addView(allList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** Rebuild the grouped display list (headers + params) from the search text and category filter. */
    private void buildRows() {
        rows.clear();
        if (table != null) {
            final String f = allFilter.trim().toLowerCase();
            if (allCat.equals(FAV_CAT)) {
                addSection(FAV_CAT, f, true, null);
            } else if (allCat.equals(ALL_CATS)) {
                addSection(FAV_CAT, f, true, null);            // favorites first
                for (String c : CAT_ORDER) addSection(c, f, false, c);
            } else {
                addSection(allCat, f, false, allCat);
            }
        }
        if (allAdapter != null) allAdapter.notifyDataSetChanged();
    }

    /** Append a header + its matching params (sorted by name). favOnly ⇒ p.fav; else p.cat==cat. */
    private void addSection(String header, String filter, boolean favOnly, String cat) {
        List<P> hits = new ArrayList<>();
        for (P p : table) {
            if (favOnly ? !p.fav : !p.cat.equals(cat)) continue;
            if (!matchesFilter(p, filter)) continue;
            hits.add(p);
        }
        if (hits.isEmpty()) return;
        java.util.Collections.sort(hits, (a, b) -> a.name.compareToIgnoreCase(b.name));
        rows.add(header + "  ·  " + hits.size());
        rows.addAll(hits);
    }

    private static boolean matchesFilter(P p, String f) {
        if (f.isEmpty()) return true;
        return p.name.toLowerCase().contains(f)
                || (p.full != null && p.full.toLowerCase().contains(f))
                || (p.desc != null && p.desc.toLowerCase().contains(f));
    }

    /** Queue one param for a (re)read triggered by a tap. force ⇒ re-read even if already loaded. */
    private void requestRead(int k, boolean force) {
        if (!duml.isUp()) { toast("Сначала подключитесь — кнопка «Подключить» вверху"); return; }
        if (!force && allText.containsKey(k)) return;
        allText.remove(k);
        allReading.add(k);
        synchronized (loadQueue) { loadQueue.add(k); loadQueue.notifyAll(); }
        ensureWorker();
        if (allAdapter != null) allAdapter.notifyDataSetChanged();
    }

    private void stopLoading() {
        synchronized (loadQueue) { loadQueue.clear(); loadQueue.notifyAll(); }
        allReading.clear();
    }

    private synchronized void ensureWorker() {
        if (workerRunning || !duml.isUp()) return;
        workerRunning = true;
        new Thread(this::loadWorker, "param-loader").start();
    }

    /**
     * The ONE reader thread for the all-params list. Drains the tap queue one param at a time via a
     * fresh-socket single read, paced, with a breather every N reads — deliberately gentle so we
     * never churn the DUML router into its fpv_sock/-1002 storm (§4). Exits when the queue drains.
     */
    private void loadWorker() {
        int sinceBreath = 0;
        try {
            while (duml.isUp()) {
                Integer k;
                synchronized (loadQueue) {
                    if (loadQueue.isEmpty()) break;
                    java.util.Iterator<Integer> it = loadQueue.iterator();
                    k = it.next(); it.remove();
                }
                if (k == null) continue;
                P p = tableByKey.get(k);
                if (p != null) readOne(p, k);
                maybeRefreshList();
                sleepMs(25);                                  // pace: keep the channel calm
                if (++sinceBreath >= 24) { sinceBreath = 0; sleepMs(300); }  // periodic breather
            }
        } finally {
            workerRunning = false;
            refreshValuesUi();
            synchronized (loadQueue) { if (!loadQueue.isEmpty()) ensureWorker(); }
        }
    }

    /** One gentle read → decode → store; also fetch the get_info factory default (once). */
    private void readOne(P p, int k) {
        try {
            byte[] raw = duml.readRaw(p.table, p.index, 1000);
            if (raw == null || raw.length < 6) { allText.put(k, "нет ответа"); return; }
            if (Duml.u32(raw, 0) != 0) { allText.put(k, "ошибка"); return; }
            if (p.isFloat()) {
                allText.put(k, fmtFloat(Duml.decodeFloat(raw, 6, p.type)));
            } else {
                long v = Duml.decodeInt(raw, 6, p.type);
                allText.put(k, String.valueOf(v));
                if (p.isBool()) allBool.put(k, (int) v);
            }
            if (!allDefault.containsKey(k)) {
                String def = infoDefault(duml.getInfoRaw(p.table, p.index, 900), p.type);
                if (def != null) allDefault.put(k, def);
            }
        } finally {
            allReading.remove(k);
        }
    }

    /** Throttle list repaints to ~3/s so notifyDataSetChanged doesn't jank the UI. */
    private void maybeRefreshList() {
        long now = System.currentTimeMillis();
        if (now - lastListRefresh < 300) return;
        lastListRefresh = now;
        refreshValuesUi();
    }

    /**
     * Push freshly-read values to whichever tab is visible. The "Все параметры" list is backed by a
     * recycling adapter (notifyDataSetChanged). The quick/favourites tab is immediate-mode — its rows
     * are static Views built in buildQuick/addStarredRow, so it only reflects new values on a full
     * render(). Without this, "Обновить" on a starred param read the value but never showed it.
     */
    private void refreshValuesUi() {
        ui.post(() -> {
            if (currentTab == TAB_ALL && allAdapter != null) allAdapter.notifyDataSetChanged();
            else render();
        });
    }

    /** Parse assets/params.json (index→name→type→min/max) into the in-memory table, once. */
    /** Load the full parameter table for the ACTIVE model's catalog (reloads when the model changes). */
    private void loadTableIfNeeded() {
        ModelCat m = effectiveModel();
        String catalog = (m != null && m.catalog != null && !m.catalog.isEmpty()) ? m.catalog : "params.json";
        if (table != null && catalog.equals(loadedCatalog)) return;
        List<P> t = new ArrayList<>();
        tableByKey.clear();
        try {
            InputStream in = getAssets().open(catalog);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject rootObj = new JSONObject(bo.toString("UTF-8"));
            JSONArray arr = rootObj.getJSONArray("params");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                P p = new P(o.optString("name"), o.optString("full", ""),
                        o.optInt("table", 0), o.optInt("index"),
                        o.optString("type", "U16"),
                        o.optDouble("min", 0), o.optDouble("max", 0), o.optDouble("def", 0),
                        o.optString("cat", "Прочие внутренние"),
                        o.optBoolean("fav", false),
                        o.has("desc") ? o.optString("desc") : null);
                t.add(p);
                tableByKey.put(key(p.table, p.index), p);
            }
            Logger.i("[all] catalog '" + catalog + "' loaded: " + t.size() + " params");
        } catch (Throwable e) {
            Logger.e("[all] table load failed (" + catalog + ")", e);
        }
        table = t;
        loadedCatalog = catalog;
    }

    private static String fmtFloat(double d) {
        if (Double.isNaN(d)) return "?";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.format(java.util.Locale.US, "%.4g", d);
    }

    /** Extract the factory default from a get_info reply (def field at offset 10), formatted by type. */
    private static String infoDefault(byte[] pl, String type) {
        if (pl == null || pl.length < 14) return null;
        if ((((pl[0] & 0xFF) | ((pl[1] & 0xFF) << 8))) != 0) return null;   // status:u16 != 0
        boolean f = "F32".equals(type) || "F64".equals(type);
        return f ? fmtFloat(Duml.decodeFloat(pl, 10, type)) : String.valueOf(Duml.decodeInt(pl, 10, type));
    }

    /**
     * On-board parameter name from a getInfoRaw reply (layout: status,table,index,type,size,def,min,max,
     * then name\0 at offset 22), or null. This is the WORKING name source — unlike Duml.getInfoName's
     * 6-byte-payload get_info, which this FC silently ignores (always times out).
     */
    private static String infoName(byte[] pl) {
        if (pl == null || pl.length <= 22) return null;
        if ((((pl[0] & 0xFF) | ((pl[1] & 0xFF) << 8))) != 0) return null;   // status:u16 != 0
        int s = 22, e = s;
        while (e < pl.length && pl[e] != 0 && (pl[e] & 0xFF) >= 0x20 && (pl[e] & 0xFF) < 0x7f) e++;
        return e > s ? new String(pl, s, e - s) : null;
    }

    /** Write an arbitrary (int or float) value from the expanded row, validate range, read it back. */
    private void onAllWrite(P p, String input) {
        if (paramsBusy) { toast("Занято, подождите"); return; }
        if (!duml.isUp()) { toast("Нет подключения к дрону"); return; }
        String s = input == null ? "" : input.trim().replace(',', '.');
        if (s.isEmpty()) { toast("Введите значение"); return; }
        final int k = key(p.table, p.index);
        final byte[] vb;
        final String shownVal;
        boolean rangeKnown = !(p.min == 0 && p.max == 0);
        try {
            if (p.isFloat()) {
                double dv = Double.parseDouble(s);
                if (rangeKnown && (dv < p.min || dv > p.max)) {
                    toast("Вне диапазона " + fmtFloat(p.min) + "…" + fmtFloat(p.max)); return;
                }
                vb = Duml.encodeFloat(p.type, dv);
                shownVal = fmtFloat(dv);
            } else {
                long lv = Long.parseLong(s);
                if (rangeKnown && (lv < (long) p.min || lv > (long) p.max)) {
                    toast("Вне диапазона " + fmtFloat(p.min) + "…" + fmtFloat(p.max)); return;
                }
                vb = Duml.encodeInt(p.type, lv);
                shownVal = String.valueOf(lv);
            }
        } catch (NumberFormatException e) { toast("Неверное число"); return; }

        paramsBusy = true;
        Logger.i("[all] write " + p.name + " idx=" + p.index + " -> " + shownVal);
        toast(p.name + ": запись " + shownVal + "…");
        if (allAdapter != null) allAdapter.notifyDataSetChanged();   // disable controls while busy
        new Thread(() -> {
            final long status = duml.writeValue(p.table, p.index, vb, 1600);
            String rb = null;
            byte[] raw = duml.readRaw(p.table, p.index, 1200);
            if (raw != null && raw.length >= 6 && Duml.u32(raw, 0) == 0) {
                rb = p.isFloat() ? fmtFloat(Duml.decodeFloat(raw, 6, p.type))
                        : String.valueOf(Duml.decodeInt(raw, 6, p.type));
                allText.put(k, rb);
                if (p.isBool()) { try { allBool.put(k, Integer.parseInt(rb)); } catch (Exception ig) {} }
            }
            final String readBack = rb;
            final boolean ok = status == 0 && readBack != null;
            paramsBusy = false;
            ui.post(() -> {
                if (allAdapter != null) allAdapter.notifyDataSetChanged();
                toast(p.name + (ok ? " · записано (читаем " + readBack + ")"
                        : " · ошибка (status=" + status + ", читаем " + readBack + ")"));
            });
        }).start();
    }

    /** Write a 0/1 param from the all-list, read it back, update the row. */
    private void onAllToggle(P p, boolean checked, Switch sw) {
        if (paramsBusy) { sw.setChecked(!checked); return; }
        paramsBusy = true;
        final long target = checked ? 1 : 0;
        final int k = key(p.table, p.index);
        sw.setEnabled(false);
        Logger.i("[all] write " + p.name + " idx=" + p.index + " -> " + target);
        toast(p.name + ": запись " + target + "…");
        new Thread(() -> {
            final long status = duml.writeParam(p.table, p.index, p.type, target, 1600);
            final Long rb = duml.readParam(p.table, p.index, p.type, 1200);
            if (rb != null) { allBool.put(k, rb.intValue()); allText.put(k, String.valueOf(rb)); }
            final boolean ok = status == 0 && rb != null && rb == target;
            paramsBusy = false;
            ui.post(() -> {
                if (allAdapter != null) allAdapter.notifyDataSetChanged();
                toast(p.name + (ok ? " · записано (" + rb + ")"
                        : " · ошибка (status=" + status + ", читаем " + rb + ")"));
            });
        }).start();
    }


    /**
     * BaseAdapter over the grouped `rows` list. Two view types: section headers (String) and compact,
     * one-line param rows (P) that expand on tap to show the Russian description, range and value.
     * Recycles row views so the ~952-param list stays light on this low-RAM RC.
     */
    private final class ParamAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int pos) { return rows.get(pos); }
        public long getItemId(int pos) { return pos; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int pos) { return rows.get(pos) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int pos) { return !(rows.get(pos) instanceof String); }

        public View getView(int pos, View convertView, ViewGroup parent) {
            Object item = rows.get(pos);
            if (item instanceof String) return headerView((String) item, convertView, parent);
            return paramView((P) item, convertView, parent);
        }

        private View headerView(String head, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView cap, sub;
            if (convertView instanceof LinearLayout && convertView.getTag() == null) {
                row = (LinearLayout) convertView;
                cap = (TextView) row.getChildAt(0);
                sub = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackgroundColor(BG);
                row.setPadding(dp(16), dp(12), dp(16), dp(6));
                cap = new TextView(MainActivity.this);
                cap.setTextColor(ACCENT); cap.setTextSize(12);
                cap.setTypeface(Typeface.DEFAULT_BOLD);
                sub = new TextView(MainActivity.this);
                sub.setTextColor(TXT_SUB); sub.setTextSize(10); sub.setPadding(0, dp(1), 0, 0);
                row.addView(cap); row.addView(sub);
            }
            // header text = "Категория  ·  N"; blurb = the "что включает" line for that category
            String catName = head.contains("  ·  ") ? head.substring(0, head.indexOf("  ·  ")) : head;
            cap.setText(head.toUpperCase());
            String blurb = CAT_DESC.get(catName);
            sub.setText(blurb == null ? "" : blurb);
            sub.setVisibility(blurb == null ? View.GONE : View.VISIBLE);
            return row;
        }

        private View paramView(final P p, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView != null && convertView.getTag() instanceof Holder) {
                h = (Holder) convertView.getTag();
            } else {
                LinearLayout outer = new LinearLayout(MainActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setBackgroundColor(ROW_BG);
                outer.setPadding(dp(16), dp(7), dp(16), dp(7));

                // one compact line: name (fills) · type · chevron
                LinearLayout line = new LinearLayout(MainActivity.this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = new TextView(MainActivity.this);
                name.setTextColor(TXT); name.setTextSize(14);
                name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView star = new TextView(MainActivity.this);
                star.setTextSize(16); star.setPadding(dp(8), 0, dp(2), 0);
                TextView val = new TextView(MainActivity.this);
                val.setTextColor(ACCENT); val.setTextSize(13); val.setPadding(dp(8), 0, 0, 0);
                TextView type = new TextView(MainActivity.this);
                type.setTextColor(TXT_SUB); type.setTextSize(11); type.setPadding(dp(8), 0, 0, 0);
                TextView chev = new TextView(MainActivity.this);
                chev.setTextColor(TXT_SUB); chev.setTextSize(12); chev.setPadding(dp(8), 0, 0, 0);
                line.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                line.addView(val); line.addView(star); line.addView(type); line.addView(chev);
                outer.addView(line);

                // detail block, hidden until expanded
                LinearLayout detail = new LinearLayout(MainActivity.this);
                detail.setOrientation(LinearLayout.VERTICAL);
                detail.setPadding(0, dp(6), 0, dp(2));
                TextView desc = new TextView(MainActivity.this);
                desc.setTextColor(TXT); desc.setTextSize(12); desc.setPadding(0, 0, 0, dp(4));
                TextView meta = new TextView(MainActivity.this);
                meta.setTextColor(TXT_SUB); meta.setTextSize(11);
                LinearLayout valRow = new LinearLayout(MainActivity.this);
                valRow.setOrientation(LinearLayout.HORIZONTAL);
                valRow.setGravity(Gravity.CENTER_VERTICAL);
                valRow.setPadding(0, dp(6), 0, 0);
                Button readBtn = new Button(MainActivity.this);
                readBtn.setAllCaps(false); readBtn.setTextSize(13); readBtn.setTextColor(ACCENT);
                readBtn.setBackgroundColor(0x00000000);
                readBtn.setPadding(0, dp(2), dp(16), dp(2));
                Switch sw = new Switch(MainActivity.this);
                int[][] st = {{android.R.attr.state_checked}, {}};
                sw.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, 0xFFCACACF}));
                sw.setTrackTintList(new ColorStateList(st, new int[]{0x882E9BFF, 0x33FFFFFF}));
                valRow.addView(readBtn); valRow.addView(sw);

                // edit row (non-bool params): number field + Записать
                LinearLayout editRow = new LinearLayout(MainActivity.this);
                editRow.setOrientation(LinearLayout.HORIZONTAL);
                editRow.setGravity(Gravity.CENTER_VERTICAL);
                editRow.setPadding(0, dp(4), 0, 0);
                EditText edit = new EditText(MainActivity.this);
                edit.setTextColor(TXT); edit.setTextSize(14);
                edit.setHint("новое значение");
                edit.setHintTextColor(TXT_SUB);
                edit.setSingleLine(true);
                edit.setBackground(pillOutline(DIVIDER));
                edit.setPadding(dp(10), dp(6), dp(10), dp(6));
                Button writeBtn = new Button(MainActivity.this);
                writeBtn.setText("Записать");
                writeBtn.setAllCaps(false); writeBtn.setTextSize(14); writeBtn.setTextColor(TXT);
                writeBtn.setTypeface(Typeface.DEFAULT_BOLD);
                writeBtn.setBackground(pill(ACCENT));
                writeBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                elp.rightMargin = dp(10);
                editRow.addView(edit, elp);
                editRow.addView(writeBtn);

                detail.addView(desc); detail.addView(meta); detail.addView(valRow); detail.addView(editRow);
                outer.addView(detail);

                h = new Holder();
                h.line = line; h.name = name; h.val = val; h.type = type; h.chev = chev; h.star = star;
                h.detail = detail; h.desc = desc; h.meta = meta; h.readBtn = readBtn; h.sw = sw;
                h.editRow = editRow; h.edit = edit; h.writeBtn = writeBtn;
                outer.setTag(h);
                convertView = outer;
            }

            final int k = key(p.table, p.index);
            boolean reading = allReading.contains(k);
            boolean exp = expanded.contains(k);
            String vtext = allText.get(k);

            h.name.setText(p.name);
            h.type.setText(p.type);
            h.chev.setText(exp ? "▾" : "▸");
            boolean star = starred.contains(k);
            h.star.setText(star ? "★" : "☆");
            h.star.setTextColor(star ? 0xFFFFCC00 : TXT_SUB);
            h.star.setOnClickListener(v -> {                 // pin/unpin to the quick tab
                toggleStar(k);
                toast(starred.contains(k) ? "Добавлено в быстрые: " + p.name : "Убрано из быстрых");
                if (allAdapter != null) allAdapter.notifyDataSetChanged();
            });
            if (reading) { h.val.setVisibility(View.VISIBLE); h.val.setText("…"); }
            else if (vtext != null) { h.val.setVisibility(View.VISIBLE); h.val.setText(vtext); }
            else h.val.setVisibility(View.GONE);

            // tap the compact line to expand/collapse (and lazily read the value on first open)
            h.line.setOnClickListener(v -> {
                if (expanded.contains(k)) expanded.remove(k);
                else { expanded.add(k); if (duml.isUp()) requestRead(k, false); }
                if (allAdapter != null) allAdapter.notifyDataSetChanged();
            });

            h.detail.setVisibility(exp ? View.VISIBLE : View.GONE);
            if (exp) {
                if (p.desc != null) { h.desc.setVisibility(View.VISIBLE); h.desc.setText(p.desc); }
                else h.desc.setVisibility(View.GONE);
                String range = p.isFloat() || (p.min == 0 && p.max == 0) ? ""
                        : "  ·  диапазон " + fmtFloat(p.min) + "…" + fmtFloat(p.max);
                String full = (p.full != null && !p.full.isEmpty() && !p.full.equals(p.name))
                        ? "\n" + p.full : "";
                String def = allDefault.get(k);
                String defStr = def != null ? "  ·  по умолч. " + def : "";
                h.meta.setText("Тип " + p.type + range + defStr + "  ·  " + p.cat
                        + "  ·  idx " + p.index + full);

                h.sw.setOnCheckedChangeListener(null);
                if (p.isBool()) {
                    Integer bv = allBool.get(k);
                    boolean loaded = bv != null;
                    h.sw.setVisibility(View.VISIBLE);
                    h.sw.setChecked(loaded && bv == 1);
                    h.sw.setEnabled(loaded && duml.isUp() && !paramsBusy);
                    h.sw.setOnCheckedChangeListener((v, checked) -> onAllToggle(p, checked, h.sw));
                    h.editRow.setVisibility(View.GONE);
                } else {
                    h.sw.setVisibility(View.GONE);
                    // number editor: prefill with the current value (unless the user is typing)
                    h.editRow.setVisibility(View.VISIBLE);
                    h.edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                            | (p.isFloat() ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
                    if (!h.edit.hasFocus()) h.edit.setText(vtext == null ? "" : vtext);
                    h.edit.setEnabled(duml.isUp() && !paramsBusy);
                    h.writeBtn.setEnabled(duml.isUp() && !paramsBusy);
                    h.writeBtn.setBackground(pill(duml.isUp() && !paramsBusy ? ACCENT : 0xFF3A3A40));
                    h.writeBtn.setOnClickListener(v -> onAllWrite(p, h.edit.getText().toString()));
                }
                String vlabel = reading ? "чтение…" : vtext == null ? "—" : vtext;
                h.readBtn.setText("Значение: " + vlabel + "   ⟳ "
                        + (vtext == null ? "прочитать" : "обновить"));
                h.readBtn.setOnClickListener(v -> requestRead(k, true));
            }
            return convertView;
        }
    }

    private static final class Holder {
        LinearLayout line, detail, editRow;
        TextView name, val, type, chev, star, desc, meta;
        Button readBtn, writeBtn;
        EditText edit;
        Switch sw;
    }

    // ---- tab: about + logs ----

    private void buildAbout(LinearLayout b) {
        addSectionCaption(b, "ПРИЛОЖЕНИЕ");
        addStatusRow(b, "Версия", version(), TXT_SUB);
        addStatusRow(b, "Пакет", getPackageName(), TXT_SUB);
        addStatusRow(b, "Устройство", Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")", TXT_SUB);
        File dir = Logger.logDir();
        addStatusRow(b, "Логи", dir != null ? dir.getAbsolutePath() : "—", TXT_SUB);

        addSectionCaption(b, "ДРОН");
        boolean up = duml.isUp();
        String ac = duml.acModel();
        if (up && !ac.isEmpty()) {
            addStatusRow(b, "Модель", acName(ac) + "  ·  " + ac.toUpperCase(), GREEN);
            if (!duml.acSerial().isEmpty()) addStatusRow(b, "Серийный номер", duml.acSerial(), TXT_SUB);
        } else {
            addStatusRow(b, "Модель", "не подключено", TXT_SUB);
        }
        if (!duml.rcModel().isEmpty()) {
            String rc = rcName(duml.rcModel());
            addStatusRow(b, "Пульт", (rc.isEmpty() ? "" : rc + "  ·  ") + duml.rcModel().toUpperCase(), TXT_SUB);
        }

        addSectionCaption(b, "ЛОГИ");
        addActionRow(b, "Собрать логи в архив (ZIP)", ACCENT, v -> exportLogs());
        addNote(b, "Архив кладётся в Android/data/com.djiparam/files/exports/ — заберите "
                + "его по MTP с внутреннего накопителя пульта.");

        addSectionCaption(b, "ЖУРНАЛ");
        logView = new TextView(this);
        logView.setTextColor(TXT_SUB);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(16), dp(6), dp(16), dp(16));
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setText(Logger.dump());
        b.addView(logView);
        Logger.setListener(() -> ui.post(() -> {
            if (logView != null) logView.setText(Logger.dump());
        }));
    }

    private void exportLogs() {
        toast("Собираю логи…");
        new Thread(() -> {
            try {
                File zip = Logger.zipLogs();
                ui.post(() -> toast("Готово: " + zip.getAbsolutePath()));
            } catch (Exception e) {
                Logger.e("log export failed", e);
                ui.post(() -> toast("Не удалось собрать логи: " + e));
            }
        }).start();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { return "?"; }
    }

    // DJI aircraft codename -> friendly model. Recent models from reference_app_3/Api.java; older ones
    // from the dji-firmware-tools wiki (github.com/o-gs/dji-firmware-tools/wiki/Abbreviations).
    // Codenames are reused/ambiguous across generations — only confirmed pairs are listed.
    private static String acName(String code) {
        if (code == null || code.isEmpty()) return "";
        switch (code.toLowerCase()) {
            // --- newer (this project's targets) ---
            case "wa151": return "Lito X1";
            case "wm265": case "wm265e": case "wm265m": case "wm265t": return "DJI Air 3 / Mavic 3";
            case "wm245": return "DJI Air 3S";
            case "wm172": return "DJI Mini 4 Pro";
            case "wm169": return "DJI Mini 3 Pro";
            case "wm161": return "DJI Mini 3";
            case "wm260": return "DJI Mavic 3";
            case "wm231": return "DJI Air 2";
            case "wm232": return "DJI Air 2S";
            case "wm240": case "wm245e": return "DJI Mavic 2";
            case "wm170": return "DJI FPV";
            // --- older (dji-firmware-tools wiki) ---
            case "wm100": return "DJI Spark";
            case "wm160": return "DJI Mavic Mini";
            case "wm220": return "DJI Mavic Pro";
            case "wm230": return "DJI Mavic Air";
            case "wm330": return "DJI Phantom 4";
            case "wm331": return "DJI Phantom 4 Pro";
            case "wm332": return "DJI Phantom 4 Advanced";
            case "wm334": case "wm334r": return "DJI Phantom 4 RTK";
            case "wm335": return "DJI Phantom 4 Pro 2.0";
            case "wm322": return "DJI Phantom 3 Advanced";
            case "wm323": return "DJI Phantom 3 Pro";
            case "wm328": return "DJI Phantom 3 SE";
            case "wm610": return "DJI Inspire 1";
            case "wm620": return "DJI Inspire 2";
            case "wk300": return "DJI Phantom 1";
            default: return code.toUpperCase();
        }
    }

    private static String rcName(String code) {
        if (code == null) return "";
        String c = code.toLowerCase();
        if (c.startsWith("rc331")) return "DJI RC 2";
        if (c.startsWith("rc")) return "DJI RC";
        return "";
    }

    // ================= UI helpers (DJI Fly style) =================

    private int dp(int v) { return (int) (v * d); }
    private static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }

    /** Rounded filled button background. */
    private android.graphics.drawable.GradientDrawable pill(int fill) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(6));
        return g;
    }

    /** Rounded outlined (transparent) button background. */
    private android.graphics.drawable.GradientDrawable pillOutline(int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(Math.max(1, dp(1)), stroke);
        g.setCornerRadius(dp(6));
        return g;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Hide the soft keyboard, drop focus and restore immersive (the IME temporarily breaks it). */
    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        if (v != null) v.clearFocus();
        immersive();
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void addSectionCaption(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(12);
        t.setPadding(dp(16), dp(16), dp(16), dp(6));
        parent.addView(t);
    }

    private void addNote(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TXT_SUB);
        t.setTextSize(12);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        parent.addView(t);
    }

    private void addDivider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(DIVIDER);
        parent.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
    }

    private void addStatusRow(LinearLayout parent, String title, String value, int valueColor) {
        LinearLayout row = newRow();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(TXT); t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        TextView val = new TextView(this);
        val.setText(value); val.setTextColor(valueColor); val.setTextSize(13);
        val.setPadding(0, dp(2), 0, 0);
        c.addView(t); c.addView(val);
        row.addView(c, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        addDivider(parent);
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String subtitle) {
        LinearLayout row = newRow();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(TXT); t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = new TextView(this);
        sub.setText(subtitle); sub.setTextColor(TXT_SUB); sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, 0);
        c.addView(t); c.addView(sub);
        row.addView(c, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{ACCENT, 0xFFCACACF}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{0x882E9BFF, 0x33FFFFFF}));
        row.addView(sw);

        parent.addView(row);
        addDivider(parent);
        return sw;
    }

    private void addActionRow(LinearLayout parent, String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(16);
        b.setBackgroundColor(ROW_BG);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(1);
        parent.addView(b, lp);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(ROW_BG);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        return row;
    }
}
