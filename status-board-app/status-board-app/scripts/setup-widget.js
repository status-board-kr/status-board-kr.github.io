// 홈 화면 위젯: 차량 현황·캘린더 하나 — setup-android.js 다음에 실행
// · 앱이 window.AndroidSettings.saveWidget(json)으로 요약 자료를 넘기면 위젯이 그림
// · 위젯 날짜를 누르면 앱이 그 날짜 일정 화면으로 열림 (consumeOpen 으로 전달)
const fs = require('fs'), path = require('path');
const main = path.join(__dirname, '..', 'android', 'app', 'src', 'main');
const manifestPath = path.join(main, 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) { console.error('android 폴더 없음'); process.exit(1); }

function findFile(dir, name){ for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
  const p = path.join(dir, f.name);
  if (f.isDirectory()) { const r = findFile(p, name); if (r) return r; } else if (f.name === name) return p; } return null; }
const mainActPath = findFile(path.join(main, 'java'), 'MainActivity.java');
if (!mainActPath) { console.error('MainActivity.java 없음'); process.exit(1); }
let act = fs.readFileSync(mainActPath, 'utf8');
const pkg = (act.match(/^package\s+([^;]+);/m) || [])[1] || 'com.jangsung.fleet';
const javaDir = path.dirname(mainActPath);

// 1) MainActivity: 위젯에서 넘어온 '열 화면' 받기 + 웹과 주고받는 통로
if (!act.includes('saveWidget')) {
  const bridgeStart = act.indexOf('class SettingsBridge');
  const braceAt = act.indexOf('{', bridgeStart);
  const bridgeMethods = [
    '',
    '        @android.webkit.JavascriptInterface',
    '        public void saveWidget(final String json) {',
    '            try {',
    '                getSharedPreferences("fleet_widget", MODE_PRIVATE).edit().putString("data", json).apply();',
    '                FleetWidgetUtil.refreshAll(MainActivity.this);',
    '                FleetWidgetWeather.refresh(MainActivity.this);',
    '            } catch (Exception ignored) { }',
    '        }',
    '',
    '        @android.webkit.JavascriptInterface',
    '        public String peekOpen() {',
    '            String s = pendingOpen; return s == null ? "" : s;',
    '        }',
    '',
    '        @android.webkit.JavascriptInterface',
    '        public void acknowledgeOpen(String handled) {',
    '            if (handled != null && handled.equals(pendingOpen)) pendingOpen = null;',
    '        }',
    '',
    '        @android.webkit.JavascriptInterface',
    '        public String consumeOpen() {',
    '            String s = pendingOpen; pendingOpen = null;',
    '            return s == null ? "" : s;',
    '        }',
    ''
  ].join('\n');
  act = act.slice(0, braceAt + 1) + bridgeMethods + act.slice(braceAt + 1);
  // 액티비티 본문에 필드·메서드 추가 (클래스 여는 괄호 바로 뒤)
  const clsAt = act.indexOf('public class MainActivity');
  const clsBrace = act.indexOf('{', clsAt);
  const actMethods = [
    '',
    '    public static volatile String pendingOpen = null;',
    '    private final android.os.Handler widgetOpenHandler = new android.os.Handler(android.os.Looper.getMainLooper());',
    '    private final Runnable widgetOpenDelivery = new Runnable() {',
    '        @Override public void run() {',
    '            if (isFinishing() || isDestroyed() || pendingOpen == null) return;',
    '            if (getBridge() != null && getBridge().getWebView() != null) {',
    '                getBridge().getWebView().evaluateJavascript("if(typeof checkWidgetOpen === \'function\') checkWidgetOpen();", null);',
    '            }',
    '            widgetOpenHandler.postDelayed(this, 500);',
    '        }',
    '    };',
    '',
    '    private void dispatchWidgetOpen() {',
    '        widgetOpenHandler.removeCallbacks(widgetOpenDelivery);',
    '        if (pendingOpen != null) widgetOpenHandler.post(widgetOpenDelivery);',
    '    }',
    '',
    '    private void captureOpen(android.content.Intent it) {',
    '        if (it == null) return;',
    '        String v = it.getStringExtra("fleetOpen");',
    '        if (v != null) { pendingOpen = v; it.removeExtra("fleetOpen"); }',
    '    }',
    '',
    '    @Override',
    '    protected void onNewIntent(android.content.Intent intent) {',
    '        super.onNewIntent(intent);',
    '        setIntent(intent);',
    '        captureOpen(intent);',
    '        dispatchWidgetOpen();',
    '    }',
    '',
    '    @Override',
    '    public void onPause() {',
    '        widgetOpenHandler.removeCallbacks(widgetOpenDelivery);',
    '        super.onPause();',
    '    }',
    '',
    '    @Override',
    '    public void onDestroy() {',
    '        widgetOpenHandler.removeCallbacks(widgetOpenDelivery);',
    '        super.onDestroy();',
    '    }',
    '',
    '    @Override',
    '    public void onResume() {',
    '        super.onResume();',
    '        captureOpen(getIntent());',
    '        dispatchWidgetOpen();',
    '    }',
    ''
  ].join('\n');
  act = act.slice(0, clsBrace + 1) + actMethods + act.slice(clsBrace + 1);
  fs.writeFileSync(mainActPath, act);
  console.log('widget bridge + open handling added');
}

