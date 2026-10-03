package PACKAGE_NAME;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Window;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;

// A floating native activity in its own task: opens directly above the home screen.
// Reads the same local schedule snapshot as the widget, without starting MainActivity.
public class FleetWidgetScheduleActivity extends Activity {
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    TextView text(String value,int size,String color){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(Color.parseColor(color));return v;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);
        setFinishOnTouchOutside(true);showDate(getIntent());
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);showDate(intent);}
    void showDate(Intent intent){
        String date=intent.getStringExtra("date");
        if(date==null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")){finish();return;}
        int month=Integer.parseInt(date.substring(5,7)),day=Integer.parseInt(date.substring(8,10));
        JSONObject data=FleetWidgetUtil.load(this),cal=null;
        if(data!=null){JSONObject months=data.optJSONObject("months");if(months!=null)cal=months.optJSONObject(date.substring(0,7));
            if(cal==null){JSONObject base=data.optJSONObject("cal");if(base!=null && base.optInt("y")==Integer.parseInt(date.substring(0,4)) && base.optInt("m")==month)cal=base;}}
        JSONObject events=cal==null?null:cal.optJSONObject("events"),items=cal==null?null:cal.optJSONObject("items");
        JSONArray rows=events==null?null:events.optJSONArray(String.valueOf(day));
        JSONArray fallback=items==null?null:items.optJSONArray(String.valueOf(day));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(20));root.setBackground(bg);root.setClipToOutline(true);
        TextView header=text("현황판 · 일정",17,"#FFFFFF");header.setPadding(dp(18),dp(17),dp(18),dp(17));header.setBackgroundColor(Color.parseColor("#1C3553"));root.addView(header);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(18),dp(18),dp(16));root.addView(body);
        TextView title=text(month+"월 "+day+"일 일정",19,"#293C32");title.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(title);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,dp(12),0,dp(8));
        int count=rows!=null?rows.length():fallback!=null?fallback.length():0;
        if(count==0){TextView empty=text(cal==null?"일정 자료가 없습니다. 현황판에서 일정을 갱신해주세요.":"이 날짜엔 등록된 일정이 없습니다.",13,"#849087");empty.setPadding(0,dp(16),0,dp(16));list.addView(empty);}
        for(int i=0;i<count;i++){
            JSONObject event=rows==null?null:rows.optJSONObject(i);
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,dp(10),0,dp(10));
            String kind=event==null?"일정":event.optString("kind","일정"),color="배차".equals(kind)?"#5698D3":"#00A762";
            TextView time=text(event==null?"":event.optString("time"),13,"#43534A");row.addView(time,new LinearLayout.LayoutParams(dp(47),ViewGroup.LayoutParams.WRAP_CONTENT));
            View line=new View(this);line.setBackgroundColor(Color.parseColor(color));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(3),dp(34));lp.setMargins(0,0,dp(10),0);row.addView(line,lp);
            LinearLayout description=new LinearLayout(this);description.setOrientation(LinearLayout.VERTICAL);
            TextView name=text(event==null?fallback.optString(i):event.optString("title"),13,"#293C32");name.setTypeface(null,android.graphics.Typeface.BOLD);description.addView(name);
            String note=event==null?"":event.optString("note");if(!note.isEmpty()){TextView detail=text(note,11,"#849087");detail.setPadding(0,dp(4),0,0);description.addView(detail);}
            row.addView(description,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));TextView tag=text(kind,10,color);tag.setPadding(dp(6),0,0,0);row.addView(tag);list.addView(row);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(list);int maxHeight=getResources().getDisplayMetrics().heightPixels*45/100;
        list.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels-dp(76),View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        body.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Math.min(list.getMeasuredHeight(),maxHeight)));
        TextView updated=text(data==null?"":data.optString("updated"),10,"#849087");body.addView(updated);
        Button close=new Button(this);close.setText("닫기");close.setAllCaps(false);close.setTextColor(Color.parseColor("#395343"));close.setOnClickListener(v->finish());body.addView(close,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        setContentView(root);getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.32f);getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels*92/100,dp(420)),ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
