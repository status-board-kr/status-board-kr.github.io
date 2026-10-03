package kr.statusboard.nativeapp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class FleetCalendarWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        FleetWidgetUtil.update(ctx, m, ids, FleetWidgetUtil.LARGE);
        FleetWidgetRefresh.enqueue(ctx);
    }

    // 위젯 안에서 날짜를 눌렀을 때: 고른 날짜를 저장하고 다시 그림
    @Override
    public void onReceive(Context ctx, android.content.Intent intent) {
        super.onReceive(ctx, intent);
        if ("fleet.widget.SELECT".equals(intent.getAction())) {
            String s = intent.getStringExtra("sel");
            if (s == null || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return;
            ctx.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).edit().putString("sel"+FleetWidgetUtil.LARGE, s).putString("month"+FleetWidgetUtil.LARGE, s.substring(0,7)).apply();
            AppWidgetManager m = AppWidgetManager.getInstance(ctx);
            FleetWidgetUtil.update(ctx, m, m.getAppWidgetIds(new android.content.ComponentName(ctx, FleetCalendarWidget.class)), FleetWidgetUtil.LARGE);
        }
    }
    @Override public void onDisabled(Context ctx) { FleetWidgetRefresh.enqueue(ctx); }
}
