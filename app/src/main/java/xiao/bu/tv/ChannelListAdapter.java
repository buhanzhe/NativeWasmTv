package xiao.bu.tv;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
    private final UiScaleHelper uiScaleHelper;
    private float uiScale = 1f;
    private ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
    private Channel[] channels = ChannelCatalog.CCTV_CHANNELS;
    private int selectedIndex;
    private int channelGroupIndex = -1;
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
    private final View.OnTouchListener favoriteTouchListener =
            new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                view.setPressed(false);
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                view.performClick();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }
            return true;
        }
    };

    ChannelListAdapter(Context context, UiScaleHelper uiScaleHelper) {
        inflater = LayoutInflater.from(context);
        this.uiScaleHelper = uiScaleHelper;
    }

    void setUiScale(float uiScale) {
        if (Math.abs(this.uiScale - uiScale) < 0.001f) {
            return;
        }
        this.uiScale = uiScale;
        notifyDataSetChanged();
    }

    void showGroups(ChannelCatalog.Group[] groups, int selectedIndex) {
        this.groups = groups;
        this.selectedIndex = selectedIndex;
        showingGroups = true;
        notifyDataSetChanged();
    }

    void showChannels(int groupIndex, Channel[] channels, int selectedIndex,
            int playingIndex, int playingSourceIndex) {
        this.channelGroupIndex = groupIndex;
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
        boolean favoriteFocused = !showingGroups && position == favoriteFocusIndex;
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
        uiScaleHelper.apply(convertView, uiScale);

        if (showingGroups) {
            ChannelCatalog.Group group = groups[position];
            holder.number.setText(group.source == ChannelCatalog.SOURCE_FAVORITES
                    ? "★" : String.valueOf(position));
            holder.name.setText(group.title);
            holder.count.setText(String.valueOf(group.channels.length));
        } else {
            Channel channel = channels[position];
            holder.number.setText(ChannelCatalog.displayNumber(
                    channelGroupIndex, position));
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
            holder.favorite.setTextColor(favoriteFocused ? Color.WHITE : favorite
                    ? FAVORITE_ACTIVE_COLOR : FAVORITE_INACTIVE_COLOR);
            holder.favorite.setContentDescription(favorite ? "取消收藏" : "收藏");
            holder.favorite.setSelected(favoriteFocused);
            holder.favorite.setScaleX(favoriteFocused ? 1.08f : 1f);
            holder.favorite.setScaleY(favoriteFocused ? 1.08f : 1f);
            holder.favorite.setTag(Integer.valueOf(position));
            holder.favorite.setOnClickListener(favoriteClickListener);
            holder.favorite.setOnTouchListener(favoriteTouchListener);
        }
        convertView.setActivated(position == selectedIndex && !favoriteFocused);
        return convertView;
    }

    private static final class ViewHolder {
        TextView number;
        TextView name;
        TextView count;
        TextView favorite;
    }
}