// 2) 위젯 그리는 코드
const util = `package ${pkg};

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

public class FleetWidgetUtil {
    public static final int LARGE = 2;
    public static final int COMPACT = 3;

    public static JSONObject load(Context ctx) {
        try {
            String s = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getString("data", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    public static void refreshAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        update(ctx, m, m.getAppWidgetIds(new ComponentName(ctx, FleetCalendarWidget.class)), LARGE);
        int[] compact = m.getAppWidgetIds(new ComponentName(ctx, FleetCompactWidget.class));
        update(ctx, m, compact, COMPACT);
    }

    public static void update(Context ctx, AppWidgetManager m, int[] ids, int size) {
        if (ids == null) return;
        for (int id : ids) m.updateAppWidget(id, build(ctx, size));
    }

    // 앱을 열면서 특정 화면으로 보내는 버튼 (open: "" | "chat" | "date:YYYY-MM-DD")
    static PendingIntent opener(Context ctx, String open, int code) {
        Intent it = new Intent(ctx, MainActivity.class);
        it.setAction("fleet.open." + code + "." + open);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (open.length() > 0) it.putExtra("fleetOpen", open);
        return PendingIntent.getActivity(ctx, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent schedulePopup(Context ctx, String date, int code) {
        Intent it = new Intent(ctx, FleetWidgetScheduleActivity.class);
        it.setAction("fleet.schedule.popup." + date);
        it.putExtra("date", date);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // 날짜를 누르면 앱을 열지 않고 위젯 안에서 그 날 일정으로 바꿈
    static PendingIntent selector(Context ctx, String ds, int code, int size) {
        Intent it = new Intent(ctx, size == COMPACT ? FleetCompactWidget.class : FleetCalendarWidget.class);
        it.setAction("fleet.widget.SELECT");
        it.putExtra("sel", ds);
        return PendingIntent.getBroadcast(ctx, code + size*1000, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String ymd(int y, int m, int d) {
        return y + "-" + (m < 10 ? "0" : "") + m + "-" + (d < 10 ? "0" : "") + d;
    }

    static int id(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }
    static int drawable(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
    }

    static String lines(JSONArray a, int max, String empty) {
        if (a == null || a.length() == 0) return empty;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length() && i < max; i++) {
            if (sb.length() > 0) sb.append("\\n");
            sb.append(a.optString(i));
        }
        if (a.length() > max) sb.append("\\n외 ").append(a.length() - max).append("건");
        return sb.toString();
    }

    static RemoteViews build(Context ctx, int size) {
        int layout = size == COMPACT ? R.layout.widget_compact : R.layout.widget_large;
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        v.setOnClickPendingIntent(R.id.w_root, opener(ctx, "", 1));
        v.setOnClickPendingIntent(R.id.w_unread, opener(ctx, "chat", 2));
        JSONObject d = load(ctx);
        if (d == null) return v;
        String[] fields = {"total", "idle", "prep", "insurance", "general", "long"};
        for (String field : fields) v.setTextViewText(id(ctx, "w_" + field), String.valueOf(d.optInt(field)));
        int unread = d.optInt("unread");
        v.setTextViewText(R.id.w_unread, unread > 0 ? "메신저 " + unread : "메신저");
        v.setInt(R.id.w_unread, "setBackgroundResource", unread > 0 ? R.drawable.wchip_red : R.drawable.wchip_gray);
        v.setTextViewText(R.id.w_updated, d.optString("updated"));
        JSONObject health = d.optJSONObject("agentHealth");
        String state = "앱 열어 연결 확인";
        int stateColor = Color.parseColor("#849087");
        if (health != null) {
            try {
                java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                long lastSeen = utc.parse(health.optString("lastSeen").substring(0,19)).getTime();
                long age = System.currentTimeMillis() - lastSeen;
                if (age >= 0 && age < 45000) {
                    boolean ok = "ok".equals(health.optString("loginState"));
                    state = ok ? "● 봇 연결됨" : "● 로그인 실패";
                    stateColor = Color.parseColor(ok ? "#009B5C" : "#BD5959");
                } else state = "연결 확인 필요";
            } catch (Exception ignored) { }
        }
        v.setTextViewText(R.id.w_health, state);
        v.setTextColor(R.id.w_health, stateColor);
        JSONObject base = d.optJSONObject("cal");
        if (base == null) return v;
        JSONObject months = d.optJSONObject("months");
        String currentKey = ymd(base.optInt("y"), base.optInt("m"), 1).substring(0, 7);
        String key = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getString("month"+size, currentKey);
        JSONObject cal = months == null ? null : months.optJSONObject(key);
        if (cal == null) { cal = base; key = currentKey; }
        int year = cal.optInt("y"), month = cal.optInt("m"), today = cal.optInt("today");
        int first = cal.optInt("firstDow"), days = cal.optInt("days");
        JSONObject count = cal.optJSONObject("count"), items = cal.optJSONObject("items"), events = cal.optJSONObject("events");
        String selected = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getString("sel"+size, "");
        int selDay = today > 0 ? today : 1;
        if (selected.startsWith(key + "-")) {
            try { selDay = Integer.parseInt(selected.substring(8)); } catch (Exception ignored) { }
        }
        if (selDay < 1 || selDay > days) selDay = today > 0 ? today : 1;
        v.setTextViewText(R.id.w_month, year + ". " + (month < 10 ? "0" : "") + month);
        java.util.Calendar cursor = java.util.Calendar.getInstance();
        cursor.set(year, month - 1, 1);
        for (int delta : new int[]{-1, 1}) {
            java.util.Calendar other = (java.util.Calendar)cursor.clone(); other.add(java.util.Calendar.MONTH, delta);
            String target = ymd(other.get(java.util.Calendar.YEAR), other.get(java.util.Calendar.MONTH)+1, 1);
            if (months == null || months.optJSONObject(target.substring(0,7)) == null) target = ymd(year, month, selDay);
            v.setOnClickPendingIntent(delta < 0 ? R.id.w_prev : R.id.w_next, selector(ctx, target, delta < 0 ? 50 : 51, size));
        }
        v.setOnClickPendingIntent(R.id.w_today, selector(ctx, d.optString("todayStr"), 52, size));
        int rows = (first + days + 6) / 7;
        for (int row = 0; row < 6; row++) v.setViewVisibility(id(ctx, "w_r" + row), row < rows ? android.view.View.VISIBLE : android.view.View.GONE);
        for (int i = 0; i < 42; i++) {
            int cell = id(ctx, "w_c" + i), day = i - first + 1;
            if (day < 1 || day > days) {
                v.setTextViewText(cell, ""); v.setInt(cell, "setBackgroundResource", 0);
                v.setOnClickPendingIntent(cell, selector(ctx, "", 100+i, size));
                continue;
            }
            int n = count == null ? 0 : count.optInt(String.valueOf(day));
            JSONArray dayEvents = events == null ? null : events.optJSONArray(String.valueOf(day));
            JSONObject firstEvent = dayEvents == null ? null : dayEvents.optJSONObject(0);
            String label = "";
            if (firstEvent != null) {
                String title = firstEvent.optString("title");
                if ("회수".equals(firstEvent.optString("kind")) && title.length() >= 4) label = title.substring(title.length()-4) + "회수";
                else label = title.length() > 5 ? title.substring(0,4) + "…" : title;
            } else if (n > 0) label = "일정 " + n + "건";
            String weather = size == COMPACT ? FleetWidgetWeather.icon(ctx, ymd(year, month, day)) : "";
            String dayText = String.valueOf(day) + (weather.isEmpty() ? "" : " " + weather);
            android.text.SpannableString cellText = new android.text.SpannableString(dayText + (label.isEmpty() ? "" : "\\n" + label));
            if (!label.isEmpty()) {
                int start = dayText.length()+1;
                cellText.setSpan(new android.text.style.AbsoluteSizeSpan(9, true), start, cellText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cellText.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor(day == selDay ? "#FFFFFF" : "#008F54")), start, cellText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            v.setTextViewText(cell, cellText);
            int color = day == selDay ? Color.parseColor("#FFFFFF") : Color.parseColor(i%7 == 0 ? "#C76C6C" : i%7 == 6 ? "#5987AD" : "#334339");
            v.setTextColor(cell, color);
            v.setInt(cell, "setBackgroundResource", day == selDay ? R.drawable.wcell_sel : day == today ? R.drawable.wcell_today : 0);
            // 기존형은 위젯 안에서 선택, 간편형은 홈 화면 위에 해당 날짜 일정 팝업.
            v.setOnClickPendingIntent(cell, size == COMPACT ? schedulePopup(ctx, ymd(year, month, day), 300+i) : selector(ctx, ymd(year, month, day), 100+i, size));
        }
        String date = ymd(year, month, selDay);
        if (size == LARGE) {
        v.setTextViewText(R.id.w_seltitle, month + "월 " + selDay + "일" + (today == selDay ? " · 오늘" : ""));
        JSONArray detail = events == null ? null : events.optJSONArray(String.valueOf(selDay));
        JSONArray fallback = items == null ? null : items.optJSONArray(String.valueOf(selDay));
        int total = count == null ? 0 : count.optInt(String.valueOf(selDay));
        v.setTextViewText(R.id.w_count, "일정 " + total + "건");
        v.setViewVisibility(R.id.w_empty, total == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        for (int i=0; i<2; i++) {
            JSONObject event = detail == null ? null : detail.optJSONObject(i);
            String old = fallback == null ? "" : fallback.optString(i);
            boolean exists = event != null || !old.isEmpty();
            v.setViewVisibility(id(ctx,"w_event"+i), exists ? android.view.View.VISIBLE : android.view.View.GONE);
            v.setTextViewText(id(ctx,"w_time"+i), event == null ? "" : event.optString("time"));
            v.setTextViewText(id(ctx,"w_title"+i), event == null ? old : event.optString("title"));
            String note = event == null ? "" : event.optString("note");
            v.setTextViewText(id(ctx,"w_note"+i), note);
            v.setViewVisibility(id(ctx,"w_note"+i), note.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            v.setTextViewText(id(ctx,"w_kind"+i), event == null ? "일정" : event.optString("kind","일정"));
            v.setOnClickPendingIntent(id(ctx,"w_event"+i), opener(ctx,"date:"+date,200+i));
        }
        v.setTextViewText(R.id.w_more, total > 2 ? "외 " + (total - 2) + "건 · 앱에서 보기" : "");
        v.setViewVisibility(R.id.w_more,total > 2 ? android.view.View.VISIBLE : android.view.View.GONE);
        v.setOnClickPendingIntent(R.id.w_more, opener(ctx,"date:"+date,202));
        } else {
            v.setTextViewText(R.id.w_weather, FleetWidgetWeather.status(ctx));
            v.setOnClickPendingIntent(R.id.w_weather, PendingIntent.getActivity(ctx, 909, new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://docs.api.met.no/doc/License.html")), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        v.setOnClickPendingIntent(R.id.w_openapp, opener(ctx,"date:"+date,3));
        return v;
    }
}
`;
fs.writeFileSync(path.join(javaDir, 'FleetWidgetUtil.java'), util);
fs.writeFileSync(path.join(javaDir, 'FleetWidgetScheduleActivity.java'), fs.readFileSync(path.join(__dirname, 'widget-schedule-popup.java'), 'utf8').replaceAll('PACKAGE_NAME', pkg));
for (const [name, size] of [['FleetCalendarWidget','LARGE'],['FleetCompactWidget','COMPACT']]) {
  const onReceive = false ? '' : `

    // 위젯 안에서 날짜를 눌렀을 때: 고른 날짜를 저장하고 다시 그림
    @Override
    public void onReceive(Context ctx, android.content.Intent intent) {
        super.onReceive(ctx, intent);
        if ("fleet.widget.SELECT".equals(intent.getAction())) {
            String s = intent.getStringExtra("sel");
            if (s == null || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return;
            ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).edit().putString("sel"+FleetWidgetUtil.${size}, s).putString("month"+FleetWidgetUtil.${size}, s.substring(0,7)).apply();
            AppWidgetManager m = AppWidgetManager.getInstance(ctx);
            FleetWidgetUtil.update(ctx, m, m.getAppWidgetIds(new android.content.ComponentName(ctx, ${name}.class)), FleetWidgetUtil.${size});
        }
    }`;
  fs.writeFileSync(path.join(javaDir, name + '.java'),
`package ${pkg};

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class ${name} extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        FleetWidgetUtil.update(ctx, m, ids, FleetWidgetUtil.${size});
    }${onReceive}
}
`);
}
console.log('widget java written');

