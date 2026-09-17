package com.gnssfilter;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GNSS Filter v5.
 *
 * Слідкуємо за ВСІМА джерелами одночасно — GPS, fused, network — і на кожному
 * циклі вирішуємо, якому можна довіряти. Назовні віддаємо один результат.
 *
 * Два різні напади розрізняються явно:
 *   ПРИДУШЕННЯ (тип 1) — фікса немає, usedInFix падає в нуль.
 *   ПІДМІНА   (тип 2) — фікс Є і виглядає впевнено, але бреше. Ловиться за
 *                        сукупністю ознак, головна з яких — розходження з
 *                        мережевою позицією, яку підмінити набагато важче.
 *
 * Ознаки підміни (потрібно щонайменше дві, або одна критична):
 *   1. Розходження GPS і мережі більше за суму їхніх похибок із запасом.
 *   2. Стрибок GPS-позиції з неможливою швидкістю.
 *   3. Вироджені азимут/елевація при сильному сигналі — приймач бачить
 *      потужні сигнали, але не може розмістити їх на небі.
 *   4. Аномально рівний C/N0 по всіх супутниках: передавач жарить усі канали
 *      однаково, реальне небо дає розкид за елевацією.
 *   5. Просідання AGC відносно адаптивної базової лінії.
 *   6. Розбіжність часу GPS і системного.
 */
public class FilterService extends Service {

    public static final String VER = "9.4";
    public static final String CH_ID = "gnssfilter";
    public static final int NOTIF_ID = 1;

    public static final String FUSED_PROVIDER = "fused";

    public static final String S_WARMUP = "WARMUP";
    public static final String S_GREEN  = "GREEN";
    public static final String S_YELLOW = "YELLOW";
    public static final String S_ORANGE = "ORANGE";
    public static final String S_RED    = "RED";

    public static final int C_GREEN  = 0xFF2E7D32;
    public static final int C_YELLOW = 0xFFF9A825;
    public static final int C_ORANGE = 0xFFEF6C00;
    public static final int C_RED    = 0xFFC62828;
    public static final int C_GREY   = 0xFF757575;

    // ---- здоров'я GNSS ----
    /**
     * Супутників у розв'язку, нижче якого фікс не вважається повноцінним.
     * Польові дані SM-S948B: чисте небо дає 25-26, деградація 0-4.
     */
    private static final int MIN_USED = 6;
    /**
     * Поріг ВТРАТИ довіри нижчий за поріг набуття: гістерезис. Польовий лог
     * 12.09: 23 втрати довіри, з них 9 при usedInFix 5-7 — міське хитання
     * біля порога, кожне з установкою й зняттям моку.
     */
    private static final int MIN_USED_DROP = 4;
    /** Під час проби фікс має бути ще й точним, інакше 6 супутників — замало. */
    private static final float PROBE_MAX_ACC = 40f;
    /** Циклів поспіль поганого GPS до виходу з довіри. */
    private static final int DEAD_STREAK = 3;
    /** Циклів поспіль доброго GPS до повернення довіри. */
    private static final int ALIVE_STREAK = 5;
    /** Те саме після виявленої підміни: спуфер не зникає за п'ять секунд. */
    private static final int ALIVE_STREAK_SPOOF = 30;
    /** Стільки чистих ПОВНИХ перевірок (без моку) знімають латч підміни. */
    private static final int SPOOF_CLEAR_STREAK = 10;
    private static final long MIN_DWELL_MS = 5000;
    private static final long GPS_TIMEOUT_MS = 8000;
    private static final long WARMUP_MS = 10000;

    // ---- ознаки підміни ----
    /** Частка супутників з нульовими азимутом і елевацією при C/N0 вище порога. */
    /** На чистому небі 37-42% супутників не мають місця на небі — це норма. */
    private static final float DEGEN_RATIO = 0.65f;
    private static final float DEGEN_CN0 = 25f;
    // Розкид C/N0 як ознака підміни не працює: 5,4 дБ під РЕБ проти 4,4-8,5 дБ
    // на чистому небі. Метрику лишаємо в логах, з вироку прибрано.
    /** Просідання AGC відносно адаптивної базової лінії, дБ. */
    private static final float AGC_DROP = 10f;
    // ---- мережа як якір ----
    /**
     * Зона мережі — двоярусна. 4363 пари справжнього GPS проти мережі (S26):
     * точна мережа (≤300 м) бреше не більш ніж у 4×, груба — непередбачувано
     * (Note 20, 15:30: заявлені 500 м, реальні 4 км).
     */
    private static final float NET_PRECISE_ACC = 300f;
    private static final float ZONE_K = 3f, ZONE_MARGIN = 500f;             // точна
    private static final float ZONE_COARSE_K = 4f, ZONE_COARSE_MARGIN = 5000f; // груба
    /** Вихід за зону, з якого вирок виноситься одразу, без другого голосу. */
    private static final float ZONE_CRIT_MIN = 5000f;
    /** Мережа гірша за це — якорем бути не може. */
    private static final float NET_ANCHOR_MAX_ACC = 3000f;
    /**
     * Проба під латчем — три яруси за тим, хто може підтвердити GPS.
     *
     * Інтервали короткі навмисно. Проба обривається на ПЕРШІЙ ознаці, тому
     * невдала проба під активним спуфером коштує 1-2 с впливу, а не повної
     * своєї тривалості. Довгий інтервал економив ці секунди, але після
     * відбою тримав нас наосліп до п'яти хвилин — асиметрія не на користь.
     */
    private static final long PROBE_INT_PRECISE_MS = 20000, PROBE_INT_COARSE_MS = 40000,
            PROBE_INT_BLIND_MS = 60000;
    /** Після кількох поспіль невдалих проб інтервал росте: спуфер нікуди не подівся. */
    private static final int PROBE_BACKOFF_AFTER = 3;
    private static final long PROBE_INT_MAX_MS = 180000;
    private static final int STREAK_PRECISE = 20, STREAK_COARSE = 40, STREAK_BLIND = 60;
    private static final long PROBE_MAX_MS = 25000, PROBE_MAX_COARSE_MS = 50000,
            PROBE_MAX_BLIND_MS = 75000;
    /**
     * Мінімальна пауза між пробами — ЗАВЖДИ, не лише під латчем. Без неї
     * «мертвий статус» відкривав пробу щоп'ять секунд, і на Note 20 мок був
     * знятий 86% часу (прогін логів 15.09). Кожна невдала проба подвоює паузу.
     */
    private static final long PROBE_MIN_GAP_MS = 30000;
    /** Скільки чекаємо, поки приймач прокинеться, якщо він не подає ознак життя. */
    private static final long PROBE_WAKE_MS = 12000;
    // ---- NMEA: справжня позиція чіпа під моком ----
    /** NMEA-фікс, старший за це, не рахуємо. */
    private static final long NMEA_FRESH_MS = 3000;
    /** Без GSV із рівнями сигналу стільки часу потік вважаємо синтетичним. */
    private static final long GSV_SILENCE_MS = 60000;
    /** Петля: NMEA повторює НАШУ видану позицію ближче за це, стільки разів. */
    private static final float LOOP_TOL_M = 2f;
    private static final int LOOP_STREAK = 10;
    /** ...і при цьому наш вихід за цей час пройшов понад стільки метрів. */
    private static final float LOOP_MIN_PATH_M = 50f;
    /** Допуск на зіставлення OBD-виміру з моментом GPS-фікса. */
    private static final long OBD_MATCH_TOL_MS = 1500;
    // ---- статус супутників голодує під моком ----
    /**
     * Note 20 / Android 13: із тестовим провайдером GnssStatus звітує 0-1
     * видимих супутників п'ять годин поспіль, хоч вимірювання йдуть. Гейт
     * «usedInFix ≥ 6» тоді не відкривається ніколи. Резерв: сирі вимірювання,
     * а якщо й вони мовчать — проба за розкладом.
     */
    private static final int STARVED_VIS = 2;
    private static final int STARVED_AFTER_S = 30;
    private static final int STARVED_TOW_MIN = 4;
    private static final int STARVED_FORCE_AFTER_S = 120;
    /**
     * Без латча підміни — проба не рідше ніж раз на стільки. Лог 15.09 (S26,
     * річка): після завади приймач під моком згасав 25 хв (видимих 44 → 1), а
     * гейт «6 у розв'язку» чекав його вічно. Проба знімає мок і будить приймач;
     * під придушенням сирий GPS — це порожнеча, навігатор нічого не втрачає.
     */
    private static final long PROBE_MAX_GAP_MS = 180000;
    // ---- фальшивий рух: акселерометр каже «стоїмо», GPS каже «їдемо» ----
    private static final int FAKE_MOTION_VOTE_S = 5;
    private static final int FAKE_MOTION_CRIT_S = 10;
    /**
     * Лог 16.09, 18:06:51: машина рушала, GPS чесно показав 8 км/год, а
     * акселерометр ще не перетнув поріг і мережевий фікс не оновився — вийшов
     * хибний вирок «підміна» на 21 секунду. Поріг піднято з 2 до 5 м/с (18
     * км/год): на рушанні це ще неоднозначно, а спуфер «везе» помітно швидше.
     */
    private static final float FAKE_MOTION_SPEED = 5f;
    /** Скільки секунд суперечність має протриматись, перш ніж стати голосом. */
    private static final int FAKE_MOTION_FLAG_S = 5;
    private static final int FAKE_MOTION_FLAG_OBD_S = 3;
    // ---- OBD: швидкість з коліс проти швидкості GPS ----
    /** Розбіжність GPS і OBD, яку вважаємо значущою: більше з двох. */
    private static final float OBD_MISMATCH_KMH = 15f;
    private static final float OBD_MISMATCH_FRAC = 0.30f;
    private static final int OBD_MISMATCH_VOTE_S = 10, OBD_MISMATCH_CRIT_S = 20;
    /** Стоїмо за OBD = менше цього. */
    private static final float OBD_STILL_KMH = 2f;
    // ---- гіроскоп: курс між мережевими фіксами ----
    /** Наскільки мережевий вектор підправляє курс гіроскопа (дрейф ~1°/хв). */
    private static final double HDG_NET_BLEND = 0.2;
    // ---- опорні точки: числення від того, чому ми вірили ----
    /**
     * Опорою стає лише ДОСТАТНЬО ТОЧНА точка: довірений GPS або мережа,
     * краща за це. Гірші фікси не тягнуть позицію взагалі — саме вони
     * перекидали нас на сотні метрів (лог 16.09: 20 стрибків понад 200 м).
     */
    private static final float ANCHOR_ACC_M = 60f;
    /** Скільки метрів шляху додає до невизначеності кожен метр числення. */
    private static final float DR_SCALE_OBD = 0.02f;    // колеса: 1-2%
    private static final float DR_SCALE_NOSPD = 0.30f;  // швидкість з опор: ~30%
    /** Дрейф курсу гіроскопа, градусів за секунду. */
    private static final float DR_HDG_DRIFT_DPS = 0.02f;
    /**
     * v9.4: раніше числення визнавалось втраченим по годиннику (120с без
     * опори) — незалежно від того, наскільки чистим лишалось саме числення.
     * На трасі/мосту з OBD похибка за 120с типово ще ~60-70м — рано здаватись.
     * У заторі під РЕБ час іде, а похибка від відстані — ні; годинник у
     * такому разі здавався б без причини. Тому стеля тепер по накопиченій
     * невизначеності, не по часу: те саме порогове значення, після якого і
     * груба мережа вже вважається непридатною (NET_ANCHOR_MAX_ACC) — узгоджено
     * з рештою логіки, не нове довільне число. У місті ніколи не сягається
     * (мережеві фікси частіші), крім одночасного blackout+РЕБ.
     */
    private static final float DR_MAX_SIG_M = NET_ANCHOR_MAX_ACC;
    /**
     * v9.4: плаский доданок невизначеності за сам факт часу (страховка на
     * повзання, яке гістерезис руху міг не помітити). Коли стоїмо і це
     * підтверджено саме OBD (колеса на нулі, не здогад з акселерометра) —
     * повзти нікуди, тож доданок майже нульовий. Інакше — як і було.
     */
    private static final float DR_BASE_MPS = 0.5f;
    private static final float DR_BASE_STILL_OBD_MPS = 0.05f;
    /** Мережа наполегливо не сходиться стільки разів — перезапуск опори. */
    private static final int DR_DISAGREE = 4;

