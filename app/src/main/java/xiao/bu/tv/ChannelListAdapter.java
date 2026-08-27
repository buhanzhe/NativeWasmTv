package xiao.bu.tv;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

final class ChannelListAdapter extends BaseAdapter {
    private static final int FAVORITE_ACTIVE_COLOR = Color.rgb(255, 204, 51);
    private static final int FAVORITE_INACTIVE_COLOR = Color.argb(200, 255, 255, 255);

    interface FavoriteListener {
        boolean isFavorite(int position);
        void onFavoriteClick(int position);
    }

    private final LayoutInflater inflater;
    private ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
    private Channel[] channels = ChannelCatalog.CCTV_CHANNELS;
    private int selectedIndex;
    private boolean showingGroups = true;
    private int favoriteFocusIndex = -1;
    private int playingIndex = -1;
    private int playingSourceIndex;
    private FavoriteListener favoriteListener;
    private final View.OnClickListener favoriteClickListener =
            new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (favoriteListener == null || !(view.getTag() instanceof Integer)) {
                return;
            }
            favoriteListener.onFavoriteClick((Integer) view.getTag());
        }
    };

    ChannelListAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void showGroups(ChannelCatalog.Group[] groups, int selectedIndex) {
        this.groups = groups;
        this.selectedIndex = selectedIndex;
        showingGroups = true;
        notifyDataSetChanged();
    }

    void showChannels(Channel[] channels, int selectedIndex,
            int playingIndex, int playingSourceIndex) {
        this.channels = channels;
        this.selectedIndex = selectedIndex;
        this.playingIndex = playingIndex;
        this.playingSourceIndex = playingSourceIndex;
        showingGroups = false;
        notifyDataSetChanged();
    }

    void setChannelState(int selectedIndex, int playingIndex, int playingSourceIndex) {
        this.selectedIndex = selectedIndex;
        this.playingIndex = playingIndex;
        this.playingSourceIndex = playingSourceIndex;
        if (!showingGroups) {
            notifyDataSetChanged();
        }
    }

    void setSelectedIndex(int selectedIndex) {
        this.selectedIndex = selectedIndex;
        notifyDataSetChanged();
    }

    void setFavoriteListener(FavoriteListener listener) {
        favoriteListener = listener;
    }

    void setFavoriteFocusIndex(int index) {
        if (favoriteFocusIndex == index) {
            notifyDataSetChanged();
            return;
        }
        favoriteFocusIndex = index;
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
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return showingGroups ? 0 : 1;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(showingGroups
                    ? R.layout.item_channel_group : R.layout.item_channel, parent, false);
            holder = new ViewHolder();
            holder.number = (TextView) convertView.findViewById(R.id.channel_number);
            holder.name = (TextView) convertView.findViewById(R.id.channel_item_name);
            holder.count = (TextView) convertView.findViewById(R.id.channel_group_count);
            holder.favorite = (TextView) convertView.findViewById(R.id.channel_item_favorite);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        if (showingGroups) {
            ChannelCatalog.Group group = groups[position];
            holder.number.setText(group.source == ChannelCatalog.SOURCE_FAVORITES
                    ? "★" : String.valueOf(position));
            holder.name.setText(group.title);
            holder.count.setText(String.valueOf(group.channels.length));
        } else {
            Channel channel = channels[position];
            holder.number.setText(channel.number);
            holder.name.setText(channel.name);
            int sourceCount = Math.max(1, channel.sourceCount());
            if (position == playingIndex) {
                int sourceNumber = (playingSourceIndex % sourceCount + sourceCount)
                        % sourceCount + 1;
                holder.count.setText(sourceNumber + "/" + sourceCount);
            } else {
                holder.count.setText(String.valueOf(sourceCount));
            }
            boolean favorite = favoriteListener != null
                    && favoriteListener.isFavorite(position);
            holder.favorite.setText(favorite ? "★" : "☆");
            holder.favorite.setTextColor(favorite
                    ? FAVORITE_ACTIVE_COLOR : FAVORITE_INACTIVE_COLOR);
            holder.favorite.setContentDescription(favorite ? "取消收藏" : "收藏");
            holder.favorite.setSelected(position == favoriteFocusIndex);
            holder.favorite.setTag(Integer.valueOf(position));
            holder.favorite.setOnClickListener(favoriteClickListener);
        }
        convertView.setActivated(position == selectedIndex);
        return convertView;
    }

    private static final class ViewHolder {
        TextView number;
        TextView name;
        TextView count;
        TextView favorite;
    }
}
