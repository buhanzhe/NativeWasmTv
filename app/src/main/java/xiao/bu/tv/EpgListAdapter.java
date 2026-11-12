package xiao.bu.tv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class EpgListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final UiScaleHelper uiScaleHelper;
    private final float density;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final EpgDisplayCache displayCache = new EpgDisplayCache();
    private List<EpgManager.Program> programs = Collections.emptyList();
    private float uiScale = 1f;
    private Channel channel;
    private List<EpgManager.Program> source=Collections.emptyList();
    private List<Long> days=Collections.emptyList();
    private boolean datePickerEnabled;
    private long selectedDay;
    void setDatePickerEnabled(boolean enabled){datePickerEnabled=enabled;}
    boolean hasDatePicker(){return datePickerEnabled&&days.size()>1;}
    List<Long> availableDays(){return days;}
    long selectedDay(){return selectedDay;}
    void selectDay(long day){if(!hasDatePicker()||!days.contains(day))return;selectedDay=day;programs=displayCache.programsForDay(source,day,0);notifyDataSetChanged();}
    private int pagedRowHeight;
    int navigationRowHeight(android.widget.ListView list){
        if(TvUiPlugin.current()==null&&pagedRowHeight>0)return pagedRowHeight;
        if(TvUiPlugin.current()!=null)return themedHeight(list);
        View sample=getView(0,null,list);int height=sample.getLayoutParams().height;
        if(TvUiPlugin.current()==null){
            ViewHolder holder=(ViewHolder)sample.getTag();
            int gap=((ViewGroup.MarginLayoutParams)holder.title.getLayoutParams()).topMargin;
            height=sample.getPaddingTop()+sample.getPaddingBottom()+holder.time.getLineHeight()+holder.title.getLineHeight()*2+gap;
            height=Math.max(height,Math.round(52f*density*uiScale));
            if(pagedRowHeight!=height){pagedRowHeight=height;notifyDataSetChanged();}
        }
        return Math.max(1,height);
    }
    private final SimpleDateFormat dateFormat=new SimpleDateFormat("MM-dd HH:mm",Locale.getDefault());

    EpgListAdapter(Context context, UiScaleHelper uiScaleHelper) {
        inflater = LayoutInflater.from(context);
        this.uiScaleHelper = uiScaleHelper;
        density = context.getResources().getDisplayMetrics().density;
    }

    void setUiScale(float uiScale) {
        if (Math.abs(this.uiScale - uiScale) < 0.001f) {
            return;
        }
        this.uiScale = uiScale;
        pagedRowHeight=0;
        notifyDataSetChanged();
    }

    void showPrograms(List<EpgManager.Program> programs) {
        showPrograms(programs,null);
    }
    void showPrograms(List<EpgManager.Program> programs,Channel channel){
        if(this.channel!=channel)selectedDay=0;
        this.channel=channel;
        source=programs;
        double catchup=channel==null?0:channel.catchupDays();
        days=datePickerEnabled?EpgGuideDays.available(programs,catchup,System.currentTimeMillis()):Collections.<Long>emptyList();
        if(!days.contains(selectedDay)){
            long today=EpgManager.dayStart(System.currentTimeMillis());
            selectedDay=days.contains(today)?today:days.isEmpty()?today:days.get(0);
        }
        this.programs = datePickerEnabled&&!days.isEmpty()?displayCache.programsForDay(programs,selectedDay,0):displayCache.programsFor(programs,catchup);
        // Keep time-based highlights fresh even when the cached list is reused.
        notifyDataSetChanged();
    }

    int currentProgramIndex() {
        long now = System.currentTimeMillis();
        for (int index = 0; index < programs.size(); index++) {
            if (programs.get(index).isPlaying(now)) {
                return index;
            }
        }
        return programs.isEmpty() ? AdapterViewCompat.INVALID_POSITION : 0;
    }

    @Override
    public int getCount() {
        return programs.size();
    }

    @Override
    public EpgManager.Program getItem(int position) {
        return position >= 0 && position < programs.size() ? programs.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if(TvUiPlugin.current()!=null){try{return themedRow(position,convertView,parent);}catch(RuntimeException|LinkageError error){TvUiPlugin.rejectRenderer(inflater.getContext(),error);convertView=null;}}
        ViewHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof ViewHolder)) {
            convertView = inflater.inflate(R.layout.item_epg_program, parent, false);
            holder = new ViewHolder();
            holder.time = (TextView) convertView.findViewById(R.id.epg_program_time);
            holder.title = (TextView) convertView.findViewById(R.id.epg_program_title);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        uiScaleHelper.apply(convertView, uiScale);
        if(pagedRowHeight>0)convertView.getLayoutParams().height=pagedRowHeight;
        convertView.setMinimumHeight(Math.round(52f * density * uiScale));
        EpgManager.Program program = getItem(position);
        // A queued ListView bind can outlive the list it was scheduled for.
        // Clear the recycled content instead of indexing the replacement empty list.
        if (program == null) {
            holder.time.setText("");
            holder.title.setText("");
            convertView.setBackgroundResource(R.drawable.epg_program_background);
            convertView.setActivated(false);
            holder.time.setActivated(false);
            holder.title.setActivated(false);
            return convertView;
        }
        holder.time.setText((program.startMillis<EpgManager.dayStart(System.currentTimeMillis())?dateFormat:timeFormat).format(new Date(program.startMillis)) + "–"
                + timeFormat.format(new Date(program.stopMillis)));
        long now=System.currentTimeMillis();
        holder.title.setText(programTitle(program,now));
        boolean current = program.isPlaying(System.currentTimeMillis());
        convertView.setBackgroundResource(current
                ? R.drawable.epg_program_current_background : R.drawable.epg_program_background);
        convertView.setActivated(current);
        holder.time.setActivated(current);
        holder.title.setActivated(current);
        holder.time.setTextColor(parent.getResources().getColorStateList(R.color.epg_program_time_text));
        convertView.refreshDrawableState();
        return convertView;
    }

    private String programTitle(EpgManager.Program program,long now){
        // Inferred catchup remains available on click without advertising uncertain support.
        String marker=channel!=null&&channel.catchupDays()>0&&!channel.automaticCatchupOnly()
                ?program.isPlaying(now)?"直播 · ":channel.canCatchup(program.startMillis,program.stopMillis,now)?"回看 · ":"":"";
        return marker+program.title;
    }

    private int themedHeight(android.widget.ListView list){
        String id=TvUiPlugin.current().id;
        float scale=inflater.getContext() instanceof xiao.bu.tv.skin.SharedTvUi.Viewport
                ?((xiao.bu.tv.skin.SharedTvUi.Viewport)inflater.getContext()).themeScale():uiScale;
        int preferred=Math.max(1,Math.round(xiao.bu.tv.skin.SharedTvUi.preferredRowHeight(id)*scale));
        int available=list.getHeight()-2*list.getPaddingTop(),divider=list.getDividerHeight();
        return available<=0?preferred:Math.max(1,xiao.bu.tv.skin.SharedTvUi.fittedRowHeight(
                available+divider,preferred+divider)-divider);
    }

    private View themedRow(int position,View old,ViewGroup parent){
        float scale=inflater.getContext() instanceof xiao.bu.tv.skin.SharedTvUi.Viewport
                ?((xiao.bu.tv.skin.SharedTvUi.Viewport)inflater.getContext()).themeScale():uiScale;
        ThemedRow holder=old!=null&&old.getTag() instanceof ThemedRow?(ThemedRow)old.getTag():null;
        if(holder==null||holder.revision!=TvUiPlugin.revision()||Math.abs(holder.scale-scale)>.001f){
            holder=new ThemedRow();holder.revision=TvUiPlugin.revision();holder.scale=scale;
            old=TvUiPlugin.provider().create(inflater.getContext(),"epg-row",holder.state,null,null);old.setTag(holder);
        }
        if(parent instanceof android.widget.ListView)
            old.getLayoutParams().height=themedHeight((android.widget.ListView)parent);
        EpgManager.Program program=getItem(position);long now=System.currentTimeMillis();
        holder.state.putString("time",program==null?"":(program.startMillis<EpgManager.dayStart(now)?dateFormat:timeFormat).format(new Date(program.startMillis))+"–"+timeFormat.format(new Date(program.stopMillis)));
        holder.state.putString("title",program==null?"":programTitle(program,now));
        holder.state.putBoolean("current",program!=null&&program.isPlaying(now));
        TvUiPlugin.provider().update(old,holder.state);return old;
    }
    private static final class ThemedRow{int revision;float scale;final android.os.Bundle state=new android.os.Bundle();}

    private static final class ViewHolder {
        TextView time;
        TextView title;
    }

    private static final class AdapterViewCompat {
        static final int INVALID_POSITION = -1;
    }
}