// 3) 모양 (배경·칩·달력 칸)
const res = path.join(main, 'res');
['layout', 'xml', 'drawable'].forEach(d => fs.mkdirSync(path.join(res, d), { recursive: true }));
const shape = (fill, radius, stroke) => `<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
  <solid android:color="${fill}"/>
  <corners android:radius="${radius}dp"/>${stroke ? `
  <stroke android:width="1dp" android:color="${stroke}"/>` : ''}
</shape>
`;
const D = {
  widget_bg:   shape('#F20F172A', 20, '#1E293B'),
  wchip_amber: shape('#33F5A623', 10, '#66F5A623'),
  wchip_green: shape('#3334D399', 10, '#6634D399'),
  wchip_blue:  shape('#3360A5FA', 10, '#6660A5FA'),
  wchip_purple: shape('#33C084FC', 10, '#66C084FC'),
  wchip_gray:  shape('#331E293B', 10, '#334B5563'),
  wchip_red:   shape('#E6EF4444', 10, null),
  wcell_today: shape('#FFF5A623', 8, null),
  wcell_mark:  shape('#2634D399', 8, '#8034D399'),
  wcell_sel:   shape('#00000000', 8, '#FFF5A623'),
  wlist_bg:    shape('#661E293B', 12, null)
};
for (const [n, x] of Object.entries(D)) fs.writeFileSync(path.join(res, 'drawable', n + '.xml'), x);

