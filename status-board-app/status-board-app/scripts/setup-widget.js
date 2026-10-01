// 홈 화면 위젯 3종(작게·중간·크게) 추가 — setup-android.js 다음에 실행
// 앱(웹 화면)이 window.AndroidSettings.saveWidget(json)으로 요약 자료를 넘기면
// 폰에 저장해두고 위젯이 그 자료를 그립니다.
const fs = require('fs'), path = require('path');
const main = path.join(__dirname, '..', 'android', 'app', 'src', 'main');
const manifestPath = path.join(main, 'AndroidManifest.xml');
if (!fs.existsSync(manifestPath)) { console.error('android 폴더 없음'); process.exit(1); }

// 1) MainActivity 찾기 + 패키지 이름
function findFile(dir, name){ for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
  const p = path.join(dir, f.name);
  if (f.isDirectory()) { const r = findFile(p, name); if (r) return r; } else if (f.name === name) return p; } return null; }
const mainActPath = findFile(path.join(main, 'java'), 'MainActivity.java');
if (!mainActPath) { console.error('MainActivity.java 없음'); process.exit(1); }
let act = fs.readFileSync(mainActPath, 'utf8');
const pkg = (act.match(/^package\s+([^;]+);/m) || [])[1] || 'com.jangsung.fleet';
const javaDir = path.dirname(mainActPath);

// 2) 웹 → 앱 자료 전달 통로(saveWidget) 추가
if (!act.includes('saveWidget')) {
  const bridgeStart = act.indexOf('class SettingsBridge');
  const braceAt = act.indexOf('{', bridgeStart);
  const method = [
    '',
    '        @android.webkit.JavascriptInterface',
    '        public void saveWidget(final String json) {',
    '            try {',
    '                getSharedPreferences("fleet_widget", MODE_PRIVATE).edit().putString("data", json).apply();',
    '                FleetWidgetUtil.refreshAll(MainActivity.this);',
    '            } catch (Exception ignored) { }',
    '        }',
    ''
  ].join('\n');
  act = act.slice(0, braceAt + 1) + method + act.slice(braceAt + 1);
  fs.writeFileSync(mainActPath, act);
  console.log('saveWidget bridge added');
}

// 3) 위젯 그리는 공용 코드
const util = `package ${pkg};

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
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

    static RemoteViews build(Context ctx, int size) {
        int layout = size == SMALL ? R.layout.widget_small : (size == MEDIUM ? R.layout.widget_medium : R.layout.widget_large);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), layout);
        JSONObject d = load(ctx);
        if (d == null) {
            v.setTextViewText(R.id.w_counts, "앱을 한 번 열어주세요");
            v.setTextViewText(R.id.w_unread, "");
        } else {
            v.setTextViewText(R.id.w_counts, "대기 " + d.optInt("idle") + "  ·  운행 " + d.optInt("run") + "  ·  준비 " + d.optInt("prep"));
            int unread = d.optInt("unread");
            v.setTextViewText(R.id.w_unread, unread > 0 ? ("💬 " + unread) : "💬 0");
            v.setTextColor(R.id.w_unread, unread > 0 ? Color.parseColor("#fbbf24") : Color.parseColor("#94a3b8"));
            if (size != SMALL) {
                StringBuilder sb = new StringBuilder();
                JSONArray r = d.optJSONArray("returns");
                if (r == null || r.length() == 0) sb.append("오늘·내일 반납 없음");
                else for (int i = 0; i < r.length() && i < (size == LARGE ? 6 : 3); i++) {
                    JSONObject o = r.optJSONObject(i); if (o == null) continue;
                    if (sb.length() > 0) sb.append("\\n");
                    sb.append(o.optString("when")).append("  ").append(o.optString("plate"));
                    String n = o.optString("name"); if (n.length() > 0) sb.append("  ").append(n);
                }
                v.setTextViewText(R.id.w_returns, sb.toString());
                v.setTextViewText(R.id.w_updated, "업데이트 " + d.optString("updated"));
            }
            if (size == LARGE) {
                JSONObject cal = d.optJSONObject("cal");
                if (cal != null) v.setImageViewBitmap(R.id.w_cal, drawCalendar(cal));
            }
        }
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            PendingIntent pi = PendingIntent.getActivity(ctx, size, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            v.setOnClickPendingIntent(R.id.w_root, pi);
        }
        return v;
    }

    // 이번 달 달력 그림 (오늘은 동그라미, 일정 있는 날은 점)
    static Bitmap drawCalendar(JSONObject cal) {
        int W = 700, H = 520;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int y = cal.optInt("y"), m = cal.optInt("m"), today = cal.optInt("today"), first = cal.optInt("firstDow"), days = cal.optInt("days");
        java.util.HashSet<Integer> marked = new java.util.HashSet<>();
        JSONArray md = cal.optJSONArray("marked");
        if (md != null) for (int i = 0; i < md.length(); i++) marked.add(md.optInt(i));
        p.setColor(Color.parseColor("#e2e8f0")); p.setTextSize(34); p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(y + "년 " + m + "월", 10, 40, p);
        String[] dow = {"일","월","화","수","목","금","토"};
        float cw = W / 7f, top = 70, ch = (H - top - 10) / 7f;
        p.setTextSize(26); p.setTypeface(Typeface.DEFAULT); p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 7; i++) {
            p.setColor(i == 0 ? Color.parseColor("#f87171") : (i == 6 ? Color.parseColor("#60a5fa") : Color.parseColor("#94a3b8")));
            c.drawText(dow[i], cw * i + cw / 2, top + ch * 0.6f, p);
        }
        p.setTextSize(30);
        for (int d = 1; d <= days; d++) {
            int idx = first + d - 1, col = idx % 7, row = idx / 7 + 1;
            float cx = cw * col + cw / 2, cy = top + ch * row + ch * 0.55f;
            if (d == today) { p.setColor(Color.parseColor("#f5a623")); c.drawCircle(cx, cy - 10, 26, p); }
            p.setColor(d == today ? Color.parseColor("#1a1a1a") : (col == 0 ? Color.parseColor("#f87171") : (col == 6 ? Color.parseColor("#60a5fa") : Color.parseColor("#e2e8f0"))));
            c.drawText(String.valueOf(d), cx, cy, p);
            if (marked.contains(d)) { p.setColor(Color.parseColor("#34d399")); c.drawCircle(cx, cy + 14, 5, p); }
        }
        return bmp;
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

// 4) 화면 모양(레이아웃)
const res = path.join(main, 'res');
fs.mkdirSync(path.join(res, 'layout'), { recursive: true });
fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
fs.mkdirSync(path.join(res, 'drawable'), { recursive: true });
fs.writeFileSync(path.join(res, 'drawable', 'widget_bg.xml'),
`<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
  <solid android:color="#F20F172A"/>
  <corners android:radius="18dp"/>
