package com.gnssfilter;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * ELM327 по Bluetooth Classic (SPP). Читає лише швидкість авто — PID 01 0D.
 *
 * Це джерело, якого немає більше ніде: незалежний від супутників доказ
 * руху. Спуфер веде на 60 км/год, колеса кажуть 0 — вирок миттєвий.
 *
 * Адаптер має бути спарений у системних налаштуваннях Bluetooth (PIN 1234
 * або 0000). Застосунок бере його зі списку спарених: або обраний вручну,
 * або перший, чиє ім'я схоже на OBD.
 *
 * Обрив зв'язку — не помилка, а буденність: перепідключення з наростаючою
 * паузою, і поки зв'язку немає — фільтр працює як без OBD.
 */
public final class Obd {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long POLL_MS = 250;
    /**
     * Лог 16.09: перші сесії тримались 471 і 460 с без жодного обриву, а після
     * перезапуску служби почався рваний цикл «20-26 с — обрив». Причина не в
     * адаптері: (1) будь-який збій читання рвав сокет, хоча клони ELM327
     * регулярно гублять одну відповідь; (2) ATZ на кожному перепідключенні —
     * апаратне скидання, яке саме по собі дестабілізує; (3) підключались
     * одразу після закриття, поки адаптер не звільнив RFCOMM-канал.
     */
    private static final int READ_RETRIES = 3;
    private static final long COOLDOWN_MS = 2000;
    private static final long FRESH_MS = 2000;
    private static final String[] NAME_HINTS =
            { "OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI", "CAR" };

    public static volatile boolean enabled = false;
    public static volatile String deviceAddr = "";      // "" = авто за ім'ям
    public static volatile String deviceName = "—";
    public static volatile float speedKmh = -1f;         // -1 = даних немає
    public static volatile long speedAt = 0;
    public static volatile String link = "вимкнено";
    public static volatile int reconnects = 0;

    /**
     * Кільцевий буфер вимірів із мітками часу. ELM327 відповідає із затримкою
     * 100-400 мс, а NMEA-фікс приходить зі своєю. Зіставляти «останню відому»
     * швидкість із поточним фіксом некоректно на розгоні й гальмуванні —
     * беремо той вимір, що ближчий за часом до самого фікса.
     */
    /**
     * Буфер має власний замок, а не монітор класу: інакше читання з головного
     * потоку (speedAtMs у вердикті) могло б чекати на start/stop, які торкаються
     * сокета. Тепер головний потік не блокується ніколи.
     */
    private static final Object RING_LOCK = new Object();
    private static final int RING = 40;
    private static final long[] tRing = new long[RING];
    private static final float[] vRing = new float[RING];
    private static int ringPos = 0, ringCnt = 0;

    private static Thread th;
    private static volatile boolean run = false;
    private static volatile BluetoothSocket sock;
    private static volatile long lastCloseAt = 0;
    private static volatile boolean everConnected = false;

    private Obd() { }

    /**
     * Свіжі дані є лише коли швидкість ДІЙСНА. Лог 16.09: при обриві speedKmh
     * ставало -1, а speedAt лишався свіжим — фільтр читав «колеса стоять» і
     * виносив хибний «фальшивий рух» при їзді на 32 км/год.
     */
    private static void push(float kmh, long t) {
        synchronized (RING_LOCK) {
            tRing[ringPos] = t;
            vRing[ringPos] = kmh;
            ringPos = (ringPos + 1) % RING;
            if (ringCnt < RING) ringCnt++;
        }
    }

