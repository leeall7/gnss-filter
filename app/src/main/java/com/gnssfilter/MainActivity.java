package com.gnssfilter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int TH_MIN = 10, TH_STEP = 10, TH_STEPS = 300;

    private static final int BG = 0xFFF3F5F9;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF1F2933;
    private static final int MUTED = 0xFF7B8794;
    private static final int ACCENT = 0xFF3B6FE0;

    private TextView glyph, stateWord, stateWhy;
    private TextView kAcc, kSpd, kSat, srcLine;
    private TextView obdLine, thLabel, diag, legend;
    private Button btn, diagBtn;
    private LinearLayout hero, diagBox;
    private CheckBox obdBox;
    private boolean diagOpen = false;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); h.postDelayed(this, 1000); }
    };

    // ------------------------------------------------------------------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, 14));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        parent.addView(c, lp);
        return c;
    }

    private TextView text(LinearLayout parent, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(t);
        return t;
    }

    private Button button(LinearLayout parent, String s, int bg, int fg, float weight,
                          View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(rounded(bg, 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(44), weight);
        lp.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(b, lp);
        return b;
    }

    private CheckBox check(LinearLayout parent, String s, boolean on,
                           CompoundButton.OnCheckedChangeListener l) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(INK);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setChecked(on);
        c.setOnCheckedChangeListener(l);
        parent.addView(c);
        return c;
    }

    /** Картка-метрика: велике число, підпис під ним. */
    private TextView metric(LinearLayout row, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(rounded(CARD, 14));
        box.setPadding(dp(6), dp(10), dp(6), dp(10));
        TextView v = text(box, "—", 22, INK, true);
        v.setGravity(Gravity.CENTER);
        TextView l = text(box, label, 11, MUTED, false);
        l.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), dp(10));
        row.addView(box, lp);
        return v;
    }

    // ------------------------------------------------------------------

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        // --- герой: стан ---
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(rounded(FilterService.C_GREY, 18));
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        glyph = new TextView(this);
        glyph.setText("○");
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        glyph.setTextColor(Color.WHITE);
        glyph.setPadding(0, 0, dp(14), 0);
        hero.addView(glyph);
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        stateWord = text(heroText, "GNSS Filter", 22, Color.WHITE, true);
        stateWhy = text(heroText, "натисніть Старт", 13, 0xEEFFFFFF, false);
        hero.addView(heroText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, 0, 0, dp(10));
        root.addView(hero, hlp);

        // --- три метрики ---
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        kAcc = metric(row, "радіус");
        kSpd = metric(row, "км/год");
        kSat = metric(row, "супутники");
        root.addView(row);

        // --- джерело + OBD ---
        LinearLayout src = card(root);
        srcLine = text(src, "Джерело: —", 14, INK, false);
        obdLine = text(src, "OBD: вимкнено", 13, MUTED, false);

        // --- керування ---
        btn = new Button(this);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        blp.setMargins(0, 0, 0, dp(8));
        root.addView(btn, blp);

        LinearLayout small = new LinearLayout(this);
        small.setOrientation(LinearLayout.HORIZONTAL);
        button(small, "Скинути мок", 0xFFE4E7EB, INK, 1f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                FilterService.forceCleanup(MainActivity.this);
                toast("Тестові провайдери знято");
            }
        });
        button(small, "База AGC", 0xFFE4E7EB, INK, 1f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                FilterService.resetAgcBase(MainActivity.this);
                FilterService.agcBaseKnown = false;
                toast("Базу знято, вчитиметься заново");
            }
        });
        button(small, "Логи ↗", ACCENT, Color.WHITE, 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { askExport(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, 0, 0, dp(10));
        root.addView(small, slp);

        // --- налаштування ---
        LinearLayout set = card(root);
        text(set, "Налаштування", 12, MUTED, true);
        check(set, "Кольорова крапка поверх екрана", FilterService.showDot,
                new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                FilterService.showDot = c;
                if (c && !Settings.canDrawOverlays(MainActivity.this)) {
                    toast("Дозвольте показ поверх інших застосунків");
                    try { startActivity(FilterService.overlaySettings(MainActivity.this)); }
                    catch (Throwable ignored) { }
                }
            }
        });
        check(set, "Вібрація при погіршенні", FilterService.vibrate,
                new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                FilterService.vibrate = c;
            }
        });
        obdBox = check(set, "OBD-адаптер (швидкість з коліс)", Obd.enabled,
                new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                Obd.enabled = c;
                prefs().edit().putBoolean("obd", c).apply();
                if (c) {
                    askBluetooth();
                    if (FilterService.running) Obd.start(MainActivity.this);
                } else {
                    Obd.stop();
                }
            }
        });
        LinearLayout obdRow = new LinearLayout(this);
        obdRow.setOrientation(LinearLayout.HORIZONTAL);
        button(obdRow, "Обрати адаптер", 0xFFE4E7EB, INK, 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { pickObd(); }
        });
        set.addView(obdRow);
        check(set, "Підміняти також fused (зазвичай вимкнено)", FilterService.mockFused,
                new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                FilterService.mockFused = c;
            }
        });
        thLabel = text(set, "", 13, INK, false);
        SeekBar sb = new SeekBar(this);
        sb.setMax(TH_STEPS);
        sb.setProgress(Math.max(0, (FilterService.accThreshold - TH_MIN) / TH_STEP));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                FilterService.accThreshold = TH_MIN + v * TH_STEP;
                prefs().edit().putInt("thr", FilterService.accThreshold).apply();
                updateTh();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        set.addView(sb);
        updateTh();

        // --- легенда ---
        LinearLayout leg = card(root);
        legend = text(leg, "● GPS у довірі   ◐ слабкий   ▲ ведемо з мережі   ✕ утримання",
                12, MUTED, false);

        // --- діагностика (згорнута) ---
        diagBtn = new Button(this);
        diagBtn.setAllCaps(false);
        diagBtn.setText("Діагностика ▾");
        diagBtn.setTextColor(MUTED);
        diagBtn.setBackground(rounded(0x00000000, 0));
        diagBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                diagOpen = !diagOpen;
                diagBox.setVisibility(diagOpen ? View.VISIBLE : View.GONE);
                diagBtn.setText(diagOpen ? "Діагностика ▴" : "Діагностика ▾");
            }
        });
        root.addView(diagBtn);
        diagBox = card(root);
        diag = text(diagBox, "", 12, INK, false);
        diag.setTypeface(Typeface.MONOSPACE);
        diagBox.setVisibility(View.GONE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);

        // збережені налаштування
        Obd.enabled = prefs().getBoolean("obd", false);
        Obd.deviceAddr = prefs().getString("obd_addr", "");
        FilterService.accThreshold = prefs().getInt("thr", FilterService.accThreshold);
        obdBox.setChecked(Obd.enabled);
        sb.setProgress(Math.max(0, (FilterService.accThreshold - TH_MIN) / TH_STEP));
        updateTh();

        askPermissions();
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("gnssfilter", MODE_PRIVATE);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------

    private void toggle() {
        if (FilterService.running) {
            stopService(new Intent(this, FilterService.class));
        } else {
            if (!hasLocation()) {
                toast("Спершу надайте дозвіл на точну локацію");
                askPermissions();
                return;
            }
            startForegroundService(new Intent(this, FilterService.class));
        }
        h.removeCallbacks(refresh);
        h.postDelayed(refresh, 300);
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        String[] perms = (Build.VERSION.SDK_INT >= 33)
                ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION,
                               Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                               Manifest.permission.ACCESS_COARSE_LOCATION};
        boolean need = false;
        for (String q : perms)
            if (checkSelfPermission(q) != PackageManager.PERMISSION_GRANTED) need = true;
        if (need) requestPermissions(perms, 1);
    }

    private void askBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && !Obd.hasPermission(this))
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        render();
    }

    private void pickObd() {
        askBluetooth();
        final List<String[]> devs = Obd.bonded(this);
        if (devs.isEmpty()) {
            toast("Спершу спаруйте адаптер у налаштуваннях Bluetooth (PIN 1234)");
            return;
        }
        final CharSequence[] names = new CharSequence[devs.size() + 1];
        names[0] = "Авто (за назвою: OBD, ELM…)";
        for (int i = 0; i < devs.size(); i++) names[i + 1] = devs.get(i)[0] + "  " + devs.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("OBD-адаптер")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Obd.deviceAddr = w == 0 ? "" : devs.get(w - 1)[1];
                        prefs().edit().putString("obd_addr", Obd.deviceAddr).apply();
                        toast(w == 0 ? "Авто-вибір" : "Обрано " + devs.get(w - 1)[0]);
                        if (Obd.enabled && FilterService.running) { Obd.stop(); Obd.start(MainActivity.this); }
                    }
                })
                .show();
    }

    private void updateTh() {
        thLabel.setText("Поріг точності мережі: " + FilterService.accThreshold + " м");
    }

    private void askExport() {
        new AlertDialog.Builder(this)
                .setTitle("Надіслати логи")
                .setMessage("В архіві — точні координати, час і швидкість усіх поїздок "
                        + "за обраний період.\n\nНадсилайте лише тому, кому довіряєте.\n\n"
                        + "Зараз збережено: " + Logger.info())
                .setPositiveButton("24 години", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doExport(1); }
                })
                .setNeutralButton("7 днів", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doExport(7); }
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }

    private void doExport(final int days) {
        toast("Пакую логи…");
        new Thread(new Runnable() {
            @Override public void run() {
                Uri u = null; String err = null;
                try { u = Logger.exportZip(MainActivity.this, days); }
                catch (Throwable t) { err = t.toString(); }
                final Uri fu = u; final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() { afterExport(fu, fe); }
                });
            }
        }).start();
    }

    private void afterExport(Uri u, String err) {
        if (err != null) { toast("Помилка: " + err); return; }
        if (u == null) { toast("За цей період логів немає"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Надіслати логи")); }
        catch (Throwable t) { toast("Архів у Downloads/GnssFilter"); }
    }

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(refresh);
        h.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    // ------------------------------------------------------------------

    private void render() {
        boolean run = FilterService.running;
        String st = FilterService.state;
        int col = run ? FilterService.colorOf(st) : FilterService.C_GREY;

        btn.setText(run ? "Стоп" : "Старт");
        btn.setBackground(rounded(run ? 0xFFC62828 : 0xFF2E7D32, 14));

        hero.setBackground(rounded(col, 18));
        String g = FilterService.S_GREEN.equals(st) ? "●"
                : FilterService.S_YELLOW.equals(st) ? "◐"
                : FilterService.S_ORANGE.equals(st) ? "▲"
                : FilterService.S_RED.equals(st) ? "✕"
                : FilterService.S_WARMUP.equals(st) ? "◌" : "○";
        glyph.setText(g);
        String word = !run ? "Зупинено"
                : FilterService.S_GREEN.equals(st) ? "GPS у довірі"
                : FilterService.S_YELLOW.equals(st) ? "GPS під наглядом"
                : FilterService.S_ORANGE.equals(st) ? "Ведемо з мережі"
                : FilterService.S_RED.equals(st) ? "Утримання"
                : "Прогрів";
        stateWord.setText(word);
        stateWhy.setText(run ? FilterService.reason : "натисніть Старт");

        kAcc.setText(run && FilterService.acc > 0
                ? String.format(Locale.US, "±%.0f м", FilterService.acc) : "—");
        kSpd.setText(run ? String.format(Locale.US, "%.0f", FilterService.speedMps * 3.6f) : "—");
        kSat.setText(run ? FilterService.usedInFix + "/" + FilterService.visible : "—");

        String s1 = "Джерело: " + FilterService.source
                + "   вхід: " + FilterService.inSrc
                + "   GPS: " + FilterService.gpsSrc
                + (FilterService.mockActive ? "   [мок]" : "");
        if (FilterService.divergence >= 0)
            s1 += String.format(Locale.US, "   GPS↔мережа %.0f м", FilterService.divergence);
        srcLine.setText(s1);

        String o;
        if (!Obd.enabled) o = "OBD: вимкнено";
        else if (Obd.fresh()) o = String.format(Locale.US, "OBD: %s · %.0f км/год",
                Obd.deviceName, Obd.speedKmh);
        else o = "OBD: " + Obd.link;
        o += "   рух: " + (FilterService.moving ? "їдемо" : "стоїмо")
                + " (" + FilterService.motionSrc + ")";
        if (FilterService.hdgDeg >= 0)
            o += String.format(Locale.US, "   курс %.0f°%s", FilterService.hdgDeg,
                    FilterService.hdgAbs ? "" : " (відн.)");
        obdLine.setText(o);

        if (!hasLocation()) stateWhy.setText("немає дозволу на точну локацію");
        if (run && FilterService.mockDenied) {
            hero.setBackground(rounded(0xFFC62828, 18));
            glyph.setText("⚠");
            stateWord.setText("Мок заборонено");
            stateWhy.setText("Developer options → Select mock location app → GNSS Filter. "
                    + "Після кожного оновлення APK вибір скидається.");
        } else if (FilterService.mockError != null && run) {
            stateWhy.setText(FilterService.mockError);
        }

        if (diagOpen) {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "C/N0 топ-4 %.0f дБГц, розкид %.1f%n",
                    FilterService.cn0Top, FilterService.cn0Sd));
            b.append(String.format(Locale.US, "Вироджені азимути %.0f%%, TOW %d%n",
                    FilterService.degenRatio * 100, FilterService.towValid));
            StringBuilder ag = new StringBuilder();
            for (String bd : new String[]{"1176", "1561", "1575", "1602"}) {
                Float d = FilterService.agcDelta.get(bd);
                ag.append(bd).append(' ').append(d == null ? "—"
                        : String.format(Locale.US, "%+.0f", d)).append("  ");
            }
            b.append("AGC Δ: ").append(ag)
             .append(FilterService.agcBaseKnown ? "" : "(бази ще немає)").append('\n');
            b.append("Ознаки: ").append(FilterService.spoofFlags).append('\n');
            b.append(String.format(Locale.US, "Акселерометр %.2f%n", FilterService.accStd));
            if (FilterService.lat != 0 || FilterService.lon != 0)
                b.append(String.format(Locale.US, "Позиція %.6f, %.6f%n",
                        FilterService.lat, FilterService.lon));
            if (FilterService.extrapMs > 0)
                b.append(String.format(Locale.US, "Екстраполяція %.1f с%n",
                        FilterService.extrapMs / 1000f));
            b.append("Відкинуто ").append(FilterService.rejected)
             .append(" · ").append(FilterService.lastReject).append('\n');
            b.append("Логи ").append(Logger.info()).append('\n');
            b.append("Версія ").append(FilterService.VER).append('\n');
            diag.setText(b.toString());
        }
    }
}
