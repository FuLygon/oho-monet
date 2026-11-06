package com.fgn.ohomonet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private TextView applyBtn;
    private TextView calibBtn;
    private Toast currentToast;
    private TextView overlayView;
    private WindowManager overlayWm;
    private volatile boolean overlayAllowed = false;
    private volatile String lastResult = "Done";
    private volatile String expectedFocus = null;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private SeekBar speedBar;
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

    // ---- theme / screens ----
    private boolean darkMode = true;
    private Palette pal = Palette.fallback(true);
    private Palette paletteDark, paletteLight;
    private boolean themeLoaded = false;
    private boolean gateShowing = false;
    private boolean mainShowing = false;
    private boolean loadingShowing = false;
    private String lastGateKey = "";
    private int selectedTab = 0;
    private final StringBuilder logBuffer = new StringBuilder();
    private TextView tabFill, tabArrow;
    private FrameLayout tabContent;
    private ThemeToggleView themeToggleView;
    private Intent pendingIntent;

    static class Palette {
        final boolean dark;
        int primary, onPrimary, primaryContainer, onPrimaryContainer;
        int secondaryContainer, onSecondaryContainer;
        int surface, onSurface, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest;
        int surfaceVariant, onSurfaceVariant;
        int outline, outlineVariant;
        int error, onError;

        Palette(boolean dark) { this.dark = dark; }

        static Palette fallback(boolean dark) {
            Palette p = new Palette(dark);
            if (dark) {
                p.primary = 0xFFA8C7FA;
                p.onPrimary = 0xFF00315F;
                p.primaryContainer = 0xFF0842A0;
                p.onPrimaryContainer = 0xFFD3E3FD;
                p.secondaryContainer = 0xFF3B4866;
                p.onSecondaryContainer = 0xFFD7E3FF;
                p.surface = 0xFF111318;
                p.onSurface = 0xFFE2E2E9;
                p.surfaceContainer = 0xFF1D2024;
                p.surfaceContainerHigh = 0xFF282A2F;
                p.surfaceContainerHighest = 0xFF33353A;
                p.surfaceVariant = 0xFF44474E;
                p.onSurfaceVariant = 0xFFC4C6D0;
                p.outline = 0xFF8E9099;
                p.outlineVariant = 0xFF44474E;
                p.error = 0xFFFFB4AB;
                p.onError = 0xFF690005;
            } else {
                p.primary = 0xFF0B57D0;
                p.onPrimary = 0xFFFFFFFF;
                p.primaryContainer = 0xFFD3E3FD;
                p.onPrimaryContainer = 0xFF041E49;
                p.secondaryContainer = 0xFFD7E3FF;
                p.onSecondaryContainer = 0xFF001B3F;
                p.surface = 0xFFF8F9FF;
                p.onSurface = 0xFF1A1C1E;
                p.surfaceContainer = 0xFFEEF0F6;
                p.surfaceContainerHigh = 0xFFE3E5EB;
                p.surfaceContainerHighest = 0xFFD8DAE0;
                p.surfaceVariant = 0xFFE1E2EC;
                p.onSurfaceVariant = 0xFF44474E;
                p.outline = 0xFF74777F;
                p.outlineVariant = 0xFFC4C6D0;
                p.error = 0xFFBA1A1A;
                p.onError = 0xFFFFFFFF;
            }
            return p;
        }
    }

    static class ColorSection {
        String key, label;
        RadioGroup modeGroup;
        RadioButton materialRadio, customRadio;
        EditText input;
        ColorPreviewView preview;
        View card;
        int materialId, customId;
        volatile boolean materialMode = true;
        int customColor = 0xFF000000;
        int materialColor = 0;
        boolean materialOk = false;
        boolean inputError = false;
        boolean suppressFocusCommit = false;
    }

    static int dpStatic(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** Circular colour preview with a checkerboard behind it for alpha. */
    static class ColorPreviewView extends View {
        int color = 0xFF000000;
        int outlineColor = 0xFF777777;
        int checkA = 0xFFDDDDDD, checkB = 0xFFBBBBBB;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path();

        ColorPreviewView(Context c) { super(c); }

        void setColor(int c) { color = c; invalidate(); }

        void setPalette(Palette p) {
            outlineColor = p.outlineVariant;
            checkA = blend(p.surface, p.onSurface, 0.06f);
            checkB = blend(p.surface, p.onSurface, 0.12f);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - dpStatic(getContext(), 1);
            clip.reset();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            int cell = Math.max(4, dpStatic(getContext(), 8));
            for (int y = 0; y < h; y += cell) {
                for (int x = 0; x < w; x += cell) {
                    paint.setColor((((x / cell) + (y / cell)) & 1) == 0 ? checkA : checkB);
                    canvas.drawRect(x, y, x + cell, y + cell, paint);
                }
            }
            paint.setColor(color);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dpStatic(getContext(), 2));
            paint.setColor(outlineColor);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** Circular dark/light toggle. Moon in dark mode, sun in light mode. */
    static class ThemeToggleView extends View {
        boolean dark = true;
        int iconColor = 0xFFFFFFFF, bgColor = 0xFF6750A4;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ThemeToggleView(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
        }

        void setPalette(Palette p, boolean dark) {
            this.dark = dark;
            iconColor = p.onPrimary;
            bgColor = p.primary;
            GradientDrawable content = new GradientDrawable();
            content.setShape(GradientDrawable.OVAL);
            content.setColor(bgColor);
            setBackground(new RippleDrawable(
                    ColorStateList.valueOf(withAlpha(p.onPrimary, 0.18f)), content, null));
            setContentDescription(dark ? "Switch to light mode" : "Switch to dark mode");
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
            paint.setColor(iconColor);
            paint.setStyle(Paint.Style.FILL);
            if (dark) {
                Path moon = new Path();
                moon.addCircle(cx, cy, r * 0.42f, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(cx + r * 0.22f, cy - r * 0.16f, r * 0.40f, Path.Direction.CW);
                moon.op(cut, Path.Op.DIFFERENCE);
                canvas.drawPath(moon, paint);
            } else {
                canvas.drawCircle(cx, cy, r * 0.28f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(r * 0.09f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float x1 = cx + (float) (Math.cos(a) * r * 0.45f);
                    float y1 = cy + (float) (Math.sin(a) * r * 0.45f);
                    float x2 = cx + (float) (Math.cos(a) * r * 0.62f);
                    float y2 = cy + (float) (Math.sin(a) * r * 0.62f);
                    canvas.drawLine(x1, y1, x2, y2, paint);
                }
            }
        }
    }

    /** Lock glyph used by the Shizuku gate. */
    static class LockIconView extends View {
        int bg, fg;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LockIconView(Context c) { super(c); }

        void setPalette(Palette p) {
            bg = p.primaryContainer;
            fg = p.onPrimaryContainer;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bg);
            canvas.drawCircle(cx, cy, r, paint);
            float s = r * 0.52f;
            paint.setColor(fg);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.13f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            RectF shackle = new RectF(cx - s * 0.72f, cy - s * 0.95f,
                    cx + s * 0.72f, cy + s * 0.25f);
            canvas.drawArc(shackle, 180, 180, false, paint);
            paint.setStyle(Paint.Style.FILL);
            RectF body = new RectF(cx - s, cy - s * 0.1f, cx + s, cy + s * 0.85f);
            canvas.drawRoundRect(body, s * 0.28f, s * 0.28f, paint);
            paint.setColor(bg);
            canvas.drawCircle(cx, cy + s * 0.28f, s * 0.18f, paint);
            RectF key = new RectF(cx - s * 0.09f, cy + s * 0.32f,
                    cx + s * 0.09f, cy + s * 0.62f);
            canvas.drawRoundRect(key, s * 0.08f, s * 0.08f, paint);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private ColorSection addColorSection(FrameLayout parent, String key, String title,
                                         int materialId, int customId, String defaultCustom) {
        ColorSection s = new ColorSection();
        s.key = key;
        s.label = title;
        s.materialId = materialId;
        s.customId = customId;
        s.materialColor = "fill".equals(key) ? materialFill : materialArrow;
        s.materialOk = "fill".equals(key) ? materialFillOk : materialArrowOk;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(pal.surfaceContainer, 28));
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.card = card;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        s.modeGroup = new RadioGroup(this);
        s.modeGroup.setOrientation(RadioGroup.VERTICAL);
        s.materialRadio = makeRadio("Material You", materialId);
        s.customRadio = makeRadio("Custom", customId);
        s.modeGroup.addView(s.materialRadio);
        s.modeGroup.addView(s.customRadio);
        left.addView(s.modeGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        s.input = new EditText(this);
        s.input.setHint("#RRGGBB");
        s.input.setSingleLine(true);
        s.input.setTextSize(14);
        s.input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        s.input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        s.input.setPadding(dp(14), 0, dp(14), 0);
        String initial = loadCustom(key, defaultCustom);
        s.input.setText(initial);
        Integer initialColor = parseColor(initial);
        s.customColor = initialColor != null ? initialColor : 0xFF000000;
        s.inputError = initialColor == null;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        ilp.leftMargin = dp(36);
        ilp.topMargin = dp(6);
        left.addView(s.input, ilp);

        top.addView(left);

        s.preview = new ColorPreviewView(this);
        s.preview.setPalette(pal);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(72), dp(72));
        plp.leftMargin = dp(16);
        top.addView(s.preview, plp);

        card.addView(top);
        parent.addView(card);

        boolean matMode = loadMode(key, true);
        s.materialMode = matMode;
        s.modeGroup.check(matMode ? materialId : customId);
        styleInput(s);
        updateSectionPreview(s);

        s.input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                s.suppressFocusCommit = true;
                try {
                    commitCustom(s);
                    s.input.clearFocus();
                } finally {
                    s.suppressFocusCommit = false;
                }
                hideKeyboard(s.input);
                return true;
            }
            return false;
        });
        s.input.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !s.suppressFocusCommit) commitCustom(s);
        });
        s.input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
            public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                saveCustom(key, e.toString());
                s.inputError = parseColor(e.toString()) == null;
                styleInput(s);
                updateApplyEnabled();
            }
        });
        s.modeGroup.setOnCheckedChangeListener((g, id) -> {
            s.materialMode = id == materialId;
            saveMode(key, s.materialMode);
            styleInput(s);
            updateSectionPreview(s);
            updateApplyEnabled();
        });
        return s;
    }

    private RadioButton makeRadio(String text, int id) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(text);
        r.setTextSize(16);
        r.setTextColor(pal.onSurface);
        r.setButtonTintList(new ColorStateList(
                new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} },
                new int[]{ pal.primary, pal.outline }));
        r.setMinHeight(0);
        r.setMinWidth(0);
        r.setPadding(0, 0, 0, 0);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return r;
    }

    private void styleInput(ColorSection s) {
        if (s.input == null) return;
        boolean enabled = !s.materialMode;
        s.input.setEnabled(enabled);
        int stroke = s.inputError ? pal.error : pal.outline;
        float strokeDp = s.inputError ? 1.5f : 1f;
        int fill = enabled ? pal.surfaceContainerHighest
                : blend(pal.surface, pal.onSurface, 0.04f);
        s.input.setBackground(outlined(fill, stroke, 14, strokeDp));
        s.input.setTextColor(enabled ? pal.onSurface : withAlpha(pal.onSurface, 0.38f));
        s.input.setHintTextColor(withAlpha(pal.onSurfaceVariant, enabled ? 0.9f : 0.38f));
    }

    private void commitCustom(ColorSection s) {
        if (s.input == null) return;
        String raw = s.input.getText().toString().trim();
        Integer c = parseColor(raw);
        if (c != null) {
            s.customColor = c;
            s.inputError = false;
            if (!s.materialMode) updateSectionPreview(s);
            log("Custom " + s.label + " color: " + colorToHex(c));
        } else {
            s.inputError = true;
            log("Invalid custom " + s.label + " color: \"" + raw + "\"");
        }
        styleInput(s);
        updateApplyEnabled();
    }

    private void updateSectionPreview(ColorSection s) {
        if (s == null || s.preview == null) return;
        s.preview.setColor(s.materialMode ? s.materialColor : s.customColor);
        s.preview.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110)
                .withEndAction(() -> s.preview.animate().scaleX(1f).scaleY(1f)
                        .setDuration(110).start()).start();
    }

    private void updateAllPreviews() {
        updateSectionPreview(fillSection);
        updateSectionPreview(arrowSection);
    }

    private void updateApplyEnabled() {
        if (applyBtn == null) return;
        boolean ok = true;
        for (ColorSection s : new ColorSection[]{fillSection, arrowSection}) {
            if (s == null) continue;
            if (s.materialMode) {
                if (!s.materialOk) ok = false;
            } else if (parseColor(s.input.getText().toString()) == null) {
                ok = false;
            }
        }
        boolean enabled = ok && !busy;
        applyBtn.setEnabled(enabled);
        applyBtn.setAlpha(enabled ? 1f : 0.5f);
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable outlined(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable d = rounded(fill, radiusDp);
        d.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return d;
    }

    private Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private TextView label(String text, float sizeSp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    private void addSpace(LinearLayout parent, float dp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private TextView pillButton(String text, boolean filled, Palette p, Runnable onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(24), 0, dp(24), 0);
        if (filled) {
            b.setTextColor(p.onPrimary);
            b.setBackground(ripple(rounded(p.primary, 28),
                    withAlpha(p.onPrimary, 0.18f)));
        } else {
            b.setTextColor(p.primary);
            b.setBackground(ripple(outlined(p.surfaceContainer, p.outline, 28, 1),
                    withAlpha(p.primary, 0.12f)));
        }
        b.setOnClickListener(v -> onClick.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return b;
    }

    private TextView tabButton(String text, int index) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        if (index == 1) lp.leftMargin = dp(12);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> selectTab(index));
        return t;
    }

    private void styleTab(TextView t, boolean selected) {
        if (t == null) return;
        if (selected) {
            t.setTextColor(pal.onPrimary);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setBackground(ripple(rounded(pal.primary, 26),
                    withAlpha(pal.onPrimary, 0.18f)));
        } else {
            t.setTextColor(pal.onSurfaceVariant);
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            t.setBackground(ripple(rounded(pal.surfaceContainerHigh, 26),
                    withAlpha(pal.onSurface, 0.10f)));
        }
    }

    private void selectTab(int index) {
        selectedTab = index;
        styleTab(tabFill, index == 0);
        styleTab(tabArrow, index == 1);
        if (fillSection != null && fillSection.card != null) {
            fillSection.card.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        }
        if (arrowSection != null && arrowSection.card != null) {
            arrowSection.card.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void tintSeekBar(SeekBar bar, Palette p) {
        bar.setProgressTintList(ColorStateList.valueOf(p.primary));
        bar.setThumbTintList(ColorStateList.valueOf(p.primary));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(
                blend(p.surface, p.onSurface, 0.12f)));
        bar.setSplitTrack(false);
    }

    private void loadDarkMode() {
        darkMode = getSharedPreferences(SETTINGS, MODE_PRIVATE)
                .getBoolean("dark_mode", true);
    }

    private void saveDarkMode(boolean dark) {
        getSharedPreferences(SETTINGS, MODE_PRIVATE).edit()
                .putBoolean("dark_mode", dark).apply();
    }

    private void toggleTheme() {
        if (busy) return;
        darkMode = !darkMode;
        saveDarkMode(darkMode);
        log("Theme: " + (darkMode ? "dark" : "light"));
        if (mainShowing && themeLoaded) {
            pal = darkMode ? paletteDark : paletteLight;
            if (pal == null) pal = Palette.fallback(darkMode);
            buildMainUi();
        }
    }

    private void applyPaletteToWindow() {
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(pal.surface));
            getWindow().setStatusBarColor(pal.surface);
            getWindow().setNavigationBarColor(pal.surface);
        } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(pal.dark ? 0 : mask, mask);
                }
            } else {
                int flags = getWindow().getDecorView().getSystemUiVisibility();
                if (pal.dark) {
                    flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                } else {
                    flags |= (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {}
    }

    private void setUiBusy(boolean b) {
        ui.post(() -> {
            if (applyBtn != null) {
                applyBtn.setEnabled(!b);
                applyBtn.setAlpha(b ? 0.5f : 1f);
            }
            if (calibBtn != null) {
                calibBtn.setEnabled(!b);
                calibBtn.setAlpha(b ? 0.5f : 1f);
            }
            if (speedBar != null) speedBar.setEnabled(!b);
            if (themeToggleView != null) themeToggleView.setEnabled(!b);
            if (!b) updateApplyEnabled();
        });
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

    /** Loads both Material You palettes and the two colours we apply. */
    private void fetchThemeData() {
        if (materialFetching) return;
        materialFetching = true;
        new Thread(() -> {
            Palette dark = null, light = null;
            boolean fillOk = false, arrowOk = false;
            int fill = 0, arrow = 0;
            try {
                if (!waitForShizuku(6000)) {
                    log("Theme: Shizuku not running");
                } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    log("Theme: Shizuku permission not granted");
                } else {
                    String[] roles = {
                            "primary", "on_primary", "primary_container", "on_primary_container",
                            "secondary_container", "on_secondary_container",
                            "surface", "on_surface", "surface_container", "surface_container_high",
                            "surface_container_highest", "surface_variant", "on_surface_variant",
                            "outline", "outline_variant", "error", "on_error"
                    };
                    StringBuilder cmd = new StringBuilder();
                    for (String r : roles) {
                        cmd.append("echo D_").append(r)
                                .append("=$(cmd overlay lookup --user 0 android android:color/system_")
                                .append(r).append("_dark)\n");
                        cmd.append("echo L_").append(r)
                                .append("=$(cmd overlay lookup --user 0 android android:color/system_")
                                .append(r).append("_light)\n");
                    }
                    cmd.append("echo D_FILL=$(cmd overlay lookup --user 0 android android:color/system_secondary_container_dark)\n");
                    cmd.append("echo D_ARROW=$(cmd overlay lookup --user 0 android android:color/system_on_secondary_container_dark)\n");
                    String out = sh(cmd.toString());
                    java.util.HashMap<String, String> map = new java.util.HashMap<>();
                    for (String line : out.split("\\r?\\n")) {
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                    dark = new Palette(true);
                    light = new Palette(false);
                    fillPalette(dark, map, "D_");
                    fillPalette(light, map, "L_");
                    Integer f = parseMaterialColor(map.get("D_FILL"));
                    Integer a = parseMaterialColor(map.get("D_ARROW"));
                    if (f != null && a != null) {
                        fill = f;
                        arrow = a;
                        fillOk = true;
                        arrowOk = true;
                    } else {
                        log("Theme: fill/arrow lookup failed, using fallback");
                    }
                }
            } catch (Throwable t) {
                log("Theme lookup failed: " + t);
            }
            if (dark == null) dark = Palette.fallback(true);
            if (light == null) light = Palette.fallback(false);
            if (!fillOk) { fill = dark.secondaryContainer; fillOk = true; }
            if (!arrowOk) { arrow = dark.onSecondaryContainer; arrowOk = true; }
            paletteDark = dark;
            paletteLight = light;
            materialFill = fill;
            materialArrow = arrow;
            materialFillOk = fillOk;
            materialArrowOk = arrowOk;
            materialRetry = 0;
            themeLoaded = true;
            materialFetching = false;
            log("Theme loaded: fill=" + colorToHex(fill) + " arrow=" + colorToHex(arrow));
            ui.post(this::buildMainUi);
        }).start();
    }

    private void fillPalette(Palette p, java.util.HashMap<String, String> map, String prefix) {
        Palette fb = Palette.fallback(p.dark);
        p.primary = colorOr(map, prefix + "primary", fb.primary);
        p.onPrimary = colorOr(map, prefix + "on_primary", fb.onPrimary);
        p.primaryContainer = colorOr(map, prefix + "primary_container", fb.primaryContainer);
        p.onPrimaryContainer = colorOr(map, prefix + "on_primary_container", fb.onPrimaryContainer);
        p.secondaryContainer = colorOr(map, prefix + "secondary_container", fb.secondaryContainer);
        p.onSecondaryContainer = colorOr(map, prefix + "on_secondary_container", fb.onSecondaryContainer);
        p.surface = colorOr(map, prefix + "surface", fb.surface);
        p.onSurface = colorOr(map, prefix + "on_surface", fb.onSurface);
        p.surfaceContainer = colorOr(map, prefix + "surface_container", fb.surfaceContainer);
        p.surfaceContainerHigh = colorOr(map, prefix + "surface_container_high", fb.surfaceContainerHigh);
        p.surfaceContainerHighest = colorOr(map, prefix + "surface_container_highest", fb.surfaceContainerHighest);
        p.surfaceVariant = colorOr(map, prefix + "surface_variant", fb.surfaceVariant);
        p.onSurfaceVariant = colorOr(map, prefix + "on_surface_variant", fb.onSurfaceVariant);
        p.outline = colorOr(map, prefix + "outline", fb.outline);
        p.outlineVariant = colorOr(map, prefix + "outline_variant", fb.outlineVariant);
        p.error = colorOr(map, prefix + "error", fb.error);
        p.onError = colorOr(map, prefix + "on_error", fb.onError);
    }

    private static int colorOr(java.util.HashMap<String, String> map, String key, int fallback) {
        Integer c = parseMaterialColor(map.get(key));
        return c != null ? c : fallback;
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
    // Screens
    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadSpeed();
        loadDarkMode();
        pal = Palette.fallback(darkMode);
        applyPaletteToWindow();

        try {
            Shizuku.addBinderReceivedListenerSticky(() -> ui.post(() -> {
                log("Shizuku binder received");
                onShizukuStateChanged();
            }));
            Shizuku.addBinderDeadListener(() -> ui.post(() -> {
                log("Shizuku binder lost");
                onShizukuStateChanged();
            }));
            Shizuku.addRequestPermissionResultListener((code, res) -> ui.post(() -> {
                log("Permission result: " + (res == PackageManager.PERMISSION_GRANTED
                        ? "GRANTED" : "DENIED(" + res + ")"));
                lastGateKey = "";
                onShizukuStateChanged();
            }));
        } catch (Throwable t) {
            log("Shizuku listener error: " + t);
        }

        log("Ready. Shizuku running: " + safePing());
        loadCalibration();
        pendingIntent = getIntent();
        onShizukuStateChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        onShizukuStateChanged();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingIntent = intent;
        if (mainShowing) {
            pendingIntent = null;
            applyIntent(intent);
        }
    }

    @Override
    protected void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }

    private void onShizukuStateChanged() {
        boolean running = safePingBool();
        boolean granted = running && safeGranted();
        if (granted) {
            if (!themeLoaded) {
                if (!loadingShowing) showLoading();
                fetchThemeData();
            } else if (!mainShowing) {
                buildMainUi();
            }
        } else {
            String key = isShizukuInstalled() + "|" + running + "|" + granted;
            if (!gateShowing || !key.equals(lastGateKey)) {
                lastGateKey = key;
                showGate();
            }
        }
    }

    private boolean safePingBool() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    private boolean safeGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showLoading() {
        loadingShowing = true;
        gateShowing = false;
        mainShowing = false;
        pal = Palette.fallback(darkMode);
        applyPaletteToWindow();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(pal.surface);
        final int pad = dp(24);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                    pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = label("OHO+ Monet", 32, pal.onSurface, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        addSpace(root, 16);

        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminateTintList(ColorStateList.valueOf(pal.primary));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(bar, blp);
        addSpace(root, 12);

        TextView msg = label("Loading theme\u2026", 14, pal.onSurfaceVariant, false);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg);

        setContentView(root);
    }

    private void buildMainUi() {
        if (!safePingBool() || !safeGranted()) {
            showGate();
            return;
        }
        if (!themeLoaded) {
            if (!loadingShowing) showLoading();
            return;
        }
        mainShowing = true;
        gateShowing = false;
        loadingShowing = false;
        pal = darkMode ? paletteDark : paletteLight;
        if (pal == null) pal = Palette.fallback(darkMode);
        applyPaletteToWindow();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(pal.surface);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        final int baseLeft = dp(20), baseTop = dp(20);
        final int baseRight = dp(20), baseBottom = dp(24);
        content.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            content.setPadding(baseLeft, baseTop + insets.getSystemWindowInsetTop(),
                    baseRight, baseBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("OHO+ Monet", 32, pal.onSurface, true);
        title.setLetterSpacing(-0.02f);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        themeToggleView = new ThemeToggleView(this);
        themeToggleView.setPalette(pal, darkMode);
        themeToggleView.setOnClickListener(v -> toggleTheme());
        header.addView(themeToggleView, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);
        addSpace(content, 20);

        // Tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabFill = tabButton("Fill color", 0);
        tabArrow = tabButton("Arrow color", 1);
        tabs.addView(tabFill);
        tabs.addView(tabArrow);
        content.addView(tabs);
        addSpace(content, 16);

        // Tab content: both cards, only the selected one is visible
        tabContent = new FrameLayout(this);
        fillSection = addColorSection(tabContent, "fill", "Fill color",
                2001, 2002, "#473646");
        arrowSection = addColorSection(tabContent, "arrow", "Arrow color",
                3001, 3002, "#D1B8CD");
        content.addView(tabContent);
        selectTab(selectedTab);
        addSpace(content, 24);

        // Speed
        content.addView(label("Speed", 16, pal.onSurface, true));
        addSpace(content, 8);
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.addView(label("Slow", 12, pal.onSurfaceVariant, false));
        speedBar = new SeekBar(this);
        speedBar.setMax(SPEED_FAST);
        speedBar.setProgress(speed);
        speedBar.setKeyProgressIncrement(1);
        tintSeekBar(speedBar, pal);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                speed = progress;
                if (fromUser) saveSpeed(progress);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        slp.leftMargin = dp(12);
        slp.rightMargin = dp(12);
        speedRow.addView(speedBar, slp);
        speedRow.addView(label("Fast", 12, pal.onSurfaceVariant, false));
        content.addView(speedRow);
        TextView speedNote = label(
                "Fast may fail on some devices. If applying fails, use Normal or Slow.",
                12, pal.onSurfaceVariant, false);
        speedNote.setLineSpacing(dp(2), 1f);
        content.addView(speedNote);
        addSpace(content, 24);

        // Actions
        applyBtn = pillButton("Apply", true, pal, this::startApply);
        content.addView(applyBtn);
        addSpace(content, 12);
        calibBtn = pillButton("Recalibrate", false, pal, this::startCalibration);
        content.addView(calibBtn);
        addSpace(content, 24);

        // Logs
        LinearLayout logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.VERTICAL);
        logs.setPadding(dp(20), dp(16), dp(20), dp(16));
        logs.setBackground(rounded(pal.surfaceContainer, 24));
        logs.addView(label("Logs", 16, pal.onSurface, true));
        addSpace(logs, 8);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(pal.onSurfaceVariant);
        logView.setText(logBuffer.toString());
        logView.setLineSpacing(dp(2), 1f);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.setNestedScrollingEnabled(true);
        logScroll.addView(logView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logs.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
        content.addView(logs);

        setContentView(scroll);
        scroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));

        updateAllPreviews();
        setUiBusy(busy);
        updateApplyEnabled();
        scroll.setAlpha(0f);
        scroll.animate().alpha(1f).setDuration(220).start();

        if (pendingIntent != null) {
            Intent it = pendingIntent;
            pendingIntent = null;
            applyIntent(it);
        }
    }

    private void showGate() {
        gateShowing = true;
        mainShowing = false;
        loadingShowing = false;
        pal = (paletteDark != null) ? (darkMode ? paletteDark : paletteLight)
                : Palette.fallback(darkMode);
        if (pal == null) pal = Palette.fallback(darkMode);
        applyPaletteToWindow();

        boolean installed = isShizukuInstalled();
        boolean running = safePingBool();
        boolean granted = running && safeGranted();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pal.surface);
        final int pad = dp(24);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                    pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        card.setBackground(rounded(pal.surfaceContainer, 32));
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LockIconView icon = new LockIconView(this);
        icon.setPalette(pal);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(72), dp(72));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(icon, ilp);
        addSpace(card, 20);

        TextView title = label("Shizuku required", 28, pal.onSurface, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(title);
        addSpace(card, 12);

        TextView body = label(
                "OHO+ Monet uses Shizuku to change One Hand Operation+ settings. "
                        + "Shizuku must be installed, running, and allowed to control this app.",
                15, pal.onSurfaceVariant, false);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setLineSpacing(dp(3), 1f);
        card.addView(body);
        addSpace(card, 20);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        statusRow.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusRow.setBackground(rounded(pal.surfaceContainerHigh, 20));
        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(!running ? pal.error : pal.primary);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(8);
        statusRow.addView(dot, dlp);
        String statusText = !installed ? "Shizuku is not installed"
                : !running ? "Shizuku is not running" : "Permission not granted";
        statusRow.addView(label(statusText, 13, pal.onSurfaceVariant, false));
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(statusRow, srp);
        addSpace(card, 24);

        String actionText = !installed ? "Install Shizuku"
                : !running ? "Open Shizuku" : "Grant permission";
        card.addView(pillButton(actionText, true, pal,
                () -> onGateAction(installed, running, granted)));
        addSpace(card, 12);
        card.addView(pillButton("Exit", false, pal, this::exitApp));

        addSpace(card, 16);
        String hintText = !installed
                ? "Install Shizuku from the Play Store, start it, then return here."
                : !running
                ? "Open Shizuku and start the service, then return here."
                : "Tap Grant permission and allow access.";
        TextView hint = label(hintText, 12, pal.onSurfaceVariant, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(hint);

        setContentView(root);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220).start();
    }

    private void onGateAction(boolean installed, boolean running, boolean granted) {
        if (granted) {
            onShizukuStateChanged();
            return;
        }
        if (!installed) {
            openPlayStore();
            return;
        }
        if (!running) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (i != null) startActivity(i);
                else toast("Could not open Shizuku");
            } catch (Throwable t) {
                toast("Could not open Shizuku");
            }
            return;
        }
        try {
            log("Requesting Shizuku permission...");
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            log("requestPermission error: " + t);
            toast("Could not request permission");
        }
    }

    private void openPlayStore() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=moe.shizuku.privileged.api"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t2) {
                toast("Install Shizuku from the Play Store");
            }
        }
    }

    private void exitApp() {
        finishAffinity();
    }

    private void applyIntent(Intent it) {
        if (it == null) return;
        if (!mainShowing || fillSection == null || arrowSection == null) {
            pendingIntent = it;
            return;
        }
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
        }

        boolean targetArrow = false;
        boolean colorTarget = false;
        if (fillExtra != null) {
            fillSection.modeGroup.check(fillSection.customId);
            fillSection.input.setText(fillExtra);
            commitCustom(fillSection);
            colorTarget = true;
        } else if (fillMat) {
            fillSection.modeGroup.check(fillSection.materialId);
            colorTarget = true;
        }
        if (arrowExtra != null) {
            arrowSection.modeGroup.check(arrowSection.customId);
            arrowSection.input.setText(arrowExtra);
            commitCustom(arrowSection);
            targetArrow = true;
            colorTarget = true;
        } else if (arrowMat) {
            arrowSection.modeGroup.check(arrowSection.materialId);
            targetArrow = true;
            colorTarget = true;
        }

        // Backward-compatible single-colour intent: --es hex --es target fill|arrow
        if (hx != null && !hx.isEmpty()) {
            if ("arrow".equalsIgnoreCase(tg)) {
                arrowSection.modeGroup.check(arrowSection.customId);
                arrowSection.input.setText(hx);
                commitCustom(arrowSection);
                targetArrow = true;
            } else {
                fillSection.modeGroup.check(fillSection.customId);
                fillSection.input.setText(hx);
                commitCustom(fillSection);
            }
            colorTarget = true;
        }
        if (colorTarget) selectTab(targetArrow ? 1 : 0);

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

    private Integer resolveColor(ColorSection s, String label) {
        if (s.materialMode) {
            if (!s.materialOk) {
                String msg = "Material You " + label + " color unavailable \u2014 check Shizuku/permission";
                log(msg);
                toast(msg);
                return null;
            }
            return s.materialColor;
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
        if ((fillSection.materialMode && !fillSection.materialOk)
                || (arrowSection.materialMode && !arrowSection.materialOk)) {
            if (materialRetry < 5) {
                materialRetry++;
                log("Waiting for Material You colors\u2026");
                ui.postDelayed(this::startApply, 800);
                return;
            }
        }
        materialRetry = 0;

        Integer fillColor = resolveColor(fillSection, "Fill");
        Integer arrowColor = resolveColor(arrowSection, "Arrow");
        if (fillColor == null || arrowColor == null) return;

        saveCustom("fill", fillSection.input.getText().toString());
        saveCustom("arrow", arrowSection.input.getText().toString());

        final int fc = fillColor, ac = arrowColor;
        busy = true;
        flowSpeed = speed;   // slider applies to Apply only
        log("Apply flow speed: " + speedName(flowSpeed));
        log("Fill=" + colorToHex(fc) + " Arrow=" + colorToHex(ac));
        setUiBusy(true);
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
                setUiBusy(false);
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
        setUiBusy(true);
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
                setUiBusy(false);
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
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(dp(24), dp(12), dp(24), dp(12));

                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.y = dp(64);
                    wm.addView(tv, lp);
                    overlayView = tv;
                    overlayWm = wm;
                }
                overlayView.setTextColor(pal.onSurface);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(pal.surfaceContainerHigh);
                bg.setCornerRadius(dp(28));
                bg.setStroke(Math.max(1, dp(1)), pal.outlineVariant);
                overlayView.setBackground(bg);
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
        synchronized (logBuffer) {
            logBuffer.append(s).append('\n');
        }
        final TextView target = logView;
        ui.post(() -> {
            if (target == null) return;
            target.append(s + "\n");
            if (target.getParent() instanceof ScrollView) {
                ScrollView sv = (ScrollView) target.getParent();
                sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
        android.util.Log.i("OhoMonet", s);
    }
}