    // ---- груба мережа: фікс — слабка прив'язка, рух між фіксами — наш ----
    /** Стрибок понад стільки сумарних сигм — підозрілий, чекає підтвердження. */
    private static final float COARSE_GATE_SIG = 3f;
    /** Без жодного злиття стільки мс — приймаємо стрибок: ми, мабуть, переїхали. */
    private static final long COARSE_STALE_MS = 30000;
    /** Ріст невизначеності: базовий + частка пройденого шляху. */
    private static final float COARSE_GROW_MPS = 2f;
    private static final float COARSE_GROW_FRAC = 0.3f;
    private static final float COARSE_SIG_MIN = 50f;
    /** Швидкість із довіреного GPS придатна для передбачення стільки мс. */
    private static final long LAST_SPEED_TTL_MS = 120000;
    // ---- утримання без мережі: мок не знімаємо, радіус чесно росте ----
    private static final float HOLD_GROWTH_MPS = 5f;
    private static final float HOLD_MAX_ACC = 5000f;
    /** Розбіжність часу GPS і системного, мс. */
    private static final long TIME_SKEW_MS = 15000;

    // ---- мережевий режим ----
    private static final float MAX_SPEED_MPS = 70f;
    private static final long MAX_NET_AGE_MS = 30000;
    /**
     * Свіжість для ВИБОРУ джерела. Польовий лог: застарілий fused (під моком
     * він не оновлюється) з кращою точністю блокував свіжий network 13 разів.
     */
    private static final long ALT_FRESH_MS = 10000;
    /** Стільки поспіль «сильних» циклів потрібно, щоб із жовтого повернутись у зелений. */
    private static final int STRONG_STREAK = 5;
    /**
     * Максимум екстраполяції. Польові дані: пауза NLP до 10 с, FLP до 7,8 с,
     * тому 5 с давали б хибний червоний на рівному місці.
     */
    private static final long MAX_EXTRAP_MS = 12000;
    private static final long RED_GRACE_MS = 2000;
    private static final float MIN_MOVE_MPS = 1.5f;
    private static final float ACC_GROWTH_MPS = 2f;
    private static final double V_ALPHA = 0.45;
    /** Гасіння швидкості на кожному повторі тієї самої координати. */
    private static final double REPEAT_DECAY = 0.7;
    /** Похибка, з якою віддається остання точка перед зняттям моку. */
    private static final float BAILOUT_ACC = 2000f;
    /** Період публікації у mock. Навігатори помітно чутливі до частоти. */
    private static final long PUMP_MS = 250;

    private static final double M_PER_DEG = 111320.0;

    // ---- детектор руху за акселерометром ----
    /** Розкид модуля прискорення (м/с²), вище якого — точно їдемо. */
    private static final float MOVE_ON = 0.35f;
    /** Нижче якого — точно стоїмо. Між ними стан не змінюється (гістерезис). */
    private static final float MOVE_OFF = 0.15f;
    /** Стільки поспіль «тихих» циклів (1 Гц) потрібно, щоб визнати зупинку. */
    private static final int STILL_STREAK = 3;
    /** Згладжування позиції на місці: мережеві стрибки усереднюються. */
    private static final double STILL_ALPHA = 0.3;

    // ---- стан для UI ----
    public static volatile boolean running = false;
    public static volatile String state = "—";
    public static volatile String reason = "—";
    public static volatile String source = "—";
    public static volatile String inSrc = "—";
    public static volatile int usedInFix = 0;
    public static volatile int visible = 0;
    public static volatile float cn0Top = 0;
    public static volatile float cn0Sd = 0;
    public static volatile float degenRatio = 0;
    public static volatile int towValid = 0;
    public static volatile String spoofFlags = "—";
    public static volatile float divergence = -1;
    public static volatile double lat = 0, lon = 0;
    public static volatile float acc = 0;
    public static volatile float speedMps = 0;
    public static volatile float bearingDeg = -1;
    public static volatile long extrapMs = 0;
    public static volatile int rejected = 0;
    public static volatile String lastReject = "—";
    public static volatile boolean mockActive = false;
    public static volatile String mockedProviders = "—";
    public static volatile String mockError = null;
    public static volatile Map<String, Float> agcDelta = new HashMap<>();
    public static volatile boolean agcBaseKnown = false;

    public static volatile float accStd = 0;
    public static volatile boolean moving = false;
    /** Курс: абсолютний, якщо hdgAbs, інакше — відносний поворот від старту. */
    public static volatile float hdgDeg = -1;
    public static volatile boolean hdgAbs = false;
    public static volatile String motionSrc = "—";   // "obd" або "accel"

    public static volatile int accThreshold = 150;
    public static volatile boolean mockFused = false;
    public static volatile boolean showDot = true;
    public static volatile boolean vibrate = true;

    private LocationManager lm;
    private Handler h;
    private PowerManager.WakeLock wl;

    private int badStreak = 0, goodStreak = 0;
    private boolean gpsTrusted = true;
    private boolean warm = true;
    private long startedAt = 0, lastStateChangeAt = 0, redSince = 0;
    private boolean fusedFailed = false;
    private String prevState = "";
    /** Після вироку «підміна» повернення довіри вимагає довшої серії. */
    private boolean spoofLatch = false;
    private int strongStreak = 0;
    private boolean probing = false;
    private long probeStart = 0, lastProbeEnd = 0;
    private int probeFails = 0;
    private long stillSince = 0;
    private int fakeMotion = 0;
    private int obdMismatch = 0;
    private long obdStillSince = 0;
    private long lastEmitWall = 0;
    // опора числення
    private double drLat = 0, drLon = 0;
    private float drSig = 0;
    private boolean drValid = false;
    private long drAt = 0, drAnchorAt = 0;
    private float drSpeed = 0;          // м/с, остання оцінка
    private int drDisagree = 0;
    private Location drLastAnchor;
    public static volatile String drSrc = "—";
    public static volatile float drSig0 = 0;
    private int starvedStreak = 0;
    private boolean starvedLogged = false;
    private final Nmea.Fix nmea = new Nmea.Fix();
    private Location nmeaLoc;
    private long nmeaAt = 0;
    private long lastGsvAt = 0;
    private int loopStreak = 0;
    private double loopFromLat = 0, loopFromLon = 0;
    public static volatile boolean nmeaTrusted = true;
    public static volatile String nmeaWhy = "—";
    public static volatile String gpsSrc = "—";     // "fix" (провайдер) або "nmea"
    public static volatile int nmeaSats = 0;
    // два останні мережеві фікси — свідок нерухомості для «фальшивого руху»
    private Location netPrev, netCur;
    private long netPrevAt = 0, netCurAt = 0;
    // гравітація (НЧ-фільтр акселерометра) — вісь, навколо якої рахуємо поворот
    private float gLx = 0, gLy = 0, gLz = 9.8f;
    /** Сирий інтеграл повороту навколо вертикалі. Рахується ЗАВЖДИ з першої події. */
    private double rawYaw = 0;
    /** Зсув до справжнього курсу, коли його вдалося дізнатись. */
    private double hdgOffset = 0;
    private boolean hdgValid = false;   // маємо абсолютний курс
    private boolean gyroSeen = false;
    private long gyroTs = 0;
    private boolean lastCrit = false;
    private boolean precise = false;
    // оцінка в грубому режимі: позиція + сигма, передбачення + корекція
    private double cLat = 0, cLon = 0;
    private float cSig = 0;
    private boolean cValid = false;
    private long cAt = 0, cPredAt = 0;
    private Location cLastRaw, cPending;
    private float lastSpeed = 0;
    private long lastSpeedAt = 0;
    private long mockDeniedAt = 0, mockDeniedBuzzAt = 0;
    public static volatile boolean mockDenied = false;
    private double holdLat = 0, holdLon = 0;
    private float holdAcc = 0;
    private boolean hasHold = false;
    private long holdAt = 0;

    private final LinkedHashSet<String> mocked = new LinkedHashSet<>();
    private final Map<String, Icon> iconCache = new HashMap<>();

    // джерела
    private Location gpsRaw, netRaw, fusRaw, prevGps;
    private long gpsAt = 0;

    // опорна точка мережевого режиму
    private Location ref;
    private long refAt = 0;
    private double vE = 0, vN = 0;
    private double outLat = 0, outLon = 0;
    private boolean hasOut = false, emitted = false;
    private double emLat = 0, emLon = 0;
    private float emAcc = 0, emSpd = 0, emBrg = -1;
    private int rejectCode = 0;

    // AGC: базова лінія вчиться ЛИШЕ коли GPS у довірі й зберігається між
    // запусками. Інакше запуск усередині зони РЕБ зробив би базовою саму заваду.
    private static final int AGC_RING = 300;
    private static final int AGC_MIN_SAMPLES = 30;
    private static final String PREFS = "gnssfilter";
    private final Map<String, float[]> agcRing = new HashMap<>();
    private final Map<String, Integer> agcPos = new HashMap<>();
    private final Map<String, Float> agcNow = new HashMap<>();
    private final Map<String, Float> agcBase = new HashMap<>();
    private long agcSavedAt = 0;

    private View dot;
    private GradientDrawable dotBg;

    private SensorManager sm;
    private final float[] accWin = new float[48];
    private int accIdx = 0, accCnt = 0;
    private int stillStreak = 0;
    private double stillLat = 0, stillLon = 0;
    private boolean hasStill = false;

    /**
     * Модуль прискорення не залежить від орієнтації телефона, а сила тяжіння
     * в розкиді скорочується. Стояча машина з двигуном дає ~0,05-0,15 м/с²,
     * рух по місту — 0,3-1,0. Саме це розрізняє «стоїмо» від стрибків мережі.
     */
    private final SensorEventListener accL = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            gLx = 0.9f * gLx + 0.1f * x;
            gLy = 0.9f * gLy + 0.1f * y;
            gLz = 0.9f * gLz + 0.1f * z;
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            accWin[accIdx] = mag;
            accIdx = (accIdx + 1) % accWin.length;
            if (accCnt < accWin.length) accCnt++;
            if (accCnt < 16) return;
            float mean = 0;
            for (int i = 0; i < accCnt; i++) mean += accWin[i];
            mean /= accCnt;
            float var = 0;
            for (int i = 0; i < accCnt; i++) var += (accWin[i] - mean) * (accWin[i] - mean);
            accStd = (float) Math.sqrt(var / accCnt);
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    /**
     * Курс із гіроскопа. Інтегрується ОДИН раз (кутова швидкість -> кут), тому
     * поворот на 90° лишається 90° і через хвилину; дрейф близько 1°/хв.
     * Проєктуємо кутову швидкість на вісь тяжіння — так орієнтація телефона
     * в тримачі не має значення. Знак: у Android додатне обертання проти
     * годинникової стрілки, компасний курс росте за годинниковою.
     */
    private final SensorEventListener gyroL = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            long ts = e.timestamp;
            if (gyroTs == 0) { gyroTs = ts; return; }
            double dt = (ts - gyroTs) / 1e9;
            gyroTs = ts;
            if (dt <= 0 || dt > 0.5) return;
            double gm = Math.sqrt(gLx * gLx + gLy * gLy + gLz * gLz);
            if (gm < 1) return;
            double yaw = (e.values[0] * gLx + e.values[1] * gLy + e.values[2] * gLz) / gm;
            // Інтегруємо ЗАВЖДИ, ще до того як дізнаємось справжній курс: так
            // гіроскоп придатний для виявлення поворотів із першої секунди,
            // а його знак можна перевірити, не рушаючи з місця.
            rawYaw -= Math.toDegrees(yaw * dt);
            while (rawYaw < 0) rawYaw += 360;
            while (rawYaw >= 360) rawYaw -= 360;
            gyroSeen = true;
            hdgDeg = (float) heading();
            hdgAbs = hdgValid;
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    /** Поточний курс: сирий інтеграл плюс зсув до істини. */
    private double heading() {
        double h = rawYaw + hdgOffset;
        while (h < 0) h += 360;
        while (h >= 360) h -= 360;
        return h;
    }

    private void seedHeading(float bearing) {
        hdgOffset = bearing - rawYaw;
        hdgValid = true;
        hdgAbs = true;
        hdgDeg = (float) heading();
    }

