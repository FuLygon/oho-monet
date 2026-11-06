package com.example.ohocolorsetter;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private double calXH0 = DEF_XH0, calXH1 = DEF_XH1;
    private double calYS0 = DEF_YS0, calYS1 = DEF_YS1;
    private boolean calibrated = false;

    private EditText hexInput;
    private RadioGroup targetGroup;
    private TextView logView;
    private Button applyBtn;
    private Button calibBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    // =====================================================================
    // UI
    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("OHO+ Color Setter");
        title.setTextSize(20);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Hex color (#RRGGBB)");
        hint.setPadding(0, pad / 2, 0, 0);
        root.addView(hint);

        hexInput = new EditText(this);
        hexInput.setHint("#473646");
        hexInput.setText("#473646");
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(hexInput);

        targetGroup = new RadioGroup(this);
        targetGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton fill = new RadioButton(this);
        fill.setText("Fill color");
        fill.setId(1001);
        RadioButton arrow = new RadioButton(this);
        arrow.setText("Arrow color");
        arrow.setId(1002);
        targetGroup.addView(fill);
        targetGroup.addView(arrow);
        targetGroup.check(1001);
        root.addView(targetGroup);

        applyBtn = new Button(this);
        applyBtn.setText("Apply");
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
            Shizuku.addBinderReceivedListenerSticky(() -> log("Shizuku binder received"));
            Shizuku.addRequestPermissionResultListener((code, res) ->
                    log("Permission result: " + (res == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED(" + res + ")")));
        } catch (Throwable t) {
            log("Shizuku listener error: " + t);
        }

        log("Ready. Shizuku running: " + safePing());
        loadCalibration();

        applyIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    private void applyIntent(Intent it) {
        if (it == null) return;
        String hx = it.getStringExtra("hex");
        if (hx != null && !hx.isEmpty()) {
            if (!hx.startsWith("#")) hx = "#" + hx;
            hexInput.setText(hx);
        }
        String tg = it.getStringExtra("target");
        if ("arrow".equalsIgnoreCase(tg)) targetGroup.check(1002);
        else if ("fill".equalsIgnoreCase(tg)) targetGroup.check(1001);

        if (it.getBooleanExtra("calibrate", false)) {
            hexInput.postDelayed(this::startCalibration, 900);
        } else if (it.getBooleanExtra("auto", false)) {
            hexInput.postDelayed(this::startApply, 900);
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

    private void startApply() {
        if (busy) return;
        String hex = hexInput.getText().toString().trim();
        if (!hex.startsWith("#")) hex = "#" + hex;
        if (!hex.matches("#[0-9a-fA-F]{6}")) {
            log("Invalid hex: " + hex);
            return;
        }
        final String h = hex;
        final boolean fill = targetGroup.getCheckedRadioButtonId() == 1001;

        busy = true;
        applyBtn.setEnabled(false);
        calibBtn.setEnabled(false);
        log("=== Apply " + h + " to " + (fill ? "Fill" : "Arrow") + " ===");

        new Thread(() -> {
            try {
                runFlow(h, fill);
            } catch (Throwable t) {
                log("ERROR: " + t);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) log("  at " + st[i]);
            } finally {
                busy = false;
                ui.post(() -> {
                    applyBtn.setEnabled(true);
                    calibBtn.setEnabled(true);
                });
                log("=== done ===");
            }
        }).start();
    }

    private void startCalibration() {
        if (busy) return;
        busy = true;
        applyBtn.setEnabled(false);
        calibBtn.setEnabled(false);
        log("=== Calibration (this device) ===");

        new Thread(() -> {
            try {
                runCalibration();
            } catch (Throwable t) {
                log("CALIBRATION ERROR: " + t);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) log("  at " + st[i]);
            } finally {
                busy = false;
                ui.post(() -> {
                    applyBtn.setEnabled(true);
                    calibBtn.setEnabled(true);
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

    private void tap(int x, int y) throws Exception {
        log("  tap " + x + "," + y);
        sh("input tap " + x + " " + y);
    }

    private void swipe(int x1, int y1, int x2, int y2, int ms) throws Exception {
        log("  swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2);
        sh("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private boolean waitForActivity(String cls, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String s = sh("dumpsys window | grep mCurrentFocus");
            if (s.contains(cls)) return true;
            sleep(200);
        }
        return false;
    }

    // =====================================================================
    // UI dump / node model
    // =====================================================================
    static class Nd {
        String cls = "", id = "", text = "", desc = "";
        int l, t, r, b;
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
        for (int attempt = 0; attempt < 3; attempt++) {
            String s = sh("uiautomator dump /data/local/tmp/oho_ui.xml >/dev/null 2>&1; cat /data/local/tmp/oho_ui.xml");
            int i = s.indexOf("<?xml");
            if (i >= 0) {
                try {
                    return parseXml(s.substring(i));
                } catch (Exception e) {
                    log("  dump parse failed: " + e.getMessage());
                }
            }
            sleep(500);
        }
        throw new Exception("uiautomator dump failed");
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

    static int[] parseHex(String hex) {
        int c = Color.parseColor(hex);
        return new int[]{Color.red(c), Color.green(c), Color.blue(c)};
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

    private void runFlow(String hex, boolean fill) throws Exception {
        if (!waitForShizuku(3000)) throw new Exception("Shizuku is not running");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new Exception("Shizuku permission not granted");

        int[] rgb = parseHex(hex);
        float[] hsv = rgbToHsv(rgb[0], rgb[1], rgb[2]);
        int vpct = pickVpct(rgb[0], rgb[1], rgb[2]);
        float H = hsv[0], S = hsv[1];
        log(String.format(Locale.US, "target rgb=(%d,%d,%d) H=%.1f S=%.3f Vpct=%d",
                rgb[0], rgb[1], rgb[2], H, S, vpct));

        // wake the screen if it is off (harmless if already on)
        try { sh("input keyevent KEYCODE_WAKEUP"); } catch (Exception ignored) {}

        // warn if locked
        String lock = sh("dumpsys window | grep mDreamingLockscreen");
        if (lock.contains("true")) log("WARNING: screen appears locked");

        // 1. launch OHO+ main
        log("1) launch OHO+");
        sh("am start -W -n " + ACT_MAIN + " --activity-clear-top");
        if (!waitForActivity(ACT_MAIN.substring(ACT_MAIN.indexOf('/') + 1), 6000))
            log("  warning: main activity not focused yet");
        sleep(700);

        // 2. Advanced settings
        log("2) Advanced settings");
        List<Nd> ui = dumpUi();
        Nd adv = byId(ui, "detail_setting");
        if (adv == null) throw new Exception("'Advanced settings' row not found");
        tap(adv.cx(), adv.cy());
        if (!waitForActivity(ACT_ADV, 6000)) log("  warning: advanced activity not focused");
        sleep(600);

        // 3. Animation row (tap title, left of the switch)
        log("3) Animation");
        ui = dumpUi();
        Nd anim = byId(ui, "arrow_animation");
        if (anim == null) throw new Exception("'Animation' row not found");
        Nd sw = byId(ui, "main_switch");
        int ax = anim.l + (int) (anim.w() * 0.35);
        if (sw != null && ax > sw.l - 40) ax = sw.l - 80;
        tap(ax, anim.cy());
        if (!waitForActivity(ACT_ANIM, 6000)) log("  warning: animation activity not focused");
        sleep(600);

        // 4. Fill / Arrow colour row
        log("4) " + (fill ? "Fill" : "Arrow") + " color row");
        ui = dumpUi();
        Nd row = byId(ui, fill ? "fill_color" : "arrow_color");
        if (row == null) throw new Exception("colour row not found");
        Nd swatchRow = row;
        tap(row.cx(), row.cy());
        if (!waitForActivity(ACT_PICK, 6000)) log("  warning: picker not focused");
        sleep(700);

        double corrX = 0, corrY = 0;
        int[] got = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            log("-- attempt " + attempt + " --");

            // 5. Spectrum tab
            ui = dumpUi();
            if (byId(ui, "sesl_color_picker_color_spectrum_view_container") == null) {
                Nd tab = byText(ui, "Spectrum");
                if (tab == null) tab = byText(ui, "SPECTRUM");
                if (tab != null) {
                    log("5) switch to Spectrum tab");
                    tap(tab.cx(), tab.cy());
                    sleep(600);
                    ui = dumpUi();
                }
            }

            Nd cont = byId(ui, "sesl_color_picker_color_spectrum_view_container");
            Nd sb = byId(ui, "sesl_color_picker_saturation_seekbar");
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

            // 6. iterate on hue / saturation using the brightness bar readback
            for (int it = 0; it < 4; it++) {
                x = clamp(x, cont.l + 2, cont.r - 2);
                y = clamp(y, cont.t + 2, cont.b - 2);
                tap((int) Math.round(x), (int) Math.round(y));
                sleep(450);

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

            // 7. brightness (drag, must be after the square tap)
            int cy = sb.cy();
            int targetX = sb.l + Math.round((sb.r - sb.l) * (vpct / 100f));
            log("7) brightness " + vpct + "% -> x=" + targetX);
            swipe(sb.r - 6, cy, targetX, cy, 450);
            sleep(400);

            // verify the value field
            List<Nd> check = dumpUi();
            Nd field = byId(check, "sesl_color_seek_bar_saturation_value_edit_view");
            if (field != null) log("   brightness field = '" + field.text + "'");
            Nd done2 = byId(check, "button1");
            if (done2 != null) done = done2;

            // 8. Done
            log("8) Done");
            tap(done.cx(), done.cy());
            if (!waitForActivity(ACT_ANIM, 6000)) log("  warning: did not return to animation screen");
            sleep(700);

            // 9. verify swatch
            Bitmap shot = screenshot();
            got = sampleSwatch(shot, swatchRow);
            log(String.format(Locale.US, "9) swatch rgb=(%d,%d,%d) target=(%d,%d,%d)",
                    got[0], got[1], got[2], rgb[0], rgb[1], rgb[2]));

            int diff = Math.max(Math.abs(got[0] - rgb[0]),
                    Math.max(Math.abs(got[1] - rgb[1]), Math.abs(got[2] - rgb[2])));
            if (diff <= 1) {
                log("SUCCESS (max diff " + diff + ", picker limit)");
                return;
            }
            log("  mismatch (max diff " + diff + "), retrying");

            // correct using achieved vs target hue/saturation
            float[] gotHsv = rgbToHsv(got[0], got[1], got[2]);
            float dH = H - gotHsv[0];
            if (dH > 180) dH -= 360;
            if (dH < -180) dH += 360;
            float dS = S - gotHsv[1];
            corrX += dH / (360.0 / (xH1 - xH0));
            corrY += dS * (yS1 - yS0);

            // reopen the picker for the next attempt
            ui = dumpUi();
            Nd row2 = byId(ui, fill ? "fill_color" : "arrow_color");
            if (row2 == null) throw new Exception("colour row gone, cannot retry");
            swatchRow = row2;
            tap(row2.cx(), row2.cy());
            if (!waitForActivity(ACT_PICK, 6000)) log("  warning: picker not focused (retry)");
            sleep(700);
        }

        throw new Exception("colour did not converge (last rgb=" +
                (got == null ? "null" : got[0] + "," + got[1] + "," + got[2]) + ")");
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

    /** Tap a point in the spectrum square and read H/S back from the brightness bar. */
    private float[] probe(double x, double y, Nd sb) throws Exception {
        tap((int) Math.round(x), (int) Math.round(y));
        sleep(450);
        Bitmap shot = screenshot();
        int px = shot.getPixel(Math.max(0, sb.r - 6), sb.cy());
        return rgbToHsv(Color.red(px), Color.green(px), Color.blue(px));
    }

    private void runCalibration() throws Exception {
        if (!waitForShizuku(3000)) throw new Exception("Shizuku is not running");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new Exception("Shizuku permission not granted");

        try { sh("input keyevent KEYCODE_WAKEUP"); } catch (Exception ignored) {}

        // --- navigate: OHO+ main -> Advanced -> Animation -> Fill color -> picker
        log("1) navigate to picker");
        sh("am start -W -n " + ACT_MAIN + " --activity-clear-top");
        waitForActivity(ACT_MAIN.substring(ACT_MAIN.indexOf('/') + 1), 6000);
        sleep(700);

        List<Nd> ui = dumpUi();
        Nd adv = byId(ui, "detail_setting");
        if (adv == null) throw new Exception("'Advanced settings' row not found");
        tap(adv.cx(), adv.cy());
        waitForActivity(ACT_ADV, 6000);
        sleep(600);

        ui = dumpUi();
        Nd anim = byId(ui, "arrow_animation");
        if (anim == null) throw new Exception("'Animation' row not found");
        Nd sw = byId(ui, "main_switch");
        int ax = anim.l + (int) (anim.w() * 0.35);
        if (sw != null && ax > sw.l - 40) ax = sw.l - 80;
        tap(ax, anim.cy());
        waitForActivity(ACT_ANIM, 6000);
        sleep(600);

        ui = dumpUi();
        Nd row = byId(ui, "fill_color");
        if (row == null) throw new Exception("'Fill color' row not found");
        tap(row.cx(), row.cy());
        waitForActivity(ACT_PICK, 6000);
        sleep(700);

        ui = dumpUi();
        if (byId(ui, "sesl_color_picker_color_spectrum_view_container") == null) {
            Nd tab = byText(ui, "Spectrum");
            if (tab == null) tab = byText(ui, "SPECTRUM");
            if (tab == null) throw new Exception("Spectrum tab not found");
            tap(tab.cx(), tab.cy());
            sleep(600);
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

        log("2) hue probes");
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

        log("3) saturation probes");
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
        log("4) verification");
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
        sleep(400);

        if (Math.abs(dH) > 10 || Math.abs(dS) > 0.06) {
            log("Calibration FAILED verification - not saved. Keep the screen still and retry.");
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

    // =====================================================================
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
