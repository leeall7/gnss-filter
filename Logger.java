package com.gnssfilter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Логування у два потоки:
 *   state_YYYY-MM-DD.csv  — рядок на кожен цикл (1 Гц). Основа для розбору.
 *   events_YYYY-MM-DD.csv — лише переходи станів, mock, помилки. Читається першим.
 *
 * Увесь запис — на власному потоці, буферизовано, з примусовим скиданням на
 * кожній події. Будь-яка помилка тихо вимикає логування: фільтр важливіший
 * за логи і не має падати через повний диск.
 */
public final class Logger {

    /** Версія схеми CSV. Змінюється лише при зміні набору колонок. */
    public static final String SCHEMA = "4";

    private static final int KEEP_DAYS = 14;
    private static final long CAP_BYTES = 200L * 1024 * 1024;
    private static final int FLUSH_ROWS = 30;

    private static final String STATE_HDR =
            "ts_utc,ts_mono_ms,ver,schema,state,reason,source,in_src,"
          + "used,vis,cn0_top,cn0_sd,degen,agc_d1575,agc_d1602,tow_valid,"
          + "gps_lat,gps_lon,gps_acc,gps_age_ms,gps_trust,div_m,"
          + "net_lat,net_lon,net_acc,net_age_ms,"
          + "fus_lat,fus_lon,fus_acc,fus_age_ms,"
          + "out_lat,out_lon,out_acc,out_speed,out_bearing,extrap_ms,"
          + "mock,reject,obd_speed,obd_link,flags,acc_std,moving";
    private static final String EVENT_HDR = "ts_utc,ts_mono_ms,ver,type,detail";

    private static HandlerThread th;
    private static Handler h;
    private static File dir;
    private static BufferedWriter stateW, evW;
    private static String stateDay = "", evDay = "";
    private static int pending = 0;
    private static volatile boolean enabled = false;

    private static final SimpleDateFormat UTC =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    static {
        UTC.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private Logger() { }

    // ------------------------------------------------------------------

    public static synchronized void init(Context c) {
        if (enabled) return;
        try {
            File base = c.getExternalFilesDir(null);
            if (base == null) return;
            dir = new File(base, "logs");
            if (!dir.exists() && !dir.mkdirs()) return;
            th = new HandlerThread("gnss-log");
            th.start();
            h = new Handler(th.getLooper());
            enabled = true;
            h.post(new Runnable() {
                @Override public void run() { prune(); }
            });
        } catch (Throwable t) {
            enabled = false;
        }
    }

    public static boolean isEnabled() { return enabled; }

    public static String dirPath() {
        return dir == null ? "—" : dir.getAbsolutePath();
    }

    /** Короткий опис для UI: скільки файлів і скільки місця займають. */
    public static String info() {
        File[] fs = list();
        if (fs.length == 0) return enabled ? "логів ще немає" : "логування вимкнене";
        long sum = 0;
        for (File f : fs) sum += f.length();
        return String.format(Locale.US, "%d файл(ів), %.1f МБ", fs.length, sum / 1048576f);
    }

    // ------------------------------------------------------------------

    public static synchronized String utcNow() {
        return UTC.format(new Date());
    }

    private static synchronized String dayNow() {
        return DAY.format(new Date());
    }

    /** Рядок стану. Формується викликачем, щоб мітка часу була точною. */
    public static void state(final String row) {
        if (!enabled) return;
        try {
            h.post(new Runnable() {
                @Override public void run() { writeState(row); }
            });
        } catch (Throwable ignored) { }
    }

    /** Подія. Пишеться негайно і скидає буфер станів на диск. */
    public static void event(String type, String detail) {
        if (!enabled) return;
        final String row = utcNow() + "," + android.os.SystemClock.elapsedRealtime()
                + "," + FilterService.VER + "," + esc(type) + "," + esc(detail);
        try {
            h.post(new Runnable() {
                @Override public void run() { writeEvent(row); }
            });
        } catch (Throwable ignored) { }
    }

    /** Екранування для CSV: коми й лапки в текстових полях. */
    public static String esc(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"").replace("\n", " ") + '"';
    }

    // ------------------------------------------------------------------
    // усе нижче виконується ЛИШЕ на потоці логера

