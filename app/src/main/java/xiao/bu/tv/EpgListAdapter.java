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
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private List<EpgManager.Program> programs = Collections.emptyList();

    EpgListAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void showPrograms(List<EpgManager.Program> programs) {
        this.programs = programs == null
                ? Collections.<EpgManager.Program>emptyList() : programs;
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
        return programs.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_epg_program, parent, false);
            holder = new ViewHolder();
            holder.time = (TextView) convertView.findViewById(R.id.epg_program_time);
            holder.title = (TextView) convertView.findViewById(R.id.epg_program_title);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        EpgManager.Program program = getItem(position);
        holder.time.setText(timeFormat.format(new Date(program.startMillis)) + "–"
                + timeFormat.format(new Date(program.stopMillis)));
        holder.title.setText(program.title);
        convertView.setActivated(program.isPlaying(System.currentTimeMillis()));
        return convertView;
    }

    private static final class ViewHolder {
        TextView time;
        TextView title;
    }

    private static final class AdapterViewCompat {
        static final int INVALID_POSITION = -1;
    }
}
