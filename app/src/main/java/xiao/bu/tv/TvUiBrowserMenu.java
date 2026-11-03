package xiao.bu.tv;

import android.os.Bundle;
import xiao.bu.tv.skin.TvUi;

/** Remote-friendly browser preferences; mutations go through the management backend. */
final class TvUiBrowserMenu implements TvUi.Events {
    interface Host { Bundle state(); void settings(Bundle patch); void back(); }
    static final String[] TITLES={"分辨率","网页遮罩","极简模式","浏览器 UA","版本标识","网页缩放","网页内核","加载图片","嗅探播放","自动播放"};
    private static final String[] KEYS={"webViewResolution","webViewPageCover","webViewMinimalMode","webViewUserAgent","webViewBrowserVersion","webViewPageScale","webSourceEngine","webViewLoadImages","webViewAutoPlaySniffed","webViewAutoPlay"};
    private static final String[][] VALUES={
        {"720p","1080p","2k","4k"},{"off","status","black"},{"false","true"},
        {"windows","macos","ipad","native","auto"},{"native","118","128","138","155"},
        {"50","75","100","125","150","200","300"},{"system","lite"},{"false","true"},{"false","true"},{"false","true"}
    };
    private static final String[][] LABELS={
        {"720P","1080P","2K","4K"},{"关闭","加载信息","纯黑"},{"关闭","开启"},
        {"Windows","macOS","iPad","本机","自动适配"},{"本机版本","118","128","138","155"},
        {"50%","75%","100%","125%","150%","200%","300%"},{"系统","轻量内核"},{"关闭","开启"},{"关闭","开启"},{"关闭","开启"}
    };
    private final Host host;
    TvUiBrowserMenu(Host host){this.host=host;}
    static String[] titles(boolean liteSupported){
        if(liteSupported)return TITLES.clone();
        String[] titles=new String[TITLES.length-1];
        System.arraycopy(TITLES,0,titles,0,6);
        System.arraycopy(TITLES,7,titles,6,TITLES.length-7);
        return titles;
    }
    private int categoryIndex(int visible){
        boolean lite=host.state().getBoolean("liteBrowserSupported");
        if(visible<0||visible>=TITLES.length-(lite?0:1))return -1;
        return !lite&&visible>=6?visible+1:visible;
    }
    Bundle initial(int category,boolean remote){
        String[] titles=titles(host.state().getBoolean("liteBrowserSupported"));
        Bundle b=new Bundle();b.putStringArray("categories",titles);
        b.putInt("category",Math.max(0,Math.min(titles.length-1,category)));
        b.putBoolean("focusOptions",true);b.putBoolean("remoteInput",remote);return b;
    }
    public Bundle options(int category){
        Bundle b=new Bundle();b.putInt("selected",-1);
        category=categoryIndex(category);
        if(category<0||category>=TITLES.length){b.putStringArray("items",new String[0]);return b;}
        Bundle s=host.state();String value;
        if(category==1)value=s.getBoolean("webViewPageCover")?s.getString("webViewPageCoverStyle"):"off";
        else if(category==2||category>=7)value=String.valueOf(s.getBoolean(KEYS[category]));
        else if(category==5)value=String.valueOf(s.getInt("webViewPageScalePercent"));
        else value=s.getString(KEYS[category]);
        String[] items=LABELS[category].clone();
        b.putStringArray("items",items);
        for(int i=0;i<items.length;i++)if(VALUES[category][i].equals(value))b.putInt("selected",i);
        return b;
    }
    public void action(int category,int option){
        int visible=category;category=categoryIndex(category);
        if(category<0||option<0||option>=options(visible).getStringArray("items").length)return;
        String value=VALUES[category][option];Bundle patch=new Bundle();
        if(category==1){
            patch.putBoolean("webViewPageCover",option!=0);
            if(option!=0)patch.putString("webViewPageCoverStyle",value);
        }else if(category==2||category>=7)patch.putBoolean(KEYS[category],"true".equals(value));
        else if(category==5)patch.putDouble(KEYS[category],Integer.parseInt(value)/100d);
        else patch.putString(KEYS[category],value);
        host.settings(patch);
    }
    public void dismiss(){host.back();}
}