    private void blendHeading(float bearing) {
        if (!hdgValid) { seedHeading(bearing); return; }
        double d = bearing - heading();
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        hdgOffset += HDG_NET_BLEND * d;   // дрейф гіроскопа гасимо зсувом
        hdgDeg = (float) heading();
    }

    /** Гістерезис руху, раз на секунду з step(). OBD має пріоритет над акселерометром. */
    private void updateMotion() {
        if (Obd.fresh()) {
            motionSrc = "obd";
            boolean mv = Obd.speedKmh >= OBD_STILL_KMH;
            if (mv) {
                stillStreak = 0; stillSince = 0;
                if (!moving) { moving = true; hasStill = false; }
            } else {
                if (moving) { moving = false; vE = 0; vN = 0; hasStill = false; }
                if (stillSince == 0) stillSince = SystemClock.elapsedRealtime();
            }
            return;
        }
        motionSrc = "accel";
        float sd = accStd;
        if (accCnt < 16) return;                 // датчик ще не набрав вікно
        if (sd > MOVE_ON) {
            stillStreak = 0;
            stillSince = 0;
            if (!moving) { moving = true; hasStill = false; }
        } else if (sd < MOVE_OFF) {
            if (++stillStreak >= STILL_STREAK && moving) {
                moving = false;
                vE = 0; vN = 0;                  // стоїмо — рух не екстраполюємо
                hasStill = false;
            }
            if (!moving && stillSince == 0) stillSince = SystemClock.elapsedRealtime();
        } else {
            stillStreak = 0;
        }
    }

    // ------------------------------------------------------------------

