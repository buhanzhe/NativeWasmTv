package xiao.bu.tv;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

final class ChannelListAdapter extends BaseAdapter implements xiao.bu.tv.skin.SharedTvUi.TitleWidths {
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
    private EpgManager epgManager;
    private boolean showLogos;
    private boolean logosActive = true;
    private boolean showFavorite=true;
    public float trailingWidth(){return showFavorite?34f:0f;}
    void setShowFavorite(boolean enabled){if(showFavorite==enabled)return;showFavorite=enabled;if(!enabled)favoriteFocusIndex=-1;notifyDataSetChanged();}
    private ChannelLogoLoader logos;
    public float leadingWidth(){return showLogos?64f:0f;}
    void setShowLogos(boolean enabled){if(showLogos==enabled)return;showLogos=enabled;if(!enabled&&logos!=null)logos.cancelPending();notifyDataSetChanged();}
    void close(){if(logos!=null)logos.close();}
    void setLogosActive(boolean active){if(logosActive==active)return;logosActive=active;if(!active&&logos!=null)logos.cancelPending();if(active)notifyDataSetChanged();}
    private void bindLogo(ImageView view,Channel channel){
        if(!showLogos){if(logos!=null)logos.clear(view);else view.setVisibility(View.GONE);return;}
        if(!logosActive){if(logos!=null)logos.clear(view);view.setVisibility(View.INVISIBLE);return;}
        if(logos==null)logos=new ChannelLogoLoader();
        logos.load(view,channel.name,epgManager==null?channel.logoUrl:epgManager.logoFor(channel));
    }
    private ChannelHealth health;
    void setHealth(ChannelHealth value){health=value;}
    private void healthStyle(View row,Object item){row.setAlpha(item instanceof Channel&&health!=null&&health.bad((Channel)item)?0.4f:1f);}

    private float measuredTitleSize=-1f, measuredTitleWidth;
    private int measuredFirst=-1,measuredCount=-1;
    public float titleWidth(android.graphics.Paint paint,int first,int count) {
        if(!showingGroups)return ChannelNameWidth.pixels(paint);
        if(measuredTitleSize!=paint.getTextSize()||measuredFirst!=first||measuredCount!=count) {
            measuredFirst=first;measuredCount=count;
            measuredTitleSize=paint.getTextSize();measuredTitleWidth=0;
            if(showingGroups){for(ChannelCatalog.Group group:groups)
                measuredTitleWidth=Math.max(measuredTitleWidth,paint.measureText(group.displayTitle));}
        }
        return measuredTitleWidth;
    }

    void refreshVisibleSelection(android.widget.ListView list) {
        for(int i=0;i<list.getChildCount();i++) {
            int position=list.getFirstVisiblePosition()+i;
            if(position<0)continue;
            if(position>=getCount())break;
            View row=list.getChildAt(i);
            getView(position,row,list);
            row.invalidate();
        }
    }

