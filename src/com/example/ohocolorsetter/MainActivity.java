package com.example.ohocolorsetter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    // ---- OHO+ package / activities ----
    static final String PKG = "com.samsung.android.sidegesturepad";
    static final String ACT_MAIN = PKG + "/.settings.SGPSettingsActivity";
    static final String ACT_ADV = "SGPMoreSettingActivity";
    static final String ACT_ANIM = "SGPAnimationStyleActivity";
    static final String ACT_PICK = "SGPColorPickerActivity";

    // ---- spectrum square calibration (fractions of the spectrum container) ----
    // Defaults were measured on a Galaxy A55 (One UI 8). Tap "Calibrate" on any
    // other device to replace them; the result is stored in SharedPreferences.
    static final double DEF_XH0 = 0.1253, DEF_XH1 = 0.8901;
    static final double DEF_YS0 = 0.0533, DEF_YS1 = 0.9378;
    static final String PREFS = "oho_calib";
    static final String SETTINGS = "oho_settings";

    // ---- speed presets ----
    static final int SPEED_SLOW = 0, SPEED_NORMAL = 1, SPEED_FAST = 2;

    private double calXH0 = DEF_XH0, calXH1 = DEF_XH1;
    private double calYS0 = DEF_YS0, calYS1 = DEF_YS1;
    private boolean calibrated = false;

    private TextView logView;
    private Button applyBtn;
    private Button calibBtn;
    private Toast currentToast;
    private TextView overlayView;
    private WindowManager overlayWm;
    private volatile boolean overlayAllowed = false;
    private volatile String lastResult = "Done";
    private volatile String expectedFocus = null;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private SeekBar speedBar;
    private TextView speedLabel;
    private volatile int speed = SPEED_NORMAL;      // user setting (apply flow)
    private volatile int flowSpeed = SPEED_NORMAL;  // snapshot used by the running flow
    private volatile boolean speedHint = false;

    // ---- per-colour sections (Material You vs Custom) ----
    private ColorSection fillSection, arrowSection;
    private volatile boolean materialFillOk = false, materialArrowOk = false;
    private volatile int materialFill = 0, materialArrow = 0;
    private volatile boolean materialFetching = false;
    private int materialRetry = 0;
    private volatile int lastSwatchDiff = 999;

    static class ColorSection {
        RadioGroup modeGroup;
        EditText input;
        TextView materialView;
        View swatch;
        volatile boolean materialMode = true;
        String key;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private ColorSection addColorSection(LinearLayout root, String title, String key,
                                         int materialId, int customId, String defaultCustom) {
        ColorSection s = new ColorSection();
        s.key = key;

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(16);
        h.setTextColor(0xFFFFFFFF);
        h.setPadding(0, dp(12), 0, 0);
        root.addView(h);

        s.modeGroup = new RadioGroup(this);
        s.modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton mat = new RadioButton(this);
        mat.setText("Material You");
        mat.setId(materialId);
        RadioButton cus = new RadioButton(this);
        cus.setText("Custom");
        cus.setId(customId);
        s.modeGroup.addView(mat);
        s.modeGroup.addView(cus);
        root.addView(s.modeGroup);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        s.swatch = new View(this);
        row.addView(s.swatch, new LinearLayout.LayoutParams(dp(32), dp(32)));
        s.materialView = new TextView(this);
        s.materialView.setTextSize(13);
        s.materialView.setPadding(dp(12), 0, 0, 0);
        s.materialView.setText("Material You: loading\u2026");
        row.addView(s.materialView);
        root.addView(row);

        s.input = new EditText(this);
        s.input.setHint("#RRGGBB or #AARRGGBB");
        s.input.setInputType(InputType.TYPE_CLASS_TEXT);
        s.input.setText(loadCustom(key, defaultCustom));
        root.addView(s.input);

        boolean matMode = loadMode(key, true);
        s.materialMode = matMode;
        s.modeGroup.check(matMode ? materialId : customId);
        s.input.setEnabled(!matMode);
        s.modeGroup.setOnCheckedChangeListener((g, id) -> {
            s.materialMode = id == materialId;
            s.input.setEnabled(!s.materialMode);
            saveMode(key, s.materialMode);
        });
        return s;
    }

    private String loadCustom(String key, String def) {
        return getSharedPreferences(SETTINGS, MODE_PRIVATE).getString(key + "_custom", def);
    }

    private void saveCustom(String key, String value) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putString(key + "_custom", value).apply();
    }

    private boolean loadMode(String key, boolean def) {
        return getSharedPreferences(SETTINGS, MODE_PRIVATE).getBoolean(key + "_material", def);
    }

    private void saveMode(String key, boolean material) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(key + "_material", material).apply();
    }

    private void setSwatch(View v, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(2, 0xFF777777);
        v.setBackground(d);
    }

    private void updateMaterialViews() {
        ui.post(() -> {
            if (fillSection == null || arrowSection == null) return;
            if (materialFillOk) {
                fillSection.materialView.setText("Material You: " + colorToHex(materialFill));
                setSwatch(fillSection.swatch, materialFill);
            } else {
                fillSection.materialView.setText("Material You: unavailable");
            }
            if (materialArrowOk) {
                arrowSection.materialView.setText("Material You: " + colorToHex(materialArrow));
                setSwatch(arrowSection.swatch, materialArrow);
            } else {
                arrowSection.materialView.setText("Material You: unavailable");
            }
        });
    }

    /** Reads the two theme colours from the system and forces 100% alpha. */
    private void fetchMaterialColors() {
        if (materialFetching) return;
        materialFetching = true;
        new Thread(() -> {
            try {
                if (!waitForShizuku(5000)) {
                    log("Material You: Shizuku not running");
                    return;
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    log("Material You: Shizuku permission not granted");
                    return;
                }
                String f = sh("cmd overlay lookup --user 0 android android:color/system_secondary_container_dark").trim();
                String a = sh("cmd overlay lookup --user 0 android android:color/system_on_secondary_container_dark").trim();
                Integer fc = parseMaterialColor(f);
                Integer ac = parseMaterialColor(a);
                if (fc == null || ac == null) {
                    log("Material You: unexpected output fill='" + f + "' arrow='" + a + "'");
                    return;
                }
                materialFill = fc;
                materialArrow = ac;
                materialFillOk = true;
                materialArrowOk = true;
                materialRetry = 0;
                log("Material You: fill=" + colorToHex(fc) + " arrow=" + colorToHex(ac));
                updateMaterialViews();
            } catch (Throwable t) {
                log("Material You lookup failed: " + t);
            } finally {
                materialFetching = false;
                updateMaterialViews();
            }
        }).start();
    }

    private static Integer parseMaterialColor(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        if (!s.startsWith("#")) s = "#" + s;
        Integer c = parseColor(s);
        if (c == null) return null;
        return 0xFF000000 | (c & 0xFFFFFF);   // Material You always starts at 100% alpha
    }

    /** Parse #RRGGBB or #AARRGGBB (alpha first); 6 digits default to FF alpha. */
    private static Integer parseColor(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6 && s.matches("[0-9a-fA-F]{6}")) {
            return 0xFF000000 | (int) Long.parseLong(s, 16);
        }
        if (s.length() == 8 && s.matches("[0-9a-fA-F]{8}")) {
            return (int) Long.parseLong(s, 16);
        }
        return null;
    }

    private static String colorToHex(int c) {
        int a = (c >>> 24) & 0xFF;
        if (a == 0xFF) return String.format(java.util.Locale.US, "#%06X", c & 0xFFFFFF);
        return String.format(java.util.Locale.US, "#%08X", c);
    }

    // =====================================================================
    // UI
    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        loadSpeed();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        // Android 15+ draws edge-to-edge; keep the content below the status bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            v.setPadding(pad, pad + top, pad, pad);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("OHO+ Color Setter");
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Applies Fill and Arrow together. Material You is read from the system theme.");
        hint.setTextSize(12);
        hint.setPadding(0, pad / 2, 0, 0);
        root.addView(hint);

        fillSection = addColorSection(root, "Fill color", "fill", 2001, 2002, "#473646");
        arrowSection = addColorSection(root, "Arrow color", "arrow", 3001, 3002, "#D1B8CD");

        TextView speedTitle = new TextView(this);
        speedTitle.setText("Speed");
        speedTitle.setPadding(0, pad / 2, 0, 0);
        root.addView(speedTitle);

        speedLabel = new TextView(this);
        speedLabel.setTextSize(14);
        root.addView(speedLabel);

        speedBar = new SeekBar(this);
        speedBar.setMax(SPEED_FAST);
        speedBar.setProgress(speed);
        speedBar.setKeyProgressIncrement(1);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                speed = progress;
                updateSpeedLabel();
                if (fromUser) saveSpeed(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(speedBar);

        TextView speedNote = new TextView(this);
        speedNote.setTextSize(11);
        speedNote.setText("Slow = safest  \u2022  Normal = default  \u2022  Fast = quicker, may fail on a laggy device");
        root.addView(speedNote);
        updateSpeedLabel();

        applyBtn = new Button(this);
        applyBtn.setText("Apply both colors");
        applyBtn.setOnClickListener(v -> startApply());
        root.addView(applyBtn);

        calibBtn = new Button(this);
        calibBtn.setText("Calibrate (this device)");
        calibBtn.setOnClickListener(v -> startCalibration());
        root.addView(calibBtn);

        Button perm = new Button(this);
        perm.setText("Grant Shizuku permission");
        perm.setOnClickListener(v -> requestPerm());
        root.addView(perm);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setTypeface(Typeface.MONOSPACE);
        ScrollView sc = new ScrollView(this);
        sc.addView(logView);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                log("Shizuku binder received");
                fetchMaterialColors();
            });
            Shizuku.addRequestPermissionResultListener((code, res) -> {
                log("Permission result: " + (res == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED(" + res + ")"));
                if (res == PackageManager.PERMISSION_GRANTED) fetchMaterialColors();
            });
        } catch (Throwable t) {
            log("Shizuku listener error: " + t);
        }

        log("Ready. Shizuku running: " + safePing());
        loadCalibration();
        fetchMaterialColors();

        applyIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override
    protected void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }

    private void applyIntent(Intent it) {
        if (it == null) return;
        String hx = it.getStringExtra("hex");
        String tg = it.getStringExtra("target");
        String fillExtra = it.getStringExtra("fill");
        String arrowExtra = it.getStringExtra("arrow");
        boolean fillMat = it.getBooleanExtra("fillMaterial", false);
        boolean arrowMat = it.getBooleanExtra("arrowMaterial", false);
        boolean auto = it.getBooleanExtra("auto", false);
        boolean cal = it.getBooleanExtra("calibrate", false);
        int sp = it.getIntExtra("speed", -1);
        if (sp >= SPEED_SLOW && sp <= SPEED_FAST) {
            speed = sp;
            saveSpeed(sp);
            if (speedBar != null) speedBar.setProgress(sp);
            updateSpeedLabel();
        }

        if (fillExtra != null) {
            fillSection.modeGroup.check(2002);
            fillSection.input.setText(fillExtra);
        } else if (fillMat) {
            fillSection.modeGroup.check(2001);
        }
        if (arrowExtra != null) {
            arrowSection.modeGroup.check(3002);
            arrowSection.input.setText(arrowExtra);
        } else if (arrowMat) {
            arrowSection.modeGroup.check(3001);
        }

        // Backward-compatible single-colour intent: --es hex --es target fill|arrow
        if (hx != null && !hx.isEmpty()) {
            if ("arrow".equalsIgnoreCase(tg)) {
                arrowSection.modeGroup.check(3002);
                arrowSection.input.setText(hx);
            } else {
                fillSection.modeGroup.check(2002);
                fillSection.input.setText(hx);
            }
        }

        if (hx != null || tg != null || fillExtra != null || arrowExtra != null
                || fillMat || arrowMat || auto || cal || sp >= 0) {
            log("intent: fill=" + (fillSection.materialMode ? "MaterialYou" : fillSection.input.getText())
                    + " arrow=" + (arrowSection.materialMode ? "MaterialYou" : arrowSection.input.getText())
                    + " auto=" + auto + " calibrate=" + cal + " speed=" + speedName(speed));
        }

        if (cal) {
            applyBtn.postDelayed(this::startCalibration, 900);
        } else if (auto) {
            applyBtn.postDelayed(this::startApply, 900);
        }
    }

    private String safePing() {
        try {
            return String.valueOf(Shizuku.pingBinder());
        } catch (Throwable t) {
            return "err(" + t.getClass().getSimpleName() + ")";
        }
    }

    private void requestPerm() {
        try {
            if (!Shizuku.pingBinder()) {
                log("Shizuku is not running. Start Shizuku first.");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                log("Shizuku permission already granted.");
                return;
            }
            log("Requesting Shizuku permission...");
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            log("requestPerm error: " + t);
        }
    }

    private Integer resolveColor(ColorSection s, boolean materialOk, int materialColor, String label) {
        if (s.materialMode) {
            if (!materialOk) {
                String msg = "Material You " + label + " color unavailable \u2014 check Shizuku/permission";
                log(msg);
                toast(msg);
                return null;
            }
            return materialColor;
        }
        String raw = s.input.getText().toString();
        Integer c = parseColor(raw);
        if (c == null) {
            String msg = "Invalid " + label + " color \"" + raw + "\" \u2014 use #RRGGBB or #AARRGGBB";
            log(msg);
            toast(msg);
            return null;
        }
        return c;
    }

    private void startApply() {
        if (busy) return;

        // Material You is fetched in the background at startup; wait briefly for it.
        if ((fillSection.materialMode && !materialFillOk)
                || (arrowSection.materialMode && !materialArrowOk)) {
            if (materialRetry < 5) {
                materialRetry++;
                log("Waiting for Material You colors\u2026");
                ui.postDelayed(this::startApply, 800);
                return;
            }
        }
        materialRetry = 0;

        Integer fillColor = resolveColor(fillSection, materialFillOk, materialFill, "Fill");
        Integer arrowColor = resolveColor(arrowSection, materialArrowOk, materialArrow, "Arrow");
        if (fillColor == null || arrowColor == null) return;

        saveCustom("fill", fillSection.input.getText().toString());
        saveCustom("arrow", arrowSection.input.getText().toString());

        final int fc = fillColor, ac = arrowColor;
        busy = true;
        flowSpeed = speed;   // slider applies to Apply only
        log("Apply flow speed: " + speedName(flowSpeed));
        log("Fill=" + colorToHex(fc) + " Arrow=" + colorToHex(ac));
        applyBtn.setEnabled(false);
        calibBtn.setEnabled(false);
        if (speedBar != null) speedBar.setEnabled(false);
        step("Applying Fill and Arrow\u2026");
        lastResult = "Done";

        new Thread(() -> {
            try {
                runFlow(fc, ac);
            } catch (Throwable t) {
                log("ERROR: " + t);
                lastResult = "Failed: " + t.getMessage();
                noteSpeedHint(t.getMessage());
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) log("  at " + st[i]);
            } finally {
                focusApp();
                finishOverlay(lastResult);
                busy = false;
                ui.post(() -> {
                    applyBtn.setEnabled(true);
                    calibBtn.setEnabled(true);
                    if (speedBar != null) speedBar.setEnabled(true);
                });
                log("=== done ===");
            }
        }).start();
    }

    private void startCalibration() {
        if (busy) return;
        busy = true;
        flowSpeed = SPEED_NORMAL;   // calibration always uses the default preset
        log("Calibration flow speed: " + speedName(flowSpeed)
                + " (slider: " + speedName(speed) + ")");
        applyBtn.setEnabled(false);
        calibBtn.setEnabled(false);
        if (speedBar != null) speedBar.setEnabled(false);
        step("Calibrating this device\u2026");
        lastResult = "Calibration done";

        new Thread(() -> {
            try {
                runCalibration();
            } catch (Throwable t) {
                log("CALIBRATION ERROR: " + t);
                lastResult = "Calibration failed: " + t.getMessage();
                noteSpeedHint(t.getMessage());
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) log("  at " + st[i]);
            } finally {
                focusApp();
                finishOverlay(lastResult);
                busy = false;
                ui.post(() -> {
                    applyBtn.setEnabled(true);
                    calibBtn.setEnabled(true);
                    if (speedBar != null) speedBar.setEnabled(true);
                });
                log("=== calibration finished ===");
            }
        }).start();
    }

    // =====================================================================
    // Shizuku shell
    // =====================================================================
    private IShizukuService svc() {
        return IShizukuService.Stub.asInterface(Shizuku.getBinder());
    }

    private byte[] shBytes(String cmd) throws Exception {
        IShizukuService s = svc();
        if (s == null) throw new Exception("Shizuku binder unavailable");
        IRemoteProcess p = s.newProcess(new String[]{"sh", "-c", cmd}, null, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ParcelFileDescriptor pfd = p.getInputStream();
        InputStream is = new FileInputStream(pfd.getFileDescriptor());
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        try { pfd.close(); } catch (Exception ignored) {}
        p.waitFor();
        return bos.toByteArray();
    }

    private String sh(String cmd) throws Exception {
        return new String(shBytes(cmd), "UTF-8");
    }

    /** Remember which OHO+ screen must be in front before the next interaction. */
    private void expect(String cls) {
        expectedFocus = cls;
    }

    /**
     * Abort if the foreground window is no longer the expected OHO+ screen.
     * This catches quick settings, the notification shade, home/recents, another
     * app, the lock screen or a different OHO+ screen.
     */
    private void checkFocus() throws Exception {
        String exp = expectedFocus;
        if (exp == null) return;
        String s = sh("dumpsys window | grep mCurrentFocus");
        if (!s.contains(exp)) {
            sleep(pollMs());
            s = sh("dumpsys window | grep mCurrentFocus");
            if (!s.contains(exp)) {
                String where = s.replace("mCurrentFocus=", "").trim();
                if (where.isEmpty()) where = "unknown";
                throw new Exception("interrupted by another screen/app (" + where + ")");
            }
        }
    }

    private void tap(int x, int y) throws Exception {
        checkFocus();
        log("  tap " + x + "," + y);
        sh("input tap " + x + " " + y);
    }

    private void swipe(int x1, int y1, int x2, int y2, int ms) throws Exception {
        checkFocus();
        log("  swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2);
        sh("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /**
     * Pause scaled by the running flow's preset. Fast is the old Normal
     * timing; Normal and Slow step up gradually from it (150 ms floor).
     */
    private void pause(long normalMs) {
        long ms = normalMs;
        if (flowSpeed == SPEED_SLOW) ms = Math.round(normalMs * 1.6);
        else if (flowSpeed == SPEED_NORMAL) ms = Math.round(normalMs * 1.25);
        if (ms < 150) ms = 150;
        sleep(ms);
    }

    /** Poll interval for focus waits. */
    private long pollMs() {
        return flowSpeed == SPEED_SLOW ? 260 : flowSpeed == SPEED_NORMAL ? 200 : 150;
    }

    /** Swipe duration scaled by the running flow's preset. */
    private int swipeMs(int normalMs) {
        if (flowSpeed == SPEED_SLOW) return Math.round(normalMs * 1.6f);
        if (flowSpeed == SPEED_NORMAL) return Math.round(normalMs * 1.25f);
        return normalMs;   // Fast
    }

    private boolean waitForActivity(String cls, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String s = sh("dumpsys window | grep mCurrentFocus");
            if (s.contains(cls)) return true;
            sleep(pollMs());
        }
        return false;
    }

    // =====================================================================
    // UI dump / node model
    // =====================================================================
    static class Nd {
        String cls = "", id = "", text = "", desc = "";
        int l, t, r, b;
        boolean checked;
        int cx() { return (l + r) / 2; }
        int cy() { return (t + b) / 2; }
        int w() { return r - l; }
        int h() { return b - t; }
        String sid() { int i = id.lastIndexOf('/'); return i < 0 ? id : id.substring(i + 1); }
        public String toString() {
            return cls + " [" + l + "," + t + "][" + r + "," + b + "] id=" + sid()
                    + " text='" + text + "'";
        }
    }

    private static final Pattern BOUNDS =
            Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    private List<Nd> dumpUi() throws Exception {
        checkFocus();
        for (int attempt = 0; attempt < 3; attempt++) {
            String s = sh("uiautomator dump /data/local/tmp/oho_ui.xml >/dev/null 2>&1; cat /data/local/tmp/oho_ui.xml");
            int i = s.indexOf("<?xml");
            if (i >= 0) {
                List<Nd> nodes = null;
                try {
                    nodes = parseXml(s.substring(i));
                } catch (Exception e) {
                    log("  dump parse failed: " + e.getMessage());
                }
                if (nodes != null) {
                    checkFocus();   // catch an interruption that happened during the dump
                    return nodes;
                }
            }
            pause(350);
        }
        throw new Exception("uiautomator dump failed");
    }

    // ---- fast full-screen activity dump (dumpsys activity top) ----
    private static final Pattern TOP_START =
            Pattern.compile("^(\\s*)([\\w.$]+)\\{.*$");
    private static final Pattern TOP_VIEW =
            Pattern.compile("^(\\s*)([\\w.$]+)\\{([^}]*)\\}.*$");
    private static final Pattern TOP_BOUNDS =
            Pattern.compile("(-?\\d+),(-?\\d+)-(-?\\d+),(-?\\d+)");
    private static final Pattern TOP_ID =
            Pattern.compile("#\\S+\\s+(?:app|android):id/([\\w.]+)");

    /**
     * Read a full-screen activity's view hierarchy from `dumpsys activity top`
     * (~0.2 s) instead of `uiautomator dump` (~2.5 s). Bounds in the dump are
     * relative to each parent, so offsets are accumulated down the tree.
     * The colour picker is a dialog window, so it still uses {@link #dumpUi()}.
     */
    private List<Nd> dumpUiTop(String activityCls) throws Exception {
        checkFocus();
        String dump = sh("dumpsys activity top");
        checkFocus();
        List<Nd> nodes = parseTopHierarchy(dump, activityCls);
        if (nodes.isEmpty()) {
            log("  dumpsys hierarchy empty for " + activityCls + " - falling back");
            return dumpUi();
        }
        return nodes;
    }

    private static List<Nd> parseTopHierarchy(String dump, String activityCls) {
        String[] lines = dump.split("\\r?\\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("[" + activityCls + "]")) start = i;
        }
        List<Nd> out = new ArrayList<>();
        if (start < 0) return out;

        // Collect view records, joining lines that the dump wrapped.
        List<String> recs = new ArrayList<>();
        List<Integer> inds = new ArrayList<>();
        StringBuilder cur = null;
        int curIndent = 0;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (cur != null && cur.indexOf("}") < 0) {
                cur.append(line.trim());              // wrapped continuation
            } else if (TOP_START.matcher(line).matches()) {
                cur = new StringBuilder(line);
                curIndent = leadingSpaces(line);
            } else {
                break;                                // end of this activity's hierarchy
            }
            if (cur != null && cur.indexOf("}") >= 0) {
                recs.add(cur.toString());
                inds.add(curIndent);
                cur = null;
            }
        }

        // Accumulate parent offsets (bounds in the dump are parent-relative).
        List<int[]> stack = new ArrayList<>();        // {indent, absLeft, absTop}
        for (int i = 0; i < recs.size(); i++) {
            Matcher m = TOP_VIEW.matcher(recs.get(i));
            if (!m.matches()) continue;
            String cls = m.group(2);
            String body = m.group(3);
            Matcher bm = TOP_BOUNDS.matcher(body);
            if (!bm.find()) continue;
            int l = Integer.parseInt(bm.group(1));
            int t = Integer.parseInt(bm.group(2));
            int r = Integer.parseInt(bm.group(3));
            int b = Integer.parseInt(bm.group(4));

            int indent = inds.get(i);
            while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent)
                stack.remove(stack.size() - 1);
            int pl = 0, pt = 0;
            if (!stack.isEmpty()) {
                int[] p = stack.get(stack.size() - 1);
                pl = p[1];
                pt = p[2];
            }
            int al = pl + l, at = pt + t, ar = pl + r, ab = pt + b;
            stack.add(new int[]{indent, al, at});

            // First token after the hash is the view state: G = gone, I = invisible.
            String before = body.substring(0, bm.start()).trim();
            String[] toks = before.split("\\s+");
            String vis = toks.length >= 2 ? toks[1] : "V";
            if (vis.startsWith("G") || vis.startsWith("I")) continue;
            if (ar <= al || ab <= at) continue;

            Nd n = new Nd();
            n.cls = cls;
            n.l = al;
            n.t = at;
            n.r = ar;
            n.b = ab;
            Matcher im = TOP_ID.matcher(body);
            if (im.find()) n.id = im.group(1);
            out.add(n);
        }
        return out;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static List<Nd> parseXml(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        Document doc = f.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes("UTF-8")));
        List<Nd> out = new ArrayList<>();
        collect(doc.getDocumentElement(), out);
        return out;
    }

    private static void collect(Element e, List<Nd> out) {
        Nd n = toNd(e);
        if (n != null) out.add(n);
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node c = kids.item(i);
            if (c instanceof Element) collect((Element) c, out);
        }
    }

    private static Nd toNd(Element e) {
        String bs = e.getAttribute("bounds");
        if (bs == null || bs.isEmpty()) return null;
        Matcher m = BOUNDS.matcher(bs);
        if (!m.find()) return null;
        Nd n = new Nd();
        n.l = Integer.parseInt(m.group(1));
        n.t = Integer.parseInt(m.group(2));
        n.r = Integer.parseInt(m.group(3));
        n.b = Integer.parseInt(m.group(4));
        n.cls = e.getAttribute("class");
        n.id = e.getAttribute("resource-id");
        n.text = e.getAttribute("text");
        n.desc = e.getAttribute("content-desc");
        n.checked = "true".equals(e.getAttribute("checked"));
        return n;
    }

    private static Nd byId(List<Nd> nodes, String shortId) {
        for (Nd n : nodes) if (n.sid().equals(shortId)) return n;
        return null;
    }

    private static Nd byText(List<Nd> nodes, String text) {
        for (Nd n : nodes) if (text.equals(n.text)) return n;
        return null;
    }

    /**
     * Detect the current animation style from the colour rows it exposes.
     * The app never changes the selected style; it only adapts to it.
     *   Arrow 3      -> fill_color + arrow_color
     *   Curved line  -> fill_color + arrow_color + stroke_color (outline, ignored)
     *   Arrow 1/2    -> no colour rows at all
     */
    private static String detectStyle(List<Nd> ui) {
        boolean fill = byId(ui, "fill_color") != null;
        boolean arrow = byId(ui, "arrow_color") != null;
        boolean stroke = byId(ui, "stroke_color") != null;
        if (fill && arrow && stroke) return "Curved line";
        if (fill && arrow) return "Arrow 3";
        if (!fill && !arrow) return "Arrow 1/2";
        return "unknown";
    }

    /** Exact style name from the checked radio (uiautomator dump, error path only). */
    private String checkedStyleName() {
        try {
            List<Nd> ui = dumpUi();
            String[] ids = {"radio_arrow1", "radio_arrow2", "radio_arrow3", "radio_curve"};
            String[] names = {"Arrow 1", "Arrow 2", "Arrow 3", "Curved line"};
            for (int i = 0; i < ids.length; i++) {
                Nd n = byId(ui, ids[i]);
                if (n != null && n.checked) return names[i];
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // =====================================================================
    // Screenshot
    // =====================================================================
    private Bitmap screenshot() throws Exception {
        byte[] png = shBytes("screencap -p");
        Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bmp == null) throw new Exception("screencap decode failed (" + png.length + " bytes)");
        return bmp;
    }

    // =====================================================================
    // Colour maths
    // =====================================================================
    static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h = 0;
        if (d > 0.00001f) {
            if (max == rf) h = 60f * (((gf - bf) / d) % 6f);
            else if (max == gf) h = 60f * (((bf - rf) / d) + 2f);
            else h = 60f * (((rf - gf) / d) + 4f);
            if (h < 0) h += 360f;
        }
        float s = max == 0 ? 0 : d / max;
        return new float[]{h, s, max};
    }

    static int[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = h / 60f;
        float x = c * (1 - Math.abs((hp % 2f) - 1));
        float m = v - c;
        float r = 0, g = 0, b = 0;
        int i = (int) Math.floor(hp) % 6;
        if (i < 0) i += 6;
        switch (i) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return new int[]{
                Math.round((r + m) * 255),
                Math.round((g + m) * 255),
                Math.round((b + m) * 255)};
    }

    static int pickVpct(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int base = Math.round(max * 100f / 255f);
        for (int d = 0; d <= 2; d++) {
            for (int sign = (d == 0 ? 1 : -1); sign <= 1; sign += 2) {
                int v = base + sign * d;
                if (v < 0 || v > 100) continue;
                if (Math.round(v * 2.55f) == max) return v;
            }
        }
        return base;
    }

    // =====================================================================
    // Main flow
    // =====================================================================
    private boolean waitForShizuku(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                if (Shizuku.pingBinder()) return true;
            } catch (Throwable ignored) {}
            sleep(100);
        }
        return false;
    }

    private void runFlow(int fillColor, int arrowColor) throws Exception {
        if (!waitForShizuku(3000)) throw new Exception("Shizuku is not running");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new Exception("Shizuku permission not granted");

        prepareOverlay();

        // wake the screen if it is off (harmless if already on)
        try { sh("input keyevent KEYCODE_WAKEUP"); } catch (Exception ignored) {}

        // warn if locked
        String lock = sh("dumpsys window | grep mDreamingLockscreen");
        if (lock.contains("true")) log("WARNING: screen appears locked");

        // 1. launch OHO+ main
        step("Opening OHO+\u2026");
        sh("am start -W -n " + ACT_MAIN + " --activity-clear-top");
        if (!waitForActivity(ACT_MAIN.substring(ACT_MAIN.indexOf('/') + 1), 6000))
            log("  warning: main activity not focused yet");
        expect("SGPSettingsActivity");
        pause(450);

        // 2. Advanced settings
        step("Navigating to Advanced settings\u2026");
        List<Nd> ui = dumpUiTop("SGPSettingsActivity");
        Nd adv = byId(ui, "detail_setting");
        if (adv == null) throw new Exception("'Advanced settings' row not found");
        tap(adv.cx(), adv.cy());
        if (!waitForActivity(ACT_ADV, 6000)) log("  warning: advanced activity not focused");
        expect("SGPMoreSettingActivity");
        pause(400);

        // 3. Animation row (tap title, left of the switch)
        step("Opening Animation settings\u2026");
        ui = dumpUiTop("SGPMoreSettingActivity");
        Nd anim = byId(ui, "arrow_animation");
        if (anim == null) throw new Exception("'Animation' row not found");
        Nd sw = byId(ui, "main_switch");
        int ax = anim.l + (int) (anim.w() * 0.35);
        if (sw != null && ax > sw.l - 40) ax = sw.l - 80;
        tap(ax, anim.cy());
        if (!waitForActivity(ACT_ANIM, 6000)) log("  warning: animation activity not focused");
        expect("SGPAnimationStyleActivity");
        pause(400);

        // 4. detect the current style once (never change it)
        ui = dumpUiTop("SGPAnimationStyleActivity");
        String style = detectStyle(ui);
        log("Animation style: " + style);
        if ("Arrow 1/2".equals(style)) {
            String exact = checkedStyleName();
            if (exact != null) style = exact;
            throw new Exception("Animation style \"" + style
                    + "\" has no Fill/Arrow color; switch to Arrow 3 or Curved line"
                    + " (the app does not change the style)");
        }

        // 5. apply Fill, then Arrow, in the same flow
        applyOneColor(style, fillColor, true);
        applyOneColor(style, arrowColor, false);

        lastResult = "Done \u2014 Fill " + colorToHex(fillColor)
                + " + Arrow " + colorToHex(arrowColor);
    }

    private void applyOneColor(String style, int color, boolean fill) throws Exception {
        String name = fill ? "Fill" : "Arrow";
        int alpha = (color >>> 24) & 0xFF;
        int[] rgb = {Color.red(color), Color.green(color), Color.blue(color)};
        float[] hsv = rgbToHsv(rgb[0], rgb[1], rgb[2]);
        int vpct = pickVpct(rgb[0], rgb[1], rgb[2]);
        int alphaPct = Math.round(alpha * 100f / 255f);
        float H = hsv[0], S = hsv[1];
        log(String.format(Locale.US,
                "%s target rgb=(%d,%d,%d) H=%.1f S=%.3f Vpct=%d alpha=%d%%",
                name, rgb[0], rgb[1], rgb[2], H, S, vpct, alphaPct));

        step("Opening " + name + " color picker\u2026");
        List<Nd> ui = dumpUiTop("SGPAnimationStyleActivity");
        Nd row = byId(ui, fill ? "fill_color" : "arrow_color");
        if (row == null) throw new Exception(name + " colour row not found");
        Nd swatchRow = row;
        tap(row.cx(), row.cy());
        if (!waitForActivity(ACT_PICK, 6000)) log("  warning: picker not focused");
        expect("SGPColorPickerActivity");
        pause(450);

        double corrX = 0, corrY = 0;
        int[] got = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            log("-- " + name + " attempt " + attempt + " --");

            // Spectrum tab
            ui = dumpUi();
            if (byId(ui, "sesl_color_picker_color_spectrum_view_container") == null) {
                Nd tab = byText(ui, "Spectrum");
                if (tab == null) tab = byText(ui, "SPECTRUM");
                if (tab != null) {
                    log("5) switch to Spectrum tab");
                    status("Selecting Spectrum\u2026");
                    tap(tab.cx(), tab.cy());
                    pause(400);
                    ui = dumpUi();
                }
            }

            Nd cont = byId(ui, "sesl_color_picker_color_spectrum_view_container");
            Nd sb = byId(ui, "sesl_color_picker_saturation_seekbar");
            Nd op = byId(ui, "sesl_color_picker_opacity_seekbar");
            Nd done = byId(ui, "button1");
            if (cont == null) throw new Exception("spectrum container not found");
            if (sb == null) throw new Exception("brightness seekbar not found");
            if (done == null) throw new Exception("Done button not found");

            double xH0 = cont.l + calXH0 * cont.w();
            double xH1 = cont.l + calXH1 * cont.w();
            double yS0 = cont.t + calYS0 * cont.h();
            double yS1 = cont.t + calYS1 * cont.h();

            double x = xH0 + (H / 360.0) * (xH1 - xH0) + corrX;
            double y = yS0 + S * (yS1 - yS0) + corrY;

            status("Picking " + name + " color\u2026");

            // iterate on hue / saturation using the brightness bar readback
            for (int it = 0; it < 4; it++) {
                x = clamp(x, cont.l + 2, cont.r - 2);
                y = clamp(y, cont.t + 2, cont.b - 2);
                tap((int) Math.round(x), (int) Math.round(y));
                pause(300);

                Bitmap shot = screenshot();
                int px = shot.getPixel(Math.max(0, sb.r - 6), sb.cy());
                float[] gotHs = rgbToHsv(Color.red(px), Color.green(px), Color.blue(px));
                float dH = H - gotHs[0];
                if (dH > 180) dH -= 360;
                if (dH < -180) dH += 360;
                float dS = S - gotHs[1];
                log(String.format(Locale.US,
                        "   iter%d @(%.0f,%.0f) read H=%.1f S=%.3f  dH=%.1f dS=%.3f",
                        it, x, y, gotHs[0], gotHs[1], dH, dS));

                if (Math.abs(dH) < 0.6f && Math.abs(dS) < 0.0025f) break;
                x += dH / (360.0 / (xH1 - xH0));
                y += dS * (yS1 - yS0);
            }

            // brightness (drag, must be after the square tap)
            int cy = sb.cy();
            int targetX = sb.l + Math.round((sb.r - sb.l) * (vpct / 100f));
            log("7) brightness " + vpct + "% -> x=" + targetX);
            status("Setting " + name + " brightness " + vpct + "%\u2026");
            swipe(sb.r - 6, cy, targetX, cy, swipeMs(400));
            pause(250);

            // alpha (opacity bar sits below the brightness bar). The picker
            // remembers the last opacity, so 100% must be set explicitly too.
            if (alphaPct >= 100) {
                resetOpacity(op);
            } else {
                log("8) alpha " + alphaPct + "%");
                status("Setting " + name + " alpha " + alphaPct + "%\u2026");
                setOpacity(op, alphaPct);
                // The Done button does not move, so the original node is still valid.
            }

            // Done
            step("Saving " + name + " color\u2026");
            tap(done.cx(), done.cy());
            if (!waitForActivity(ACT_ANIM, 6000)) log("  warning: did not return to animation screen");
            expect("SGPAnimationStyleActivity");
            pause(450);

            // verify swatch. Arrow 3 keeps its preview stale until the
            // Animation screen is re-entered, so refresh it first.
            if ("Arrow 3".equals(style)) {
                swatchRow = refreshAnimationScreen(fill);
            }
            got = verifySwatch(swatchRow, rgb, alphaPct);
            int diff = lastSwatchDiff;
            log(String.format(Locale.US,
                    "%s swatch rgb=(%d,%d,%d) target=(%d,%d,%d) diff=%d",
                    name, got[0], got[1], got[2], rgb[0], rgb[1], rgb[2], diff));

            int tolerance = alphaPct < 100 ? 6 : 1;
            if (diff <= tolerance) {
                log(name + " SUCCESS (max diff " + diff + ")");
                return;
            }
            log("  " + name + " mismatch (max diff " + diff + "), retrying");
            status("Almost there \u2014 adjusting " + name + "\u2026");

            // correct using achieved vs target hue/saturation (only meaningful
            // when the swatch shows the full colour, not an alpha composite)
            if (alphaPct >= 100) {
                float[] gotHsv = rgbToHsv(got[0], got[1], got[2]);
                float dH = H - gotHsv[0];
                if (dH > 180) dH -= 360;
                if (dH < -180) dH += 360;
                float dS = S - gotHsv[1];
                corrX += dH / (360.0 / (xH1 - xH0));
                corrY += dS * (yS1 - yS0);
            }

            // reopen the picker for the next attempt
            ui = dumpUiTop("SGPAnimationStyleActivity");
            Nd row2 = byId(ui, fill ? "fill_color" : "arrow_color");
            if (row2 == null) throw new Exception(name + " colour row gone, cannot retry");
            swatchRow = row2;
            tap(row2.cx(), row2.cy());
            if (!waitForActivity(ACT_PICK, 6000)) log("  warning: picker not focused (retry)");
            expect("SGPColorPickerActivity");
            pause(450);
        }

        throw new Exception(name + " colour did not converge (last rgb=" +
                (got == null ? "null" : got[0] + "," + got[1] + "," + got[2]) + ")");
    }

    private void resetOpacity(Nd op) throws Exception {
        if (op == null) return;
        // A previous custom-alpha run may have left the bar below 100%.
        // Touch the far right end (the same gesture the brightness bar uses).
        swipe(op.r - 6, op.cy(), op.r - 2, op.cy(), swipeMs(400));
        pause(250);
    }

    private void setOpacity(Nd op, int targetPct) throws Exception {
        if (op == null) {
            log("  warning: opacity seekbar not found; alpha stays at 100%");
            return;
        }
        for (int i = 0; i < 2; i++) {
            int targetX = op.l + Math.round((op.r - op.l) * targetPct / 100f);
            log("   opacity swipe -> " + targetPct + "% (x=" + targetX + ")");
            swipe(op.r - 6, op.cy(), targetX, op.cy(), swipeMs(400));
            pause(250);
            List<Nd> ui = dumpUi();
            Nd val = byId(ui, "sesl_color_seek_bar_opacity_value_edit_view");
            if (val != null) {
                int actual = parseIntSafe(val.text);
                log("   opacity field = '" + val.text + "'");
                if (actual >= 0 && Math.abs(actual - targetPct) <= 1) return;
            } else {
                log("   opacity value field not found");
                return;
            }
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    // =====================================================================
    // Calibration
    // =====================================================================
    private void loadCalibration() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        calibrated = sp.getBoolean("valid", false);
        calXH0 = sp.getFloat("hx0", (float) DEF_XH0);
        calXH1 = sp.getFloat("hx1", (float) DEF_XH1);
        calYS0 = sp.getFloat("sy0", (float) DEF_YS0);
        calYS1 = sp.getFloat("sy1", (float) DEF_YS1);
        if (calibrated) {
            log(String.format(Locale.US,
                    "Calibration loaded: H[%.4f..%.4f] S[%.4f..%.4f] (%s)",
                    calXH0, calXH1, calYS0, calYS1, sp.getString("model", "?")));
        } else {
            log("No calibration yet - using built-in defaults (tap Calibrate).");
        }
    }

    private void saveCalibration(double hx0, double hx1, double sy0, double sy1, String note) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("valid", true)
                .putFloat("hx0", (float) hx0)
                .putFloat("hx1", (float) hx1)
                .putFloat("sy0", (float) sy0)
                .putFloat("sy1", (float) sy1)
                .putString("model", android.os.Build.MODEL)
                .putString("note", note)
                .putLong("time", System.currentTimeMillis())
                .apply();
        calXH0 = hx0;
        calXH1 = hx1;
        calYS0 = sy0;
        calYS1 = sy1;
        calibrated = true;
    }

    // =====================================================================
    // Speed preset
    // =====================================================================
    private void loadSpeed() {
        speed = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getInt("speed", SPEED_NORMAL);
        if (speed < SPEED_SLOW || speed > SPEED_FAST) speed = SPEED_NORMAL;
    }

    private void saveSpeed(int s) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putInt("speed", s).apply();
        log("Speed preset: " + speedName(s));
    }

    private static String speedName(int s) {
        return s == SPEED_SLOW ? "Slow" : s == SPEED_FAST ? "Fast" : "Normal";
    }

    private void updateSpeedLabel() {
        if (speedLabel == null) return;
        speedLabel.setText("Speed: " + speedName(speed));
        speedLabel.setTextColor(speed == SPEED_FAST ? 0xFFFF6D00 : 0xFF808080);
    }

    /**
     * If a Fast run fails, tell the user the preset may be too fast for this
     * device. Interruptions and Shizuku problems are not speed related.
     */
    private void noteSpeedHint(String reason) {
        if (flowSpeed != SPEED_FAST) return;
        if (reason != null) {
            String r = reason.toLowerCase(Locale.US);
            if (r.contains("interrupt") || r.contains("shizuku")
                    || r.contains("permission") || r.contains("animation style")
                    || r.contains("no fill/arrow")) return;
        }
        speedHint = true;
        lastResult = lastResult + " \u2014 fast preset may be too fast; try Normal or Slow";
        log("HINT: Fast preset failed \u2014 this device may be lagging. Reduce speed to Normal or Slow.");
    }

    /** Tap a point in the spectrum square and read H/S back from the brightness bar. */
    private float[] probe(double x, double y, Nd sb) throws Exception {
        tap((int) Math.round(x), (int) Math.round(y));
        pause(300);
        Bitmap shot = screenshot();
        int px = shot.getPixel(Math.max(0, sb.r - 6), sb.cy());
        return rgbToHsv(Color.red(px), Color.green(px), Color.blue(px));
    }

    private void runCalibration() throws Exception {
        if (!waitForShizuku(3000)) throw new Exception("Shizuku is not running");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new Exception("Shizuku permission not granted");

        prepareOverlay();

        try { sh("input keyevent KEYCODE_WAKEUP"); } catch (Exception ignored) {}

        // --- navigate: OHO+ main -> Advanced -> Animation -> Fill color -> picker
        step("Opening OHO+\u2026");
        sh("am start -W -n " + ACT_MAIN + " --activity-clear-top");
        waitForActivity(ACT_MAIN.substring(ACT_MAIN.indexOf('/') + 1), 6000);
        expect("SGPSettingsActivity");
        pause(450);

        List<Nd> ui = dumpUiTop("SGPSettingsActivity");
        Nd adv = byId(ui, "detail_setting");
        if (adv == null) throw new Exception("'Advanced settings' row not found");
        status("Navigating to Advanced settings\u2026");
        tap(adv.cx(), adv.cy());
        waitForActivity(ACT_ADV, 6000);
        expect("SGPMoreSettingActivity");
        pause(400);

        ui = dumpUiTop("SGPMoreSettingActivity");
        Nd anim = byId(ui, "arrow_animation");
        if (anim == null) throw new Exception("'Animation' row not found");
        Nd sw = byId(ui, "main_switch");
        int ax = anim.l + (int) (anim.w() * 0.35);
        if (sw != null && ax > sw.l - 40) ax = sw.l - 80;
        status("Opening Animation settings\u2026");
        tap(ax, anim.cy());
        waitForActivity(ACT_ANIM, 6000);
        expect("SGPAnimationStyleActivity");
        pause(400);

        ui = dumpUiTop("SGPAnimationStyleActivity");
        String style = detectStyle(ui);
        log("Calibration animation style: " + style);
        Nd row = byId(ui, "fill_color");
        if (row == null) {
            String exact = checkedStyleName();
            if (exact != null) style = exact;
            throw new Exception("Calibration needs a color picker, but animation style \""
                    + style + "\" has no Fill/Arrow color; switch to Arrow 3 or Curved line"
                    + " (the app does not change the style)");
        }
        status("Opening color picker\u2026");
        tap(row.cx(), row.cy());
        waitForActivity(ACT_PICK, 6000);
        expect("SGPColorPickerActivity");
        pause(450);

        ui = dumpUi();
        if (byId(ui, "sesl_color_picker_color_spectrum_view_container") == null) {
            Nd tab = byText(ui, "Spectrum");
            if (tab == null) tab = byText(ui, "SPECTRUM");
            if (tab == null) throw new Exception("Spectrum tab not found");
            status("Selecting Spectrum\u2026");
            tap(tab.cx(), tab.cy());
            pause(400);
            ui = dumpUi();
        }

        Nd cont = byId(ui, "sesl_color_picker_color_spectrum_view_container");
        Nd sb = byId(ui, "sesl_color_picker_saturation_seekbar");
        Nd cancel = byId(ui, "button2");
        if (cont == null) throw new Exception("spectrum container not found");
        if (sb == null) throw new Exception("brightness seekbar not found");

        // --- hue probes (high saturation gives an accurate hue readback)
        double yHigh = cont.t + 0.80 * cont.h();
        double xA = cont.l + 0.25 * cont.w();
        double xB = cont.l + 0.75 * cont.w();

        step("Calibrating hue\u2026");
        float[] pA = probe(xA, yHigh, sb);
        float[] pB = probe(xB, yHigh, sb);
        log(String.format(Locale.US, "   x=%.0f -> H=%.2f S=%.3f", xA, pA[0], pA[1]));
        log(String.format(Locale.US, "   x=%.0f -> H=%.2f S=%.3f", xB, pB[0], pB[1]));

        double hA = pA[0], hB = pB[0];
        if (hB < hA) hB += 360;                 // unwrap past 360
        double aH = (hB - hA) / (xB - xA);
        double bH = hA - aH * xA;
        if (!(aH > 0.05)) throw new Exception("bad hue slope: " + aH);
        double xH0 = (0 - bH) / aH;
        double xH1 = (360 - bH) / aH;
        log(String.format(Locale.US, "   H(x)=%.5f*x%+.2f  H0@%.1f  H360@%.1f",
                aH, bH, xH0, xH1));

        // --- saturation probes (hue does not matter for S)
        double xMid = cont.l + 0.50 * cont.w();
        double yA = cont.t + 0.25 * cont.h();
        double yB = cont.t + 0.75 * cont.h();

        step("Calibrating saturation\u2026");
        float[] sA = probe(xMid, yA, sb);
        float[] sB = probe(xMid, yB, sb);
        log(String.format(Locale.US, "   y=%.0f -> S=%.3f", yA, sA[1]));
        log(String.format(Locale.US, "   y=%.0f -> S=%.3f", yB, sB[1]));

        double aS = (sB[1] - sA[1]) / (yB - yA);
        double bS = sA[1] - aS * yA;
        if (!(aS > 0.0001)) throw new Exception("bad saturation slope: " + aS);
        double yS0 = (0 - bS) / aS;
        double yS1 = (1 - bS) / aS;
        log(String.format(Locale.US, "   S(y)=%.6f*y%+.4f  S0@%.1f  S1@%.1f",
                aS, bS, yS0, yS1));

        // --- sanity: the derived range must be near the container
        double marginX = cont.w() * 0.15, marginY = cont.h() * 0.15;
        if (xH0 < cont.l - marginX || xH1 > cont.r + marginX)
            throw new Exception("hue range outside container");
        if (yS0 < cont.t - marginY || yS1 > cont.b + marginY)
            throw new Exception("saturation range outside container");
        if (xH1 - xH0 < cont.w() * 0.3)
            throw new Exception("hue range too small");

        // --- verification at a known point
        step("Verifying calibration\u2026");
        double vx = xH0 + (90.0 / 360.0) * (xH1 - xH0);
        double vy = yS0 + 0.5 * (yS1 - yS0);
        float[] v = probe(vx, vy, sb);
        float dH = 90f - v[0];
        if (dH > 180) dH -= 360;
        if (dH < -180) dH += 360;
        float dS = 0.5f - v[1];
        log(String.format(Locale.US,
                "   target H=90 S=0.5 -> H=%.2f S=%.3f (dH=%.2f dS=%.3f)",
                v[0], v[1], dH, dS));

        // --- discard the temporary colour
        if (cancel != null) {
            tap(cancel.cx(), cancel.cy());
        } else {
            sh("input keyevent 4");
        }
        waitForActivity(ACT_ANIM, 5000);
        expect("SGPAnimationStyleActivity");
        pause(250);

        if (Math.abs(dH) > 10 || Math.abs(dS) > 0.06) {
            log("Calibration FAILED verification - not saved. Keep the screen still and retry.");
            lastResult = "Calibration failed \u2014 retry";
            noteSpeedHint(null);
            status(lastResult);
            return;
        }

        double hx0 = (xH0 - cont.l) / cont.w();
        double hx1 = (xH1 - cont.l) / cont.w();
        double sy0 = (yS0 - cont.t) / cont.h();
        double sy1 = (yS1 - cont.t) / cont.h();
        saveCalibration(hx0, hx1, sy0, sy1,
                String.format(Locale.US, "dH=%.2f dS=%.3f", dH, dS));
        log(String.format(Locale.US,
                "SAVED: H[%.4f..%.4f] S[%.4f..%.4f]", hx0, hx1, sy0, sy1));
        lastResult = "Calibration saved";
        status(lastResult);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int[] sampleSwatch(Bitmap bmp, Nd row) {
        // the swatch circle sits ~59% of the row height in from the left edge
        int x = row.l + Math.max(24, (int) (row.h() * 0.59));
        int y = row.cy();
        int c = bmp.getPixel(x, y);
        int[] px = {Color.red(c), Color.green(c), Color.blue(c)};
        // if we hit the dark background, scan the row for the most saturated pixel
        if (px[0] < 60 && px[1] < 60 && px[2] < 60) {
            int bestScore = -1;
            int[] best = px;
            int xEnd = row.l + Math.max(140, (int) (row.w() * 0.30));
            for (int yy = row.t + 10; yy < row.b - 10; yy += 4) {
                for (int xx = row.l + 10; xx < xEnd; xx += 4) {
                    int cc = bmp.getPixel(xx, yy);
                    int r = Color.red(cc), g = Color.green(cc), b = Color.blue(cc);
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    int score = mx - mn;
                    if (score > bestScore) {
                        bestScore = score;
                        best = new int[]{r, g, b};
                    }
                }
            }
            if (bestScore > 4) px = best;
        }
        return px;
    }

    /**
     * Sample the swatch until the picker's dismiss animation no longer covers it.
     * For alpha < 100% the swatch is composited over the row background, so the
     * expected value is blended with the background sampled from the same shot.
     */
    private int[] verifySwatch(Nd row, int[] target, int alphaPct) throws Exception {
        int[] last = null;
        for (int i = 0; i < 3; i++) {
            Bitmap shot = screenshot();
            last = sampleSwatch(shot, row);
            int diff = swatchDiff(shot, row, last, target, alphaPct);
            lastSwatchDiff = diff;
            log(String.format(Locale.US, "  verify[%d] rgb=(%d,%d,%d) diff=%d",
                    i, last[0], last[1], last[2], diff));
            if (diff <= (alphaPct < 100 ? 6 : 12)) return last;
            pause(300);
        }
        return last;
    }

    private int swatchDiff(Bitmap shot, Nd row, int[] sampled, int[] target, int alphaPct) {
        if (alphaPct >= 100) return maxDiff(sampled, target);
        int[] bg = sampleRowBackground(shot, row);
        int alpha = Math.round(alphaPct * 2.55f);
        int[] expected = composite(target, alpha, bg);
        return maxDiff(sampled, expected);
    }

    private int[] sampleRowBackground(Bitmap bmp, Nd row) {
        // the top edge of the row is the card background (the title/swatch sit lower)
        int x = row.l + row.w() / 2;
        int y = row.t + Math.max(4, row.h() / 12);
        int c = bmp.getPixel(x, y);
        return new int[]{Color.red(c), Color.green(c), Color.blue(c)};
    }

    private static int[] composite(int[] src, int alpha, int[] bg) {
        int a = Math.max(0, Math.min(255, alpha));
        return new int[]{
                (src[0] * a + bg[0] * (255 - a) + 127) / 255,
                (src[1] * a + bg[1] * (255 - a) + 127) / 255,
                (src[2] * a + bg[2] * (255 - a) + 127) / 255
        };
    }

    private static int maxDiff(int[] a, int[] b) {
        return Math.max(Math.abs(a[0] - b[0]),
                Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
    }

    /**
     * Arrow 3 keeps its colour preview stale after the picker closes. Go back to
     * Advanced and re-enter Animation to force a refresh, then return the new row.
     */
    private Nd refreshAnimationScreen(boolean fill) throws Exception {
        log("  refreshing Animation preview\u2026");
        // Let the picker-close transition settle, then go back with the
        // action-bar arrow (a global Back can be swallowed mid-transition).
        pause(400);
        boolean reached = false;
        for (int attempt = 0; attempt < 3 && !reached; attempt++) {
            if (attempt == 0) {
                List<Nd> cur = dumpUiTop("SGPAnimationStyleActivity");
                Nd back = byId(cur, "action_bar_back");
                if (back != null) tap(back.cx(), back.cy());
                else sh("input keyevent KEYCODE_BACK");
            } else {
                sh("input keyevent KEYCODE_BACK");
            }
            reached = waitForActivity("SGPMoreSettingActivity", 4000);
            if (!reached) pause(400);
        }
        if (!reached)
            throw new Exception("could not return to Advanced settings to refresh the preview");
        expect("SGPMoreSettingActivity");
        pause(300);
        List<Nd> ui = dumpUiTop("SGPMoreSettingActivity");
        Nd anim = byId(ui, "arrow_animation");
        if (anim == null) throw new Exception("'Animation' row gone on refresh");
        Nd sw = byId(ui, "main_switch");
        int ax = anim.l + (int) (anim.w() * 0.35);
        if (sw != null && ax > sw.l - 40) ax = sw.l - 80;
        tap(ax, anim.cy());
        if (!waitForActivity("SGPAnimationStyleActivity", 6000))
            log("  warning: animation not focused on refresh");
        expect("SGPAnimationStyleActivity");
        pause(400);
        List<Nd> ui2 = dumpUiTop("SGPAnimationStyleActivity");
        Nd row = byId(ui2, fill ? "fill_color" : "arrow_color");
        if (row == null) throw new Exception("colour row gone on refresh");
        return row;
    }

    // =====================================================================
    /** Show a short message over whatever app is on screen (also logged). */
    private void toast(String msg) {
        ui.post(() -> {
            try {
                if (currentToast != null) currentToast.cancel();
                currentToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
                currentToast.show();
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Ask Shizuku to allow the overlay permission, then check it. Called on the
     * worker thread before a flow starts. Toasts from a background app are
     * suppressed by Android, so the live progress is drawn as an overlay window.
     */
    private void prepareOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) {
                sh("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                pause(250);
            }
        } catch (Throwable t) {
            log("overlay grant failed: " + t);
        }
        overlayAllowed = Settings.canDrawOverlays(this);
        log("overlay allowed: " + overlayAllowed);
    }

    /** Update the floating progress pill; falls back to a toast if not allowed. */
    private void showOverlay(String msg) {
        if (!overlayAllowed) {
            toast(msg);
            return;
        }
        ui.post(() -> {
            try {
                if (overlayView == null) {
                    TextView tv = new TextView(this);
                    tv.setTextSize(15);
                    tv.setTextColor(Color.WHITE);
                    tv.setPadding(48, 20, 48, 20);
                    tv.setGravity(Gravity.CENTER);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xE6000000);
                    bg.setCornerRadius(60);
                    bg.setStroke(2, 0x66FFFFFF);
                    tv.setBackground(bg);

                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.y = 90;
                    wm.addView(tv, lp);
                    overlayView = tv;
                    overlayWm = wm;
                }
                overlayView.setText(msg);
                overlayView.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                log("overlay error: " + t);
                toast(msg);
            }
        });
    }

    private void hideOverlay() {
        ui.post(() -> {
            try {
                if (overlayView != null && overlayWm != null) overlayWm.removeView(overlayView);
            } catch (Throwable ignored) {
            }
            overlayView = null;
            overlayWm = null;
        });
    }

    /** A user-facing step: written to the log and shown on the overlay. */
    private void step(String msg) {
        log(msg);
        showOverlay(msg);
    }

    /** Transient status on the overlay, without a log line. */
    private void status(String msg) {
        showOverlay(msg);
    }

    /** Show the final result briefly, then remove the overlay. */
    private void finishOverlay(String msg) {
        showOverlay(msg);
        long ms = speedHint ? 5000 : 1800;
        speedHint = false;
        ui.postDelayed(this::hideOverlay, ms);
    }

    /** Bring this app back to the foreground after driving another app. */
    private void focusApp() {
        try {
            sh("am start -n " + getPackageName() + "/.MainActivity --activity-reorder-to-front");
            log("focused app");
        } catch (Throwable t) {
            log("could not focus app: " + t);
            ui.post(() -> {
                try {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t2) {
                    log("focus fallback failed: " + t2);
                }
            });
        }
    }

    private void log(String s) {
        ui.post(() -> {
            logView.append(s + "\n");
            if (logView.getParent() instanceof ScrollView) {
                ((ScrollView) logView.getParent()).post(() -> {
                    ScrollView sv = (ScrollView) logView.getParent();
                    if (sv != null) sv.fullScroll(ScrollView.FOCUS_DOWN);
                });
            }
        });
        android.util.Log.i("OhoColorSetter", s);
    }
}
