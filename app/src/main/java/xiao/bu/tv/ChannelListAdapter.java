package xiao.bu.tv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

final class ChannelListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
    private Channel[] channels = ChannelCatalog.CCTV_CHANNELS;
    private int selectedIndex;
    private boolean showingGroups = true;

    ChannelListAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void showGroups(ChannelCatalog.Group[] groups, int selectedIndex) {
        this.groups = groups;
        this.selectedIndex = selectedIndex;
        showingGroups = true;
        notifyDataSetChanged();
    }

    void showChannels(Channel[] channels, int selectedIndex) {
        this.channels = channels;
        this.selectedIndex = selectedIndex;
        showingGroups = false;
        notifyDataSetChanged();
    }

    void setSelectedIndex(int selectedIndex) {
        this.selectedIndex = selectedIndex;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return showingGroups ? groups.length : channels.length;
    }

    @Override
    public Object getItem(int position) {
        return showingGroups ? groups[position] : channels[position];
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_channel, parent, false);
            holder = new ViewHolder();
            holder.number = (TextView) convertView.findViewById(R.id.channel_number);
            holder.name = (TextView) convertView.findViewById(R.id.channel_item_name);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        if (showingGroups) {
            ChannelCatalog.Group group = groups[position];
            holder.number.setText(String.valueOf(position + 1));
            holder.name.setText(group.title);
        } else {
            Channel channel = channels[position];
            holder.number.setText(channel.number);
            holder.name.setText(channel.sourceCount() > 1
                    ? channel.name + "  ·  " + channel.sourceCount() + " 条线路"
                    : channel.name);
        }
        convertView.setActivated(position == selectedIndex);
        return convertView;
    }

    private static final class ViewHolder {
        TextView number;
        TextView name;
    }
}
