package xiao.bu.tv;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class EpgDateAdapter extends BaseAdapter {
    private List<Long> days=Collections.emptyList();
    private long selected;
    private float uiScale=1f;
    void setUiScale(float scale){uiScale=scale;notifyDataSetChanged();}
    private final SimpleDateFormat weekday=new SimpleDateFormat("EEE",Locale.CHINA);
    private final SimpleDateFormat date=new SimpleDateFormat("MM-dd",Locale.getDefault());
    void show(List<Long> values,long day){days=values;selected=day;notifyDataSetChanged();}
    int selectedIndex(){return days.indexOf(selected);}
    public int getCount(){return days.size();}
    public Long getItem(int position){return position>=0&&position<days.size()?days.get(position):null;}
    public long getItemId(int position){Long day=getItem(position);return day==null?0:day;}
    public View getView(int position,View recycled,ViewGroup parent){
        TextView text=recycled instanceof TextView?(TextView)recycled:new TextView(parent.getContext());
        Context context=parent.getContext();float scale=TvUiPlugin.current()!=null&&context instanceof xiao.bu.tv.skin.SharedTvUi.Viewport
                ?((xiao.bu.tv.skin.SharedTvUi.Viewport)context).themeScale():context.getResources().getDisplayMetrics().density*uiScale;
        TvUiStyle theme=TvUiPlugin.current();
        int height=Math.round((theme==null?60:xiao.bu.tv.skin.SharedTvUi.preferredRowHeight(theme.id))*scale);
        if(theme!=null&&parent.getHeight()>0)height=xiao.bu.tv.skin.SharedTvUi.fittedRowHeight(parent.getHeight(),height);
        text.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,height));
        text.setGravity(Gravity.CENTER);text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,14*scale);
        Long day=getItem(position);boolean chosen=day!=null&&day==selected;
        text.setText(day==null?"":(day==EpgManager.dayStart(System.currentTimeMillis())?"今天":weekday.format(new Date(day)))+"\n"+date.format(new Date(day)));
        boolean focused=chosen&&parent.hasFocus();
        android.os.Bundle palette=xiao.bu.tv.skin.SharedTvUi.dialogPalette(theme==null?"builtin":theme.id);
        text.setTextColor(focused?palette.getInt("focusText"):chosen?palette.getInt("accent"):palette.getInt("text"));
        if(focused){
            android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,new int[]{palette.getInt("focusStart"),palette.getInt("focusEnd")});
            background.setCornerRadius((theme==null?8:theme.id.equals("ui2")?22:theme.id.equals("ui5")?20:theme.id.equals("ui6")?12:6)*scale);
            text.setBackgroundDrawable(background);
        }else text.setBackgroundResource(android.R.color.transparent);
        text.setActivated(chosen);return text;
    }
}
