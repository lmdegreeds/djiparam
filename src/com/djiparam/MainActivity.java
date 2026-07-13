package com.djiparam;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.content.Intent;
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
    private static final String[] TABS = { "Быстрые", "Все параметры", "О программе" };

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

    // ---- verification of quick-param IDs (per firmware), persisted across sessions ----
    // The overlay writes blind, so before we ever trust an index we confirm the on-board name at it
    // via get_info (0xE1) during a normal «Подключить» session (Fly closed). The result — param name →
    // index that verified — is stored per firmware identity ("<crc>_<count>", so the wa530 CRC-collision
    // revisions get separate records) and reused later so the overlay opens without re-checking.
    private static final String PREFS = "djiparam";
    private final java.util.HashMap<String, Integer> verifiedIdx = new java.util.HashMap<>(); // name → verified idx (current drone)

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
    private TelemetrySink telemetry;               // optional one-shot connection reporter, loaded by reflection

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
        // optional telemetry module — present only in private builds; absent in the public source tree
        try {
            telemetry = (TelemetrySink) Class.forName("com.djiparam.Telemetry")
                    .getDeclaredConstructor(android.content.Context.class).newInstance(this);
        } catch (Throwable t) { telemetry = null; Logger.i("[tlm] модуль телеметрии отсутствует — пропуск"); }

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
        if (telemetry != null) telemetry.flush();   // retry any queued connection reports (throttled)
        if (autoConnectPending && !connecting) { autoConnectPending = false; connect(); }
        // quick one-shot model detect on first foreground (no full session), so coexist toggles
        // resolve correct indices without «Подключить». Skips if a session is already active.
        else if (!detectTried && !duml.running() && boardCrc == 0) { detectTried = true; quickDetect(); }
        // first launch: ensure the APK-install permission (for self-update). On the RC it comes from
        // deploy (appop); on a phone the user grants it once via Settings.
        if (!installPermTried && Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls() && !isRc()) {
            installPermTried = true;
            requestInstallPerm();
        }
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
        mdl.setText((effectiveModel() != null ? effectiveModel().name : "модель") + " ▾");
        mdl.setSingleLine(true);
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

        // Стоп DJI Fly — opens App-Info (user taps "Остановить"); only if Fly is installed.
        // Кнопка = иконка DJI Fly без текста в красном обводе.
        String pkg = DjiFly.installedPackage(this);
        if (pkg != null) {
            final String p = pkg;
            android.graphics.drawable.Drawable flyIcon = null;
            try { flyIcon = getPackageManager().getApplicationIcon(p); } catch (Throwable t) {}
            View.OnClickListener stopClick = v -> {
                Logger.i("stop DJI Fly -> App Info " + p);
                DjiFly.openAppInfo(this, p);
            };
            if (flyIcon != null) {                     // иконка DJI Fly без текста в красном обводе
                android.widget.ImageView stop = new android.widget.ImageView(this);
                stop.setImageDrawable(flyIcon);
                stop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);  // без пустых полей
                stop.setBackground(pillOutline(RED));
                stop.setPadding(dp(2), dp(2), dp(2), dp(2));
                stop.setOnClickListener(stopClick);
                // высота = как у соседних кнопок (по высоте бара), ширина под квадрат
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        dp(52), ViewGroup.LayoutParams.MATCH_PARENT);
                slp.rightMargin = dp(8);
                bar.addView(stop, slp);
            } else {                                   // fallback: текстовая кнопка
                Button stop = new Button(this);
                stop.setText("Стоп Fly");
                stop.setAllCaps(false);
                stop.setTextSize(14);
                stop.setTextColor(RED);
                stop.setBackground(pillOutline(RED));
                stop.setPadding(dp(14), dp(7), dp(14), dp(7));
                stop.setOnClickListener(stopClick);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.rightMargin = dp(8);
                bar.addView(stop, slp);
            }
        }

        // «Меню» — иконка ☰ (три полоски, как хэндл оверлея), квадрат в размер кнопки DJI, без текста
        Button menu = new Button(this);
        menu.setText("≡");                 // ≡ три полоски
        menu.setAllCaps(false);
        menu.setTextSize(22);
        menu.setTextColor(TXT);
        menu.setGravity(Gravity.CENTER);
        menu.setBackground(pill(0xFF3A3A40));   // цвет не меняем
        menu.setMinWidth(0); menu.setMinimumWidth(0);
        menu.setPadding(0, 0, 0, 0);
        menu.setOnClickListener(v -> { if (overlayOn) stopOverlay(); else startOverlay(); });
        LinearLayout.LayoutParams menlp = new LinearLayout.LayoutParams(
                dp(52), ViewGroup.LayoutParams.MATCH_PARENT);
        menlp.rightMargin = dp(8);
        bar.addView(menu, menlp);

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
            tb.setSingleLine(true);
            tb.setTextSize(13);
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
            if (ti != null) { boardCrc = ti[0]; boardCount = ti[1]; loadVerifiedIntoMemory(); }
            Logger.i("connect: up=" + duml.isUp() + " ac=" + duml.acModel()
                    + " sn=" + duml.acSerial() + " rc=" + duml.rcModel()
                    + " crc=" + Long.toHexString(boardCrc) + " count=" + boardCount);
            // The RC's 40007 stream is alive even with no drone linked (RC-only telemetry), so isUp()
            // alone is a false "connected". A real drone answers 0xE0 (FC table) or broadcasts its
            // codename — require one of those, else report "no drone" and drop the session.
            boolean droneUp = boardCrc != 0 || !duml.acModel().isEmpty();
            if (!droneUp) {
                Logger.w("connect: no drone (0xE0 silent, no codename) — dropping session");
                duml.stop();
                boardCrc = 0; boardCount = 0;
                connecting = false; connPhase = "";
                ui.post(() -> { toast("Дрон не подключён к пульту — включите дрон и дождитесь связи"); render(); });
                return;
            }
            connPhase = "Чтение…";       // фаза чтения быстрых параметров
            ui.post(this::render);
            readQuickParams();           // pull current toggle state (verify names, read values)
            connecting = false;
            connPhase = "";
            ui.post(this::render);
            reportConnectionTelemetry(); // one-shot per (serial,crc,count); queued if offline
        }).start();
    }

    /** After a successful connect, hand the drone identity + resolved quick-param ids to Telemetry. */
    private void reportConnectionTelemetry() {
        if (telemetry == null || boardCrc == 0) return;
        try {
            JSONObject ids = new JSONObject();
            for (QuickParam q : QUICK) {
                int ix = effectiveIdx(q);
                if (ix < 0) continue;
                JSONObject e = new JSONObject();
                e.put("id", ix);
                e.put("verified", verifiedIdx.containsKey(q.name));
                ids.put(q.name, e);
            }
            ModelCat m = effectiveModel();
            telemetry.reportConnection(Long.toHexString(boardCrc), boardCount, duml.acSerial(),
                    duml.acModel(), m != null ? m.name : "", ids, appVersion());
        } catch (Throwable t) { Logger.w("[tlm] report: " + t); }
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable t) { return ""; }
    }

    private volatile boolean detecting = false;
    private boolean detectTried = false;
    private boolean installPermTried = false;

    /**
     * Quick model detect WITHOUT a full session: briefly open the reader, fire ONE 0xE0 (get_table)
     * to read the firmware-table CRC (+count), then release the socket immediately. CRC → model via the
     * bundled catalog. Reliable (0xE0 answers) and fast — unlike a full name scan, which this FC drops.
     * Auto-runs once at startup so the coexist toggles get correct indices with no «Подключить».
     */
    private void quickDetect() {
        if (detecting || duml.running()) return;   // don't fight an active session
        detecting = true;
        render();
        new Thread(() -> {
            duml.start();
            long deadline = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < deadline && !duml.isUp()) sleepMs(50);
            long[] ti = duml.isUp() ? duml.tableInfo(0, 1200) : null;
            if (ti != null) { boardCrc = ti[0]; boardCount = ti[1]; loadVerifiedIntoMemory(); }
            duml.stop();                            // release 40007 at once — this is a quick probe only
            final boolean got = ti != null;
            Logger.i("[detect] crc=" + Long.toHexString(boardCrc) + " count=" + boardCount
                    + " -> " + (detectedModel() != null ? detectedModel().name : "нет в каталоге"));
            detecting = false;
            ui.post(() -> {
                ModelCat m = detectedModel();
                toast(!got ? "Модель: 0xE0 без ответа (дрон включён? Fly закрыта?)"
                        : m != null ? "Модель определена: " + m.name
                        : "CRC " + Long.toHexString(boardCrc) + " — нет в каталоге");
                render();
            });
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
        // НЕ сбрасываем boardCrc/boardCount: модель дрона от отключения не меняется, и оверлею
        // нужны корректные индексы после «Отключить» (иначе фолбэк на LitoX1). Re-detect при новом
        // «Подключить» перечитает 0xE0.
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

    /**
     * Index to actually use for a quick param: the verified one if we've confirmed the name at it for
     * this firmware, otherwise the catalog guess. Verified indices win — they may come from a manual
     * override or a brute-force scan when the catalog index was wrong for this firmware revision.
     */
    private int effectiveIdx(QuickParam q) {
        Integer v = verifiedIdx.get(q.name);
        return v != null ? v : idxOf(q);
    }

    // ---- verified-index persistence (keyed by firmware identity) ----

    /** Firmware identity of the connected drone, or null if 0xE0 hasn't answered this session. */
    private String crcKey() {
        return boardCrc != 0 ? Long.toHexString(boardCrc) + "_" + boardCount : null;
    }
    /** Identity to use for the (blind) overlay: this session's, else the last drone we verified. */
    private String crcKeyForOverlay() {
        String k = crcKey();
        if (k != null) return k;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("lastCrcKey", null);
    }
    private java.util.HashMap<String, Integer> loadVerified(String crcKey) {
        java.util.HashMap<String, Integer> m = new java.util.HashMap<>();
        if (crcKey == null) return m;
        String js = getSharedPreferences(PREFS, MODE_PRIVATE).getString("verified_" + crcKey, null);
        if (js == null) return m;
        try {
            JSONObject o = new JSONObject(js);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getInt(k)); }
        } catch (Exception ignore) {}
        return m;
    }
    /** Record that quick param `name` verified at `index` on the connected firmware (memory + prefs). */
    private void putVerified(String name, int index) {
        String k = crcKey();
        if (k == null) return;
        verifiedIdx.put(name, index);
        java.util.HashMap<String, Integer> m = loadVerified(k);
        m.put(name, index);
        JSONObject o = new JSONObject();
        try { for (java.util.Map.Entry<String, Integer> e : m.entrySet()) o.put(e.getKey(), e.getValue()); }
        catch (Exception ignore) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("verified_" + k, o.toString())
                .putString("lastCrcKey", k)
                .apply();
    }
    /** Pull this firmware's saved verifications into memory (called once 0xE0 identifies the drone). */
    private void loadVerifiedIntoMemory() {
        verifiedIdx.clear();
        verifiedIdx.putAll(loadVerified(crcKey()));
        if (!verifiedIdx.isEmpty()) Logger.i("[verify] загружено проверенных id: " + verifiedIdx);
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
        // если оверлей открыт — перезапустить его, чтобы он взял индексы новой модели
        if (overlayOn) { Logger.i("[overlay] restart on model change"); stopOverlay(); startOverlay(); }
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
            int ix = effectiveIdx(q);
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
            if (name != null && q.matches(name)) putVerified(q.name, ix);   // name confirmed at this id → remember
            else if (name != null && !name.isEmpty()) Logger.w("[verify] " + q.name + " idx=" + ix
                    + " → имя борта '" + name + "' НЕ совпало");
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

        for (final QuickParam q : QUICK) {
            final int ix = effectiveIdx(q);
            String seenName = ix < 0 ? null : qpName.get(ix);   // null if get_info didn't answer
            Long cur = ix < 0 ? null : qpValue.get(ix);
            boolean haveName = seenName != null && !seenName.isEmpty();
            boolean nameMismatch = haveName && !q.matches(seenName);   // get_info answered a DIFFERENT name
            boolean verified = verifiedIdx.containsKey(q.name);

            String status;
            if (!connected) {
                status = q.name + " · нет данных";
            } else if (ix < 0) {
                status = q.name + " · нет в каталоге выбранной модели";
            } else if (cur == null) {
                status = q.name + " · не прочитан (нет ответа)";
            } else if (nameMismatch) {
                status = "⚠ на этой прошивке idx " + ix + " = " + seenName + " — id не тот, запись заблокирована";
            } else {
                String now = cur == q.onValue ? q.onLabel
                        : cur == q.offValue ? q.offLabel : String.valueOf(cur);
                String def = qpDefault.get(ix);
                status = q.name + " · сейчас: " + now + (def != null ? " · по умолч. " + def : "")
                        + (verified ? " · id проверен ✓" : haveName ? "" : " · имя не проверено");
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
            // name at the catalog id is the WRONG parameter → offer to fix the id (manual / brute-force)
            if (connected && nameMismatch && !paramsBusy) {
                addActionRow(b, "   ↳ Задать id вручную", ACCENT, v -> promptManualIndex(q));
                addActionRow(b, "   ↳ Найти перебором (может быть долго)", ACCENT, v -> resolveQuickByScan(q));
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
        if (defv == null) defv = fmtFloat(p.def);            // тип не показываем — показываем default
        String meta = (p.isFloat() || (p.min == 0 && p.max == 0) ? ""
                : "диапазон " + fmtFloat(p.min) + "…" + fmtFloat(p.max) + "  ·  ")
                + (defv != null && !defv.isEmpty() ? "по умолч. " + defv + "  ·  " : "")
                + (reading ? "чтение…" : vtext == null ? "нет данных" : "значение: " + vtext);
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
    /**
     * fcc-style "без остановки DJI Fly" section: blind write-only toggles on the 40009 inject socket.
     * No reader, no board read, no Fly-stop — works with DJI Fly running in the background (свернуть Fly,
     * зайти сюда, нажать). Index is resolved from the selected model catalog; no readback confirmation.
     */
    private void buildCoexistSection(LinearLayout b) {
        addSectionCaption(b, "БЕЗ ОСТАНОВКИ DJI FLY · 40008 (вслепую)");
        ModelCat model = effectiveModel();
        addNote(b, "Запись одним пакетом в inject-сокет 40008, без чтения и без ответа (fire-and-forget). "
                + "Работает с запущенной DJI Fly: сверни Fly, зайди сюда, нажми, вернись в Fly. "
                + "Подтверждения нет (значение не вычитывается). Индекс — из выбранной модели"
                + (model != null ? " (" + model.name + ")" : ", иначе зашитый для Lito X1")
                + ". Подключение и остановка Fly НЕ требуются.");
        addActionRow(b, detecting ? "⏳ Определение модели…"
                        : "🔎 Определить модель (0xE0)" + (model != null ? " · сейчас: " + model.name : ""),
                detecting ? TXT_SUB : ACCENT, v -> { if (!detecting) quickDetect(); });
        addActionRow(b, overlayOn ? "⏹ Убрать оверлей поверх Fly" : "▧ Оверлей поверх Fly (не сворачивая её)",
                overlayOn ? RED : ACCENT, v -> { if (overlayOn) stopOverlay(); else startOverlay(); });
        for (final QuickParam q : QUICK) {
            int ixResolved = effectiveIdx(q);
            final int ix = ixResolved >= 0 ? ixResolved : q.index;   // fallback to the built-in LitoX1 index
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(ROW_BG);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(12), dp(10));

            TextView label = new TextView(this);
            label.setText(q.title + (ix < 0 ? " · нет в модели" : "  ·  idx " + ix));
            label.setTextColor(ix < 0 ? TXT_SUB : TXT);
            label.setTextSize(14);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(coexistBtn(q, ix, true));    // включить (onValue)
            row.addView(coexistBtn(q, ix, false));   // выключить (offValue)

            b.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addDivider(b);
        }
    }

    private Button coexistBtn(final QuickParam q, final int ix, final boolean on) {
        final long val = on ? q.onValue : q.offValue;
        Button btn = new Button(this);
        btn.setText((on ? q.onLabel : q.offLabel) + " (" + val + ")");
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTextColor(on ? GREEN : RED);
        btn.setBackground(pillOutline(on ? GREEN : RED));
        btn.setPadding(dp(14), dp(6), dp(14), dp(6));
        btn.setEnabled(ix >= 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        btn.setLayoutParams(lp);
        if (ix >= 0) btn.setOnClickListener(v -> {
            Logger.i("[coexist] 40009 write " + q.name + " idx=" + ix + " type=" + q.type + " -> " + val);
            toast(q.title + ": 40009 → " + val + "…");
            new Thread(() -> {
                boolean sent = duml.writeOnceCoexist(q.table, ix, q.type, val);
                ui.post(() -> toast(q.title + (sent ? " · отправлено (40008)" : " · ошибка отправки")));
            }).start();
        });
        return btn;
    }

    private volatile boolean scanBusy = false;
    private volatile String scanStatus = "";
    private TextView scanProgressView;

    /** Update the live scan-progress line (and cache the text so a re-render keeps it). */
    private void setScanStatus(String s) {
        scanStatus = s;
        ui.post(() -> { if (scanProgressView != null) scanProgressView.setText(s); });
    }

    /**
     * fcc-style live name scan: read the whole FLYC param table by name straight from the board —
     * 0xE0 for the count, then get_info (0xE1) over every index 0..count, building an index→name map.
     * This is exactly how fcc resolves indices (it never trusts a bundled table). Needs the 40007
     * reader, so DJI Fly must be CLOSED. Test hook: dumps the map + a reliability summary to the log.
     */
    private void runNameScan() {
        if (scanBusy) return;
        scanBusy = true;
        setScanStatus("старт… (DJI Fly должна быть закрыта)");
        render();
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            boolean startedHere = false;
            if (!duml.running()) { duml.start(); startedHere = true; }
            setScanStatus("подключение к релею 40007…");
            for (int i = 0; i < 30 && !duml.isUp(); i++) sleepMs(100);
            if (!duml.isUp()) {
                Logger.w("[scan] relay 40007 not up — DJI Fly закрыта? дрон включён?");
                setScanStatus("✗ релей 40007 недоступен (Fly закрыта? дрон включён?)");
                scanBusy = false; ui.post(this::render); return;
            }
            setScanStatus("чтение count (0xE0)…");
            long[] ti = duml.tableInfo(0, 2500);
            if (ti == null) {
                Logger.w("[scan] 0xE0 tableInfo — нет ответа");
                setScanStatus("✗ нет ответа на 0xE0 (count/crc)");
                if (startedHere) duml.stop();
                scanBusy = false; ui.post(this::render); return;
            }
            final long crc = ti[0], count = ti[1];
            final int cap = (int) Math.min(count, 4096);
            Logger.i("[scan] table0 crc=" + Long.toHexString(crc) + " count=" + count + " → скан " + cap + " индексов");
            setScanStatus("0/" + cap + " · имён 0 · crc " + Long.toHexString(crc));
            java.util.LinkedHashMap<Integer, String> map = duml.scanNames(0, cap, new Duml.ScanCb() {
                @Override public boolean cancelled() { return !scanBusy || !duml.isUp(); }
                @Override public void progress(int idx, int c, int named) {
                    long el = (System.currentTimeMillis() - t0) / 1000;
                    setScanStatus(idx + "/" + cap + " · имён " + named + " · " + el + "с");
                    Logger.i("[scan] " + idx + "/" + cap + " named=" + named + " " + el + "s");
                }
            });
            final long dt = System.currentTimeMillis() - t0;
            final int fNamed = map.size(), fEmpty = cap - map.size();
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<Integer, String> e : map.entrySet())
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            Logger.i("[scan] DONE crc=" + Long.toHexString(crc) + " count=" + count + " read=" + cap
                    + " named=" + fNamed + " empty=" + fEmpty + " in " + dt + "ms");
            Logger.i("[scan] index→name map (" + map.size() + "):\n" + sb);
            if (startedHere) duml.stop();
            boolean stopped = !scanBusy && map.size() < cap;
            setScanStatus((stopped ? "⏹ остановлено: " : "✓ готово: ") + fNamed + "/" + cap
                    + " имён, пусто " + fEmpty + ", " + (dt / 1000) + "с (карта в логе)");
            scanBusy = false;
            ui.post(this::render);
        }).start();
    }

    /**
     * Start the floating overlay (fcc gesture-app style): a handle on top of DJI Fly that expands the
     * coexist toggles. Resolves each toggle's index from the detected/cached model (fallback = built-in
     * LitoX1) and hands them to OverlayService. Requests the "draw over other apps" grant if missing.
     */
    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            if (isRc()) {
                // On the DJI RC the Settings overlay-toggle is blocked ("функция отключена"), so the grant
                // can only come from the system shell — show instructions instead of the dead Settings screen.
                showOverlayPermHelp();
            } else {
                toast("Разреши «Поверх других приложений», затем нажми снова");
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) { Logger.w("[overlay] settings intent: " + t); }
            }
            return;
        }
        // The overlay writes BLIND (no readback). We only ever expose a toggle whose id we've confirmed
        // by name for THIS firmware in a prior «Подключить». If the drone was never verified — refuse to
        // open and tell the user to connect first (CLAUDE.md §5/§10: never write by an unverified index).
        String ck = crcKeyForOverlay();
        java.util.HashMap<String, Integer> ver = loadVerified(ck);
        if (crcKey() != null && crcKey().equals(ck)) ver.putAll(verifiedIdx);   // freshest for this session
        Logger.i("[overlay] start: firmware=" + (ck != null ? ck : "НЕИЗВЕСТНА")
                + " проверенных id=" + ver.size() + " crc=" + Long.toHexString(boardCrc) + " count=" + boardCount);
        if (ck == null || ver.isEmpty()) {
            showOverlayNotVerifiedWarning();
            return;
        }
        // build arrays ONLY for verified toggles, in the fixed ATTI / LED / GPS order, at verified id
        String[] wantNames  = { "fswitch_selection", "forearm_led_ctrl", "gps_enable" };
        String[] wantTitles = { "ATTI", "LED", "GPS" };
        java.util.ArrayList<String> lTitles = new java.util.ArrayList<>(), lTypes = new java.util.ArrayList<>(),
                lOnl = new java.util.ArrayList<>(), lOffl = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> lIdx = new java.util.ArrayList<>();
        java.util.ArrayList<Long> lOnv = new java.util.ArrayList<>(), lOffv = new java.util.ArrayList<>();
        for (int w = 0; w < wantNames.length; w++) {
            Integer ix = ver.get(wantNames[w]);
            if (ix == null) { Logger.i("[overlay]   " + wantTitles[w] + " (" + wantNames[w] + ") — не проверен, скрыт"); continue; }
            for (QuickParam q : QUICK) {
                if (!q.name.equals(wantNames[w])) continue;
                lTitles.add(wantTitles[w]); lTypes.add(q.type); lIdx.add(ix);
                lOnv.add(q.onValue); lOffv.add(q.offValue); lOnl.add(q.onLabel); lOffl.add(q.offLabel);
                Logger.i("[overlay]   " + wantTitles[w] + " (" + q.name + ") idx=" + ix + " ← проверен");
                break;
            }
        }
        int n = lTitles.size();
        if (n == 0) { showOverlayNotVerifiedWarning(); return; }
        String[] titles = lTitles.toArray(new String[0]), types = lTypes.toArray(new String[0]),
                onl = lOnl.toArray(new String[0]), offl = lOffl.toArray(new String[0]);
        int[] idxs = new int[n]; long[] onv = new long[n], offv = new long[n];
        for (int i = 0; i < n; i++) { idxs[i] = lIdx.get(i); onv[i] = lOnv.get(i); offv[i] = lOffv.get(i); }
        Intent it = new Intent(this, OverlayService.class);
        it.putExtra("titles", titles); it.putExtra("types", types); it.putExtra("indices", idxs);
        it.putExtra("onVals", onv); it.putExtra("offVals", offv);
        it.putExtra("onLabels", onl); it.putExtra("offLabels", offl);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it); else startService(it);
        } catch (Throwable t) {
            Logger.w("[overlay] start failed: " + t);
            toast("Не удалось запустить оверлей: " + t.getClass().getSimpleName());
            return;
        }
        overlayOn = true;
        render();
    }

    private void showOverlayPermHelp() {
        showShellPermHelp("SYSTEM_ALERT_WINDOW", "Нужно разрешение на оверлей",
                "запуск окна поверх других приложений");
    }

    /**
     * RC-only helper: a permission the RC's Settings UI blocks (overlay, install APK, usage access)
     * can only be granted from the dpad.fuli system shell. Show the exact appops command with
     * copy + open/download dpad.fuli.
     */
    private void showShellPermHelp(String appop, String title, String what) {
        final String cmd = "appops set com.djiparam " + appop + " allow";
        final boolean fuli = isInstalled(FULI_PKG);
        String msg = "Нужно разрешение: " + what + ".\n\n"
                + "На пульте DJI его нельзя выдать через настройки — только через системный shell "
                + "приложения dpad.fuli:\n\n"
                + (fuli ? "" : "У вас не установлено приложение dpad.fuli (сервисный shell DJI для "
                        + "тестирования и отладки) — скачайте и установите его кнопкой ниже.\n\n")
                + "1. Откройте dpad.fuli (Shell).\n"
                + "2. Введите и выполните команду:\n\n" + cmd + "\n\n"
                + "3. Вернитесь и повторите действие.";
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Копировать команду", null)   // wired in onShow so it does NOT dismiss
                .setNeutralButton(fuli ? "Открыть dpad.fuli" : "Скачать dpad.fuli", (dd, w) -> {
                    if (fuli) {
                        Intent li = getPackageManager().getLaunchIntentForPackage(FULI_PKG);
                        if (li != null) startActivity(li); else toast("dpad.fuli не установлен");
                    } else {
                        installApkFromUrl(FULI_URL, "dpad.fuli");
                    }
                })
                .setNegativeButton("Закрыть", null)
                .create();
        dlg.setOnShowListener(di -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {                       // copy without closing the dialog
                    try {
                        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(android.content.ClipData.newPlainText("cmd", cmd));
                        toast("Команда скопирована — вставь её в dpad.fuli");
                    } catch (Throwable t) { toast("Не удалось скопировать"); }
                }));
        dlg.show();
    }

    private void stopOverlay() {
        stopService(new Intent(this, OverlayService.class));
        overlayOn = false;
        render();
    }

    private boolean overlayOn = false;

    /** Refuse to open the (blind-writing) overlay for a drone whose quick-param ids were never verified. */
    private void showOverlayNotVerifiedWarning() {
        Logger.w("[overlay] запуск отклонён: нет проверенных id для "
                + (crcKeyForOverlay() == null ? "неизвестной прошивки" : crcKeyForOverlay()));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Оверлей недоступен — дрон не проверен")
                .setMessage("Быстрые параметры этого дрона ещё ни разу не проверялись: приложение не знает, "
                        + "какие id на этой прошивке действительно соответствуют нужным параметрам, а оверлей "
                        + "пишет вслепую (без чтения-подтверждения).\n\nСначала нажмите «Подключить» (DJI Fly "
                        + "закрыта, дрон на земле) — приложение сверит имена быстрых параметров по их id и "
                        + "запомнит результат. После этого оверлей будет открываться сразу и покажет только "
                        + "успешно проверенные тумблеры.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    /** Manually set the id of a quick param; the name at it is read and stored only if it matches. */
    private void promptManualIndex(final QuickParam q) {
        if (!duml.isUp()) { toast("Сначала «Подключить» (Fly закрыта) — id проверяется чтением имени"); return; }
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setHint("index параметра " + q.name);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Задать id для «" + q.title + "»")
                .setMessage("Введите index, по которому на этой прошивке лежит «" + q.name + "». Приложение "
                        + "прочитает имя по этому id и запомнит его только если оно совпадёт с нужным.")
                .setView(in)
                .setPositiveButton("Проверить", (d, w) -> {
                    String s = in.getText().toString().trim();
                    if (s.isEmpty()) { toast("Введите id"); return; }
                    final int idx;
                    try { idx = Integer.parseInt(s); } catch (Exception e) { toast("Не число"); return; }
                    verifyManualIndex(q, idx);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void verifyManualIndex(final QuickParam q, final int idx) {
        if (paramsBusy) { toast("Занято, подождите"); return; }
        paramsBusy = true; render();
        new Thread(() -> {
            final String name = infoName(duml.getInfoRaw(q.table, idx, 1200));
            final boolean ok = name != null && q.matches(name);
            if (ok) putVerified(q.name, idx);
            paramsBusy = false;
            ui.post(() -> {
                if (ok) toast("✓ id " + idx + " = " + q.name + " — запомнено");
                else toast("По id " + idx + " имя '" + (name == null ? "нет ответа" : name)
                        + "' — не совпадает с " + q.name);
                if (duml.isUp()) new Thread(this::readQuickParams).start(); else render();
            });
        }).start();
    }

    private volatile boolean resolveBusy = false, resolveCancel = false;

    /**
     * Brute-force the on-board id of a quick param by NAME. Tries the ids most likely to hit first —
     * every id this param has across our bundled model tables (plus the built-in default) — and only
     * then falls back to a full sequential name scan (0xE1 over the whole table). Needs the 40007
     * reader, so DJI Fly must be CLOSED; the full scan is the slow part we warn about.
     */
    private void resolveQuickByScan(final QuickParam q) {
        if (resolveBusy || scanBusy) { toast("Уже идёт скан/перебор"); return; }
        loadModelsIfNeeded();
        final java.util.LinkedHashSet<Integer> cand = new java.util.LinkedHashSet<>();
        int ci = idxOf(q); if (ci >= 0) cand.add(ci);                              // текущий каталог — первым
        for (ModelCat m : models) { Integer i = m.idx.get(q.name); if (i != null) cand.add(i); }  // все известные модели
        cand.add(q.index);                                                         // встроенный дефолт
        final android.widget.TextView tv = new android.widget.TextView(this);
        tv.setPadding(dp(20), dp(16), dp(20), dp(8));
        tv.setText("Готовлю перебор…");
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Поиск id для «" + q.name + "»")
                .setView(tv)
                .setNegativeButton("Остановить", (d, w) -> resolveCancel = true)
                .setCancelable(false)
                .create();
        dlg.show();
        resolveBusy = true; resolveCancel = false;
        new Thread(() -> {
            boolean startedHere = false;
            if (!duml.running()) { duml.start(); startedHere = true; }
            upd(tv, "подключение к релею 40007…");
            for (int i = 0; i < 30 && !duml.isUp(); i++) sleepMs(100);
            int found = -1;
            if (!duml.isUp()) {
                upd(tv, "✗ релей 40007 недоступен (Fly закрыта? дрон включён?)");
            } else {
                int k = 0;
                for (int idx : cand) {
                    if (resolveCancel) break;
                    upd(tv, "известные id: " + (++k) + "/" + cand.size() + " (id " + idx + ")");
                    String name = infoName(duml.getInfoRaw(q.table, idx, 800));
                    if (name != null && q.matches(name)) { found = idx; break; }
                    sleepMs(QP_PACE_MS);
                }
                if (found < 0 && !resolveCancel) {
                    long[] ti = duml.tableInfo(0, 2000);
                    final int count = ti != null ? (int) Math.min(ti[1], 4096) : 1600;
                    upd(tv, "полный перебор 0…" + count + " (может занять минуту)…");
                    java.util.LinkedHashMap<Integer, String> map = duml.scanNames(0, count, new Duml.ScanCb() {
                        @Override public boolean cancelled() { return resolveCancel || !duml.isUp(); }
                        @Override public void progress(int idx, int c, int named) {
                            upd(tv, "перебор " + idx + "/" + count + " · имён " + named);
                        }
                    });
                    for (java.util.Map.Entry<Integer, String> e : map.entrySet())
                        if (q.matches(e.getValue())) { found = e.getKey(); break; }
                }
            }
            if (startedHere) duml.stop();
            final int f = found;
            if (f >= 0) putVerified(q.name, f);
            resolveBusy = false;
            ui.post(() -> {
                try { dlg.dismiss(); } catch (Throwable ignore) {}
                if (f >= 0) {
                    toast("✓ Найдено: " + q.name + " = id " + f + " — запомнено");
                    if (duml.isUp()) new Thread(this::readQuickParams).start(); else render();
                } else if (resolveCancel) { toast("Перебор остановлен"); render(); }
                else { toast("id для " + q.name + " не найден — задайте вручную"); render(); }
            });
        }).start();
    }

    private void upd(final android.widget.TextView tv, final String s) {
        Logger.i("[resolve] " + s);
        ui.post(() -> tv.setText(s));
    }

    // ---- self-update from GitHub releases ----
    private static final String GH_LATEST = "https://api.github.com/repos/lmdegreeds/djiparam/releases/latest";
    private static final String FULI_PKG = "com.dpad.fuli";
    private static final String FULI_URL = "https://github.com/lmdegreeds/djiparam/releases/download/tools/utilities.apk";

    private boolean isInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Throwable t) { return false; }
    }
    private volatile boolean updateBusy = false;

    /** Simple centered info popup — visible regardless of scroll position (unlike a toast). */
    private void infoDialog(String title, String msg) {
        new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }

    /** Check GitHub for a newer release; if found, offer to download + install it. */
    private void checkUpdate() {
        if (updateBusy) return;
        updateBusy = true; render();
        toast("Проверка обновлений…");
        new Thread(() -> {
            try {
                // Use the github.com "latest" redirect (not the rate-limited API): it 302s to the
                // latest release tag page — read the Location header and derive tag + APK URL.
                final String tag = latestTag();
                final String latest = tag == null ? "" : tag.replaceFirst("^[vV]", "");
                final String cur = version();
                final String apkUrl = tag == null ? null
                        : "https://github.com/lmdegreeds/djiparam/releases/download/" + tag + "/DjiParam-" + tag + ".apk";
                final boolean newer = tag != null && isNewer(latest, cur);
                Logger.i("[update] latest=" + tag + " cur=" + cur + " newer=" + newer + " apk=" + apkUrl);
                ui.post(() -> {
                    updateBusy = false; render();
                    if (tag == null || latest.isEmpty()) { infoDialog("Обновление", "Не удалось определить последнюю версию на GitHub."); return; }
                    if (!newer) {
                        infoDialog("Обновление", "У вас установлена последняя версия (" + cur + ").");
                        return;
                    }
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Доступно обновление")
                            .setMessage("Текущая версия: " + cur + "\nНовая версия: " + latest
                                    + "\n\nСкачать и установить?")
                            .setPositiveButton("Обновить", (d, w) -> installApkFromUrl(apkUrl, "версии " + latest))
                            .setNegativeButton("Отмена", null)
                            .show();
                });
            } catch (Throwable t) {
                Logger.w("[update] check failed: " + t);
                ui.post(() -> {
                    updateBusy = false; render();
                    infoDialog("Обновление", "Не удалось проверить обновления.\n"
                            + "Проверьте подключение к интернету.\n(" + t.getClass().getSimpleName() + ")");
                });
            }
        }).start();
    }

    /** Download an APK by URL and install it via PackageInstaller (used for self-update and dpad.fuli). */
    private void installApkFromUrl(String url, String label) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            requestInstallPerm();
            return;
        }
        if (updateBusy) return;
        updateBusy = true; render();
        toast("Скачивание " + label + "…");
        new Thread(() -> {
            try {
                byte[] apk = httpGetBytes(url);
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams params =
                        new android.content.pm.PackageInstaller.SessionParams(
                                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sid = pi.createSession(params);
                android.content.pm.PackageInstaller.Session s = pi.openSession(sid);
                try (java.io.OutputStream out = s.openWrite("apk", 0, apk.length)) {
                    out.write(apk); s.fsync(out);
                }
                Intent it = new Intent(this, InstallReceiver.class);
                int flags = Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0;
                android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(this, sid, it, flags);
                s.commit(pend.getIntentSender());
                s.close();
                Logger.i("[update] committed session " + sid + " (" + apk.length + " b) — " + label);
                ui.post(() -> { updateBusy = false; render(); toast("Установка " + label + "…"); });
            } catch (Throwable t) {
                Logger.w("[update] install failed (" + label + "): " + t);
                ui.post(() -> { updateBusy = false; render(); toast("Ошибка установки: " + t.getClass().getSimpleName()); });
            }
        }).start();
    }

    /** Ensure the "install unknown apps" permission — RC grants it via the shell (Settings blocked); phones via Settings. */
    private void requestInstallPerm() {
        if (isRc()) {
            showShellPermHelp("REQUEST_INSTALL_PACKAGES", "Нужно разрешение на установку",
                    "установка APK (для обновления приложения)");
        } else {
            toast("Разреши установку приложений и повтори «Обновить»");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) { Logger.w("[update] install-settings: " + t); }
        }
    }

    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestProperty("User-Agent", "djiparam");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(9000); c.setReadTimeout(9000);
        try (InputStream in = c.getInputStream()) {
            return new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    /** Latest release tag via github.com's /releases/latest 302 redirect — not API-rate-limited. */
    private static String latestTag() throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("https://github.com/lmdegreeds/djiparam/releases/latest").openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "djiparam");
        c.setConnectTimeout(9000); c.setReadTimeout(9000);
        try {
            c.getResponseCode();
            String loc = c.getHeaderField("Location");     // …/releases/tag/v0.7
            if (loc == null) return null;
            int i = loc.lastIndexOf('/');
            return i >= 0 && i + 1 < loc.length() ? loc.substring(i + 1) : null;
        } finally { c.disconnect(); }
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestProperty("User-Agent", "djiparam");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000); c.setReadTimeout(60000);
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** True if version string a is newer than b (numeric dot-compare, ignores a leading 'v'). */
    private static boolean isNewer(String a, String b) {
        String[] pa = a.split("[.\\-+]"), pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? intOr0(pa[i]) : 0, y = i < pb.length ? intOr0(pb[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }
    private static int intOr0(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    /** True on the DJI RC 2 (rc331) — where the Settings overlay-permission screen is blocked by DJI. */
    private static boolean isRc() {
        return "DJI".equalsIgnoreCase(Build.MANUFACTURER)
                || (Build.DEVICE != null && Build.DEVICE.toLowerCase().startsWith("rc"));
    }

    private void onQuickToggle(QuickParam q, boolean checked) {
        if (paramsBusy) return;
        final int ix = effectiveIdx(q);
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

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_search);
        icon.setColorFilter(TXT);                       // белая подкраска, общий стиль
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ilp.rightMargin = dp(8);
        searchRow.addView(icon, ilp);

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
                t.setTextColor(cats.get(p).equals(allCat) ? ACCENT : TXT);   // общая палитра: белый / акцент
                t.setBackgroundColor(ROW_BG);
                t.setTextSize(14);
                t.setPadding(dp(14), dp(12), dp(14), dp(12));
                return t;
            }
        };
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spin.setAdapter(sa);
        if (Build.VERSION.SDK_INT >= 16) spin.setPopupBackgroundDrawable(new ColorDrawable(0xFF161619));
        int sel = cats.indexOf(allCat);
        spin.setSelection(sel < 0 ? cats.indexOf(ALL_CATS) : sel);
        spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                String chosen = cats.get(position);
                if (!chosen.equals(allCat)) { allCat = chosen; buildRows(); }
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        searchRow.addView(spin, new LinearLayout.LayoutParams(dp(200),
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
            String defShown = allDefault.get(k);                 // тип не показываем — вместо него default
            if (defShown == null) defShown = fmtFloat(p.def);    // из встроенной таблицы, если ещё не читали
            h.type.setText(defShown == null || defShown.isEmpty() ? "" : "умолч. " + defShown);
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
                if (def == null) def = fmtFloat(p.def);          // тип убран, показываем default
                String defStr = def != null && !def.isEmpty() ? "по умолч. " + def : "";
                h.meta.setText(defStr + range + "  ·  " + p.cat
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
        addSectionCaption(b, "ВОЗМОЖНОСТИ");
        addNote(b, "Редактор параметров полётного контроллера дрона. Работает только на пульте DJI RC 2. "
                + "Что умеет:\n"
                + "•  Быстрые переключатели: режим ATTI, передние LED, приём GPS.\n"
                + "•  Полный список параметров дрона с поиском по названию и фильтром по категориям — "
                + "с описанием, значением по умолчанию и диапазоном. Текущее значение параметра "
                + "читается с борта по тапу на строку (по требованию).\n"
                + "•  Плавающее меню поверх DJI Fly: менять переключатели можно, не выходя из DJI Fly.\n"
                + "•  Автоматическое определение модели подключённого дрона.\n"
                + "•  Все изменения — под вашу ответственность: выполняйте на земле, дрон в покое.");

        addSectionCaption(b, "КАК ИСПОЛЬЗОВАТЬ");
        addNote(b, "Основной режим — чтение и правка параметров (DJI Fly закрыта).\n"
                + "1.  Включите дрон, дождитесь связи с пультом.\n"
                + "2.  Закройте DJI Fly кнопкой с логотипом DJI — приложение и DJI Fly не могут держать "
                + "связь с дроном одновременно.\n"
                + "3.  Нажмите «Подключить». Модель дрона определяется автоматически (по прошивке борта); "
                + "при необходимости выберите её вручную кнопкой «модель». Приложение прочитает текущие "
                + "значения и покажет состояние параметров.\n"
                + "4.  Вкладка «Быстрые» — переключатели ATTI / LED / GPS с их текущим состоянием. "
                + "Вкладка «Все параметры» — поиск и правка любого параметра. Тап по строке "
                + "разворачивает карточку и читает текущее значение с борта (по одному, по требованию).\n"
                + "5.  Когда закончите: если статус «Подключено», перед запуском DJI Fly нажмите "
                + "«Отключить» — пока приложение подключено, оно держит связь с дроном, и DJI Fly не "
                + "запустится корректно.");
        addNote(b, "Массовое чтение всех значений с борта по радио слишком медленное, поэтому в "
                + "приложении оно не используется — значения читаются поштучно, по тапу. Для полного "
                + "бэкапа/выгрузки всех параметров лучше использовать утилиты, работающие через USB.");
        addNote(b, "Быстрые действия без остановки DJI Fly — кнопка «≡».\n"
                + "Открывает плавающее меню поверх DJI Fly: переключать ATTI / LED / GPS, не выходя из "
                + "Fly. Запись идёт вслепую — без чтения текущего значения.\n"
                + "Для этого режима нужно разрешение на запуск окна поверх других приложений. На пульте "
                + "DJI системный экран настроек заблокирован, поэтому разрешение выдаётся командой через "
                + "системный shell заводского сервисного приложения DJI (для тестирования и отладки) — "
                + "приложение покажет точную команду, если нажать «≡» без разрешения.\n"
                + "Сам оверлей «Подключить» не требует (пишет вслепую). Но если вы перед этим "
                + "подключались кнопкой «Подключить» (статус «Подключено») — нажмите «Отключить» до "
                + "запуска DJI Fly, иначе Fly не запустится корректно.\n"
                + "Все изменения — только на земле, дрон в покое, под вашу ответственность.");

        addSectionCaption(b, "ПРИЛОЖЕНИЕ");
        addLine(b, "Версия " + version() + "   ·   " + getPackageName(), TXT_SUB);
        addLine(b, Build.MODEL + "   ·   Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")", TXT_SUB);
        addLine(b, "GitHub: github.com/lmdegreeds/djiparam", TXT_SUB);

        addSectionCaption(b, "ОБНОВЛЕНИЕ");
        addActionRow(b, updateBusy ? "Проверка…" : "Проверить обновления",
                updateBusy ? TXT_SUB : ACCENT, v -> { if (!updateBusy) checkUpdate(); });
        addNote(b, "Ищет новую версию в релизах на GitHub и, если найдена, скачивает и устанавливает её.");

        addSectionCaption(b, "ДРОН");
        ModelCat em = effectiveModel();
        addLine(b, "Модель: " + (em != null
                        ? em.name + (manualModel == null ? " · авто" : " · вручную") : "не определена"),
                em != null ? GREEN : TXT_SUB);
        boolean crcMatch = em != null && em.crc != 0 && em.crc == boardCrc;
        String ac = duml.acModel();
        String a1 = "CRC " + (boardCrc != 0 ? Long.toHexString(boardCrc) + (crcMatch ? "✓" : "") : "—");
        String a2 = "count " + (boardCount != 0 ? boardCount + (em != null && em.count == boardCount ? "✓" : "") : "—");
        String a3 = "код " + (ac != null && !ac.isEmpty() ? ac.toUpperCase() : "—");
        addLine(b, a1 + "   ·   " + a2 + "   ·   " + a3, boardCrc != 0 ? TXT : TXT_SUB);
        addLine(b, "Набор: " + (em != null ? em.catalog + " (" + em.count + ")" : "—")
                + (duml.acSerial().isEmpty() ? "" : "   ·   SN " + duml.acSerial())
                + (duml.rcModel().isEmpty() ? "" : "   ·   пульт " + duml.rcModel().toUpperCase()), TXT_SUB);
        addNote(b, "Модель — по CRC таблицы (0xE0); при общем CRC (wa150/wa151) различаем по count; "
                + "кодовое имя — запасной признак. От модели зависят индексы записи.");

        addSectionCaption(b, "ЛОГИ");
        File dir = Logger.logDir();
        addLine(b, "Папка логов: " + (dir != null ? dir.getAbsolutePath() : "—"), TXT_SUB);
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

    /** Compact single-line info row (label + several elements on one line) for the About tab. */
    private void addLine(LinearLayout parent, String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(13);
        t.setPadding(dp(16), dp(3), dp(16), dp(3));
        parent.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