    void setEpgManager(EpgManager manager) { epgManager = manager; }
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
        measuredTitleSize=-1f;
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
        measuredTitleSize=-1f;
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
        if (this.selectedIndex == selectedIndex) return;
        this.selectedIndex = selectedIndex;
        notifyDataSetChanged();
    }

    void setFavoriteListener(FavoriteListener listener) {
        favoriteListener = listener;
    }

    void setFavoriteFocusIndex(int index) {
        if (favoriteFocusIndex == index) {
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
        if (position < 0 || position >= getCount()) return null;
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
        final Object item = getItem(position);
        // A queued ListView bind can outlive a catalog or column switch.
        // Stale positions must not index the replacement array or reject its theme.
        if (item == null) {
            View empty = new View(inflater.getContext());
            empty.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            empty.setEnabled(false);
            return empty;
        }
        final boolean groupRow = item instanceof ChannelCatalog.Group;
        if(TvUiPlugin.current()!=null){try{View row=pluginRow(position,convertView,item);healthStyle(row,item);return row;}catch(RuntimeException|LinkageError failure){TvUiPlugin.rejectRenderer(inflater.getContext(),failure);convertView=null;}}
        ViewHolder holder;
        boolean favoriteFocused = !groupRow && parent.hasFocus() && position == favoriteFocusIndex;
        // A recycled row can survive an adapter or group/channel mode change.
        // Never bind a group holder (without program/favorite views) as a channel.
        ViewHolder recycled = convertView != null && convertView.getTag() instanceof ViewHolder
                ? (ViewHolder) convertView.getTag() : null;
        if (recycled == null || recycled.groupRow != groupRow
                || recycled.themeRevision != TvUiPlugin.revision()) {
            xiao.bu.tv.skin.TvUi renderer=TvUiPlugin.provider();
            convertView = renderer==null?inflater.inflate(groupRow?R.layout.item_channel_group:R.layout.item_channel,parent,false)
                    :renderer.create(parent.getContext(),groupRow?"group-row":"channel-row",new android.os.Bundle(),new View[]{parent},null);
            holder = new ViewHolder();
            holder.groupRow = groupRow;
            holder.themeRevision = TvUiPlugin.revision();
            holder.number = (TextView) convertView.findViewById(R.id.channel_number);
            holder.name = (TextView) convertView.findViewById(R.id.channel_item_name);
            holder.count = (TextView) convertView.findViewById(R.id.channel_group_count);
            holder.groupFavorite = (ImageView) convertView.findViewById(
                    R.id.channel_group_favorite_icon);
            holder.favorite = (ImageView) convertView.findViewById(
                    R.id.channel_item_favorite);
            holder.program = (TextView) convertView.findViewById(R.id.channel_item_program);
            holder.logo = (ImageView) convertView.findViewById(R.id.channel_item_logo);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        if (holder.themeScale != uiScale) {
            uiScaleHelper.apply(convertView, uiScale);
            holder.themeScale = uiScale;
        }

        if (parent instanceof android.widget.ListView && parent.getHeight()>0) {
            android.widget.ListView list=(android.widget.ListView)parent;
            int preferred=Math.max(1,Math.round(46f*parent.getResources().getDisplayMetrics().density*uiScale));
            int divider=list.getDividerHeight();
            int available=Math.max(1,list.getHeight()-list.getPaddingTop()*2);
            int pitch=xiao.bu.tv.skin.SharedTvUi.fittedRowHeight(available+divider,preferred+divider);
            convertView.getLayoutParams().height=Math.max(1,pitch-divider);
        }

        if (groupRow) {
            ChannelCatalog.Group group = (ChannelCatalog.Group) item;
            boolean favoritesGroup = group.source == ChannelCatalog.SOURCE_FAVORITES;
            holder.number.setVisibility(favoritesGroup ? View.GONE : View.VISIBLE);
            holder.number.setText(favoritesGroup ? "" : String.valueOf(position));
            if (holder.groupFavorite != null) {
                holder.groupFavorite.setVisibility(favoritesGroup
                        ? View.VISIBLE : View.GONE);
            }
            holder.name.setText(group.displayTitle);
            holder.count.setText(String.valueOf(group.channels.length));
        } else {
            Channel channel = (Channel) item;
            if(holder.logo!=null)bindLogo(holder.logo,channel);
            holder.number.setText(ChannelCatalog.displayNumber(
                    channelGroupIndex, position));
            if (!android.text.TextUtils.equals(holder.name.getText(), channel.name)) {
                holder.name.setText(channel.name);
            }
            String title = holder.currentProgram.titleFor(epgManager, channel, System.currentTimeMillis());
            holder.program.setText(title);
            holder.program.setVisibility(title.length() == 0 ? View.GONE : View.VISIBLE);
            int sourceCount = Math.max(1, channel.sourceCount());
            holder.count.setVisibility(sourceCount > 1 ? View.VISIBLE : View.GONE);
            if (position == playingIndex) {
                int sourceNumber = (playingSourceIndex % sourceCount + sourceCount)
                        % sourceCount + 1;
                holder.count.setText(sourceNumber + "/" + sourceCount);
            } else {
                holder.count.setText(String.valueOf(sourceCount));
            }
            boolean favorite = favoriteListener != null
                    && favoriteListener.isFavorite(position);
            holder.favorite.setVisibility(showFavorite?View.VISIBLE:View.GONE);
            holder.favorite.setImageResource(favorite
                    ? R.drawable.collection_fill : R.drawable.collection);
            holder.favorite.setContentDescription(favorite ? "取消收藏" : "收藏");
            holder.favorite.setSelected(favoriteFocused);
            // ListView propagates its selected state to descendants on DPAD OK.
            // An inactive star must not acquire a background from that state.
            holder.favorite.setBackgroundResource(favoriteFocused
                    ? R.drawable.favorite_button_background : android.R.color.transparent);
            // Keep the focus border inside the measured button bounds.
            holder.favorite.setScaleX(1f);
            holder.favorite.setScaleY(1f);
            holder.favorite.setTag(Integer.valueOf(position));
            holder.favorite.setOnClickListener(favoriteClickListener);
            holder.favorite.setOnTouchListener(favoriteTouchListener);
        }
        boolean focused=position==selectedIndex&&parent.hasFocus()&&!favoriteFocused;
        boolean scrollTitle = !groupRow && focused;
        holder.name.setEllipsize(scrollTitle
                ? android.text.TextUtils.TruncateAt.MARQUEE
                : android.text.TextUtils.TruncateAt.END);
        holder.name.setSelected(scrollTitle);
        // ListView reapplies activated flags during layout. Inactive columns need
        // a transparent drawable as well, otherwise they retain a second fill.
        convertView.setBackgroundResource(focused
                ? groupRow ? R.drawable.channel_group_selected_background : R.drawable.channel_selected_background
                : android.R.color.transparent);
        convertView.setActivated(focused);
        holder.name.setTextColor((groupRow?position==selectedIndex&&!parent.hasFocus():position==playingIndex&&!focused)
                ? parent.getResources().getColor(R.color.accent)
                : focused ? android.graphics.Color.WHITE
                : parent.getResources().getColorStateList(R.color.channel_item_text).getDefaultColor());
        healthStyle(convertView,item);
        return convertView;
    }

    private View pluginRow(int position,View old,Object item){
        boolean groupRow=item instanceof ChannelCatalog.Group;
        float rowScale=inflater.getContext() instanceof xiao.bu.tv.skin.SharedTvUi.Viewport
                ?((xiao.bu.tv.skin.SharedTvUi.Viewport)inflater.getContext()).themeScale():uiScale;
        UiRow holder=old!=null&&old.getTag() instanceof UiRow?(UiRow)old.getTag():null;
        if(holder==null||holder.revision!=TvUiPlugin.revision()||holder.group!=groupRow||Math.abs(holder.scale-rowScale)>.001f){holder=new UiRow();holder.scale=rowScale;holder.revision=TvUiPlugin.revision();holder.group=groupRow;holder.state.putBoolean("group",groupRow);old=TvUiPlugin.provider().create(inflater.getContext(),groupRow?"group-row":"channel-row",holder.state,null,null);old.setTag(holder);}
        android.os.Bundle b=holder.state;
        b.putBoolean("selected",position==selectedIndex);b.putBoolean("showLogos",showLogos);b.putBoolean("showFavorite",showFavorite);b.putBoolean("favoriteFocused",showFavorite&&position==favoriteFocusIndex);b.putBoolean("playing",!groupRow&&position==playingIndex);
        if(groupRow){ChannelCatalog.Group group=(ChannelCatalog.Group)item;b.putString("title",group.displayTitle);b.putString("number",String.valueOf(position+1));b.putInt("count",group.channels.length);b.putBoolean("favoriteGroup",group.source==ChannelCatalog.SOURCE_FAVORITES);}
        else{Channel channel=(Channel)item;b.putString("title",channel.name);b.putString("number",ChannelCatalog.displayNumber(channelGroupIndex,position));
            if(showLogos&&holder.logo==null){final UiRow bound=holder;final View row=old;
                holder.logo=new ImageView(inflater.getContext()){
                    @Override public void setImageBitmap(android.graphics.Bitmap bitmap){super.setImageBitmap(bitmap);bound.state.putParcelable("logo",bitmap);row.invalidate();}
                    @Override public void setImageDrawable(android.graphics.drawable.Drawable drawable){super.setImageDrawable(drawable);if(drawable==null){bound.state.remove("logo");row.invalidate();}}
                };
            }
            if(holder.logo!=null)bindLogo(holder.logo,channel);
            String title=holder.currentProgram.titleFor(epgManager,channel,System.currentTimeMillis());
            b.putInt("sources",channel.urls.length);b.putInt("source",playingSourceIndex);b.putString("program",title);b.putBoolean("favorite",favoriteListener!=null&&favoriteListener.isFavorite(position));
        }
        if(!groupRow){
            old.setOnTouchListener((view,event)->{
                if(!showFavorite)return false;
                boolean inside=event.getX()>=view.getWidth()-40*rowScale&&event.getX()<=view.getWidth()&&event.getY()>=0&&event.getY()<view.getHeight();
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(!inside)return false;view.setPressed(true);return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_UP){view.setPressed(false);if(inside&&favoriteListener!=null)favoriteListener.onFavoriteClick(position);return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)view.setPressed(false);
                return true;
            });
        }
        TvUiPlugin.provider().update(old,b);return old;
    }
    private static final class UiRow{int revision;float scale;boolean group;ImageView logo;final android.os.Bundle state=new android.os.Bundle();final EpgManager.CurrentProgram currentProgram=new EpgManager.CurrentProgram();}

    private static final class ViewHolder {
        final EpgManager.CurrentProgram currentProgram = new EpgManager.CurrentProgram();
        int themeRevision;
        float themeScale = -1;
        boolean groupRow;
        TextView number;
        TextView name;
        TextView count;
        ImageView groupFavorite;
        ImageView favorite;
        ImageView logo;
        TextView program;
    }
}
