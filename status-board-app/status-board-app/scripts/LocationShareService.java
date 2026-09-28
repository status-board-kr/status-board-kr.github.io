package __PKG__;

// 위치 공유 전용 안드로이드 서비스 (setup-android.js가 앱 폴더로 복사합니다)
// 앱 화면(웹)이 닫혀도 계속 돌도록 위치 측정과 서버 저장을 전부 여기서 직접 합니다.
// - 1분마다 절전 모드로 위치 측정
// - 30m 이상 움직였거나 5분이 지나면 Firebase에 저장
// - 공유 시간이 끝나면 내 위치를 지우고 스스로 종료
// - 로그인 토큰은 refresh token으로 직접 갱신

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocationShareService extends Service {
    private static final String TAG = "LocationShare";
    private static final String PREFS = "location_share";
    private static final String CHANNEL = "location_share";
    private static final int NOTI_ID = 28352;
    private static final long INTERVAL_MS = 60 * 1000;          // 1분마다 측정
    private static final long HEARTBEAT_MS = 5 * 60 * 1000;     // 제자리여도 5분마다 저장
    private static final float MIN_MOVE_M = 30f;                // 30m 이상 움직이면 바로 저장

    static volatile boolean running = false;

    private FusedLocationProviderClient client;
    private LocationCallback callback;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Location lastFix = null;      // 마지막으로 받은 위치
    private Location lastSavedFix = null; // 마지막으로 저장한 위치
    private long lastSavedAt = 0;
    private boolean finishing = false;

    // ── 앱 화면에서 부르는 시작/종료 ──
    // 앱이 화면에 떠 있을 때만 시작할 수 있습니다 (안드로이드 12+ 규칙). 실패하면 false.
    static boolean start(Context ctx, String configJson) {
        prefs(ctx).edit().putString("config", configJson).putBoolean("active", true)
                .remove("idToken").remove("idExp").remove("refreshToken").apply();   // 새 로그인 정보로
        try {
            Intent i = new Intent(ctx, LocationShareService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "start failed", e);
            prefs(ctx).edit().putBoolean("active", false).apply();
            return false;
        }
    }

    static void stop(Context ctx) {
        prefs(ctx).edit().putBoolean("active", false).apply();
        ctx.stopService(new Intent(ctx, LocationShareService.class));
    }

    static boolean isActive(Context ctx) {
        return running && prefs(ctx).getBoolean("active", false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private JSONObject config() {
        try {
            String s = prefs(this).getString("config", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject cfg = config();
        // startForegroundService로 시작했으면 5초 안에 꼭 startForeground를 불러야 합니다
        if (!goForeground(cfg)) { stopSelf(); return START_NOT_STICKY; }
        if (cfg == null || !prefs(this).getBoolean("active", false)) { stopSelf(); return START_NOT_STICKY; }
        if (!withinHours(cfg)) { finish(); return START_NOT_STICKY; }
        finishing = false;
        running = true;
        startUpdates();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, INTERVAL_MS);
        return START_STICKY;   // 폰이 서비스를 죽이면 다시 살림
    }

    private boolean goForeground(JSONObject cfg) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "위치 공유", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            b.setContentTitle(cfg != null ? cfg.optString("title", "위치 공유 중") : "위치 공유 중")
             .setContentText(cfg != null ? cfg.optString("text", "동료에게 위치를 공유하고 있어요") : "동료에게 위치를 공유하고 있어요")
             .setSmallIcon(R.drawable.ic_tracking)
             .setOngoing(true);
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                b.setContentIntent(PendingIntent.getActivity(this, 0, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTI_ID, n);
            }
            return true;
        } catch (Exception e) {
            // 위치 권한이 없으면 여기서 실패합니다
            Log.w(TAG, "startForeground failed", e);
            return false;
        }
    }

    private void startUpdates() {
        try {
            if (client == null) client = LocationServices.getFusedLocationProviderClient(this);
            if (callback != null) client.removeLocationUpdates(callback);
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
                    .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
                    .build();
            callback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc != null) onFix(loc);
                }
            };
            client.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.w(TAG, "no location permission", e);
            finish();
        }
    }

    // 1분마다: 공유 시간이 끝났는지 확인 + 위치가 안 들어와도 5분마다 '살아있음' 저장
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            JSONObject cfg = config();
            if (cfg == null || !withinHours(cfg)) { finish(); return; }
            if (lastFix != null && System.currentTimeMillis() - lastSavedAt >= HEARTBEAT_MS) save(lastFix);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void onFix(Location loc) {
        JSONObject cfg = config();
        if (cfg == null || !withinHours(cfg)) { finish(); return; }
        lastFix = loc;
        boolean moved = lastSavedFix == null || lastSavedFix.distanceTo(loc) >= MIN_MOVE_M;
        boolean waited = System.currentTimeMillis() - lastSavedAt >= HEARTBEAT_MS;
        if (moved || waited) save(loc);
    }

    private void save(final Location loc) {
        lastSavedAt = System.currentTimeMillis();   // 실패해도 매분 재시도하지 않게 먼저 기록
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject cfg = config();
                    if (cfg == null) return;
                    JSONObject body = new JSONObject();
                    body.put("lat", loc.getLatitude());
                    body.put("lng", loc.getLongitude());
                    body.put("email", cfg.optString("email", ""));
                    body.put("at", isoNow());
                    int code = request("PUT", dbUrl(cfg, token(cfg, false)), body.toString(), "application/json");
                    if (code == 401) code = request("PUT", dbUrl(cfg, token(cfg, true)), body.toString(), "application/json");
                    if (code >= 200 && code < 300) lastSavedFix = loc;
                    else Log.w(TAG, "save failed: " + code);
                } catch (Exception e) { Log.w(TAG, "save error", e); }
            }
        });
    }

    // 공유 시간 끝 / 권한 없음: 내 위치 지우고 종료
    private void finish() {
        if (finishing) return;
        finishing = true;
        running = false;
        prefs(this).edit().putBoolean("active", false).apply();
        handler.removeCallbacks(tick);
        if (client != null && callback != null) client.removeLocationUpdates(callback);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject cfg = config();
                    if (cfg != null) request("DELETE", dbUrl(cfg, token(cfg, false)), null, null);
                } catch (Exception e) { Log.w(TAG, "delete error", e); }
                handler.post(new Runnable() { @Override public void run() { stopSelf(); } });
            }
        });
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 최근 앱 목록에서 밀어서 닫아도 계속 공유 (아무것도 안 함)
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        if (client != null && callback != null) client.removeLocationUpdates(callback);
        super.onDestroy();
    }

    // ── 공유 시간 (예: 09:00~18:00, 밤샘 20:00~06:00도 됨) ──
    private static int toMin(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return -1; }
    }

    private static boolean withinHours(JSONObject cfg) {
        int s = toMin(cfg.optString("start", "09:00")), e = toMin(cfg.optString("end", "18:00"));
        if (s < 0 || e < 0 || s == e) return false;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return s < e ? (now >= s && now < e) : (now >= s || now < e);
    }

    // ── 로그인 토큰: 5분 넘게 남았으면 그대로, 아니면 refresh token으로 새로 받음 ──
    private synchronized String token(JSONObject cfg, boolean force) throws Exception {
        SharedPreferences p = prefs(this);
        String id = p.getString("idToken", cfg.optString("idToken", ""));
        long exp = p.getLong("idExp", cfg.optLong("idExp", 0));
        if (!force && !id.isEmpty() && exp - System.currentTimeMillis() > 5 * 60 * 1000) return id;
        String refresh = p.getString("refreshToken", cfg.optString("refreshToken", ""));
        String form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8");
        String url = "https://securetoken.googleapis.com/v1/token?key=" + URLEncoder.encode(cfg.optString("apiKey", ""), "UTF-8");
        String[] out = new String[1];
        int code = request("POST", url, form, "application/x-www-form-urlencoded", out);
        if (code < 200 || code >= 300 || out[0] == null) throw new Exception("token refresh failed: " + code);
        JSONObject d = new JSONObject(out[0]);
        id = d.getString("id_token");
        exp = System.currentTimeMillis() + d.optLong("expires_in", 3600) * 1000;
        p.edit().putString("idToken", id).putLong("idExp", exp)
                .putString("refreshToken", d.optString("refresh_token", refresh)).apply();
        return id;
    }

    private static String dbUrl(JSONObject cfg, String token) throws Exception {
        return cfg.getString("dbUrl") + "/" + cfg.getString("path") + ".json?auth=" + URLEncoder.encode(token, "UTF-8");
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static int request(String method, String url, String body, String type) throws Exception {
        return request(method, url, body, type, null);
    }

    private static int request(String method, String url, String body, String type, String[] out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", type);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            if (out != null) {
                InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
                if (is != null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) buf.write(b, 0, n);
                    is.close();
                    out[0] = buf.toString("UTF-8");
                }
            }
            return code;
        } finally {
            c.disconnect();
        }
    }
}
