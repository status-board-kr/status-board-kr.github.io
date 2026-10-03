package kr.statusboard.nativeapp;

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
            com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            String owner = ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).getString("owner", "");
            if (user == null || !owner.startsWith(user.getUid()+":")) return null;
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
            if (sb.length() > 0) sb.append("\n");
            sb.append(a.optString(i));
        }
        if (a.length() > max) sb.append("\n외 ").append(a.length() - max).append("건");
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
            android.text.SpannableString cellText = new android.text.SpannableString(dayText + (label.isEmpty() ? "" : "\n" + label));
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
