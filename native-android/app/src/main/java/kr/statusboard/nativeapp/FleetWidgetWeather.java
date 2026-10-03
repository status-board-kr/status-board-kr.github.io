package kr.statusboard.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.net.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

// Fixed public Jangseong town coordinates. No device location or fleet data is sent.
// MET Norway CC BY 4.0 forecast, reduced to a representative daytime symbol.
public class FleetWidgetWeather {
    private static boolean busy;
    public static synchronized void refresh(Context context) {
        final Context ctx=context.getApplicationContext();
        if(android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetIds(new android.content.ComponentName(ctx,FleetCompactWidget.class)).length==0)return;
        SharedPreferences p=ctx.getSharedPreferences("fleet_weather",Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(busy || now<p.getLong("next",0)) return;
        busy=true;
        p.edit().putLong("next",now+3600000L).apply();
        new Thread(() -> {
            HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=35.3018&lon=126.7848").openConnection();
                c.setConnectTimeout(5000);c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent","JangseongFleetWidget/1.0 (https://github.com/status-board-kr/status-board-kr.github.io)");
                String modified=p.getString("modified","");
                if(!modified.isEmpty())c.setRequestProperty("If-Modified-Since",modified);
                int status=c.getResponseCode();
                long next=Math.max(System.currentTimeMillis()+3600000L,c.getHeaderFieldDate("Expires",0));
                if(status==304){p.edit().putLong("fetched",System.currentTimeMillis()).putLong("next",next).apply();return;}
                if(status!=200)return;
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){bytes.write(buf,0,n);if(bytes.size()>2000000)throw new IOException("oversized weather response");}}
                JSONObject response=new JSONObject(bytes.toString("UTF-8"));
                JSONArray series=response.getJSONObject("properties").getJSONArray("timeseries");
                SimpleDateFormat utc=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));
                SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd",Locale.US);date.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
                Calendar local=Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
                JSONObject daily=new JSONObject();Map<String,Integer> scores=new HashMap<>();
                for(int i=0;i<series.length();i++){
                    JSONObject row=series.getJSONObject(i);Date time=utc.parse(row.getString("time"));local.setTime(time);
                    String ds=date.format(time);int score=Math.abs(local.get(Calendar.HOUR_OF_DAY)-12);
                    JSONObject data=row.getJSONObject("data"),part=data.optJSONObject("next_6_hours");
                    if(part==null)part=data.optJSONObject("next_1_hours");if(part==null)part=data.optJSONObject("next_12_hours");
                    if(part==null || part.optJSONObject("summary")==null)continue;
                    if(!scores.containsKey(ds)||score<scores.get(ds)){daily.put(ds,part.getJSONObject("summary").optString("symbol_code"));scores.put(ds,score);}
                }
                if(daily.length()>0)p.edit().putString("days",daily.toString()).putString("modified",c.getHeaderField("Last-Modified")).putLong("fetched",System.currentTimeMillis()).putLong("next",next).apply();
            }catch(Exception ignored){}finally{if(c!=null)c.disconnect();synchronized(FleetWidgetWeather.class){busy=false;}FleetWidgetUtil.refreshAll(ctx);}
        },"fleet-weather").start();
    }
    public static String icon(Context ctx,String date){
        SharedPreferences p=ctx.getSharedPreferences("fleet_weather",Context.MODE_PRIVATE);
        if(System.currentTimeMillis()-p.getLong("fetched",0)>86400000L)return "";
        try{
            String s=new JSONObject(p.getString("days","{}")).optString(date);
            if(s.isEmpty())return "";
            if(s.contains("thunder"))return "⚡";
            if(s.contains("snow")||s.contains("sleet"))return "❄";
            if(s.contains("rain"))return "☂";
            if(s.contains("clearsky"))return "☀";
            if(s.contains("fair")||s.contains("partlycloudy"))return "⛅";
            return "☁";
        }catch(Exception ignored){return "";}
    }
    public static String status(Context ctx){
        long fetched=ctx.getSharedPreferences("fleet_weather",Context.MODE_PRIVATE).getLong("fetched",0);
        if(fetched==0 || System.currentTimeMillis()-fetched>86400000L)return "장성군 · 앱 열어 날씨 갱신";
        SimpleDateFormat fmt=new SimpleDateFormat("M/d HH:mm",Locale.KOREA);fmt.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        return "장성군 낮 예보 · "+fmt.format(new Date(fetched))+" · MET Norway";
    }
}