const A = 'xmlns:android="http://schemas.android.com/apk/res/android"';
const chip = (id, bg, color, label) => `
    <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
      android:layout_marginEnd="4dp" android:orientation="vertical" android:gravity="center"
      android:paddingTop="4dp" android:paddingBottom="4dp" android:background="@drawable/${bg}">
      <TextView android:id="@+id/${id}" android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="-" android:textColor="${color}" android:textSize="17sp" android:textStyle="bold" android:maxLines="1"/>
      <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:text="${label}" android:textColor="${color}" android:textSize="10sp" android:maxLines="1"/>
    </LinearLayout>`;
const header = `
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
    <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
      android:text="🚗 현황판" android:textColor="#F5A623" android:textSize="14sp" android:textStyle="bold"/>
    <TextView android:id="@+id/w_unread" android:layout_width="wrap_content" android:layout_height="wrap_content"
      android:paddingStart="9dp" android:paddingEnd="9dp" android:paddingTop="3dp" android:paddingBottom="3dp"
      android:background="@drawable/wchip_gray" android:text="💬 0" android:textColor="#FFFFFF" android:textSize="12sp" android:textStyle="bold"/>
  </LinearLayout>`;
const chips = `
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:layout_marginTop="8dp">${chip('w_idle','wchip_amber','#FBBF24','대기')}${chip('w_prep','wchip_blue','#60A5FA','준비')}${chip('w_run','wchip_green','#34D399','운행')}${chip('w_long','wchip_purple','#C084FC','장기')}
  </LinearLayout>`;