</shape>
`);
const head = (id) => `<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
  android:id="@+id/w_root" android:layout_width="match_parent" android:layout_height="match_parent"
  android:orientation="vertical" android:padding="12dp" android:background="@drawable/widget_bg">
  <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
    <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
      android:text="🚗 현황판" android:textColor="#F5A623" android:textSize="13sp" android:textStyle="bold"/>
    <TextView android:id="@+id/w_unread" android:layout_width="wrap_content" android:layout_height="wrap_content"
      android:text="💬 0" android:textColor="#94A3B8" android:textSize="13sp" android:textStyle="bold"/>
  </LinearLayout>
  <TextView android:id="@+id/w_counts" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginTop="4dp" android:text="앱을 한 번 열어주세요" android:textColor="#E2E8F0" android:textSize="15sp" android:textStyle="bold"/>
`;
const returnsBlock = `  <TextView android:id="@+id/w_returns" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginTop="8dp" android:text="" android:textColor="#CBD5E1" android:textSize="13sp" android:lineSpacingExtra="2dp"/>
`;
const updatedBlock = `  <TextView android:id="@+id/w_updated" android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginTop="4dp" android:text="" android:textColor="#64748B" android:textSize="10sp"/>
`;
fs.writeFileSync(path.join(res, 'layout', 'widget_small.xml'), head() + '</LinearLayout>\n');
fs.writeFileSync(path.join(res, 'layout', 'widget_medium.xml'), head() + returnsBlock + updatedBlock + '</LinearLayout>\n');
fs.writeFileSync(path.join(res, 'layout', 'widget_large.xml'), head() + returnsBlock +
`  <ImageView android:id="@+id/w_cal" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"
    android:layout_marginTop="6dp" android:scaleType="fitCenter" android:adjustViewBounds="true"/>
` + updatedBlock + '</LinearLayout>\n');

const info = (layout, w, h, cells) => `<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
  android:minWidth="${w}dp" android:minHeight="${h}dp"
  android:targetCellWidth="${cells[0]}" android:targetCellHeight="${cells[1]}"
  android:updatePeriodMillis="1800000" android:initialLayout="@layout/${layout}"
  android:resizeMode="horizontal|vertical" android:widgetCategory="home_screen"/>
`;
fs.writeFileSync(path.join(res, 'xml', 'widget_small_info.xml'), info('widget_small', 110, 40, [2,1]));
fs.writeFileSync(path.join(res, 'xml', 'widget_medium_info.xml'), info('widget_medium', 250, 110, [4,2]));
fs.writeFileSync(path.join(res, 'xml', 'widget_large_info.xml'), info('widget_large', 250, 250, [4,4]));
console.log('widget layouts written');

// 5) 앱 설정(AndroidManifest)에 위젯 3종 등록
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
