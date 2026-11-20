package xiao.bu.channel.bl;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Recycled video card with a measured cover and media metadata. */
final class BlCard extends LinearLayout {
    private final BlImages images;
    private final FrameLayout image;
    private final ImageView cover;
    private final LinearLayout badges;
    private final TextView title,owner,views,danmaku,duration;
    private final Drawable playIcon,liveIcon,authorIcon;

    BlCard(BlUi ui,BlImages images){
        super(ui.context);
        this.images=images;
        setOrientation(VERTICAL);
        setPadding(ui.px(10),ui.px(12),ui.px(10),ui.px(12));
        setBackgroundDrawable(ui.focus(0));
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        image=new FrameLayout(ui.context);
        cover=new ImageView(ui.context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.addView(cover,new FrameLayout.LayoutParams(-1,-1));

        badges=ui.horizontal();
        badges.setPadding(ui.px(7),ui.px(8),ui.px(7),0);
        badges.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000,0xcc000000}));
        image.addView(badges,new FrameLayout.LayoutParams(-1,ui.px(34),Gravity.BOTTOM));
        views=metadata(ui);
        badges.addView(views);
        danmaku=metadata(ui);
        LinearLayout.LayoutParams danmakuLayout=new LinearLayout.LayoutParams(-2,-2);
        danmakuLayout.leftMargin=ui.px(6);
        badges.addView(danmaku,danmakuLayout);
        badges.addView(new View(ui.context),new LinearLayout.LayoutParams(0,1,1));
        duration=metadata(ui);
        badges.addView(duration);
        addView(image,new LinearLayout.LayoutParams(-1,ui.px(130)));

        title=ui.text("",19,ui.theme.text);
        title.setMaxLines(2);
        title.setMinLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLayout=new LinearLayout.LayoutParams(-1,ui.px(52));
        titleLayout.topMargin=ui.px(8);
        addView(title,titleLayout);
        owner=metadata(ui);
        owner.setTextColor(ui.theme.muted);
        addView(owner,new LinearLayout.LayoutParams(-1,ui.px(24)));

        int iconSize=ui.px(19);
        playIcon=images.iconDrawable(getResources(),"icon_video_play_count",iconSize);
        liveIcon=images.iconDrawable(getResources(),"tab_live",iconSize);
        authorIcon=images.iconDrawable(getResources(),"icon_video_up",ui.px(17));
        if(authorIcon!=null)authorIcon.setColorFilter(ui.theme.muted,android.graphics.PorterDuff.Mode.SRC_IN);
        danmaku.setCompoundDrawables(images.iconDrawable(getResources(),"icon_video_danmaku",iconSize),null,null,null);
    }

    private TextView metadata(BlUi ui){
        TextView label=ui.text("",14,BlUi.TEXT);
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setCompoundDrawablePadding(ui.px(3));
        return label;
    }

    void bind(BlItem item){
        title.setText(item.title);
        boolean live=item.kind.equals("live"),video=item.kind.equals("video"),folder=item.kind.equals("folder");
        String subtitle=folder?"收藏夹":live?"直播":item.kind.equals("season")?"番剧":"";
        owner.setText(item.owner.isEmpty()?subtitle:item.owner);
        owner.setCompoundDrawables(video&&!item.owner.isEmpty()?authorIcon:null,null,null,null);

        boolean hasViews=video||live||item.count>0;
        boolean hasDanmaku=video&&item.danmakuCount>=0;
        boolean hasDuration=item.duration>0;
        views.setText(BlUi.count(item.count)+(folder?" 个视频":""));
        views.setVisibility(hasViews?VISIBLE:GONE);
        views.setCompoundDrawables(live?liveIcon:video?playIcon:null,null,null,null);
        danmaku.setText(hasDanmaku?BlUi.count(item.danmakuCount):"");
        danmaku.setVisibility(hasDanmaku?VISIBLE:GONE);
        duration.setText(hasDuration?BlUi.time(item.duration):"");
        duration.setVisibility(hasDuration?VISIBLE:GONE);
        badges.setVisibility(hasViews||hasDanmaku||hasDuration?VISIBLE:GONE);
        images.load(cover,item.picture,true);
        setContentDescription(item.title);
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        if(MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            ViewGroup.LayoutParams layout=image.getLayoutParams();
            layout.height=Math.max(1,(width*9+8)/16);
        }
        super.onMeasure(widthSpec,heightSpec);
    }
}