const list = (h) => `
  <TextView android:id="@+id/w_list" android:layout_width="match_parent" android:layout_height="${h}" ${h === '0dp' ? 'android:layout_weight="1"' : ''}
    android:layout_marginTop="8dp" android:padding="8dp" android:background="@drawable/wlist_bg"
    android:text="" android:textColor="#E2E8F0" android:textSize="12sp" android:lineSpacingExtra="2dp"/>`;
const updated = `
  <TextView android:id="@+id/w_updated" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginTop="4dp" android:gravity="end" android:text="" android:textColor="#64748B" android:textSize="9sp"/>`;
const root = (inner) => `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout ${A}
  android:id="@+id/w_root" android:layout_width="match_parent" android:layout_height="match_parent"
  android:orientation="vertical" android:padding="12dp" android:background="@drawable/widget_bg">${inner}
</LinearLayout>
`;
// 달력: 요일 줄 + 6주 × 7일 (칸마다 눌러서 그 날짜 일정 열기)
let cal = `
  <TextView android:id="@+id/w_month" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginTop="8dp" android:text="" android:textColor="#E2E8F0" android:textSize="13sp" android:textStyle="bold"/>
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:layout_marginTop="2dp">`;
['일','월','화','수','목','금','토'].forEach((w, i) => {
  cal += `
    <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:gravity="center"
      android:text="${w}" android:textSize="10sp" android:textColor="${i === 0 ? '#F87171' : (i === 6 ? '#60A5FA' : '#94A3B8')}"/>`;
});
cal += `
  </LinearLayout>`;
for (let r = 0; r < 6; r++) {
  cal += `
  <LinearLayout android:id="@+id/w_r${r}" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">`;
  for (let c = 0; c < 7; c++) {
    cal += `
    <TextView android:id="@+id/w_c${r * 7 + c}" android:layout_width="0dp" android:layout_weight="1" android:layout_height="match_parent"
      android:layout_margin="1dp" android:gravity="center" android:text="" android:textSize="11sp" android:lineSpacingMultiplier="0.85" android:textColor="#E2E8F0"/>`;
  }
  cal += `
  </LinearLayout>`;
}
fs.writeFileSync(path.join(res, 'layout', 'widget_small.xml'), root(header + chips));
fs.writeFileSync(path.join(res, 'layout', 'widget_medium.xml'), root(header + chips + list('0dp') + updated));
const selRow = `
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal"
    android:gravity="center_vertical" android:layout_marginTop="8dp">
    <TextView android:id="@+id/w_seltitle" android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
      android:text="" android:textColor="#F5A623" android:textSize="13sp" android:textStyle="bold"/>
    <TextView android:id="@+id/w_openapp" android:layout_width="wrap_content" android:layout_height="wrap_content"
      android:paddingStart="10dp" android:paddingEnd="10dp" android:paddingTop="4dp" android:paddingBottom="4dp"
      android:background="@drawable/wchip_gray" android:text="앱에서 보기 ›" android:textColor="#CBD5E1" android:textSize="11sp"/>
  </LinearLayout>`;
fs.writeFileSync(path.join(res, 'layout', 'widget_large.xml'), root(header + chips + cal + selRow +
  list('wrap_content').replace('android:layout_marginTop="8dp"', 'android:layout_marginTop="4dp" android:minHeight="64dp" android:maxLines="5"') + updated));

