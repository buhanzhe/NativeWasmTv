package xiao.bu.channel.bl;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

/** Shared geometry and focus styling for the native BL pages. */
final class BlUi {
    static final int TEXT=0xffeeeeee,SUB=0xffcccccc,ACCENT=0xffbb86fc;
    final Context context;final float scale;
    BlTheme theme=BlTheme.BLACK;
    BlUi(Context c){context=c;int width=Math.max(c.getResources().getDisplayMetrics().widthPixels,c.getResources().getDisplayMetrics().heightPixels);scale=Math.max(.5f,width/1280f);}
    int px(float n){return Math.round(n*scale);}
    TextView text(String text,int size,int color){TextView t=new TextView(context);t.setText(text);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size));t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    GradientDrawable fill(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;}
    GradientDrawable backdrop(BlTheme palette,int radius){
        if(palette!=BlTheme.CLASSIC)return fill(palette.background,radius);
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{palette.background,0xff54233e,0xff943c55});
        gradient.setDither(true);
        gradient.setCornerRadius(px(radius));
        return gradient;
    }
    StateListDrawable focus(int normal){
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focused=fill(theme==BlTheme.BLACK?0xff695082:theme.tint(58),10);
        focused.setStroke(px(2),theme.accent);
        states.addState(new int[]{android.R.attr.state_focused},focused);
        states.addState(new int[]{android.R.attr.state_pressed},fill(theme==BlTheme.BLACK?0xff745395:theme.tint(72),10));
        states.addState(new int[]{android.R.attr.state_selected},fill(theme==BlTheme.BLACK?0xff55415e:theme.tint(40),10));
        states.addState(new int[]{},fill(normal,10));
        return states;
    }
    int buttonColor(){return theme==BlTheme.BLACK?0x66333333:theme.tint(15);}
    void style(Button button){button.setTextColor(theme.text);button.setBackgroundDrawable(focus(buttonColor()));}
    Button button(String text){Button b=new Button(context);b.setText(text);b.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(18));style(b);b.setPadding(px(16),px(8),px(16),px(8));b.setMinHeight(px(44));b.setMinimumHeight(px(44));b.setMinWidth(0);b.setMinimumWidth(0);b.setSingleLine(true);return b;}
    LinearLayout vertical(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout horizontal(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    void gap(LinearLayout parent,int width){parent.addView(new View(context),new LinearLayout.LayoutParams(px(width),1));}
    ScrollView scroll(View child){ScrollView s=new ScrollView(context);s.setFillViewport(true);s.addView(child);return s;}
    FrameLayout.LayoutParams frame(int w,int h,int gravity){return new FrameLayout.LayoutParams(w<0?w:px(w),h<0?h:px(h),gravity);}
    TextView title(String value){TextView t=text(value,26,theme.text);t.setTypeface(null,Typeface.BOLD);return t;}
    static String plain(String value){return value.replaceAll("<[^>]+>","").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"");}
    static String count(long number){if(number>=100000000)return String.format(java.util.Locale.US,"%.1f亿",number/100000000d);if(number>=10000)return String.format(java.util.Locale.US,"%.1f万",number/10000d);return ""+number;}
    static String time(long ms){long sec=Math.max(0,ms/1000);return sec>=3600?String.format(java.util.Locale.US,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60):String.format(java.util.Locale.US,"%02d:%02d",sec/60,sec%60);}
}