    /** Швидкість, виміряна найближче до моменту t. -1, якщо немає в межах tol. */
    public static float speedAtMs(long t, long tolMs) {
        synchronized (RING_LOCK) {
            float best = -1f;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < ringCnt; i++) {
                long d = Math.abs(tRing[i] - t);
                if (d < bestD) { bestD = d; best = vRing[i]; }
            }
            return bestD <= tolMs ? best : -1f;
        }
    }

    /** Вік останнього виміру, мс; -1 якщо вимірів немає. */
    public static long ageMs() {
        return speedAt > 0 ? SystemClock.elapsedRealtime() - speedAt : -1;
    }

    public static void clearRing() {
        synchronized (RING_LOCK) { ringCnt = 0; ringPos = 0; }
    }

    public static boolean fresh() {
        return speedKmh >= 0 && speedAt > 0
                && SystemClock.elapsedRealtime() - speedAt < FRESH_MS;
    }

    /** Швидкість у м/с або -1. */
    public static float speedMps() {
        return fresh() ? speedKmh / 3.6f : -1f;
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Спарені пристрої: імена й адреси, для вибору в UI. */
    public static List<String[]> bonded(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            if (!hasPermission(c)) return out;
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) return out;
            Set<BluetoothDevice> set = ad.getBondedDevices();
            if (set == null) return out;
            for (BluetoothDevice d : set) {
                String n = d.getName();
                out.add(new String[]{ n == null ? "?" : n, d.getAddress() });
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static synchronized void start(final Context c) {
        if (run) return;
        run = true;
        speedKmh = -1f; speedAt = 0; reconnects = 0;
        link = "запуск";
        th = new Thread(new Runnable() {
            @Override public void run() { loop(c.getApplicationContext()); }
        }, "obd");
        th.setDaemon(true);
        th.start();
    }

    public static synchronized void stop() {
        run = false;
        // Закриття сокета може заблокувати виклик — головний потік цього не робить.
        final BluetoothSocket s = sock;
        sock = null;
        if (s != null) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try { s.close(); } catch (Throwable ignored) { }
                }
            }, "obd-close");
            t.setDaemon(true);
            t.start();
        }
        lastCloseAt = SystemClock.elapsedRealtime();
        link = "вимкнено";
        speedKmh = -1f; speedAt = 0;
        clearRing();
        th = null;
    }

    private static void closeQuiet() {
        try { BluetoothSocket s = sock; sock = null; if (s != null) s.close(); }
        catch (Throwable ignored) { }
    }

    private static BluetoothDevice pick(Context c, BluetoothAdapter ad) {
        try {
            Set<BluetoothDevice> set = ad.getBondedDevices();
            if (set == null) return null;
            if (!deviceAddr.isEmpty()) {
                for (BluetoothDevice d : set)
                    if (deviceAddr.equalsIgnoreCase(d.getAddress())) return d;
            }
            for (BluetoothDevice d : set) {
                String n = d.getName();
                if (n == null) continue;
                String u = n.toUpperCase(Locale.US);
                for (String h : NAME_HINTS) if (u.contains(h)) return d;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void loop(Context c) {
        long backoff = 2000;
        while (run) {
            if (!hasPermission(c)) {
                link = "немає дозволу Bluetooth";
                sleep(5000);
                continue;
            }
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) {
                link = "Bluetooth вимкнено";
                sleep(5000);
                continue;
            }
            // Витримка після закриття: адаптер має звільнити RFCOMM-канал,
            // інакше наступне підключення сиплеться «Broken pipe».
            long since = SystemClock.elapsedRealtime() - lastCloseAt;
            if (lastCloseAt > 0 && since < COOLDOWN_MS) sleep(COOLDOWN_MS - since);

            BluetoothDevice dev = pick(c, ad);
            if (dev == null) {
                link = "адаптер не знайдено серед спарених";
                sleep(5000);
                continue;
            }
            try { deviceName = dev.getName(); } catch (Throwable ignored) { }

            try {
                link = "підключення до " + deviceName;
                try { ad.cancelDiscovery(); } catch (Throwable ignored) { }
                BluetoothSocket s;
                try {
                    s = dev.createRfcommSocketToServiceRecord(SPP);
                    s.connect();
                } catch (Throwable first) {
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                    s.connect();
                }
                sock = s;
                InputStream in = s.getInputStream();
                OutputStream os = s.getOutputStream();

                link = "ініціалізація";
                // ATZ — тільки при першому підключенні. На перепідключенні
                // адаптер уже налаштований, а скидання лише дестабілізує.
                if (!everConnected) cmd(os, in, "ATZ", 3000);
                cmd(os, in, "ATE0", 1500);   // без луни
                cmd(os, in, "ATL0", 1500);   // без переведення рядка
                cmd(os, in, "ATS0", 1500);   // без пробілів
                cmd(os, in, "ATH0", 1500);   // без заголовків
                cmd(os, in, "ATSP0", 3000);  // протокол авто
                cmd(os, in, "0100", 6000);   // перший запит будить ЕБК і обирає протокол

                link = "з'єднано";
                everConnected = true;
                backoff = 2000;
                int misses = 0, readFails = 0;
                while (run) {
                    long t0 = SystemClock.elapsedRealtime();
                    String r;
                    try {
                        r = cmd(os, in, "010D", 1500);
                        readFails = 0;
                    } catch (Throwable readErr) {
                        // Поодинокий збій читання — не привід рвати з'єднання.
                        if (++readFails >= READ_RETRIES) throw readErr;
                        link = "збій читання " + readFails + "/" + READ_RETRIES;
                        sleep(200);
                        continue;
                    }
                    int v = parseSpeed(r);
                    if (v >= 0) {
                        speedKmh = v;
                        // Мітка — середина інтервалу «запит-відповідь»: саме там
                        // ЕБК віддав значення, а не коли ми його дочитали.
                        long mid = t0 + (SystemClock.elapsedRealtime() - t0) / 2;
                        speedAt = SystemClock.elapsedRealtime();
                        push(v, mid);
                        misses = 0;
                        link = "з'єднано";
                    } else if (++misses > 8) {
                        // ЕБК мовчить (запалювання вимкнено?) — не рвемо зв'язок, але
                        // швидкість уже не свіжа: фільтр сам перейде на акселерометр.
                        link = "без даних (запалювання?)";
                    }
                    long dt = SystemClock.elapsedRealtime() - t0;
                    if (dt < POLL_MS) sleep(POLL_MS - dt);
                }
            } catch (Throwable t) {
                link = "обрив: " + short_(t);
                reconnects++;
            } finally {
                closeQuiet();
                lastCloseAt = SystemClock.elapsedRealtime();
            }
            speedKmh = -1f;
            speedAt = 0;          // дані більше не свіжі — жодних «колеса стоять»
            clearRing();
            sleep(backoff);
            backoff = Math.min(30000, backoff * 2);
        }
    }

    private static String short_(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    /** Надсилає команду й читає до промпта '>' або таймауту. */
    private static String cmd(OutputStream os, InputStream in, String c, long timeoutMs)
            throws Exception {
        os.write((c + "\r").getBytes("US-ASCII"));
        os.flush();
        StringBuilder b = new StringBuilder();
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        byte[] buf = new byte[128];
        while (SystemClock.elapsedRealtime() < deadline) {
            if (in.available() > 0) {
                int n = in.read(buf);
                for (int i = 0; i < n; i++) {
                    char ch = (char) (buf[i] & 0xFF);
                    if (ch == '>') return b.toString();
                    b.append(ch);
                }
            } else {
                sleep(20);
            }
        }
        return b.toString();
    }

    /**
     * "410D2A" -> 0x2A = 42 км/год. Терпить "SEARCHING...", пробіли, CR/LF,
     * повтори кадру. Повертає -1, якщо кадру немає.
     */
    static int parseSpeed(String resp) {
        if (resp == null) return -1;
        String s = resp.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        int i = s.indexOf("410D");
        if (i < 0 || i + 6 > s.length()) return -1;
        try { return Integer.parseInt(s.substring(i + 4, i + 6), 16); }
        catch (Throwable t) { return -1; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