const info = (layout, w, h, cells) => `<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider ${A}
  android:minWidth="${w}dp" android:minHeight="${h}dp"
  android:targetCellWidth="${cells[0]}" android:targetCellHeight="${cells[1]}"
  android:updatePeriodMillis="1800000" android:initialLayout="@layout/${layout}"
  android:resizeMode="horizontal|vertical" android:widgetCategory="home_screen"/>
`;
fs.writeFileSync(path.join(res, 'xml', 'widget_small_info.xml'), info('widget_small', 250, 90, [4,1]));
fs.writeFileSync(path.join(res, 'xml', 'widget_medium_info.xml'), info('widget_medium', 250, 150, [4,2]));
fs.writeFileSync(path.join(res, 'xml', 'widget_large_info.xml'), info('widget_large', 250, 320, [4,4]));
console.log('widget layouts written');

// 4) 앱 설정에 위젯 3종 등록
let man = fs.readFileSync(manifestPath, 'utf8');
man = man.replace(/<receiver\b[^>]*android:name="\.FleetWidget(?:Small|Medium|Large)"[^>]*>[\s\S]*?<\/receiver>/g, '');
const rec = (cls, xml, label) => `
        <receiver android:name=".${cls}" android:exported="false" android:label="${label}">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/${xml}"/>
        </receiver>`;
man=man.replace(/<receiver\b[^>]*android:name="\.Fleet(?:Calendar|Compact)Widget"[^>]*>[\s\S]*?<\/receiver>/g,'');
man=man.replace('</application>',rec('FleetCalendarWidget','widget_large_info','차량 현황 · 기존형')+rec('FleetCompactWidget','widget_compact_info','차량 현황 · 간편형')+'\n</application>');
man=man.replace(/<activity\b[^>]*android:name="\.FleetWidgetScheduleActivity"[^>]*\/>/g,'');
man=man.replace('</application>',`<activity android:name=".FleetWidgetScheduleActivity" android:exported="false" android:excludeFromRecents="true" android:launchMode="singleTask" android:taskAffinity="${pkg}.widgetpopup" android:theme="@android:style/Theme.Material.Light.Dialog.NoActionBar" />\n</application>`);

