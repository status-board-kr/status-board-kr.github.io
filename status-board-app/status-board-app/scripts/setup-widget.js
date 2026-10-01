// 홈 화면 위젯 3종(작게·중간·크게) — setup-android.js 다음에 실행
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
    '            } catch (Exception ignored) { }',
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
    '    }',
    '',
    '    @Override',
    '    public void onResume() {',
    '        super.onResume();',
    '        captureOpen(getIntent());',
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
    public static final int SMALL = 0, MEDIUM = 1, LARGE = 2;

    public static JSONObject load(Context ctx) {
        try {
            String s = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getString("data", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) { return null; }
    }

    public static void refreshAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        update(ctx, m, m.getAppWidgetIds(new ComponentName(ctx, FleetWidgetSmall.class)), SMALL);
        update(ctx, m, m.getAppWidgetIds(new ComponentName(ctx, FleetWidgetMedium.class)), MEDIUM);
        update(ctx, m, m.getAppWidgetIds(new ComponentName(ctx, FleetWidgetLarge.class)), LARGE);
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
        int layout = size == SMALL ? R.layout.widget_small : (size == MEDIUM ? R.layout.widget_medium : R.layout.widget_large);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        v.setOnClickPendingIntent(R.id.w_root, opener(ctx, "", 1));
        JSONObject d = load(ctx);
        if (d == null) {
            v.setTextViewText(R.id.w_idle, "-");
            return v;
        }
        v.setTextViewText(R.id.w_idle, String.valueOf(d.optInt("idle")));
        v.setTextViewText(R.id.w_prep, String.valueOf(d.optInt("prep")));
        v.setTextViewText(R.id.w_run, String.valueOf(d.optInt("run")));
        v.setTextViewText(R.id.w_long, String.valueOf(d.optInt("long")));
        int unread = d.optInt("unread");
        v.setTextViewText(R.id.w_unread, "💬 " + unread);
        v.setInt(R.id.w_unread, "setBackgroundResource", unread > 0 ? R.drawable.wchip_red : R.drawable.wchip_gray);
        v.setOnClickPendingIntent(R.id.w_unread, opener(ctx, "chat", 2));

        if (size == MEDIUM) {
            v.setTextViewText(R.id.w_list, lines(d.optJSONArray("soon"), 4, "오늘·내일 일정 없음"));
            v.setTextViewText(R.id.w_updated, d.optString("updated"));
            v.setOnClickPendingIntent(R.id.w_list, opener(ctx, "date:" + d.optString("todayStr"), 3));
        }
        if (size == LARGE) {
            JSONObject cal = d.optJSONObject("cal");
            if (cal != null) {
                int y = cal.optInt("y"), m = cal.optInt("m"), today = cal.optInt("today");
                int first = cal.optInt("firstDow"), days = cal.optInt("days");
                JSONObject cnt = cal.optJSONObject("count");
                v.setTextViewText(R.id.w_month, y + "년 " + m + "월");
                for (int i = 0; i < 42; i++) {
                    int cid = id(ctx, "w_c" + i);
                    if (cid == 0) continue;
                    int day = i - first + 1;
                    if (day < 1 || day > days) {
                        v.setTextViewText(cid, "");
                        v.setInt(cid, "setBackgroundResource", 0);
                        continue;
                    }
                    int n = cnt == null ? 0 : cnt.optInt(String.valueOf(day));
                    int col = i % 7;
                    v.setTextViewText(cid, n > 0 ? (day + "\\n" + (n > 9 ? "9+" : "•" + n)) : String.valueOf(day));
                    int color = day == today ? Color.parseColor("#1A1A1A")
                        : (col == 0 ? Color.parseColor("#F87171") : (col == 6 ? Color.parseColor("#60A5FA") : Color.parseColor("#E2E8F0")));
                    v.setTextColor(cid, color);
                    v.setInt(cid, "setBackgroundResource", day == today ? R.drawable.wcell_today : (n > 0 ? R.drawable.wcell_mark : 0));
                    String ds = y + "-" + (m < 10 ? "0" : "") + m + "-" + (day < 10 ? "0" : "") + day;
                    v.setOnClickPendingIntent(cid, opener(ctx, "date:" + ds, 100 + i));
                }
            }
            v.setTextViewText(R.id.w_list, lines(d.optJSONArray("today"), 3, "오늘 일정 없음"));
            v.setTextViewText(R.id.w_updated, d.optString("updated"));
            v.setOnClickPendingIntent(R.id.w_list, opener(ctx, "date:" + d.optString("todayStr"), 3));
        }
        return v;
    }
}
`;
fs.writeFileSync(path.join(javaDir, 'FleetWidgetUtil.java'), util);
for (const [name, size] of [['FleetWidgetSmall','SMALL'],['FleetWidgetMedium','MEDIUM'],['FleetWidgetLarge','LARGE']]) {
  fs.writeFileSync(path.join(javaDir, name + '.java'),
`package ${pkg};

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class ${name} extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        FleetWidgetUtil.update(ctx, m, ids, FleetWidgetUtil.${size});
    }
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
  <LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">`;
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
fs.writeFileSync(path.join(res, 'layout', 'widget_large.xml'), root(header + chips + cal +
  list('wrap_content').replace('android:layout_marginTop="8dp"', 'android:layout_marginTop="6dp" android:maxLines="4"') + updated));

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
if (!man.includes('FleetWidgetSmall')) {
  const rec = (cls, xml, label) => `
        <receiver android:name=".${cls}" android:exported="false" android:label="${label}">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/${xml}"/>
        </receiver>`;
  man = man.replace('</application>',
    rec('FleetWidgetSmall', 'widget_small_info', '현황판 (작게)') +
    rec('FleetWidgetMedium', 'widget_medium_info', '현황판 (중간)') +
    rec('FleetWidgetLarge', 'widget_large_info', '현황판 (크게)') + '\n    </application>');
  fs.writeFileSync(manifestPath, man);
  console.log('widget receivers registered');
}

