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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final int TH_MIN = 10;
    private static final int TH_STEP = 10;
    private static final int TH_STEPS = 300;

    private TextView badge;
    private TextView legend;
    private TextView out;
    private TextView thLabel;
    private Button btn;
    private Button resetBtn;
    private CheckBox fusedBox;
    private CheckBox dotBox;
    private CheckBox vibBox;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            h.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        badge = new TextView(this);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(14), dp(12), dp(14));
        root.addView(badge);

        legend = new TextView(this);
        legend.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        legend.setPadding(0, dp(8), 0, dp(8));
        legend.setText("● зелений — GPS у довірі\n"
                + "● жовтий — GPS у довірі, але слабкий\n"
                + "● помаранчевий — GPS не в довірі, ведемо з мережі\n"
                + "● червоний — мережі немає, тримаємо останню позицію");
        root.addView(legend);

        btn = new Button(this);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (FilterService.running) {
                    stopService(new Intent(MainActivity.this, FilterService.class));
                } else {
                    if (!hasLocation()) {
                        Toast.makeText(MainActivity.this,
                                "Спершу надайте дозвіл на точну локацію",
                                Toast.LENGTH_LONG).show();
                        askPermissions();
                        return;
                    }
                    startForegroundService(
                            new Intent(MainActivity.this, FilterService.class));
                }
                h.removeCallbacks(refresh);
                h.postDelayed(refresh, 300);
            }
        });
        root.addView(btn);

        resetBtn = new Button(this);
        resetBtn.setText("СКИНУТИ МОК");
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FilterService.forceCleanup(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        "Тестові провайдери знято", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(resetBtn);

        Button agcBtn = new Button(this);
        agcBtn.setText("СКИНУТИ БАЗУ AGC");
        agcBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                FilterService.resetAgcBase(MainActivity.this);
                FilterService.agcBaseKnown = false;
                Toast.makeText(MainActivity.this,
                        "Базу знято, вчитиметься заново на чистому небі",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(agcBtn);

        Button logBtn = new Button(this);
        logBtn.setText("НАДІСЛАТИ ЛОГИ");
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askExport(); }
        });
        root.addView(logBtn);

        dotBox = new CheckBox(this);
        dotBox.setText("Кольорова крапка поверх екрана");
        dotBox.setChecked(FilterService.showDot);
        dotBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                FilterService.showDot = c;
                if (c && !Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this,
                            "Дозвольте показ поверх інших застосунків",
                            Toast.LENGTH_LONG).show();
                    try {
                        startActivity(FilterService.overlaySettings(MainActivity.this));
                    } catch (Throwable ignored) { }
                }
            }
        });
        root.addView(dotBox);

        vibBox = new CheckBox(this);
        vibBox.setText("Вібрація при погіршенні");
        vibBox.setChecked(FilterService.vibrate);
        vibBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                FilterService.vibrate = c;
            }
        });
        root.addView(vibBox);

        fusedBox = new CheckBox(this);
        fusedBox.setText("Підміняти також fused (вимкнено: fused — наш вхід)");
        fusedBox.setChecked(FilterService.mockFused);
        fusedBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                FilterService.mockFused = c;
            }
        });
        root.addView(fusedBox);

        thLabel = new TextView(this);
        root.addView(thLabel);

        SeekBar sb = new SeekBar(this);
        sb.setMax(TH_STEPS);
        sb.setProgress(Math.max(0, (FilterService.accThreshold - TH_MIN) / TH_STEP));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean u) {
                FilterService.accThreshold = TH_MIN + v * TH_STEP;
                updateThLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(sb);
        updateThLabel();

        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        out.setPadding(0, dp(12), 0, 0);
        root.addView(out);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        askPermissions();
    }

    /** Попередження про вміст логів обов'язкове: там точні координати всіх поїздок. */
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
        Toast.makeText(this, "Пакую логи…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                Uri u = null;
                String err = null;
                try {
                    u = Logger.exportZip(MainActivity.this, days);
                } catch (Throwable t) {
                    err = t.toString();
                }
                final Uri fu = u;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() { afterExport(fu, fe); }
                });
            }
        }).start();
    }

    private void afterExport(Uri u, String err) {
        if (err != null) {
            Toast.makeText(this, "Помилка: " + err, Toast.LENGTH_LONG).show();
            return;
        }
        if (u == null) {
            Toast.makeText(this, "За цей період логів немає", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Надіслати логи"));
        } catch (Throwable t) {
            Toast.makeText(this, "Архів у Downloads/GnssFilter", Toast.LENGTH_LONG).show();
        }
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

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        render();
    }

    private void updateThLabel() {
        thLabel.setText("Поріг точності мережі: " + FilterService.accThreshold + " м");
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

    private void render() {
        boolean run = FilterService.running;
        btn.setText(run ? "СТОП" : "СТАРТ");
        resetBtn.setEnabled(!run);
        fusedBox.setEnabled(!run);

        String st = FilterService.state;
        int col = FilterService.colorOf(st);

        String word = FilterService.S_GREEN.equals(st) ? "GPS У ДОВІРІ"
                : FilterService.S_YELLOW.equals(st) ? "GPS СЛАБКИЙ"
                : FilterService.S_ORANGE.equals(st) ? "ВЕДЕМО З МЕРЕЖІ"
                : FilterService.S_RED.equals(st) ? "УТРИМАННЯ"
                : FilterService.S_WARMUP.equals(st) ? "ПРОГРІВ"
                : "ЗУПИНЕНО";
        badge.setText(word + (run ? "\n" + FilterService.reason : ""));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(col);
        badge.setBackground(bg);

        StringBuilder b = new StringBuilder();
        b.append("Джерело   ").append(FilterService.source);
        if (FilterService.mockActive) b.append("   [мок: ")
                .append(FilterService.mockedProviders).append(']');
        b.append("\n\n");

        if (!hasLocation()) b.append("! немає дозволу на точну локацію\n\n");
        if (FilterService.showDot && !Settings.canDrawOverlays(this))
            b.append("! крапка вимкнена: немає дозволу поверх вікон\n\n");

        b.append(String.format(Locale.US, "Супутники у розв'язку  %d з %d видимих%n",
                FilterService.usedInFix, FilterService.visible));
        b.append(String.format(Locale.US, "C/N0 топ-4 %.0f дБГц, розкид %.1f дБ%n",
                FilterService.cn0Top, FilterService.cn0Sd));
        b.append(String.format(Locale.US, "Вироджених азимутів %.0f%%, TOW %d%n",
                FilterService.degenRatio * 100, FilterService.towValid));

        StringBuilder ag = new StringBuilder();
        for (String bd : new String[]{"1176", "1561", "1575", "1602"}) {
            Float d = FilterService.agcDelta.get(bd);
            ag.append(bd).append(' ')
              .append(d == null ? "—" : String.format(Locale.US, "%+.0f", d))
              .append("  ");
        }
        b.append("AGC Δ до бази: ").append(ag)
         .append(FilterService.agcBaseKnown ? "" : "  (базу ще не вивчено)").append('\n');
        b.append("Ознаки підміни: ").append(FilterService.spoofFlags).append('\n');
        if (FilterService.divergence >= 0)
            b.append(String.format(Locale.US, "GPS vs мережа: %.0f м%n",
                    FilterService.divergence));
        b.append("Вхідне джерело: ").append(FilterService.inSrc).append('\n');
        b.append(String.format(Locale.US, "Рух: %s (акселерометр %.2f)%n",
                FilterService.moving ? "їдемо" : "стоїмо", FilterService.accStd));

        if (FilterService.lat != 0 || FilterService.lon != 0) {
            b.append(String.format(Locale.US, "Позиція   %.6f, %.6f%n",
                    FilterService.lat, FilterService.lon));
            b.append(String.format(Locale.US, "Радіус    ±%.0f м%n", FilterService.acc));
            b.append(String.format(Locale.US, "Швидкість %.0f км/год%n",
                    FilterService.speedMps * 3.6f));
            if (FilterService.bearingDeg >= 0)
                b.append(String.format(Locale.US, "Курс      %.0f°%n",
                        FilterService.bearingDeg));
            if (FilterService.extrapMs > 0)
                b.append(String.format(Locale.US, "Екстрапол %.1f с%n",
                        FilterService.extrapMs / 1000f));
        } else {
            b.append("Позиція   немає\n");
        }

        b.append("\nЛоги      ").append(Logger.info()).append('\n');
        b.append("Відкинуто ").append(FilterService.rejected).append('\n');
        b.append("Причина   ").append(FilterService.lastReject).append('\n');

        if (FilterService.mockError != null)
            b.append("\n! ").append(FilterService.mockError).append('\n');

        out.setText(b.toString());
        out.setTextColor(Color.DKGRAY);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