// Approved white/green calendar widget, six fleet counts and selected-day agenda.
const lightDrawables = {
  widget_bg:shape('#FFFFFFFF',23,null),wchip_gray:shape('#FFF5F8F6',7,null),
  wchip_red:shape('#FFFCECEC',7,null),wcell_today:shape('#FFE9F8EF',9,null),
  wcell_sel:shape('#FF00A762',9,null),wlist_bg:shape('#FFF5F8F6',10,null),
  wline_green:shape('#FF00A762',2,null)
};
for(const [name,xml]of Object.entries(lightDrawables))fs.writeFileSync(path.join(res,'drawable',name+'.xml'),xml);
const text=(id,value,size,color,extra='')=>'<TextView '+(id?'android:id="@+id/'+id+'" ':'')+'android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="'+value+'" android:textSize="'+size+'sp" android:textColor="'+color+'" '+extra+'/>';
const whiteHeader='<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:orientation="horizontal">'+text('','차량 현황',15,'#293C32','android:textStyle="bold" android:layout_weight="1"')+text('w_unread','메신저',10,'#537B64','android:padding="5dp" android:background="@drawable/wchip_gray"')+'</LinearLayout>';
const healthRow=text('w_health','앱 열어 연결 확인',9,'#849087','android:layout_gravity="end" android:layout_marginTop="3dp"');
const fleetCounts='<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="10dp" android:background="@drawable/wlist_bg" android:paddingTop="7dp" android:paddingBottom="7dp" android:orientation="horizontal">'+[['total','전체'],['idle','대기'],['prep','준비중'],['insurance','보험'],['general','일반'],['long','장기']].map(([id,label])=>'<LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical" android:gravity="center">'+text('',label,9,'#748079')+text('w_'+id,'-',17,id==='total'?'#009B5C':'#293C32','android:textStyle="bold" android:layout_marginTop="3dp"')+'</LinearLayout>').join('')+'</LinearLayout>';
const lightRoot=inner=>'<?xml version="1.0" encoding="utf-8"?><LinearLayout '+A+' android:id="@+id/w_root" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="14dp" android:background="@drawable/widget_bg">'+inner+'</LinearLayout>';
const monthRow='<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:layout_marginTop="10dp" android:orientation="horizontal">'+text('w_month','',19,'#293C32','android:textStyle="bold" android:layout_weight="1"')+text('w_prev','‹',24,'#758178','android:paddingStart="9dp" android:paddingEnd="9dp"')+text('w_next','›',24,'#758178','android:paddingStart="9dp" android:paddingEnd="9dp"')+text('w_today','오늘',10,'#758178','android:padding="6dp" android:background="@drawable/wchip_gray"')+'</LinearLayout>';
let lightCal='<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:layout_marginTop="8dp" android:layout_marginBottom="4dp">'+['일','월','화','수','목','금','토'].map((day,i)=>text('',day,10,i===0?'#C76C6C':i===6?'#5987AD':'#849087','android:layout_weight="1" android:gravity="center"')).join('')+'</LinearLayout>';
for(let row=0;row<6;row++){lightCal+='<LinearLayout android:id="@+id/w_r'+row+'" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal" android:baselineAligned="false">';for(let col=0;col<7;col++)lightCal+=text('w_c'+(row*7+col),'',14,'#334339','android:layout_weight="1" android:gravity="center" android:layout_margin="1dp" android:lineSpacingMultiplier="1.0" android:maxLines="2" android:ellipsize="end"').replace('android:layout_width="wrap_content" android:layout_height="wrap_content"','android:layout_width="0dp" android:layout_height="match_parent"');lightCal+='</LinearLayout>';}
const separator='<TextView android:layout_width="match_parent" android:layout_height="1dp" android:background="#E9EDEA" android:layout_marginTop="8dp" android:layout_marginBottom="8dp"/>';
const selectedRow='<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical" android:orientation="horizontal">'+text('w_seltitle','오늘 일정',12,'#293C32','android:textStyle="bold" android:layout_weight="1"')+text('w_count','',9,'#849087')+'</LinearLayout>';
let agenda=text('w_empty','등록된 일정이 없습니다',11,'#849087','android:paddingTop="8dp" android:paddingBottom="8dp"');
for(let i=0;i<2;i++)agenda+='<LinearLayout android:id="@+id/w_event'+i+'" android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="37dp" android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginTop="5dp">'+text('w_time'+i,'',12,'#43534A','android:textStyle="bold" android:layout_marginEnd="7dp"')+'<TextView android:layout_width="3dp" android:layout_height="29dp" android:background="@drawable/wline_green" android:layout_marginEnd="8dp"/><LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical">'+text('w_title'+i,'',12,'#293C32','android:textStyle="bold" android:maxLines="1" android:ellipsize="end"')+text('w_note'+i,'',9,'#849087','android:maxLines="1" android:ellipsize="end" android:layout_marginTop="3dp"')+'</LinearLayout>'+text('w_kind'+i,'',9,'#008F54','android:padding="4dp" android:background="@drawable/wchip_gray"')+'</LinearLayout>';
agenda+=text('w_more','',9,'#758178','android:layout_gravity="end" android:layout_marginTop="4dp"');
const footer='<LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="7dp" android:gravity="center_vertical" android:orientation="horizontal">'+text('w_updated','앱 열어 갱신',8,'#9AA59E','android:layout_weight="1"')+text('w_openapp','현황판 열기 ›',10,'#6B7B71','android:padding="3dp"')+'</LinearLayout>';
fs.writeFileSync(path.join(res,'layout','widget_small.xml'),lightRoot(whiteHeader+fleetCounts));
fs.writeFileSync(path.join(res,'layout','widget_medium.xml'),lightRoot(whiteHeader+healthRow+fleetCounts+list('0dp').replace('#E2E8F0','#43534A')+updated.replace('#64748B','#9AA59E')));
fs.writeFileSync(path.join(res,'layout','widget_large.xml'),lightRoot(whiteHeader+healthRow+fleetCounts+monthRow+lightCal+separator+selectedRow+agenda+footer));
fs.writeFileSync(path.join(res,'xml','widget_large_info.xml'),info('widget_large',250,400,[4,5]).replace('android:resizeMode=', 'android:minResizeHeight="400dp" android:resizeMode='));
fs.writeFileSync(path.join(res,'layout','widget_compact.xml'),lightRoot(whiteHeader+healthRow+fleetCounts+monthRow+text('w_weather','장성군 · 앱 열어 날씨 갱신',8,'#849087','android:layout_marginTop="4dp"')+lightCal+footer));
fs.writeFileSync(path.join(res,'xml','widget_compact_info.xml'),info('widget_compact',250,320,[4,4]).replace('android:resizeMode=', 'android:minResizeHeight="320dp" android:resizeMode='));
console.log('two approved calendar widgets applied');

// Remove generated legacy providers and layouts on an existing Android checkout too.
for (const name of ['FleetWidgetSmall','FleetWidgetMedium','FleetWidgetLarge']) { const file=path.join(javaDir,name+'.java');if(fs.existsSync(file))fs.unlinkSync(file); }
for(const folder of ['layout','xml'])for(const size of ['small','medium']) {const file=path.join(res,folder,'widget_'+size+(folder==='xml'?'_info':'')+'.xml');if(fs.existsSync(file))fs.unlinkSync(file);}
fs.writeFileSync(manifestPath, man);

