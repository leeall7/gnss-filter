package com.gnssfilter;

/**
 * Мінімальний розбір NMEA-0183: GGA (позиція, якість, супутники, HDOP)
 * і RMC (швидкість, курс). Будь-який talker: GP, GN, GA, GL, GB, GQ.
 *
 * NMEA іде з приймача напряму, повз шар провайдерів Android, тому під
 * тестовим провайдером несе СПРАВЖНЮ позицію чіпа. Це дозволяє звіряти GPS
 * з мережею безперервно, не знімаючи мок.
 */
final class Nmea {

    static final class Fix {
        double lat, lon;
        int quality;        // GGA: 0 немає, 1 GPS, 2 DGPS, 4/5 RTK, 6 числення
        int sats;           // GGA: супутників у розв'язку
        float hdop = -1;
        float speedMps = -1;
        float course = -1;
        boolean rmcValid;
    }

    private Nmea() { }

    /** true, якщо рядок має правильну контрольну суму (або її немає — тоді довіряємо). */
    static boolean checksumOk(String s) {
        if (s == null || s.length() < 4 || s.charAt(0) != '$') return false;
        int star = s.indexOf('*');
        if (star < 0) return true;
        if (star + 3 > s.length()) return false;
        int x = 0;
        for (int i = 1; i < star; i++) x ^= s.charAt(i);
        try {
            int want = Integer.parseInt(s.substring(star + 1, star + 3), 16);
            return want == x;
        } catch (Throwable t) { return false; }
    }

    /** Тип речення без talker'а: "GGA", "RMC" або null. */
    static String type(String s) {
        if (s == null || s.length() < 7 || s.charAt(0) != '$') return null;
        int comma = s.indexOf(',');
        if (comma < 6) return null;
        return s.substring(comma - 3, comma);
    }

    /** "ddmm.mmmm" + півкуля -> градуси; NaN при помилці. */
    static double toDeg(String v, String hemi) {
        try {
            if (v == null || v.isEmpty()) return Double.NaN;
            int dot = v.indexOf('.');
            int degLen = (dot < 0 ? v.length() : dot) - 2;
            if (degLen < 1) return Double.NaN;
            double deg = Double.parseDouble(v.substring(0, degLen));
            double min = Double.parseDouble(v.substring(degLen));
            double d = deg + min / 60.0;
            if ("S".equals(hemi) || "W".equals(hemi)) d = -d;
            return d;
        } catch (Throwable t) { return Double.NaN; }
    }

    /** Оновлює fix з GGA. Повертає true, якщо є позиція з якістю > 0. */
    static boolean parseGGA(String s, Fix fix) {
        String body = s.indexOf('*') > 0 ? s.substring(0, s.indexOf('*')) : s;
        String[] f = body.split(",", -1);
        if (f.length < 10) return false;
        int q;
        try { q = f[6].isEmpty() ? 0 : Integer.parseInt(f[6]); } catch (Throwable t) { q = 0; }
        double la = toDeg(f[2], f[3]), lo = toDeg(f[4], f[5]);
        fix.quality = q;
        try { fix.sats = f[7].isEmpty() ? 0 : Integer.parseInt(f[7]); } catch (Throwable t) { fix.sats = 0; }
        try { fix.hdop = f[8].isEmpty() ? -1 : Float.parseFloat(f[8]); } catch (Throwable t) { fix.hdop = -1; }
        if (q <= 0 || Double.isNaN(la) || Double.isNaN(lo)) return false;
        fix.lat = la; fix.lon = lo;
        return true;
    }

    /** Оновлює швидкість/курс з RMC. Повертає true, якщо статус A. */
    static boolean parseRMC(String s, Fix fix) {
        String body = s.indexOf('*') > 0 ? s.substring(0, s.indexOf('*')) : s;
        String[] f = body.split(",", -1);
        if (f.length < 9) return false;
        fix.rmcValid = "A".equals(f[2]);
        if (!fix.rmcValid) return false;
        try { fix.speedMps = f[7].isEmpty() ? -1 : Float.parseFloat(f[7]) * 0.514444f; }
        catch (Throwable t) { fix.speedMps = -1; }
        try { fix.course = f[8].isEmpty() ? -1 : Float.parseFloat(f[8]); }
        catch (Throwable t) { fix.course = -1; }
        // позиція з RMC — запасна, якщо GGA не було
        if (fix.quality <= 0) {
            double la = toDeg(f[3], f[4]), lo = toDeg(f[5], f[6]);
            if (!Double.isNaN(la) && !Double.isNaN(lo)) { fix.lat = la; fix.lon = lo; fix.quality = 1; }
        }
        return true;
    }

    /** Оцінка точності з HDOP: UERE ~5 м, межі 3..100 м. */
    static float accuracy(Fix fix) {
        if (fix.hdop <= 0) return 15f;
        return Math.max(3f, Math.min(100f, fix.hdop * 5f));
    }
}