    private static void writeState(String row) {
        try {
            String d = dayNow();
            if (stateW == null || !d.equals(stateDay)) {
                closeQuiet(stateW);
                stateDay = d;
                stateW = open(new File(dir, "state_" + d + ".csv"), STATE_HDR);
                prune();
            }
            if (stateW == null) return;
            stateW.write(row);
            stateW.write('\n');
            if (++pending >= FLUSH_ROWS) { stateW.flush(); pending = 0; }
        } catch (Throwable t) {
            enabled = false;
        }
    }

    private static void writeEvent(String row) {
        try {
            String d = dayNow();
            if (evW == null || !d.equals(evDay)) {
                closeQuiet(evW);
                evDay = d;
                evW = open(new File(dir, "events_" + d + ".csv"), EVENT_HDR);
            }
            if (evW != null) {
                evW.write(row);
                evW.write('\n');
                evW.flush();
            }
            if (stateW != null) { stateW.flush(); pending = 0; }
        } catch (Throwable t) {
            enabled = false;
        }
    }

    private static BufferedWriter open(File f, String header) throws Exception {
        boolean fresh = !f.exists() || f.length() == 0;
        BufferedWriter w = new BufferedWriter(new FileWriter(f, true), 8192);
        if (fresh) { w.write(header); w.write('\n'); w.flush(); }
        return w;
    }

    private static void closeQuiet(BufferedWriter w) {
        if (w == null) return;
        try { w.flush(); w.close(); } catch (Throwable ignored) { }
    }

    public static void close() {
        if (!enabled) return;
        try {
            h.post(new Runnable() {
                @Override public void run() {
                    closeQuiet(stateW); closeQuiet(evW);
                    stateW = null; evW = null; pending = 0;
                }
            });
        } catch (Throwable ignored) { }
    }

    /** Гарантує, що все з буфера лягло на диск. Блокує викликача до 2 с. */
    public static void flushBlocking() {
        if (!enabled) return;
        final CountDownLatch l = new CountDownLatch(1);
        try {
            h.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (stateW != null) { stateW.flush(); pending = 0; }
                        if (evW != null) evW.flush();
                    } catch (Throwable ignored) { }
                    l.countDown();
                }
            });
            l.await(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------

    private static File[] list() {
        if (dir == null) return new File[0];
        File[] fs = dir.listFiles();
        return fs == null ? new File[0] : fs;
    }

    /** Видаляє файли, старші за KEEP_DAYS, і тримає теку в межах CAP_BYTES. */
    private static void prune() {
        try {
            File[] fs = list();
            long cutoff = System.currentTimeMillis() - KEEP_DAYS * 86400000L;
            long sum = 0;
            List<File> keep = new ArrayList<>();
            for (File f : fs) {
                if (f.lastModified() < cutoff) { f.delete(); continue; }
                keep.add(f);
                sum += f.length();
            }
            if (sum <= CAP_BYTES) return;
            keep.sort(new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : keep) {
                if (sum <= CAP_BYTES) break;
                long len = f.length();
                if (f.delete()) sum -= len;
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Пакує логи за останні days діб у Downloads/GnssFilter і повертає Uri,
     * придатний для передачі в інший застосунок через share sheet.
     * Викликати з фонового потоку.
     */
    public static Uri exportZip(Context c, int days) throws Exception {
        flushBlocking();
        long cutoff = System.currentTimeMillis() - days * 86400000L;
        List<File> pick = new ArrayList<>();
        for (File f : list())
            if (f.lastModified() >= cutoff && f.getName().endsWith(".csv")) pick.add(f);
        if (pick.isEmpty()) return null;
        pick.sort(new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        String name;
        synchronized (Logger.class) {
            name = "gnss-filter-logs-" + STAMP.format(new Date()) + ".zip";
        }

        ContentResolver r = c.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        cv.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/GnssFilter");
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) return null;

        try (OutputStream os = r.openOutputStream(u);
             ZipOutputStream z = new ZipOutputStream(os)) {
            byte[] buf = new byte[16384];
            for (File f : pick) {
                z.putNextEntry(new ZipEntry(f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
        cv.clear();
        cv.put(MediaStore.Downloads.IS_PENDING, 0);
        r.update(u, cv, null, null);
        return u;
    }

    /** Скільки різних діб є в теці — для підказки в UI. */
    public static int dayCount() {
        java.util.HashSet<String> days = new java.util.HashSet<>();
        for (File f : list()) {
            String n = f.getName();
            int i = n.indexOf('_');
            if (i > 0 && n.endsWith(".csv")) days.add(n.substring(i + 1, n.length() - 4));
        }
        return days.size();
    }
}