fs.writeFileSync(path.join(javaDir,"FleetWidgetWeather.java"), 'package PACKAGE_NAME;\n\nimport android.content.Context;\nimport android.content.SharedPreferences;\nimport org.json.*;\nimport java.net.*;\nimport java.io.*;\nimport java.text.SimpleDateFormat;\nimport java.util.*;\n\n// Fixed public Jangseong town coordinates. No device location or fleet data is sent.\n// MET Norway CC BY 4.0 forecast, reduced to a representative daytime symbol.\npublic class FleetWidgetWeather {\n    private static boolean busy;\n    public static synchronized void refresh(Context context) {\n        final Context ctx=context.getApplicationContext();\n        if(android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetIds(new android.content.ComponentName(ctx,FleetCompactWidget.class)).length==0)return;\n        SharedPreferences p=ctx.getSharedPreferences("fleet_weather",Context.MODE_PRIVATE);\n        long now=System.currentTimeMillis();\n        if(busy || now<p.getLong("next",0)) return;\n        busy=true;\n        p.edit().putLong("next",now+3600000L).apply();\n        new Thread(() -> {\n            HttpURLConnection c=null;\n            try {\n                c=(HttpURLConnection)new URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=35.3018&lon=126.7848").openConnection();\n                c.setConnectTimeout(5000);c.setReadTimeout(8000);\n                c.setRequestProperty("User-Agent","JangseongFleetWidget/1.0 (https://github.com/status-board-kr/status-board-kr.github.io)");\n                String modified=p.getString("modified","");\n                if(!modified.isEmpty())c.setRequestProperty("If-Modified-Since",modified);\n                int status=c.getResponseCode();\n                long next=Math.max(System.currentTimeMillis()+3600000L,c.getHeaderFieldDate("Expires",0));\n                if(status==304){p.edit().putLong("fetched",System.currentTimeMillis()).putLong("next",next).apply();return;}\n                if(status!=200)return;\n                ByteArrayOutputStream bytes=new ByteArrayOutputStream();\n                try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){bytes.write(buf,0,n);if(bytes.size()>2000000)throw new IOException("oversized weather response");}}\n                JSONObject response=new JSONObject(bytes.toString("UTF-8"));\n                JSONArray series=response.getJSONObject("properties").getJSONArray("timeseries");\n                SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ss\'Z\'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));\n                SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd",Locale.US);date.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));\n                Calendar local=Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));\n                JSONObject daily=new JSONObject();Map<String,Integer> scores=new HashMap<>();\n                for(int i=0;i<series.length();i++){\n                    JSONObject row=series.getJSONObject(i);Date time=utc.parse(row.getString("time"));local.setTime(time);\n                    String ds=date.format(time);int score=Math.abs(local.get(Calendar.HOUR_OF_DAY)-12);\n                    JSONObject data=row.getJSONObject("data"),part=data.optJSONObject("next_6_hours");\n                    if(part==null)part=data.optJSONObject("next_1_hours");if(part==null)part=data.optJSONObject("next_12_hours");\n                    if(part==null || part.optJSONObject("summary")==null)continue;\n                    if(!scores.containsKey(ds)||score<scores.get(ds)){daily.put(ds,part.getJSONObject("summary").optString("symbol_code"));scores.put(ds,score);}\n                }\n                if(daily.length()>0)p.edit().putString("days",daily.toString()).putString("modified",c.getHeaderField("Last-Modified")).putLong("fetched",System.currentTimeMillis()).putLong("next",next).apply();\n            }catch(Exception ignored){}finally{if(c!=null)c.disconnect();synchronized(FleetWidgetWeather.class){busy=false;}FleetWidgetUtil.refreshAll(ctx);}\n        },"fleet-weather").start();\n    }\n    public static String icon(Context ctx,String date){\n        SharedPreferences p=ctx.getSharedPreferences("fleet_weather",Context.MODE_PRIVATE);\n        if(System.currentTimeMillis()-p.getLong("fetched",0)>86400000L)return "";\n        try{\n            String s=new JSONObject(p.getString("days","{}")).optString(date);\n            if(s.isEmpty())return "";\n            if(s.contains("thunder"))return "⚡";\n            if(s.contains("snow")||s.contains("sleet"))return "❄";\n            if(s.contains("rain"))return "☂";\n            if(s.contains("clearsky"))return "☀";\n            if(s.contains("fair")||s.contains("partlycloudy"))return "⛅";\n            return "☁";\n        }catch(Exception ignored){return "";}\n    }\n    public static String status(Context ctx){\n        long fetched=ctx.getSharedPreferences("fleet_weather",Context.MODE_PRIVATE).getLong("fetched",0);\n        if(fetched==0 || System.currentTimeMillis()-fetched>86400000L)return "장성군 · 앱 열어 날씨 갱신";\n        SimpleDateFormat fmt=new SimpleDateFormat("M/d HH:mm",Locale.KOREA);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));\n        return "장성군 낮 예보 · "+fmt.format(new Date(fetched))+" · MET Norway";\n    }\n}\n'.replace("PACKAGE_NAME",pkg));