    private static boolean isMock(Location l) {
        if (l == null) return false;
        if (Build.VERSION.SDK_INT >= 31) return l.isMock();
        return l.isFromMockProvider();
    }

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L;
    }

    private static String band(double hz) {
        double f = hz / 1e6;
        if (f > 1174 && f < 1179) return "1176";
        if (f > 1559 && f < 1564) return "1561";
        if (f > 1574 && f < 1577) return "1575";
        if (f > 1598 && f < 1607) return "1602";
        return null;
    }

    // ---- супутники: головне джерело здоров'я GNSS ----

    private final GnssStatus.Callback statusCb = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus s) {
            int n = s.getSatelliteCount();
            int used = 0, degen = 0, strong = 0;
            float[] cn = new float[n];
            int cnN = 0;
            for (int i = 0; i < n; i++) {
                if (s.usedInFix(i)) used++;
                float c = s.getCn0DbHz(i);
                if (c > 0) cn[cnN++] = c;
                if (c >= DEGEN_CN0) {
                    strong++;
                    // Сильний сигнал без місця на небі — приймач його не прив'язав.
                    if (s.getAzimuthDegrees(i) == 0f && s.getElevationDegrees(i) == 0f) degen++;
                }
            }
            usedInFix = used;
            visible = n;
            degenRatio = strong > 0 ? (float) degen / strong : 0f;

            if (cnN > 0) {
                float[] c = Arrays.copyOf(cn, cnN);
                Arrays.sort(c);
                int k = Math.min(4, cnN);
                float sum = 0;
                for (int i = 0; i < k; i++) sum += c[cnN - 1 - i];
                cn0Top = sum / k;
                float mean = 0;
                for (int i = 0; i < cnN; i++) mean += c[i];
                mean /= cnN;
                float var = 0;
                for (int i = 0; i < cnN; i++) var += (c[i] - mean) * (c[i] - mean);
                cn0Sd = (float) Math.sqrt(var / cnN);
            } else {
                cn0Top = 0;
                cn0Sd = 0;
            }
        }
    };

    /** Вимірювання потрібні лише заради AGC і лічильника декодованого часу. */
    private final GnssMeasurementsEvent.Callback measCb = new GnssMeasurementsEvent.Callback() {
        @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent e) {
            int tow = 0;
            Map<String, Float> legacy = new HashMap<>();
            for (GnssMeasurement m : e.getMeasurements()) {
                if ((m.getState() & GnssMeasurement.STATE_TOW_DECODED) != 0) tow++;
                if (m.hasAutomaticGainControlLevelDb() && m.hasCarrierFrequencyHz()) {
                    String b = band(m.getCarrierFrequencyHz());
                    if (b != null) legacy.put(b, (float) m.getAutomaticGainControlLevelDb());
                }
            }
            towValid = tow;

            Map<String, Float> modern = new HashMap<>();
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    for (android.location.GnssAutomaticGainControl g
                            : e.getGnssAutomaticGainControls()) {
                        String b = band(g.getCarrierFrequencyHz());
                        if (b != null) modern.put(b, (float) g.getLevelDb());
                    }
                } catch (Throwable ignored) { }
            }
            Map<String, Float> a = !modern.isEmpty() ? modern : legacy;
            if (!a.isEmpty()) updateAgc(a);
        }
    };

    /** Поточні рівні прийшли з вимірювань — рахуємо відхилення від бази. */
    private void updateAgc(Map<String, Float> now) {
        agcNow.putAll(now);
        Map<String, Float> d = new HashMap<>();
        for (Map.Entry<String, Float> en : agcNow.entrySet()) {
            Float base = agcBase.get(en.getKey());
            if (base != null) d.put(en.getKey(), en.getValue() - base);
        }
        agcDelta = d;
    }

    /**
     * Навчання бази. Викликається лише в зеленому стані: те, що ми бачимо
     * при справному GPS, і є чистим небом за визначенням.
     */
    private void learnAgc() {
        if (agcNow.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<String, Float> en : agcNow.entrySet()) {
            String b = en.getKey();
            float[] ring = agcRing.get(b);
            if (ring == null) {
                ring = new float[AGC_RING];
                Arrays.fill(ring, Float.NaN);
                agcRing.put(b, ring);
                agcPos.put(b, 0);
            }
            int pos = agcPos.get(b);
            ring[pos] = en.getValue();
            agcPos.put(b, (pos + 1) % AGC_RING);

            int cnt = 0;
            float[] tmp = new float[AGC_RING];
            for (float x : ring) if (!Float.isNaN(x)) tmp[cnt++] = x;
            if (cnt < AGC_MIN_SAMPLES) continue;
            float[] sorted = Arrays.copyOf(tmp, cnt);
            Arrays.sort(sorted);
            float cand = sorted[(int) (cnt * 0.8f)];
            Float old = agcBase.get(b);
            // Тільки вгору: завада лише знижує AGC, тож вища оцінка завжди
            // ближча до чистого неба. Так база самолікується, але не отруюється.
            // Змінили тримач і AGC упав назавжди — кнопка скидання.
            if (old == null || cand > old) {
                agcBase.put(b, cand);
                changed = true;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (changed && now - agcSavedAt > 60000) {
            agcSavedAt = now;
            saveAgcBase();
        }
    }

    private void loadAgcBase() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            for (String b : new String[]{"1176", "1561", "1575", "1602"}) {
                float v = sp.getFloat("agc_" + b, Float.NaN);
                if (!Float.isNaN(v)) agcBase.put(b, v);
            }
            agcBaseKnown = !agcBase.isEmpty();
        } catch (Throwable ignored) { }
    }

    private void saveAgcBase() {
        try {
            android.content.SharedPreferences.Editor e =
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (Map.Entry<String, Float> en : agcBase.entrySet())
                e.putFloat("agc_" + en.getKey(), en.getValue());
            e.apply();
            agcBaseKnown = true;
            Logger.event("AGC_BASE", agcBase.toString());
        } catch (Throwable ignored) { }
    }

    /** Скидання вивченої бази — якщо телефон переїхав на інший тримач чи авто. */
    public static void resetAgcBase(Context c) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) { }
    }

    /** Без вивченої бази ознака мовчить: гадати гірше, ніж не знати. */
    /**
     * Мережа каже «стоїмо»: два свіжі фікси з різницею ≥ 5 с на одному місці.
     * Один застарілий фікс (лог 15.09, 08:15: та сама точка 10 с при їзді
     * на 36 км/год) свідком не є.
     */
    private boolean netStill(long now) {
        if (netCur == null || netPrev == null) return false;
        if (now - netCurAt > 10000) return false;   // свідок має бути свіжим
        if (netCurAt - netPrevAt < 5000) return false;
        float tol = Math.max(30f, Math.max(netCur.hasAccuracy() ? netCur.getAccuracy() : 30f,
                netPrev.hasAccuracy() ? netPrev.getAccuracy() : 30f));
        return netCur.distanceTo(netPrev) < tol;
    }

    private boolean agcAlarm() {
        for (Float d : agcDelta.values()) if (d != null && d < -AGC_DROP) return true;
        return false;
    }

    // ---- слухачі позицій: усі три, моки відсіюються на вході ----

    private LocationListener listener(final int which) {
        return new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (isMock(l)) return;
                if (which == 0) {
                    prevGps = gpsRaw;
                    gpsRaw = l;
                    gpsAt = SystemClock.elapsedRealtime();
                    gpsSrc = "fix";
                } else if (which == 1) {
                    netRaw = l;
                    netPrev = netCur; netPrevAt = netCurAt;
                    netCur = l; netCurAt = SystemClock.elapsedRealtime();
                } else {
                    fusRaw = l;
                }
            }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
        };
    }

    /**
     * NMEA з чіпа. Під тестовим провайдером Location-фікс до нас не доходить,
     * а ці речення — доходять, і несуть СПРАВЖНЮ позицію приймача. Тому під
     * моком саме вони стають gpsRaw: зона мережі, стрибки, фальшивий рух —
     * усе перевіряється без зняття моку. Проба лишається запасним шляхом.
     */
    private final OnNmeaMessageListener nmeaL = new OnNmeaMessageListener() {
        @Override public void onNmeaMessage(String m, long ts) {
            if (m == null) return;
            String t = Nmea.type(m);
            if ("GSV".equals(t)) {
                if (Nmea.checksumOk(m) && Nmea.countGsvSnr(m) > 0)
                    lastGsvAt = SystemClock.elapsedRealtime();
                return;
            }
            if (!"GGA".equals(t) && !"RMC".equals(t)) return;
            if (!Nmea.checksumOk(m)) return;
            boolean pos = "GGA".equals(t) ? Nmea.parseGGA(m, nmea)
                    : (Nmea.parseRMC(m, nmea) && nmea.quality > 0);
            nmeaSats = nmea.sats;
            if (!pos) return;
            Location l = new Location("nmea");
            l.setLatitude(nmea.lat);
            l.setLongitude(nmea.lon);
            l.setAccuracy(Nmea.accuracy(nmea));
            l.setTime(System.currentTimeMillis());
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (nmea.speedMps >= 0) l.setSpeed(nmea.speedMps);
            if (nmea.course >= 0) l.setBearing(nmea.course);
            nmeaLoc = l;
            nmeaAt = SystemClock.elapsedRealtime();
            checkLoop(l);
            if (!nmeaTrusted) return;
            // v9.3: раніше живило gpsRaw лише під моком («без мока провайдер
            // сам дає фікс»). Тепер — завжди. Причина: зняття моку через
            // NMEA_TRUST більше не перевидає підписку gpsL (див. removeMock
            // нижче), тож саме NMEA лишається джерелом, яке не залежить від
            // того, чи gpsL сам відновив потік від провайдера.
            prevGps = gpsRaw;
            gpsRaw = l;
            gpsAt = nmeaAt;
            gpsSrc = "nmea";
        }
    };

    /**
     * Захист від петлі: на деяких прошивках (особливо магнітол) система може
     * транслювати НАШІ ж мок-координати назад у вигляді NMEA. Тоді фільтр
     * перевіряв би сам себе. Ознака: NMEA повторює нашу видану позицію з
     * точністю до метрів, поки ця позиція відчутно рухається.
     */
    private void checkLoop(Location l) {
        if (mocked.isEmpty() || !emitted) { loopStreak = 0; return; }
        float[] r = new float[1];
        Location.distanceBetween(l.getLatitude(), l.getLongitude(), emLat, emLon, r);
        if (r[0] > LOOP_TOL_M) { loopStreak = 0; return; }
        if (loopStreak == 0) { loopFromLat = emLat; loopFromLon = emLon; }
        loopStreak++;
        if (loopStreak < LOOP_STREAK) return;
        Location.distanceBetween(loopFromLat, loopFromLon, emLat, emLon, r);
        if (r[0] < LOOP_MIN_PATH_M) return;   // стояли — збіг міг бути випадковим
        if (nmeaTrusted) {
            nmeaTrusted = false;
            nmeaWhy = "петля: NMEA повторює наш мок";
            Logger.event("NMEA_LOOP", nmeaWhy);
        }
    }

    /** NMEA придатний, якщо свіжий, не в петлі й підтверджений живими GSV. */
    private boolean nmeaFresh() {
        if (nmeaLoc == null || !nmeaTrusted) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - nmeaAt > NMEA_FRESH_MS) return false;
        if (lastGsvAt > 0 && now - lastGsvAt > GSV_SILENCE_MS) {
            nmeaTrusted = false;
            nmeaWhy = "GSV замовкли — потік схожий на синтетичний";
            Logger.event("NMEA_SYNTH", nmeaWhy);
            return false;
        }
        return true;
    }

    private final LocationListener gpsL = listener(0);
    private final LocationListener netL = listener(1);
    private final LocationListener fusL = listener(2);

    // ------------------------------------------------------------------
    // оцінка довіри

    /** Найкраще з альтернативних джерел: fused точніший, network — запасний. */
    private Location bestAlt() {
        // Поки діє латч підміни, fused не можна брати: він успадковує GPS.
        return bestAlt(!spoofLatch);
    }

    private Location bestAlt(boolean allowFused) {
        Location f = allowFused ? fusRaw : null, n = netRaw;
        // Кандидат має бути свіжим і ще не спожитим (не той самий об'єкт, що ref).
        boolean fo = f != null && f.hasAccuracy() && ageMs(f) <= ALT_FRESH_MS && f != ref;
        boolean no = n != null && n.hasAccuracy() && ageMs(n) <= ALT_FRESH_MS && n != ref;
        if (fo && no) {
            boolean pickF = f.getAccuracy() <= n.getAccuracy();
            inSrc = pickF ? "fused" : "network";
            return pickF ? f : n;
        }
        if (fo) { inSrc = "fused"; return f; }
        if (no) { inSrc = "network"; return n; }
        // Нічого нового немає — джерело показуємо за поточною опорою.
        if (ref != null) inSrc = FUSED_PROVIDER.equals(ref.getProvider()) ? "fused" : "network";
        else inSrc = "—";
        return null;
    }

    /**
     * Чи можна довіряти поточному GPS-фіксу. Викликається ЛИШЕ коли GPS видно
     * (мок знято: у довірі або під час проби).
     *
     * Мережа — якір. GPS у довірі, якщо він у ЗОНІ мережі; поза зоною — підміна.
     * Без мережі GPS не перевіряється, і під латчем підміни довіри не буде.
     * Повертає "" (довіра) або назву причини.
     */
    private String gpsVerdict() {
        Location g = gpsRaw;
        long now = SystemClock.elapsedRealtime();
        lastCrit = false;

        int floor = gpsTrusted ? MIN_USED_DROP : MIN_USED;
        // GGA рахує супутники з чіпа — це рятує там, де GnssStatus під моком мовчить.
        int usedEff = Math.max(usedInFix, nmeaFresh() ? nmeaSats : 0);
        if (usedEff < floor) {
            divergence = -1;
            spoofFlags = agcAlarm() ? "AGC" : "—";
            return agcAlarm() ? "ЗАВАДА" : "СЛАБКИЙ";
        }
        if (g == null || now - gpsAt > GPS_TIMEOUT_MS) {
            divergence = -1;
            spoofFlags = "—";
            return (g == null && now - gpsAt <= GPS_TIMEOUT_MS) ? "" : "НЕМАЄ_ФІКСА";
        }

        List<String> f = new ArrayList<>();
        boolean crit = false, anchored = false, confirmed = false;

        // --- зона мережі ---
        Location nl = netRaw;
        divergence = -1;
        if (nl != null && nl.hasAccuracy() && ageMs(nl) <= MAX_NET_AGE_MS
                && nl.getAccuracy() <= NET_ANCHOR_MAX_ACC) {
            anchored = true;
            float d = g.distanceTo(nl);
            divergence = d;
            float a = nl.getAccuracy();
            float zone = a <= NET_PRECISE_ACC
                    ? ZONE_K * a + ZONE_MARGIN
                    : ZONE_COARSE_K * a + ZONE_COARSE_MARGIN;
            if (d > zone) {
                f.add("поза_зоною");
                if (d > Math.max(2 * zone, ZONE_CRIT_MIN)) crit = true;
            } else if (a <= NET_PRECISE_ACC && d <= zone / 2) {
                confirmed = true;   // мережа прямо підтверджує позицію GPS
            }
        }

        // --- фальшивий рух: GPS «їде», а незалежний свідок каже «стоїмо» ---
        // Тиша акселерометра САМА ПО СОБІ не голосує: лог 15.09 — три хибні
        // вироки при плавній їзді на 36 км/год (розкид 0,1 при порозі 0,15).
        boolean obd = Obd.fresh();
        // Рушання з місця: колеса вже 1 км/год, а GPS уже бачить рух. Тому
        // «колеса стоять» має протриматись кілька секунд, перш ніж голосувати.
        if (obd && Obd.speedKmh < OBD_STILL_KMH) {
            if (obdStillSince == 0) obdStillSince = now;
        } else {
            obdStillSince = 0;
        }
        boolean obdStill = obdStillSince > 0
                && now - obdStillSince >= FAKE_MOTION_VOTE_S * 1000L;
        boolean accelStill = stillSince > 0 && now - stillSince >= FAKE_MOTION_VOTE_S * 1000L;
        boolean witness = obdStill || (accelStill && netStill(now));
        if (witness && g.hasSpeed() && g.getSpeed() > FAKE_MOTION_SPEED) {
            fakeMotion++;
            // Голос лише після стійкої суперечності, а не з першої секунди.
            if (fakeMotion >= (obdStill ? FAKE_MOTION_FLAG_OBD_S : FAKE_MOTION_FLAG_S))
                f.add(obdStill ? "фальш_рух_OBD" : "фальш_рух");
            // З OBD доказ прямий: колеса не крутяться — вирок удвічі швидше.
            if (fakeMotion >= (obdStill ? FAKE_MOTION_VOTE_S : FAKE_MOTION_CRIT_S)) crit = true;
        } else {
            fakeMotion = 0;
        }

        // --- розбіжність швидкостей GPS і OBD: спуфер веде не з нашою швидкістю ---
        if (obd && g.hasSpeed()) {
            // Беремо той вимір OBD, що найближчий за часом до самого фікса:
            // ELM327 відповідає із затримкою, і на розгоні різниця уявна.
            long fixMs = g.getElapsedRealtimeNanos() / 1000000L;
            float atFix = Obd.speedAtMs(fixMs, OBD_MATCH_TOL_MS);
            float gk = g.getSpeed() * 3.6f, ok = atFix >= 0 ? atFix : Obd.speedKmh;
            float tol = Math.max(OBD_MISMATCH_KMH, OBD_MISMATCH_FRAC * Math.max(gk, ok));
            if (Math.abs(gk - ok) > tol) {
                obdMismatch++;
                if (obdMismatch >= OBD_MISMATCH_VOTE_S) f.add("швидкість≠OBD");
                if (obdMismatch >= OBD_MISMATCH_CRIT_S) crit = true;
            } else {
                obdMismatch = 0;
            }
        } else {
            obdMismatch = 0;
        }

        // --- стрибок ---
        if (prevGps != null) {
            long dt = (g.getElapsedRealtimeNanos() - prevGps.getElapsedRealtimeNanos())
                    / 1000000L;
            if (dt > 200) {
                float v = g.distanceTo(prevGps) / (dt / 1000f);
                if (v > MAX_SPEED_MPS) f.add("стрибок");
            }
        }

        if (degenRatio > DEGEN_RATIO) f.add("азимут0");

        long skew = Math.abs(g.getTime() - (System.currentTimeMillis() - ageMs(g)));
        if (skew > TIME_SKEW_MS) f.add("час");

        int votes = f.size();
        if (agcAlarm()) f.add("AGC");          // попередження, не голос
        if (!anchored) f.add("без_мережі");    // інформація, не голос
        spoofFlags = f.isEmpty() ? "—" : String.join("+", f);
        lastCrit = crit;

        if (crit) return "ПІДМІНА";
        // Якщо ТОЧНА мережа підтверджує позицію GPS, другорядні ознаки —
        // вироджені азимути, розбіжність часу, стрибок — вироку не виносять.
        // Лог 17.09, 22:48:06 і 22:49:36: «час+азимут0» дали «підміну» при
        // розходженні з мережею всього 24-30 м, тобто при повній згоді.
        // Якір на те й якір: він тут головний свідок, а не непрямі ознаки.
        if (confirmed && !f.contains("фальш_рух")) {
            return "";
        }
        if (votes >= 2) return "ПІДМІНА";
        // Мережа — якір: вихід за її зону сам по собі вирок (через серію DEAD_STREAK).
        // Фальшивий рух — теж: це прямий фізичний доказ.
        if (f.contains("поза_зоною") || f.contains("фальш_рух")) return "ПІДМІНА";
        if (spoofLatch && votes >= 1) return "ПІДМІНА";
        // Під латчем без мережі проба дозволена лише в «сліпому» ярусі (довгому).
        if (spoofLatch && !anchored && probeTier > 0) return "НЕ_ПЕРЕВІРЕНО";
        return "";
    }

    /** 0 — підтвердити нікому, 1 — груба мережа, 2 — точна. */
    private int verifierTier() {
        Location nl = netRaw;
        if (nl == null || !nl.hasAccuracy() || ageMs(nl) > MAX_NET_AGE_MS
                || nl.getAccuracy() > NET_ANCHOR_MAX_ACC) return 0;
        return nl.getAccuracy() <= NET_PRECISE_ACC ? 2 : 1;
    }
    private int probeTier = 2;

    private void loseTrust(String verdict, long now) {
        gpsTrusted = false;
        lastStateChangeAt = now;
        probing = false;
        lastProbeEnd = now;
        goodStreak = 0;
        Logger.event("TRUST", "GPS втрачено: " + verdict
                + (spoofFlags.equals("—") ? "" : " [" + spoofFlags + "]"));
        if ("ПІДМІНА".equals(verdict)) {
            spoofLatch = true;
            if (ref != null && FUSED_PROVIDER.equals(ref.getProvider())) {
                ref = null;
                hasOut = false;
            }
            vE = 0; vN = 0;
        } else {
            seedVelocityFromGps();
        }
        installMock();
    }

    private void gainTrust(long now) {
        gpsTrusted = true;
        probeFails = 0;
        lastStateChangeAt = now;
        probing = false;
        badStreak = 0;
        hasOut = false;
        if (spoofLatch) {
            spoofLatch = false;
            Logger.event("TRUST", "GPS відновлено, латч підміни знято (у зоні мережі)");
        } else {
            Logger.event("TRUST", "GPS відновлено");
        }
    }

    private void startProbe(long now) {
        probing = true;
        probeStart = now;
        goodStreak = 0;
        removeMock();   // GPS стає видимим; навігатор теж бачить його — це ціна проби
        Logger.event("PROBE", spoofLatch ? "проба під латчем, ярус " + probeTier
                : (goodStreak >= ALIVE_STREAK ? "проба" : "проба за розкладом (будимо приймач)"));
    }

    private void endProbe(String why, long now) {
        probing = false;
        lastProbeEnd = now;
        goodStreak = 0;
        // Будь-яка проба, що не повернула довіру, рахується невдалою: пауза росте.
        probeFails++;
        Logger.event("PROBE_END", why + " (невдач поспіль: " + probeFails + ")");
        installMock();
    }

    // ------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { step(); } catch (Throwable t) { mockError = t.toString(); }
            h.postDelayed(this, 1000);
        }
    };

    /** Публікація у mock іде швидше за аналіз: навігатору потрібен рівний потік. */
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            try {
                if (precise && S_ORANGE.equals(state) && !mocked.isEmpty() && ref != null) {
                    long age = SystemClock.elapsedRealtime() - refAt;
                    if (age <= MAX_EXTRAP_MS) emitExtrapolated(age);
                }
            } catch (Throwable ignored) { }
            h.postDelayed(this, PUMP_MS);
        }
    };

    private void step() {
        long now = SystemClock.elapsedRealtime();
        updateMotion();

        // --- прогрів: до першого фікса або 10 с; мок не ставимо ---
        if (warm) {
            if (now - startedAt < WARMUP_MS && gpsRaw == null && usedInFix == 0) {
                setState(S_WARMUP, "прогрів");
                emitted = false;
                trackAlt();
                publish();
                return;
            }
            warm = false;
            lastStateChangeAt = now;
            String v0 = gpsVerdict();
            gpsTrusted = v0.isEmpty();
            if (!gpsTrusted) installMock();
        }

        // ================= GPS У ДОВІРІ: мок знято, навігатор на GPS =================
        if (gpsTrusted) {
            String verdict = gpsVerdict();
            boolean ok = verdict.isEmpty();
            if (ok) { goodStreak++; badStreak = 0; } else { badStreak++; goodStreak = 0; }
            boolean spoofNow = "ПІДМІНА".equals(verdict);

            boolean drop;
            if (spoofNow && (lastCrit || spoofLatch)) {
                drop = true;                       // критичне або рецидив — негайно
            } else {
                drop = badStreak >= DEAD_STREAK && now - lastStateChangeAt >= MIN_DWELL_MS;
            }
            if (drop) {
                loseTrust(verdict, now);
                // далі — гілка «під моком» у цьому ж циклі
            } else {
                boolean weakNow = usedInFix < MIN_USED + 2 || !spoofFlags.equals("—")
                        || badStreak > 0;
                if (weakNow) strongStreak = 0; else strongStreak++;
                boolean weak = weakNow
                        || (S_YELLOW.equals(state) && strongStreak < STRONG_STREAK);
                setState(weak ? S_YELLOW : S_GREEN, weak ? "GPS слабкий" : "GPS впевнений");
                if (!weak && !spoofLatch) learnAgc();
                Location gg = gpsRaw;
                if (gg != null && gg.hasBearing() && gg.hasSpeed() && gg.getSpeed() > MIN_MOVE_MPS)
                    seedHeading(gg.getBearing());   // справжній курс — еталон для гіроскопа
                if (gg != null && gg.hasSpeed()) {
                    lastSpeed = gg.getSpeed();
                    lastSpeedAt = now;
                    drSpeed = gg.getSpeed();
                }
                if (anchorGrade(gg)) setAnchor(gg, "GPS", now);   // найкраща опора
                cValid = false;                     // груба оцінка стартує заново з GPS
                source = "GPS";
                emitted = false;
                precise = false;
                trackAlt();
                removeMock();
                showFrom(gpsRaw);
                rememberHold(lat, lon, acc);
                publish();
                return;
            }
        }

        // ================= ПРОБА: мок знято тимчасово, дивимось на GPS =================
        if (probing) {
            String verdict = gpsVerdict();
            boolean ok = verdict.isEmpty();
            boolean haveFix = gpsRaw != null && now - gpsAt <= 3000;
            if ("ПІДМІНА".equals(verdict)) {
                if (!spoofLatch) spoofLatch = true;
                endProbe("підміна: " + spoofFlags, now);
            } else if (!ok && !"СЛАБКИЙ".equals(verdict) && !"ЗАВАДА".equals(verdict)
                    && !"НЕМАЄ_ФІКСА".equals(verdict)) {
                endProbe(verdict, now);
            } else if (!ok) {
                // Приймач під моком дрімає: після зняття йому потрібні секунди,
                // щоб видати фікс. Лог 17.09: усі п'ять проб уривались за 1 с
                // із «СЛАБКИЙ», і застосунок не виходив із помаранчевого
                // три хвилини при чистому небі. Тримаємо вікно проби.
                // Приймач без ознак життя довго тримати не варто.
                long maxW = PROBE_WAKE_MS;
                if (now - probeStart > maxW) {
                    endProbe(verdict, now);
                } else {
                    setState(S_YELLOW, "проба GPS: приймач прокидається");
                    source = "GPS";
                    precise = false;
                    trackAlt();
                    showFrom(gpsRaw);
                    publish();
                    return;
                }
            } else if (!haveFix && now - probeStart > PROBE_MAX_MS) {
                endProbe("фікс не прийшов", now);
            } else if (!haveFix) {
                // фікс іще в дорозі: ні добре, ні погано — чекаємо
                setState(S_YELLOW, "проба GPS: чекаємо фікс");
                source = "GPS";
                precise = false;
                trackAlt();
                publish();
                return;
            } else if (gpsRaw != null && gpsRaw.hasAccuracy() && gpsRaw.getAccuracy() > PROBE_MAX_ACC) {
                // супутників вистачає, а фікс грубий — ще не довіра, але й не провал
                goodStreak = 0;
                setState(S_YELLOW, "проба GPS: фікс грубий");
                source = "GPS";
                precise = false;
                trackAlt();
                showFrom(gpsRaw);
                publish();
                return;
            } else {
                goodStreak++;
                int need = !spoofLatch ? ALIVE_STREAK
                        : probeTier == 2 ? STREAK_PRECISE
                        : probeTier == 1 ? STREAK_COARSE : STREAK_BLIND;
                if (goodStreak >= need) {
                    gainTrust(now);
                    setState(S_GREEN, "GPS впевнений");
                    source = "GPS";
                    precise = false;
                    trackAlt();
                    showFrom(gpsRaw);
                    rememberHold(lat, lon, acc);
                    publish();
                    return;
                }
                // Тривалість проби має вміщати потрібну серію свого ярусу.
                long maxProbe = !spoofLatch ? PROBE_MAX_MS
                        : probeTier == 2 ? PROBE_MAX_MS
                        : probeTier == 1 ? PROBE_MAX_COARSE_MS : PROBE_MAX_BLIND_MS;
                if (now - probeStart > maxProbe) {
                    endProbe("час вийшов", now);
                } else {
                    setState(S_YELLOW, spoofLatch
                            ? "проба GPS " + goodStreak + "/" + need : "проба GPS");
                    source = "GPS";
                    precise = false;
                    trackAlt();
                    showFrom(gpsRaw);
                    publish();
                    return;
                }
            }
        }

        // ============ ПІД МОКОМ ============
        // NMEA дає справжню позицію чіпа повз мок — тоді працює повна перевірка
        // (зона мережі, стрибок, фальшивий рух), і знімати мок заради проби не треба.
        if (nmeaFresh()) {
            String nv = gpsVerdict();
            boolean nok = nv.isEmpty() && gpsRaw != null && gpsRaw.hasAccuracy()
                    && gpsRaw.getAccuracy() <= PROBE_MAX_ACC;
            if (nok) goodStreak++; else goodStreak = 0;
            if ("ПІДМІНА".equals(nv)) {
                if (!spoofLatch) {
                    spoofLatch = true;
                    Logger.event("TRUST", "підміна виявлена через NMEA: " + spoofFlags);
                }
                probeFails++;
                lastProbeEnd = now;
            }
            int tierN = verifierTier();
            int needN = !spoofLatch ? ALIVE_STREAK
                    : tierN == 2 ? STREAK_PRECISE
                    : tierN == 1 ? STREAK_COARSE : STREAK_BLIND;
            if (nok && goodStreak >= needN && now - lastStateChangeAt >= MIN_DWELL_MS) {
                Logger.event("NMEA_TRUST", "довіра повернена через NMEA, без проби");
                gainTrust(now);
                removeMock(true);   // довіру вже підтвердив NMEA — resub не потрібен
                setState(S_GREEN, "GPS впевнений");
                source = "GPS";
                precise = false;
                trackAlt();
                showFrom(gpsRaw);
                rememberHold(lat, lon, acc);
                publish();
                return;
            }
            starvedStreak = 0;
            String whyN = spoofLatch ? "підміна GPS"
                    : "ЗАВАДА".equals(nv) ? "завада GNSS"
                    : "СЛАБКИЙ".equals(nv) ? "слабкий сигнал"
                    : "GPS під наглядом";
            installMock();
            trackAlt();
            coarseOrPrecise(whyN, now);
            return;
        }

        // Голодування ширше, ніж «видимих нуль»: лог 17.09 показав 25-45
        // видимих супутників при usedInFix 0-2 і нормальному AGC — тобто
        // прапорець «у розв'язку» під моком просто не оновлюється.
        boolean statusDead = usedInFix < MIN_USED && visible >= 8 && !agcAlarm();
        if (visible <= STARVED_VIS || statusDead) starvedStreak++; else starvedStreak = 0;
        boolean starved = starvedStreak >= STARVED_AFTER_S;
        if (starved && !starvedLogged) {
            starvedLogged = true;
            Logger.event("STATUS_STARVED", "GnssStatus мовчить під моком; гейт — з вимірювань/розкладу");
        }
        boolean phys;
        if (!starved) {
            phys = usedInFix >= MIN_USED && !agcAlarm();
        } else if (starvedStreak >= STARVED_FORCE_AFTER_S) {
            phys = !agcAlarm();                       // за розкладом, фізику не питаємо
        } else {
            phys = towValid >= STARVED_TOW_MIN && !agcAlarm();
        }
        if (phys) goodStreak++; else goodStreak = 0;
        if (usedInFix < MIN_USED) spoofFlags = agcAlarm() ? "AGC" : "—";
        else spoofFlags = agcAlarm() ? "AGC" : "—";

        int tier = verifierTier();
        long interval = tier == 2 ? PROBE_INT_PRECISE_MS
                : tier == 1 ? PROBE_INT_COARSE_MS : PROBE_INT_BLIND_MS;
        // Кожна невдала проба подвоює паузу: приймач, що не прокидається,
        // не має права тримати мок знятим.
        long gap = Math.max(spoofLatch ? interval : PROBE_MIN_GAP_MS, PROBE_MIN_GAP_MS);
        if (probeFails > 0)
            gap = Math.min(PROBE_INT_MAX_MS, gap * (1L << Math.min(3, probeFails)));
        boolean cooled = now - lastProbeEnd >= gap;
        boolean overdue = !spoofLatch && now - lastProbeEnd >= PROBE_MAX_GAP_MS
                && now - lastStateChangeAt >= MIN_DWELL_MS;
        boolean canProbe = !nmeaFresh() && cooled && (overdue
                || (goodStreak >= ALIVE_STREAK && now - lastStateChangeAt >= MIN_DWELL_MS));
        if (canProbe) {
            probeTier = tier;
            startProbe(now);
            setState(S_YELLOW, spoofLatch ? "проба GPS під латчем" : "проба GPS");
            source = "GPS";
            precise = false;
            trackAlt();
            publish();
            return;
        }

        String why = spoofLatch ? "підміна GPS"
                : starved ? "статус GNSS недоступний"
                : usedInFix < MIN_USED ? (agcAlarm() ? "завада GNSS" : "слабкий сигнал")
                : "GPS не перевірено";

        installMock();
        trackAlt();
        coarseOrPrecise(why, now);
    }

    /**
     * Вихід під моком за пріоритетом: точна мережа з екстраполяцією,
     * груба мережа з передбаченням, утримання, порожній мок.
     */
    private void coarseOrPrecise(String why, long now) {

        // 1) точна мережа — екстраполяція
        long age = ref == null ? Long.MAX_VALUE : now - refAt;
        if (ref != null && age <= MAX_EXTRAP_MS) {
            precise = true;
            setState(S_ORANGE, why + ", ведемо з мережі");
            source = inSrc;
            emitExtrapolated(age);
            rememberHold(lat, lon, acc);
            cValid = false;     // груба оцінка при потребі стартує з цього виходу
            publish();
            return;
        }
        precise = false;

        // 2) числення від останньої ДОСТАТНЬО ТОЧНОЇ опори.
        Location nl = netRaw;
        boolean netFresh = nl != null && nl.hasAccuracy() && ageMs(nl) <= MAX_NET_AGE_MS;

        if (netFresh && anchorGrade(nl) && nl != drLastAnchor) {
            setAnchor(nl, "network", now);      // нова опора — скидає похибку
        } else if (drValid) {
            deadReckon(now);
            if (netFresh && nl != drLastAnchor) {
                // Груба мережа опорою не стає, але стежить, чи ми не загубились.
                float[] rr = new float[1];
                Location.distanceBetween(drLat, drLon, nl.getLatitude(), nl.getLongitude(), rr);
                if (rr[0] > 3 * (drSig + nl.getAccuracy())) drDisagree++;
                else drDisagree = 0;
                // Перезапуск опори лише КРЕДИТОСПРОМОЖНИМ фіксом: той, що гірший
                // за нашу накопичену невизначеність, спростувати нас не може.
                // Інакше фікс із похибкою 800 м перекидав би нас на кілометри —
                // рівно той дефект, який ця схема мала прибрати.
                boolean credible = nl.getAccuracy() <= Math.max(ANCHOR_ACC_M, drSig);
                if (drDisagree >= DR_DISAGREE && credible) {
                    Logger.event("DR_RESET", String.format(Locale.US,
                            "числення розійшлось із мережею на %.0f м (фікс ±%.0f)",
                            rr[0], nl.getAccuracy()));
                    setAnchor(nl, "network(скид)", now);
                }
            }
            if (drSig > DR_MAX_SIG_M) {
                drValid = false;
                drSrc = "—";
                Logger.event("DR_LOST", String.format(Locale.US,
                        "числення розійшлось на ±%.0f м без опори", drSig));
            }
        }

        if (drValid) {
            setState(S_ORANGE, why + (Obd.fresh() ? ", числення (OBD)" : ", числення"));
            source = drSrc;
            extrapMs = now - drAnchorAt;
            float sp = Obd.fresh() ? Math.max(0f, Obd.speedMps()) : (moving ? drSpeed : 0f);
            boolean mv = moving && sp > MIN_MOVE_MPS;
            emit(drLat, drLon, drSig, mv ? sp : 0, (mv && hdgValid) ? (float) heading() : -1);
            rememberHold(lat, lon, acc);
            publish();
            return;
        }

        // 2б) опори немає взагалі — грубий фікс краще, ніж нічого
        if (netFresh && nl.getAccuracy() <= NET_ANCHOR_MAX_ACC) {
            setState(S_ORANGE, why + ", груба мережа");
            source = "network";
            coarseStep(nl, now);
            publish();
            return;
        }

        // 3) мережі немає — утримання. Мок стоїть: спуфер до навігатора не дістає.
        if (hasHold) {
            float grown = Math.min(HOLD_MAX_ACC,
                    holdAcc + (now - holdAt) / 1000f * HOLD_GROWTH_MPS);
            setState(S_RED, why + ", утримання ±" + (int) grown + " м");
            source = "HOLD";
            extrapMs = now - holdAt;
            emit(holdLat, holdLon, grown, 0, -1);
            publish();
            return;
        }

        // 4) утримувати нічого — мок стоїть порожнім, навігатор бачить «немає GPS»
        setState(S_RED, why + ", позиції немає");
        source = "—";
        emitted = false;
        publish();
    }

    /** Оцінка швидкості для передбачення, м/с: OBD, інакше недавній GPS, інакше 0. */
    private float speedEstimate(long now) {
        if (Obd.fresh()) return Math.max(0f, Obd.speedMps());
        if (!moving) return 0f;
        if (lastSpeedAt > 0 && now - lastSpeedAt <= LAST_SPEED_TTL_MS) return lastSpeed;
        return 0f;
    }

    /**
     * Груба мережа: передбачення + корекція.
     *   Передбачення — рух за швидкістю (OBD/недавній GPS) і курсом (гіроскоп),
     *   невизначеність росте.
     *   Корекція — новий фікс зливається з вагою за точністю; стрибок понад
     *   COARSE_GATE_SIG сигм іде в карантин до підтвердження другим фіксом.
     */
    private void coarseStep(Location nl, long now) {
        if (!cValid) {
            // стартуємо з того, у що вірили востаннє, якщо є; інакше з фікса
            if (emitted) { cLat = emLat; cLon = emLon; cSig = Math.max(COARSE_SIG_MIN, emAcc); }
            else { cLat = nl.getLatitude(); cLon = nl.getLongitude(); cSig = nl.getAccuracy(); }
            cValid = true; cAt = now; cPredAt = now; cPending = null; cLastRaw = null;
        }

        // --- передбачення ---
        double dt = (now - cPredAt) / 1000.0;
        cPredAt = now;
        float sp = speedEstimate(now);
        if (dt > 0 && dt < 5) {
            if (sp > MIN_MOVE_MPS && hdgValid) {
                double r = Math.toRadians(heading());
                cLat += (Math.cos(r) * sp * dt) / M_PER_DEG;
                double k = M_PER_DEG * Math.cos(Math.toRadians(cLat));
                if (Math.abs(k) > 1) cLon += (Math.sin(r) * sp * dt) / k;
                cSig += (float) (dt * (COARSE_GROW_MPS + COARSE_GROW_FRAC * sp));
            } else {
                cSig += (float) (dt * COARSE_GROW_MPS);
            }
        }

        // --- корекція новим фіксом ---
        if (nl != cLastRaw) {
            cLastRaw = nl;
            float a = nl.getAccuracy();
            float[] rr = new float[1];
            Location.distanceBetween(cLat, cLon, nl.getLatitude(), nl.getLongitude(), rr);
            float d = rr[0];
            if (d <= COARSE_GATE_SIG * (cSig + a)) {
                double k = (double) (cSig * cSig) / (cSig * cSig + a * a);
                cLat += k * (nl.getLatitude() - cLat);
                cLon += k * (nl.getLongitude() - cLon);
                cSig = (float) Math.max(COARSE_SIG_MIN, Math.sqrt((1 - k) * cSig * cSig));
                cAt = now; cPending = null;
            } else if (cPending != null && nl.distanceTo(cPending) <= a) {
                // другий фікс на тому ж новому місці — це не викид, ми переїхали
                cLat = nl.getLatitude(); cLon = nl.getLongitude(); cSig = a;
                cAt = now; cPending = null;
            } else if (now - cAt > COARSE_STALE_MS) {
                cLat = nl.getLatitude(); cLon = nl.getLongitude(); cSig = a;
                cAt = now; cPending = null;
            } else {
                cPending = nl;
            }
        }

        float rad = cSig * 1.5f;
        if (cPending != null) {
            float[] rr = new float[1];
            Location.distanceBetween(cLat, cLon, cPending.getLatitude(), cPending.getLongitude(), rr);
            rad = Math.max(rad, rr[0]);       // чесно: є незгода з останнім фіксом
        }
        extrapMs = now - cAt;
        boolean mv = sp > MIN_MOVE_MPS;
        emit(cLat, cLon, rad, mv ? sp : 0, (mv && hdgValid) ? (float) heading() : -1);
        rememberHold(lat, lon, acc);
    }

    /** Чи достатньо точна точка, щоб стати опорою. */
    private static boolean anchorGrade(Location l) {
        return l != null && l.hasAccuracy() && l.getAccuracy() <= ANCHOR_ACC_M;
    }

    /** Нова опора: скидаємо накопичену невизначеність до точності самої точки. */
    private void setAnchor(Location l, String src, long now) {
        if (drValid && drLastAnchor != null) {
            long dt = now - drAnchorAt;
            float d = drLastAnchor.distanceTo(l);
            if (dt > 1000 && dt < 120000) {
                float v = d / (dt / 1000f);
                // Масштаб уточнюємо заднім числом: пройшли стільки за стільки.
                if (v <= MAX_SPEED_MPS) drSpeed = v;
            }
        }
        drLat = l.getLatitude();
        drLon = l.getLongitude();
        drSig = l.getAccuracy();
        drSig0 = drSig;
        drValid = true;
        drAt = now;
        drAnchorAt = now;
        drDisagree = 0;
        drLastAnchor = new Location(l);
        drSrc = src;
    }

    /**
     * Крок числення: від опори рухаємось за швидкістю й курсом, невизначеність
     * росте. Фікси, гірші за ANCHOR_ACC_M, опорою не стають — вони лише
     * перевіряють, чи ми не загубились.
     */
    private void deadReckon(long now) {
        double dt = (now - drAt) / 1000.0;
        drAt = now;
        if (dt <= 0 || dt > 5) return;

        float sp = Obd.fresh() ? Math.max(0f, Obd.speedMps()) : (moving ? drSpeed : 0f);
        if (!moving) sp = 0f;

        if (sp > MIN_MOVE_MPS && hdgValid) {
            double r = Math.toRadians(heading());
            drLat += (Math.cos(r) * sp * dt) / M_PER_DEG;
            double k = M_PER_DEG * Math.cos(Math.toRadians(drLat));
            if (Math.abs(k) > 1) drLon += (Math.sin(r) * sp * dt) / k;
        }

        // Невизначеність: частка пройденого шляху плюс бічний знос від дрейфу курсу
        // плюс страховка на повзання, яке гістерезис руху міг не помітити —
        // майже нульова, якщо саме OBD підтверджує повний нуль на колесах.
        double path = sp * dt;
        float scale = Obd.fresh() ? DR_SCALE_OBD : DR_SCALE_NOSPD;
        boolean obdConfirmedStill = "obd".equals(motionSrc) && !moving;
        float base = obdConfirmedStill ? DR_BASE_STILL_OBD_MPS : DR_BASE_MPS;
        double drift = Math.toRadians(DR_HDG_DRIFT_DPS * (now - drAnchorAt) / 1000.0);
        drSig += (float) (path * scale + Math.abs(path * Math.sin(drift)) + base * dt);
    }

    /** Остання позиція, в яку ми вірили. Основа утримання без мережі. */
    private void rememberHold(double la, double lo, float a) {
        if (la == 0 && lo == 0) return;
        holdLat = la; holdLon = lo; holdAcc = a;
        holdAt = SystemClock.elapsedRealtime();
        hasHold = true;
    }

    /** Приймання мережевої опори. Викликається в будь-якому стані. */
    private void trackAlt() {
        Location alt = bestAlt();
        if (alt == null || alt == ref) return;
        String why = reject(alt);
        if (why == null) accept(alt);
        else if (!gpsTrusted) { rejected++; lastReject = why; }
    }

    private String reject(Location n) {
        rejectCode = 0;
        if (!n.hasAccuracy()) { rejectCode = 1; return "немає поля accuracy"; }
        long a = ageMs(n);
        if (a > MAX_NET_AGE_MS) {
            rejectCode = 2;
            return String.format(Locale.US, "фікс застарів: %.0f с", a / 1000f);
        }
        if (n.getAccuracy() > accThreshold) {
            rejectCode = 3;
            return String.format(Locale.US, "точність %.0f м > %d м",
                    n.getAccuracy(), accThreshold);
        }
        if (ref != null) {
            long dtMs = (n.getElapsedRealtimeNanos() - ref.getElapsedRealtimeNanos())
                    / 1000000L;
            if (dtMs > 0) {
                float d = n.distanceTo(ref);
                float v = d / (dtMs / 1000f);
                // Зсув у межах похибок обох точок — не стрибок, а шум або
                // різниця між джерелами (fused проти network).
                float noise = n.getAccuracy() + ref.getAccuracy();
                if (v > MAX_SPEED_MPS && d > noise) {
                    rejectCode = 4;
                    return String.format(Locale.US, "стрибок %.1f км за %.0f с (%.0f м/с)",
                            d / 1000f, dtMs / 1000f, v);
                }
            }
        }
        return null;
    }

    /**
     * Прийняли опорну точку. Повторення тієї самої координати НЕ обнуляє
     * вектор швидкості: NLP повторює позицію майже в половині випадків,
     * і наївний розрахунок зупиняв би екстраполяцію на ходу.
     */
    private void accept(Location n) {
        if (ref != null) {
            long dtMs = (n.getElapsedRealtimeNanos() - ref.getElapsedRealtimeNanos())
                    / 1000000L;
            double d = n.distanceTo(ref);
            boolean sameSrc = n.getProvider() != null
                    && n.getProvider().equals(ref.getProvider());
            if (d < 1.0) {
                // Повтор координати: свіжість оновили, швидкість не обнулили,
                // але гасимо — три повтори поспіль означають, що ми стоїмо.
                refAt = SystemClock.elapsedRealtime();
                ref = n;
                vE *= REPEAT_DECAY; vN *= REPEAT_DECAY;
                lastReject = "—";
                return;
            }
            if (!sameSrc) {
                // Джерело змінилось: точку беремо, швидкість не перераховуємо.
                ref = n;
                refAt = SystemClock.elapsedRealtime();
                lastReject = "—";
                return;
            }
            // Зсув менший за похибку котроїсь із точок — це шум, а не рух.
            // Лог 12.09: стояча машина «їхала» саме на таких стрибках.
            double noise = Math.max(n.getAccuracy(), ref.getAccuracy());
            if (!moving || d <= noise) {
                vE *= REPEAT_DECAY; vN *= REPEAT_DECAY;
            } else if (dtMs > 500 && dtMs < 30000) {
                double dt = dtMs / 1000.0;
                double br = Math.toRadians(ref.bearingTo(n));
                double sp = d / dt;
                if (sp <= MAX_SPEED_MPS) {
                    vE = V_ALPHA * (sp * Math.sin(br)) + (1 - V_ALPHA) * vE;
                    vN = V_ALPHA * (sp * Math.cos(br)) + (1 - V_ALPHA) * vN;
                    blendHeading(ref.bearingTo(n));   // мережа тримає гіроскоп від дрейфу
                }
            } else if (dtMs >= 30000) {
                vE = 0; vN = 0;
            }
        }
        ref = n;
        refAt = SystemClock.elapsedRealtime();
        lastReject = "—";
    }

    private void seedVelocityFromGps() {
        vE = 0; vN = 0;
        Location g = gpsRaw;
        if (g != null && g.hasSpeed() && g.hasBearing() && g.getSpeed() > MIN_MOVE_MPS) {
            double br = Math.toRadians(g.getBearing());
            vE = g.getSpeed() * Math.sin(br);
            vN = g.getSpeed() * Math.cos(br);
            seedHeading(g.getBearing());
        }
    }

    private void emitExtrapolated(long ageMs) {
        if (ref == null) return;
        double dt = ageMs / 1000.0;

        if (!moving) {
            // Стоїмо: мережеві фікси незалежні, тому їх усереднення сходиться
            // до справжньої точки. Жодної екстраполяції, курс не публікуємо.
            if (!hasStill) {
                stillLat = ref.getLatitude(); stillLon = ref.getLongitude(); hasStill = true;
            } else {
                stillLat = STILL_ALPHA * ref.getLatitude() + (1 - STILL_ALPHA) * stillLat;
                stillLon = STILL_ALPHA * ref.getLongitude() + (1 - STILL_ALPHA) * stillLon;
            }
            outLat = stillLat; outLon = stillLon; hasOut = true;
            extrapMs = ageMs;
            emit(stillLat, stillLon, ref.getAccuracy(), 0, -1);
            return;
        }

        // Довжина: колеса (OBD), інакше мережевий вектор. Напрямок: гіроскоп,
        // інакше той самий вектор. Кожне джерело замінює гірше лише коли воно є.
        double sp = Obd.fresh() ? Obd.speedMps() : Math.hypot(vE, vN);
        double dirE, dirN;
        if (hdgValid) {
            double r = Math.toRadians(heading());
            dirE = Math.sin(r); dirN = Math.cos(r);
        } else {
            double m = Math.hypot(vE, vN);
            dirE = m > 0 ? vE / m : 0; dirN = m > 0 ? vN / m : 0;
        }
        double la = ref.getLatitude(), lo = ref.getLongitude();
        if (sp > MIN_MOVE_MPS) {
            la += (dirN * sp * dt) / M_PER_DEG;
            double k = M_PER_DEG * Math.cos(Math.toRadians(ref.getLatitude()));
            if (Math.abs(k) > 1) lo += (dirE * sp * dt) / k;
        }
        if (hasOut) {
            float[] r = new float[1];
            Location.distanceBetween(outLat, outLon, la, lo, r);
            if (r[0] < ref.getAccuracy()) { la = (la + outLat) / 2; lo = (lo + outLon) / 2; }
        }
        outLat = la; outLon = lo; hasOut = true;

        // З OBD похибка екстраполяції менша: швидкість відома на 1-2%, не на 30%.
        float a = (float) (ref.getAccuracy() + dt * ACC_GROWTH_MPS
                + sp * dt * (Obd.fresh() ? 0.15 : 0.5));
        float br = -1;
        if (sp > MIN_MOVE_MPS) {
            br = (float) Math.toDegrees(Math.atan2(dirE, dirN));
            if (br < 0) br += 360f;
        }
        extrapMs = ageMs;
        emit(la, lo, a, (float) sp, br);
    }

    private void emit(double la, double lo, float a, float sp, float br) {
        emLat = la; emLon = lo; emAcc = a; emSpd = sp; emBrg = br;
        emitted = true;
        lat = la; lon = lo; acc = a; speedMps = sp; bearingDeg = br;
        // Мітки часу ставимо БЕЗПОСЕРЕДНЬО перед записом у провайдер, і стінний
        // час робимо строго зростаючим: стрибок системного годинника назад
        // навігатори читають як «застарілий фікс» і показують пошук сигналу.
        long wall = System.currentTimeMillis();
        // Монотонність лише проти ДРІБНИХ стрибків назад (NTP-корекція).
        // Після великого стрибка приймаємо новий час: інакше одна аномалія
        // назавжди прив'язала б нас до хибної епохи й навігатор бачив би
        // мітки з майбутнього.
        if (wall <= lastEmitWall && lastEmitWall - wall < 60000) wall = lastEmitWall + 1;
        lastEmitWall = wall;
        for (String p : new ArrayList<>(mocked)) {
            Location l = new Location(p);
            l.setLatitude(la);
            l.setLongitude(lo);
            l.setAccuracy(a);
            l.setTime(wall);
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            if (sp > MIN_MOVE_MPS) {
                l.setSpeed(sp);
                l.setSpeedAccuracyMetersPerSecond(Math.max(1f, sp * 0.3f));
                if (br >= 0) {
                    l.setBearing(br);
                    l.setBearingAccuracyDegrees(25f);
                }
            }
            try { lm.setTestProviderLocation(p, l); }
            catch (Throwable t) { mockError = t.toString(); }
        }
    }

    private void showFrom(Location g) {
        if (g == null) { hasOut = false; return; }
        lat = g.getLatitude(); lon = g.getLongitude();
        acc = g.hasAccuracy() ? g.getAccuracy() : 0;
        speedMps = g.hasSpeed() ? g.getSpeed() : 0;
        bearingDeg = g.hasBearing() ? g.getBearing() : -1;
        extrapMs = 0;
    }

    // ------------------------------------------------------------------
    // mock

    private void installMock() {
        boolean wantFused = mockFused && !fusedFailed;
        if (mocked.isEmpty()) {
            // Під моком слухач провайдера бачитиме лише нас; старий фікс більше
            // не актуальний. NMEA, якщо є, наповнить gpsRaw знову за секунду.
            gpsRaw = null;
            prevGps = null;
            gpsSrc = "—";
        }
        if (mocked.contains(LocationManager.GPS_PROVIDER)
                && (!wantFused || mocked.contains(FUSED_PROVIDER))) return;
        boolean gps = addProvider(LocationManager.GPS_PROVIDER, true);
        if (wantFused && !addProvider(FUSED_PROVIDER, false)) fusedFailed = true;
        mockActive = gps;
        mockedProviders = mocked.isEmpty() ? "—" : String.join(", ", mocked);
        if (gps) mockError = null;
    }

    private boolean addProvider(String name, boolean critical) {
        if (mocked.contains(name)) return true;
        try { lm.removeTestProvider(name); } catch (Throwable ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                android.location.provider.ProviderProperties p =
                        new android.location.provider.ProviderProperties.Builder()
                                .setHasNetworkRequirement(false)
                                .setHasSatelliteRequirement(false)
                                .setHasCellRequirement(false)
                                .setHasMonetaryCost(false)
                                .setHasAltitudeSupport(false)
                                .setHasSpeedSupport(true)
                                .setHasBearingSupport(true)
                                .setPowerUsage(android.location.provider
                                        .ProviderProperties.POWER_USAGE_LOW)
                                .setAccuracy(android.location.provider
                                        .ProviderProperties.ACCURACY_FINE)
                                .build();
                lm.addTestProvider(name, p);
            } else {
                lm.addTestProvider(name, false, false, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            }
            lm.setTestProviderEnabled(name, true);
            mocked.add(name);
            if (mockDenied && critical) { mockDenied = false; mockError = null; }
            Logger.event("MOCK_ADD", name);
            return true;
        } catch (SecurityException se) {
            if (critical) {
                mockError = "МОК ЗАБОРОНЕНО: Developer options → Select mock location app";
                mockDenied = true;
                long now = SystemClock.elapsedRealtime();
                // Лог 14.09: 16 773 записи за день. Раз на хвилину достатньо.
                if (now - mockDeniedAt > 60000) {
                    Logger.event("MOCK_DENIED", name);
                    mockDeniedAt = now;
                }
                if (vibrate && now - mockDeniedBuzzAt > 120000) {
                    mockDeniedBuzzAt = now;
                    try {
                        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                        if (v != null && v.hasVibrator())
                            v.vibrate(VibrationEffect.createOneShot(600,
                                    VibrationEffect.DEFAULT_AMPLITUDE));
                    } catch (Throwable ignored) { }
                }
            }
            return false;
        } catch (Throwable t) {
            if (critical) mockError = t.toString();
            Logger.event("MOCK_FAIL", name + " " + t);
            return false;
        }
    }

    private void removeMock() { removeMock(false); }

    /**
     * Польовий лог 12.09 (S26 Ultra, Android 16): після зняття тестового
     * провайдера справжній GPS-фікс до слухача gpsL НЕ повертався сам —
     * 13 з 23 втрат довіри були саме цим, петля з періодом 10 с. Тому
     * підписку на GPS перевидаємо явно, а таймер свіжості фікса запускаємо
     * з нуля: 8 с на те, щоб фікс знову почав приходити. Це стосується
     * «сліпої» проби (без NMEA) — там gpsL лишається єдиним джерелом
     * gpsRaw, і resub тут обов'язковий.
     *
     * v9.3: коли довіру повернув NMEA_TRUST, довіру вже підтвердив чіп
     * напряму, в обхід шару провайдерів — resub тут нічого не перевіряє
     * заново, лише перезапускає підписку. Лог 17.09 (S26): саме це стало
     * джерелом повторних втрат довіри кожні 10-20 с (vis 50→11→4,
     * «азимут0» одразу після resub). Для цього шляху — skipResub=true;
     * gpsRaw і далі живиться з NMEA (nmeaL, тепер завжди, не лише під
     * моком), тож розбудити gpsL заново не треба.
     */
    private void removeMock(boolean skipResub) {
        if (mocked.isEmpty()) { mockActive = false; mockedProviders = "—"; return; }
        Logger.event("MOCK_REMOVE", String.join(", ", mocked));
        for (String p : new ArrayList<>(mocked)) {
            try { lm.setTestProviderEnabled(p, false); } catch (Throwable ignored) { }
            try { lm.removeTestProvider(p); } catch (Throwable ignored) { }
        }
        mocked.clear();
        mockActive = false;
        fusedFailed = false;
        mockedProviders = "—";
        hasOut = false;

        if (skipResub) return;

        try { lm.removeUpdates(gpsL); } catch (Throwable ignored) { }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                    gpsL, Looper.getMainLooper());
        } catch (Throwable ignored) { }
        gpsAt = SystemClock.elapsedRealtime();
        Logger.event("GPS_RESUB", "підписку на GPS перевидано");
    }

    public static void forceCleanup(Context c) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        String[] all = { LocationManager.GPS_PROVIDER, FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER };
        for (String p : all) {
            try { lm.setTestProviderEnabled(p, false); } catch (Throwable ignored) { }
            try { lm.removeTestProvider(p); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // індикація

    public static int colorOf(String s) {
        if (S_GREEN.equals(s)) return C_GREEN;
        if (S_YELLOW.equals(s)) return C_YELLOW;
        if (S_ORANGE.equals(s)) return C_ORANGE;
        if (S_RED.equals(s)) return C_RED;
        return C_GREY;
    }

    private void setState(String s, String why) {
        state = s;
        reason = why;
    }

    private Icon iconFor(String s) {
        Icon cached = iconCache.get(s);
        if (cached != null) return cached;
        int n = 96, c = n / 2;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        if (S_GREEN.equals(s)) {
            cv.drawCircle(c, c, 34, p);
        } else if (S_YELLOW.equals(s)) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(13);
            cv.drawCircle(c, c, 29, p);
        } else if (S_ORANGE.equals(s)) {
            Path path = new Path();
            path.moveTo(c, 12); path.lineTo(n - 10, n - 16); path.lineTo(10, n - 16);
            path.close();
            cv.drawPath(path, p);
        } else if (S_RED.equals(s)) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(15);
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(20, 20, n - 20, n - 20, p);
            cv.drawLine(n - 20, 20, 20, n - 20, p);
        } else {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(9);
            cv.drawCircle(c, c, 30, p);
        }
        Icon ic = Icon.createWithBitmap(b);
        iconCache.put(s, ic);
        return ic;
    }

    private void updateDot(int color) {
        if (!showDot || !Settings.canDrawOverlays(this)) { removeDot(); return; }
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (dot == null) {
                float d = getResources().getDisplayMetrics().density;
                dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setStroke((int) (2 * d), Color.argb(170, 0, 0, 0));
                dot = new View(this);
                dot.setBackground(dotBg);
                int sz = (int) (16 * d);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(sz, sz,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = (int) (8 * d);
                lp.y = (int) (56 * d);
                wm.addView(dot, lp);
            }
            dotBg.setColor(color);
            dot.invalidate();
        } catch (Throwable ignored) { }
    }

    private void removeDot() {
        if (dot == null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.removeView(dot);
        } catch (Throwable ignored) { }
        dot = null;
        dotBg = null;
    }

    private void buzz(String s) {
        if (!vibrate) return;
        boolean worse = (S_ORANGE.equals(s) && !S_RED.equals(prevState)) || S_RED.equals(s);
        if (!worse) return;
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            v.vibrate(VibrationEffect.createOneShot(
                    S_RED.equals(s) ? 250 : 120, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------
    // лог

    private static String f6(double v) { return String.format(Locale.US, "%.6f", v); }
    private static String f1(double v) { return String.format(Locale.US, "%.1f", v); }

    private void appendLoc(StringBuilder b, Location l) {
        if (l == null) { b.append(",,,,"); return; }
        b.append(f6(l.getLatitude())).append(',')
         .append(f6(l.getLongitude())).append(',')
         .append(f1(l.hasAccuracy() ? l.getAccuracy() : 0)).append(',')
         .append(ageMs(l)).append(',');
    }

    private String stateRow() {
        StringBuilder b = new StringBuilder(320);
        b.append(Logger.utcNow()).append(',')
         .append(SystemClock.elapsedRealtime()).append(',')
         .append(VER).append(',').append(Logger.SCHEMA).append(',')
         .append(state).append(',').append(Logger.esc(reason)).append(',')
         .append(source).append(',').append(inSrc).append(',')
         .append(usedInFix).append(',').append(visible).append(',')
         .append(f1(cn0Top)).append(',').append(f1(cn0Sd)).append(',')
         .append(f1(degenRatio)).append(',')
         .append(agcDelta.containsKey("1575") ? f1(agcDelta.get("1575")) : "").append(',')
         .append(agcDelta.containsKey("1602") ? f1(agcDelta.get("1602")) : "").append(',')
         .append(towValid).append(',');
        appendLoc(b, gpsRaw);
        b.append(gpsTrusted ? 1 : 0).append(',')
         .append(divergence >= 0 ? f1(divergence) : "").append(',');
        appendLoc(b, netRaw);
        appendLoc(b, fusRaw);
        if (emitted) b.append(f6(emLat)).append(',').append(f6(emLon)).append(',')
                      .append(f1(emAcc)).append(',').append(f1(emSpd)).append(',')
                      .append(emBrg >= 0 ? f1(emBrg) : "").append(',')
                      .append(extrapMs).append(',');
        else b.append(",,,,,,");
        b.append(mockDenied ? -1 : (mockActive ? 1 : 0)).append(',')
         .append(rejectCode).append(',')
         .append(Obd.fresh() ? f1(Obd.speedKmh) : "").append(',')
         .append(Logger.esc(Obd.enabled ? Obd.link : "")).append(',')
         .append(Logger.esc(spoofFlags)).append(',')
         .append(f1(accStd)).append(',')
         .append(moving ? 1 : 0).append(',')
         .append(gyroSeen ? f1(heading()) : "").append(',')
         .append(hdgValid ? 1 : 0).append(',')
         .append(motionSrc).append(',')
         .append(f1(speedMps * 3.6f)).append(',')
         .append(Obd.ageMs()).append(',')
         .append(nmeaTrusted ? 1 : 0).append(',')
         .append(drValid ? drSrc : "").append(',')
         .append(drValid ? f1(drSig) : "").append(',')
         .append(gpsSrc).append(',')
         .append(nmeaSats).append(',')
         .append(nmeaLoc == null ? "" : String.valueOf(SystemClock.elapsedRealtime() - nmeaAt));
        return b.toString();
    }

    private long lastNotif = 0;
    private void publish() {
        boolean changed = !state.equals(prevState);
        long now = SystemClock.elapsedRealtime();
        Logger.state(stateRow());
        if (changed) {
            Logger.event("STATE",
                    (prevState.isEmpty() ? "—" : prevState) + ">" + state + " " + reason
                    + (spoofFlags.equals("—") ? "" : " [" + spoofFlags + "]"));
            buzz(state);
        }
        updateDot(colorOf(state));
        if (!changed && now - lastNotif < 3000) return;
        lastNotif = now;
        prevState = state;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotif());
        } catch (Throwable ignored) { }
    }

    private String subtitle() {
        if (mockDenied) return "МОК ЗАБОРОНЕНО — оберіть застосунок у Developer options";
        if (S_ORANGE.equals(state))
            return String.format(Locale.US, "%s · ±%.0f м · %.0f км/год",
                    reason, acc, speedMps * 3.6f);
        if (S_GREEN.equals(state) || S_YELLOW.equals(state))
            return reason + " · супутників " + usedInFix;
        return reason;
    }

    private Notification buildNotif() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_ID)
                .setContentTitle(mockDenied ? "GNSS Filter · НЕ ПРАЦЮЄ" : "GNSS Filter · " + state)
                .setContentText(subtitle())
                .setSmallIcon(iconFor(state))
                .setColor(colorOf(state))
                .setColorized(true)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------------
    // життєвий цикл

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Чи обрано нас застосунком для мок-локації. Дізнаємось ДО старту, а не
     * за SecurityException після. Якщо перевірка недоступна — не лякаємо.
     */
    public static boolean mockAllowed(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ao == null) return true;
            return ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(), c.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return true; }
    }

    /** Меню розробника з підсвіткою пункту вибору мок-застосунку. */
    public static void openMockPicker(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(":settings:fragment_args_key", "mock_location_app");
            android.os.Bundle b = new android.os.Bundle();
            b.putString(":settings:fragment_args_key", "mock_location_app");
            i.putExtra(":settings:show_fragment_args", b);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) { }
        }
    }

    public static Intent overlaySettings(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
    }

    @Override public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        h = new Handler(Looper.getMainLooper());
        Logger.init(this);
        loadAgcBase();
        forceCleanup(this);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (running) return START_STICKY;

        if (!hasLocationPermission()) {
            mockError = "Немає дозволу на точну локацію — служба не стартувала";
            state = "—";
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!mockAllowed(this)) {
            mockDenied = true;
            mockError = "МОК ЗАБОРОНЕНО: Developer options → Select mock location app";
            Logger.event("MOCK_DENIED", "перевірка AppOps до старту");
        }
        state = S_WARMUP;
        reason = "прогрів";
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH_ID, "GNSS Filter",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            startForeground(NOTIF_ID, buildNotif(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Throwable t) {
            mockError = "foreground: " + t;
            state = "—";
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        mockError = null;
        warm = true;
        badStreak = 0; goodStreak = 0;
        gpsTrusted = true;
        gpsRaw = null; prevGps = null; netRaw = null; fusRaw = null;
        ref = null; hasOut = false; emitted = false;
        vE = 0; vN = 0;
        gpsAt = 0; redSince = 0;
        spoofLatch = false;
        strongStreak = 0;
        probing = false; probeStart = 0; lastProbeEnd = 0;
        stillSince = 0; fakeMotion = 0; lastCrit = false; precise = false;
        hasHold = false; probeTier = 2; probeFails = 0;
        cValid = false; cPending = null; cLastRaw = null; lastSpeedAt = 0;
        drValid = false; drDisagree = 0; drSpeed = 0; drLastAnchor = null;
        drSrc = "—"; drSig0 = 0;
        starvedStreak = 0; starvedLogged = false; netPrev = null; netCur = null;
        obdStillSince = 0; lastGsvAt = 0; loopStreak = 0; lastEmitWall = 0;
        nmeaTrusted = true; nmeaWhy = "—";
        nmeaLoc = null; nmeaAt = 0; nmeaSats = 0; gpsSrc = "—";
        mockDenied = false; mockDeniedAt = 0; mockDeniedBuzzAt = 0;
        usedInFix = 0; visible = 0; towValid = 0;
        divergence = -1; spoofFlags = "—";
        agcRing.clear(); agcPos.clear(); agcNow.clear();
        agcDelta = new HashMap<>();
        // agcBase НЕ чистимо: вона пережила попередній запуск і це її сенс.
        startedAt = SystemClock.elapsedRealtime();
        lastStateChangeAt = startedAt;
        prevState = "";

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnssfilter:tick");
            wl.setReferenceCounted(false);
            wl.acquire();
        } catch (Throwable ignored) { }

        try {
            sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a != null) sm.registerListener(accL, a, SensorManager.SENSOR_DELAY_GAME);
            else Logger.event("NO_ACCEL", "акселерометра немає — рух не детектується");
            Sensor gy = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gy != null) sm.registerListener(gyroL, gy, SensorManager.SENSOR_DELAY_GAME);
            else Logger.event("NO_GYRO", "гіроскопа немає — курс лише з мережі");
        } catch (Throwable t) { Logger.event("NO_ACCEL", t.toString()); }
        accCnt = 0; accIdx = 0; stillStreak = 0; moving = false; hasStill = false;
        hdgValid = false; hdgAbs = false; hdgDeg = -1; gyroTs = 0;
        rawYaw = 0; hdgOffset = 0; gyroSeen = false; obdMismatch = 0;
        if (Obd.enabled) Obd.start(this);

        nmeaLoc = null; nmeaAt = 0; nmeaSats = 0; gpsSrc = "—";
        try { lm.addNmeaListener(nmeaL, h); }
        catch (Throwable t) { Logger.event("NO_NMEA", t.toString()); }
        try { lm.registerGnssStatusCallback(statusCb, h); }
        catch (Throwable t) { mockError = "status: " + t; }
        try { lm.registerGnssMeasurementsCallback(measCb, h); }
        catch (Throwable ignored) { }

        int sub = 0;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                    gpsL, Looper.getMainLooper());
            sub++;
        } catch (Throwable ignored) { }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0,
                    netL, Looper.getMainLooper());
            sub++;
        } catch (Throwable ignored) { }
        try {
            lm.requestLocationUpdates(FUSED_PROVIDER, 1000, 0,
                    fusL, Looper.getMainLooper());
            sub++;
        } catch (Throwable t) {
            Logger.event("NO_FUSED", t.toString());
        }

        Logger.event("START", Build.MANUFACTURER + " " + Build.MODEL
                + " sdk=" + Build.VERSION.SDK_INT + " джерел=" + sub
                + " mockFused=" + mockFused + " thr=" + accThreshold
                + " agcBase=" + (agcBaseKnown ? agcBase.toString() : "немає"));

        h.removeCallbacks(tick);
        h.removeCallbacks(pump);
        h.post(tick);
        h.postDelayed(pump, PUMP_MS);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        Logger.event("STOP", "відкинуто=" + rejected);
        h.removeCallbacks(tick);
        h.removeCallbacks(pump);
        try { if (sm != null) sm.unregisterListener(accL); } catch (Throwable ignored) { }
        try { if (sm != null) sm.unregisterListener(gyroL); } catch (Throwable ignored) { }
        Obd.stop();
        try { lm.removeNmeaListener(nmeaL); } catch (Throwable ignored) { }
        try { lm.unregisterGnssStatusCallback(statusCb); } catch (Throwable ignored) { }
        try { lm.unregisterGnssMeasurementsCallback(measCb); } catch (Throwable ignored) { }
        try { lm.removeUpdates(gpsL); } catch (Throwable ignored) { }
        try { lm.removeUpdates(netL); } catch (Throwable ignored) { }
        try { lm.removeUpdates(fusL); } catch (Throwable ignored) { }
        removeMock();
        removeDot();
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
        wl = null;
        state = "—";
        source = "—";
        reason = "—";
        Logger.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
