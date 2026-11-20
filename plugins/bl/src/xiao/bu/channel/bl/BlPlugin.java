package xiao.bu.channel.bl;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import org.json.*;
import okhttp3.OkHttpClient;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import xiao.bu.tv.jar.ChannelPlugin;
import java.util.*;
import java.util.concurrent.*;

/** BBLL-inspired native single-View Bilibili channel. No Activity, Fragment or WebView. */
public final class BlPlugin implements ChannelPlugin,ChannelPlugin.NativePage {
    private interface Job{Object run()throws Exception;}
    private interface Result{void show(Object value)throws Exception;}
    private static final class Route{
        String kind,title,arg;long playbackCid,playbackEpisode;int playbackPart;int page=1,selection,scroll;boolean more=true,playFromDetail;JSONArray cached=new JSONArray();JSONObject data;
        Route(String kind,String title,String arg){this.kind=kind;this.title=title;this.arg=arg;}
    }
    private static final class Option{
        final String title;
        final Runnable action;
        final boolean checked;
        final BlTheme preview;
        Option(String title,Runnable action){this(title,action,false);}
        Option(String title,Runnable action,boolean checked){this(title,action,checked,null);}
        Option(String title,Runnable action,boolean checked,BlTheme preview){this.title=title;this.action=action;this.checked=checked;this.preview=preview;}
    }
    private static final class Panel{String title;List<Option> rows;Panel parent;int selection;Panel(String title,List<Option> rows,Panel parent){this.title=title;this.rows=rows;this.parent=parent;}}
    private static final class Playback{
        final JSONObject data;
        final JSONArray streams;
        Playback(JSONObject data,JSONArray streams){this.data=data;this.streams=streams;}
    }
    private Panel panel,openingPanel;
    private BlLiveDanmaku liveDanmaku;private boolean liveLegacy,progressiveFallback;
    private final Set<String> rejectedDashTracks=new HashSet<>();
    private Context context;private Host host;private BlUi ui;private BlApi api;private BlImages images;private BlMediaProxy proxy;
    private Root root;private LinearLayout sidebar,headerActions;private FrameLayout content,modal;private GridView grid;private TextView status;
    private ImageView background;private ImageButton sidebarAccount;private TextView emptyContent;
    private CardAdapter adapter;private final List<BlItem> items=new ArrayList<>();
    private final Map<String,Button> tabs=new LinkedHashMap<>();
    private final Map<String,Route> homeSections=new LinkedHashMap<>();
    private final Map<String,Route> mineSections=new LinkedHashMap<>();
    private final Map<String,Button> sectionButtons=new LinkedHashMap<>();
    private static final String[][] HOME_SECTIONS={{"推荐","home"},{"热门","popular"},{"番剧","season"},{"影视","movies"}};
    private static final String[][] MINE_SECTIONS={{"历史","history"},{"收藏","favorites"},{"追番","animeFollows"},{"追剧","dramaFollows"},{"稍后观看","later"}};
    private static final String[][] SEARCH_TYPES={{"视频","video"},{"番剧","media_bangumi"},{"直播","live_room"},{"UP主","bili_user"}};
    private static final int DEFAULT_AUDIO_TRACK=BlStreams.AUDIO_192K;
    private String homeSection="home", mineSection="history",searchDraft="", settingsSection="general";
    private boolean searchGrouped=true;
    private View contentEntry,pageAction;
    private ListView settingsList;
    private BlSearchView searchView;
    private List<String> trendingSearches;
    private JSONArray liveAreas;
    private boolean mineSignedIn;
    private final Deque<Route> history=new ArrayDeque<>();private Route route;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(2,2,20,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(16),new ThreadPoolExecutor.DiscardOldestPolicy());
    private int generation,modalGeneration;private boolean closed,loading,statusError,active=true;private String selectedTab="home";
    private BlPlayer player;private BlDanmakuFeed dmFeed;private long dmCid;private final List<BlPlayer> retired=new ArrayList<>();private JSONObject video,playData,selectedStream;private long cid,episode;private int playbackGeneration,playingGeneration;private int partIndex;private View modalFocus;
    /** Build the entire native channel inside one root View using the host transport. */
    public View create(Context context,ClassLoader resources,JSONObject config,Host host)throws Exception{
        if(!(host instanceof ChannelPlugin.NativeHost))throw new java.io.IOException("BL 需要更新支持原生插件的宿主 APK");
        Object client=((ChannelPlugin.NativeHost)host).httpClient();if(!(client instanceof OkHttpClient))throw new java.io.IOException("宿主共享 OkHttp 不可用");
        // A fixed, separate namespace lets device instrumentation leave real accounts/history intact.
        String preferences=config.optBoolean("testPreferences",false)?"ntv-bl-instrumentation":"ntv-bl";
        this.context=context;this.host=host;ui=new BlUi(context);api=new BlApi((OkHttpClient)client,context.getSharedPreferences(preferences,Context.MODE_PRIVATE),config);images=new BlImages(api,resources);proxy=new BlMediaProxy(api);
        root=new Root(context);root.setContentDescription("BL频道");
        background=new ImageView(context);background.setScaleType(ImageView.ScaleType.CENTER_CROP);background.setImageBitmap(images.asset("background.png"));root.addView(background,new FrameLayout.LayoutParams(-1,-1));
        sidebar=ui.vertical();sidebar.setPadding(ui.px(8),ui.px(12),ui.px(8),ui.px(12));
        tab("search","搜索","icon_search");tab("home","首页","icon_home");tab("region","分区","tab_recommend");tab("dynamic","动态","tab_dynamic");tab("live","直播","tab_live");tab("mine","我的","tab_me");
        sidebar.addView(new View(context),new LinearLayout.LayoutParams(1,0,1));
        sidebarAccount=new ImageButton(context);
        sidebarAccount.setContentDescription("用户");
        sidebarAccount.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int avatarPadding=ui.px(10);
        sidebarAccount.setPadding(avatarPadding,avatarPadding,avatarPadding,avatarPadding);
        sidebarAccount.setOnClickListener(v->{
            if(api.signedIn())showMineAccount(savedProfile().optString("uname","账号"));
            else openLogin();
        });
        sidebar.addView(sidebarAccount,new LinearLayout.LayoutParams(-1,ui.px(52)));
        tab("settings","设置","icon_setting");
        root.addView(sidebar,new FrameLayout.LayoutParams(ui.px(100),-1,Gravity.LEFT));content=new FrameLayout(context);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-1);cp.leftMargin=ui.px(108);root.addView(content,cp);
        for(String[] section:HOME_SECTIONS)homeSections.put(section[1],new Route(section[1],section[0],""));
        for(String[] section:MINE_SECTIONS)mineSections.put(section[1],new Route(section[1],section[0],""));
        String start=api.prefs.getString("home_section","home");
        if(mineSections.containsKey(start)){mineSection=start;selectedTab="mine";}
        else if(homeSections.containsKey(start))homeSection=start;
        else if(start.equals("region")||start.equals("live")||start.equals("dynamic")||start.equals("search"))selectedTab=start;
        route=topRoute(selectedTab,tabs.get(selectedTab).getText().toString());
        applyTheme();render();return root;
    }
    private Route topRoute(String key,String title){
        if(key.equals("home"))return homeSections.get(homeSection);
        if(key.equals("mine"))return mineSections.get(mineSection);
        return new Route(key,title,"");
    }
    private BlTheme preferredTheme(){return BlTheme.saved(api.prefs.getString("theme",BlTheme.BLACK.name()));}
    private void applyTheme(){
        ui.theme=preferredTheme().resolve(context.getResources().getConfiguration());
        boolean classic=ui.theme==BlTheme.CLASSIC;
        if(classic)root.setBackgroundDrawable(ui.backdrop(ui.theme,0));
        else root.setBackgroundColor(ui.theme.background);
        sidebar.setBackgroundColor(classic?0xd92d1428:ui.theme.dark?0xf021242c:ui.theme.surface);
        sidebarAccount.setBackgroundDrawable(ui.focus(ui.buttonColor()));
        background.setAlpha(classic?0f:ui.theme.dark?.20f:.035f);
        for(Button button:tabs.values()){
            ui.style(button);
            Drawable icon=button.getCompoundDrawables()[1];
            if(icon!=null)icon.setColorFilter(ui.theme.text,PorterDuff.Mode.SRC_IN);
        }
    }
    private void tab(final String key,String title,String icon){
        Button b=ui.button(title);b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(16));b.setPadding(ui.px(2),ui.px(4),ui.px(2),ui.px(4));b.setContentDescription(title);
        b.setCompoundDrawables(null,images.iconDrawable(context.getResources(),icon,ui.px(32)),null,null);
        b.setCompoundDrawablePadding(ui.px(4));
        sidebar.addView(b,new LinearLayout.LayoutParams(-1,ui.px(70)));tabs.put(key,b);
        b.setOnClickListener(v->{
            if(key.equals("settings")){
                settingsSection="general";
                navigate(topRoute(key,title),true);
                return;
            }
            Route next=topRoute(key,title);
            if(key.equals(selectedTab)&&(next==route||next.kind.equals(route.kind))){
                refreshRoute();
                return;
            }
            selectedTab=key;
            history.clear();
            navigate(next,false);
        });
        b.setOnKeyListener((v,code,event)->{
            if(code!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;
            View target=searchView!=null?searchView.entry():grid!=null&&adapter.getCount()>0?grid:contentEntry;
            if(target==null)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN){if(target==grid)focusGrid();else target.requestFocusFromTouch();}
            return true;
        });
    }
    private void navigate(Route next,boolean push){
        saveRoute();closePlayer();hideModal();if(push&&route!=null){history.push(route);while(history.size()>12)history.removeLast();}route=next;render();
    }
    private void saveRoute(){
        if(searchView!=null){
            searchDraft=searchView.query();
            searchGrouped=searchView.isGrouped();
            searchView.hideIme();
        }
        if(route==null||grid==null)return;
        int selected=grid.getSelectedItemPosition();
        if(grid.hasFocus()&&selected>=0)route.selection=selected;
        route.scroll=grid.getFirstVisiblePosition();
    }
    private void focusGrid(){
        int selected=Math.min(route.selection,adapter.getCount()-1);
        grid.requestFocusFromTouch();
        grid.setSelection(selected);
    }
    private void render(){
        int settingPosition=settingsList!=null&&settingsList.hasFocus()?settingsList.getSelectedItemPosition():-1;
        settingsList=null;
        generation++;loading=false;statusError=false;content.removeAllViews();grid=null;adapter=null;contentEntry=null;pageAction=null;searchView=null;emptyContent=null;status=null;headerActions=null;sectionButtons.clear();items.clear();
        for(Map.Entry<String,Button> t:tabs.entrySet())t.getValue().setSelected(t.getKey().equals(selectedTab));
        boolean standalone=route.kind.equals("player")||route.kind.equals("settings");
        sidebar.setVisibility(standalone?View.GONE:View.VISIBLE);
        if(!standalone)images.load(sidebarAccount,savedProfile().optString("face"),false);
        FrameLayout.LayoutParams cp=(FrameLayout.LayoutParams)content.getLayoutParams();
        cp.leftMargin=standalone?0:ui.px(108);
        content.setLayoutParams(cp);
        if(route.kind.equals("player")){renderPlayer();return;}
        final LinearLayout page=ui.vertical();page.setPadding(ui.px(20),ui.px(18),ui.px(20),ui.px(12));content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        if(route.kind.equals("search")){renderSearch(page);return;}
        if(mineSections.containsKey(route.kind)){renderMine(page);return;}
        if(route.kind.equals("settings")){
            renderSettings(page);
            if(settingPosition>=0){
                settingsList.requestFocusFromTouch();
                settingsList.setSelection(settingPosition);
            }
            return;
        }

        LinearLayout header=ui.horizontal();
        if(homeSections.containsKey(route.kind)){
            header.addView(sectionBar(homeSections),new LinearLayout.LayoutParams(0,ui.px(48),1));
        }else if(route.kind.equals("live")||route.kind.equals("liveArea")){
            renderLiveAreas(header);
        }else if(route.kind.equals("region")||route.kind.equals("regionItems")){
            renderRegions(header);
        }else if(route.kind.equals("dynamic")){
            header.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        }else{
            TextView title=ui.title(route.title);
            title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(title,new LinearLayout.LayoutParams(0,ui.px(48),1));
        }
        appendStatus(header);
        page.addView(header);
        if(route.kind.equals("detail")){renderDetail(page);return;}
        if(route.kind.equals("seasonDetail")){renderSeason(page);return;}
        if(route.kind.equals("login")){renderLogin(page);return;}
        if(route.kind.equals("dynamic")&&!api.signedIn()){loginGate(page,"登录后查看关注的 UP 主动态");return;}
        if(route.kind.equals("movies"))renderFilmTypes(page);
        if(route.kind.equals("results"))renderSearchTypes(page);
        showGrid(page);
    }
    private void appendStatus(LinearLayout header){
        status=ui.text("",15,ui.theme.muted);
        status.setMaxWidth(ui.px(250));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setVisibility(View.GONE);
        header.addView(status);
        headerActions=ui.horizontal();
        action(headerActions,"错误详情",this::showDiagnostics);
        action(headerActions,"刷新",this::refreshRoute);
        headerActions.setVisibility(View.GONE);
        header.addView(headerActions);
    }
    private void setStatus(String text){
        if(status==null)return;
        status.setText(text);
        status.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void refreshRoute(){
        saveRoute();
        if(searchView!=null)trendingSearches=null;
        resetPage(route);
        render();
    }
    private LinearLayout sectionBar(Map<String,Route> sections){
        LinearLayout row=ui.horizontal();
        for(Route section:sections.values()){
            Button button=ui.button(section.title);
            button.setSelected(section.kind.equals(route.kind));
            row.addView(button);ui.gap(row,6);
            sectionButtons.put(section.kind,button);
            button.setOnClickListener(v->{
                if(section==route)refreshRoute();
                else{
                    if(sections==homeSections)homeSection=section.kind;else mineSection=section.kind;
                    navigate(section,false);
                }
                sectionButtons.get(section.kind).requestFocusFromTouch();
            });
            button.setOnKeyListener((v,code,event)->{
                if(code!=KeyEvent.KEYCODE_DPAD_DOWN)return false;
                boolean hasCards=grid!=null&&adapter.getCount()>0;
                if(!hasCards&&pageAction==null)return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN){if(hasCards)focusGrid();else pageAction.requestFocusFromTouch();}
                return true;
            });
        }
        contentEntry=sectionButtons.get(route.kind);
        return row;
    }
    private void resetPage(Route target){target.cached=new JSONArray();target.page=1;target.more=true;}
    private void showGrid(LinearLayout page){
        makeGrid(page);
        if(route.cached.length()>0||!route.more)bind(route.cached,false);else loadPage(false);
    }
    private void makeGrid(LinearLayout page){
        grid=new GridView(context);grid.setNumColumns(4);grid.setHorizontalSpacing(ui.px(8));grid.setVerticalSpacing(ui.px(8));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setColumnWidth(ui.px(240));grid.setClipToPadding(false);grid.setPadding(0,ui.px(18),0,ui.px(14));
        android.graphics.drawable.GradientDrawable selector=ui.fill(ui.theme.tint(24),10);selector.setStroke(ui.px(2),ui.theme.accent);grid.setSelector(selector);grid.setDrawSelectorOnTop(true);
        adapter=new CardAdapter();grid.setAdapter(adapter);
        FrameLayout results=new FrameLayout(context);
        results.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        emptyContent=ui.text("",20,ui.theme.muted);emptyContent.setGravity(Gravity.CENTER);emptyContent.setVisibility(View.GONE);
        results.addView(emptyContent,new FrameLayout.LayoutParams(-1,-1));
        page.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        final Route target=route;
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(parent==grid&&grid.hasFocus())target.selection=position;
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> p,View v,int position,long id){openItem(items.get(position));}});
        grid.setOnKeyListener((v,code,event)->{
            int selected=Math.max(0,grid.getSelectedItemPosition());
            View next=null;
            if(code==KeyEvent.KEYCODE_DPAD_LEFT&&selected%4==0)next=tabs.get(selectedTab);
            if(code==KeyEvent.KEYCODE_DPAD_UP&&selected<4)next=contentEntry;
            if(next==null)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN)next.requestFocusFromTouch();
            return true;
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){if(state==SCROLL_STATE_IDLE&&route.more&&adapter.getCount()>0&&v.getLastVisiblePosition()>=adapter.getCount()-4)loadPage(true);}public void onScroll(AbsListView v,int first,int visible,int count){if(count>0&&first>0&&first+visible>=count-4&&route.more&&!loading)loadPage(true);}});
    }
    private final class CardAdapter extends BaseAdapter{
        public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View reuse,ViewGroup parent){BlCard card=reuse instanceof BlCard?(BlCard)reuse:new BlCard(ui,images);card.bind(items.get(p));return card;}
    }
    private String expected(){
        switch(route.kind){
            case "favorites":return "folder";
            case "following":return "user";
            case "season":case "movies":case "animeFollows":case "dramaFollows":return "season";
            case "results":return route.data.optString("type","video");
            case "dynamic":return "dynamic";
            case "live":case "liveArea":return "live";
            default:return "video";
        }
    }
    private void bind(JSONArray data,boolean append){
        if(!append)items.clear();String type=expected();Set<String> ids=new HashSet<>();for(BlItem i:items)ids.add(i.kind+":"+i.id);
        for(int i=0;i<data.length()&&items.size()<144;i++){JSONObject v=data.optJSONObject(i);if(v==null)continue;BlItem item=new BlItem(v,type);if(!item.id.isEmpty()&&!item.id.equals("0")&&!item.title.trim().isEmpty()&&ids.add(item.kind+":"+item.id))items.add(item);}
        adapter.notifyDataSetChanged();if(!append&&!items.isEmpty()&&(root.findFocus()==null||root.findFocus()==grid))focusGrid();
        emptyContent.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
        if(items.isEmpty())emptyContent.setText(route.kind.equals("history")&&!api.signedIn()?"暂无本地观看历史":"暂无内容");
        statusError=false;
        headerActions.setVisibility(View.GONE);
        setStatus("");
        if(items.size()>=144)route.more=false;
    }
    private void loadPage(final boolean more){
        if(loading||closed||!route.more)return;final int page=more?route.page+1:1;final Route target=route;final int token=generation;loading=true;setStatus("正在加载…");
        submit(new Job(){public Object run()throws Exception{
            String k=target.kind;if(k.equals("home"))try{return api.home(page);}catch(Exception error){return api.popular(page);}
            if(k.equals("popular")||k.equals("region"))return api.popular(page);if(k.equals("live"))return api.live(page);
            if(k.equals("liveArea"))return api.liveArea(Integer.parseInt(target.arg),page);
            if(k.equals("season"))return api.seasons(page,1);
            if(k.equals("movies"))return api.seasons(page,target.arg.isEmpty()?2:Integer.parseInt(target.arg));
            if(k.equals("history"))return api.history(page);if(k.equals("later"))return api.later();
            if(k.equals("animeFollows")||k.equals("dramaFollows"))return api.followedSeasons(k.equals("animeFollows")?1:2,page);
            if(k.equals("favorites"))return api.favoriteFolders();if(k.equals("favoriteItems"))return api.favorites(Long.parseLong(target.arg),page);
            if(k.equals("following"))return api.followed(page);if(k.equals("uploads"))return api.uploads(Long.parseLong(target.arg),page);
            if(k.equals("regionItems"))return target.arg.isEmpty()?api.popular(page):api.region(Integer.parseInt(target.arg),page);
            if(k.equals("results"))return api.search(target.arg,target.data.optString("type","video"),page);
            if(k.equals("dynamic")){String offset="";if(target.cached.length()>0){JSONObject last=target.cached.optJSONObject(target.cached.length()-1);if(last!=null)offset=last.optString("id_str");}return api.dynamic(offset);}
            return new JSONArray();
        }},token,new Result(){public void show(Object value)throws Exception{
            loading=false;JSONArray data=(JSONArray)value;if(!more)target.cached=new JSONArray();for(int i=0;i<data.length()&&target.cached.length()<144;i++)target.cached.put(data.get(i));target.page=page;target.more=data.length()>=(target.kind.equals("regionItems")?15:20)&&!target.kind.equals("favorites")&&!target.kind.equals("later")&&!(target.kind.equals("history")&&!api.signedIn());bind(data,more);
        }});
    }
    private void openItem(BlItem item){
        if(item.kind.equals("folder"))navigate(new Route("favoriteItems",item.title,item.id),true);
        else if(item.kind.equals("user"))navigate(new Route("uploads",item.title,item.id),true);
        else if(item.kind.equals("season"))navigate(new Route("seasonDetail",item.title,item.id),true);
        else if(item.kind.equals("live")){video=item.raw;episode=0;cid=Long.parseLong(item.id);Route r=new Route("player",item.title,item.id);r.data=item.raw;try{r.data.put("bl_live",true);}catch(Exception ignored){}navigate(r,true);}
        else{
            Route detail=new Route("detail",item.title,item.id);
            detail.playFromDetail=!api.prefs.getBoolean("show_video_details",true);
            navigate(detail,true);
        }
    }
    private void renderSearch(LinearLayout page){
        searchView=new BlSearchView(ui,searchDraft,searchGrouped,recentSearches(),this::submitSearch,()->{
            saveRoute();
            api.prefs.edit().remove("search_history").apply();
            render();
        });
        page.addView(searchView,new LinearLayout.LayoutParams(-1,0,1));
        contentEntry=searchView.entry();
        if(trendingSearches!=null){searchView.showTrending(trendingSearches);return;}
        final BlSearchView visible=searchView;
        submit(()->{
            try{return api.trendingSearches();}catch(Exception unavailable){return Collections.emptyList();}
        },generation,value->{
            List<String> words=new ArrayList<>();
            for(Object word:(List<?>)value)if(word instanceof String)words.add((String)word);
            trendingSearches=words;
            visible.showTrending(words);
        },false);
    }
    private List<String> recentSearches(){
        List<String> recent=new ArrayList<>();
        try{
            JSONArray saved=new JSONArray(api.prefs.getString("search_history","[]"));
            for(int i=0;i<Math.min(12,saved.length());i++){
                String query=saved.optString(i);
                if(!query.isEmpty())recent.add(query);
            }
        }catch(JSONException ignored){}
        return recent;
    }
    private void submitSearch(String value){
        String query=value.trim();
        if(query.isEmpty())return;
        List<String> recent=recentSearches();
        recent.remove(query);
        recent.add(0,query);
        api.prefs.edit().putString("search_history",new JSONArray(recent.subList(0,Math.min(12,recent.size()))).toString()).apply();
        searchView.setQuery(query);
        java.util.regex.Matcher id=java.util.regex.Pattern.compile("BV[0-9A-Za-z]{10}").matcher(query);
        if(id.find())navigate(new Route("detail",id.group(),id.group()),true);
        else openSearchResults(query,"video",true);
    }
    private void openSearchResults(String query,String type,boolean push){
        Route next=new Route("results","搜索 · "+query,query);
        next.data=new JSONObject();
        try{next.data.put("type",type);}catch(JSONException ignored){}
        navigate(next,push);
    }
    private void renderSearchTypes(LinearLayout page){
        String current=route.data.optString("type","video");
        LinearLayout filters=ui.horizontal();
        for(String[] pair:SEARCH_TYPES){
            String type=pair[1];
            Button filter=ui.button(pair[0]);
            filter.setSelected(type.equals(current));
            if(type.equals(current))contentEntry=filter;
            filters.addView(filter);ui.gap(filters,8);
            filter.setOnClickListener(v->{
                if(type.equals(current))return;
                openSearchResults(route.arg,type,false);
                contentEntry.requestFocusFromTouch();
            });
        }
        page.addView(filters);
    }
    private void renderFilmTypes(LinearLayout page){
        LinearLayout row=ui.horizontal();
        String active=route.arg.isEmpty()?"2":route.arg;
        for(String[] category:new String[][]{{"电影","2"},{"电视剧","5"},{"纪录片","3"}}){
            Button button=ui.button(category[0]);
            boolean selected=category[1].equals(active);
            button.setSelected(selected);if(selected)contentEntry=button;
            button.setOnKeyListener(this::focusGridOnDown);row.addView(button);ui.gap(row,6);
            button.setOnClickListener(v->{
                if(category[1].equals(active))return;
                route.arg=category[1];route.selection=0;resetPage(route);render();
                contentEntry.requestFocusFromTouch();
            });
        }
        page.addView(row);
    }
    private void renderLiveAreas(LinearLayout header){
        HorizontalScrollView choices=new HorizontalScrollView(context);
        choices.setHorizontalScrollBarEnabled(false);
        LinearLayout row=ui.horizontal();choices.addView(row);
        header.addView(choices,new LinearLayout.LayoutParams(0,ui.px(52),1));
        showLiveAreaButtons(row);
        if(liveAreas==null)submit(()->{
            try{return api.liveAreas();}catch(Exception unavailable){return new JSONArray();}
        },generation,value->{liveAreas=(JSONArray)value;showLiveAreaButtons(row);},false);
    }
    private void showLiveAreaButtons(LinearLayout row){
        if(row.getChildCount()==0){
            Button recommended=liveAreaButton("推荐","");row.addView(recommended);ui.gap(row,6);
            contentEntry=recommended;
        }
        if(liveAreas==null)return;
        for(int i=0;i<Math.min(liveAreas.length(),24);i++){
            JSONObject area=liveAreas.optJSONObject(i);
            if(area==null||area.optInt("id")<=0||area.optString("name").isEmpty())continue;
            String id=area.optString("id");Button button=liveAreaButton(area.optString("name"),id);
            row.addView(button);ui.gap(row,6);
            if(id.equals(route.arg))contentEntry=button;
        }
    }
    private Button liveAreaButton(String title,String id){
        Button button=ui.button(title);button.setSelected(id.equals(route.arg));
        button.setOnKeyListener(this::focusGridOnDown);
        button.setOnClickListener(v->{
            if(id.equals(route.arg)){
                refreshRoute();
            }else{
                navigate(new Route(id.isEmpty()?"live":"liveArea","直播",id),false);
            }
            contentEntry.requestFocusFromTouch();
        });
        return button;
    }
    private boolean focusGridOnDown(View view,int code,KeyEvent event){
        if(code!=KeyEvent.KEYCODE_DPAD_DOWN||grid==null||adapter.getCount()==0)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN)focusGrid();
        return true;
    }
    private void renderRegions(LinearLayout header){
        String[][] sections={{"全站",""},{"动画","1005"},{"音乐","1003"},{"舞蹈","1004"},
                {"游戏","1008"},{"知识","1010"},{"科学","1012"},{"运动","1018"},
                {"汽车","1013"},{"生活","1031"},{"美食","1020"},{"动物圈","1024"},
                {"娱乐","1002"},{"影视","1001"},{"鬼畜","1007"},{"资讯","1009"}};
        HorizontalScrollView choices=new HorizontalScrollView(context);
        choices.setHorizontalScrollBarEnabled(false);
        LinearLayout row=ui.horizontal();
        choices.addView(row);
        header.addView(choices,new LinearLayout.LayoutParams(0,ui.px(52),1));
        for(String[] section:sections){
            String id=section[1];
            Button button=ui.button(section[0]);
            boolean selected=id.equals(route.arg);
            button.setSelected(selected);
            if(selected)contentEntry=button;
            button.setOnKeyListener(this::focusGridOnDown);
            row.addView(button);
            ui.gap(row,6);
            button.setOnClickListener(v->{
                if(id.equals(route.arg)){
                    refreshRoute();
                }else if(id.isEmpty()&&!history.isEmpty()&&history.peek().kind.equals("region")){
                    onBack();
                }else{
                    navigate(new Route(id.isEmpty()?"region":"regionItems",section[0],id),route.kind.equals("region"));
                }
                contentEntry.requestFocusFromTouch();
            });
        }
    }
    private void renderMine(LinearLayout page){
        boolean signedIn=api.signedIn();
        if(signedIn!=mineSignedIn){for(Route section:mineSections.values())resetPage(section);mineSignedIn=signedIn;}
        JSONObject profile=savedProfile();

        LinearLayout account=ui.horizontal();
        ImageButton avatar=new ImageButton(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackgroundDrawable(ui.focus(ui.buttonColor()));
        avatar.setPadding(ui.px(4),ui.px(4),ui.px(4),ui.px(4));
        avatar.setContentDescription("用户");
        String accountName=profile.optString("uname","账号");
        avatar.setOnClickListener(v->showMineAccount(accountName));
        images.load(avatar,profile.optString("face"),false);
        account.addView(avatar,new LinearLayout.LayoutParams(ui.px(58),ui.px(58)));
        ui.gap(account,12);
        account.addView(sectionBar(mineSections),new LinearLayout.LayoutParams(0,ui.px(48),1));
        appendStatus(account);
        page.addView(account);
        if(signedIn&&!profile.has("uname"))submit(api::profile,generation,value->{
            JSONObject user=(JSONObject)value;
            images.load(avatar,user.optString("face"),false);
            images.load(sidebarAccount,user.optString("face"),false);
            avatar.setOnClickListener(v->showMineAccount(user.optString("uname","账号")));
        },false);
        if(!signedIn&&!route.kind.equals("history")){loginGate(page,"登录后查看"+route.title);return;}
        showGrid(page);
    }
    private JSONObject savedProfile(){
        if(api.signedIn())try{return new JSONObject(api.prefs.getString("user","{}"));}catch(JSONException ignored){}
        return new JSONObject();
    }
    private void showMineAccount(String accountName){
        List<Option> rows=new ArrayList<>();
        if(api.signedIn()){
            rows.add(new Option("我的关注",()->navigate(new Route("following","我的关注",""),true)));
            rows.add(new Option("退出登录",()->confirm("退出登录","确认退出登录",()->{
                api.logout();
                for(Route section:mineSections.values())resetPage(section);
                render();
            })));
        }else rows.add(new Option("扫码登录",this::openLogin));
        rows.add(new Option("清空本地观看历史",()->confirm("观看历史","确认清空本地观看历史",()->{
            api.prefs.edit().remove("history").apply();
            resetPage(mineSections.get("history"));
            if(route.kind.equals("history"))render();
            message("本地历史已清空",false);
        })));
        options(accountName,rows);
    }
    private void openLogin(){navigate(new Route("login","扫码登录",""),true);}
    private void loginGate(LinearLayout page,String label){
        FrameLayout area=new FrameLayout(context);
        LinearLayout card=ui.vertical();
        card.setPadding(ui.px(30),ui.px(26),ui.px(30),ui.px(26));
        android.graphics.drawable.GradientDrawable surface=ui.fill(ui.theme.surface,18);
        surface.setStroke(ui.px(1),ui.theme.tint(68));
        card.setBackgroundDrawable(surface);
        LinearLayout heading=ui.horizontal();
        ImageView account=new ImageView(context);
        account.setImageDrawable(tintedIcon("icon_user",32,ui.theme.accent));
        account.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        account.setBackgroundDrawable(ui.fill(ui.theme.tint(34),12));
        heading.addView(account,new LinearLayout.LayoutParams(ui.px(54),ui.px(54)));ui.gap(heading,18);
        TextView title=ui.text(label,23,ui.theme.text);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(heading);
        Button login=ui.button("扫码登录");login.setOnClickListener(v->openLogin());
        Button retry=ui.button("重试");retry.setOnClickListener(v->{
            refreshRoute();
            if(pageAction!=null)pageAction.requestFocusFromTouch();
        });
        View tab=sectionButtons.get(route.kind);
        if(tab!=null){
            View.OnKeyListener backToTab=(v,code,event)->{
                if(code!=KeyEvent.KEYCODE_DPAD_UP)return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN)tab.requestFocusFromTouch();
                return true;
            };
            login.setOnKeyListener(backToTab);retry.setOnKeyListener(backToTab);
        }
        LinearLayout actions=ui.horizontal();actions.addView(login);ui.gap(actions,12);actions.addView(retry);
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-1,ui.px(48));action.topMargin=ui.px(20);card.addView(actions,action);
        pageAction=login;
        if(contentEntry==null)contentEntry=login;
        area.addView(card,ui.frame(510,-2,Gravity.CENTER));
        page.addView(area,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void renderSettings(LinearLayout page){
        LinearLayout header=ui.horizontal();
        ImageButton back=new ImageButton(context);
        back.setContentDescription("返回");
        back.setBackgroundDrawable(ui.focus(ui.buttonColor()));
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int backPadding=ui.px(13);
        back.setPadding(backPadding,backPadding,backPadding,backPadding);
        back.setImageDrawable(tintedIcon("icon_back",22,ui.theme.text));
        back.setOnClickListener(v->onBack());
        header.addView(back,new LinearLayout.LayoutParams(ui.px(48),ui.px(48)));
        ui.gap(header,14);
        header.addView(ui.title("设置"),new LinearLayout.LayoutParams(0,ui.px(48),1));
        appendStatus(header);
        page.addView(header);

        LinearLayout body=ui.horizontal();body.setGravity(Gravity.TOP);
        body.setPadding(0,ui.px(26),0,0);
        LinearLayout sections=ui.vertical();
        body.addView(sections,new LinearLayout.LayoutParams(ui.px(256),-1));ui.gap(body,26);
        final FrameLayout pane=new FrameLayout(context);
        body.addView(pane,new LinearLayout.LayoutParams(0,-1,1));
        final List<Button> buttons=new ArrayList<>();
        for(String[] section:new String[][]{
                {"通用设置","general","icon_setting"},{"播放设置","playback","icon_video_play_count"},
                {"弹幕设置","danmaku","icon_video_danmaku"},{"关于应用","about","icon_about"},
                {"设备信息","device","ic_tv"}}){
            String key=section[1];Button button=ui.button(section[0]);
            button.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            button.setCompoundDrawables(tintedIcon(section[2],28,ui.theme.text),null,null,null);
            button.setCompoundDrawablePadding(ui.px(12));
            button.setSelected(key.equals(settingsSection));
            if(key.equals(settingsSection))contentEntry=button;
            LinearLayout.LayoutParams dimensions=new LinearLayout.LayoutParams(-1,ui.px(56));
            dimensions.bottomMargin=ui.px(10);
            sections.addView(button,dimensions);
            buttons.add(button);
            button.setOnClickListener(v->{
                settingsSection=key;contentEntry=button;
                for(Button candidate:buttons)candidate.setSelected(candidate==button);
                showSettingsSection(pane);
                button.requestFocusFromTouch();
            });
            button.setOnKeyListener((v,code,event)->{
                if(code!=KeyEvent.KEYCODE_DPAD_RIGHT||event.getAction()!=KeyEvent.ACTION_DOWN
                        ||settingsList.getChildCount()>0)return false;
                ListView pending=settingsList;
                pending.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
                    @Override public void onLayoutChange(View view,int left,int top,int right,int bottom,
                            int oldLeft,int oldTop,int oldRight,int oldBottom){
                        view.removeOnLayoutChangeListener(this);
                        view.post(()->{
                            if(settingsList==pending&&button.isFocused())root.moveFocus(View.FOCUS_RIGHT);
                        });
                    }
                });
                return true;
            });
        }
        page.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        showSettingsSection(pane);
        back.requestFocusFromTouch();
    }
    private void showSettingsSection(FrameLayout pane){
        pane.removeAllViews();
        settingsList=optionList(settingsOptions());
        pane.addView(settingsList,new FrameLayout.LayoutParams(-1,-1));
    }
    private List<Option> settingsOptions(){
        List<Option> rows=new ArrayList<>();
        if(settingsSection.equals("danmaku"))return danmakuOptions();
        if(settingsSection.equals("about")){
            rows.add(new Option("插件版本\nBL v"+BlDiagnostics.VERSION,null));
            rows.add(new Option("错误详情与诊断",this::showDiagnostics));
        }else if(settingsSection.equals("device")){
            android.util.DisplayMetrics display=context.getResources().getDisplayMetrics();
            rows.add(new Option("设备\n"+Build.MANUFACTURER+" "+Build.MODEL,null));
            rows.add(new Option("系统\nAndroid "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT,null));
            rows.add(new Option("屏幕\n"+display.widthPixels+" × "+display.heightPixels+" · "+display.densityDpi+" dpi",null));
            rows.add(new Option("架构\n"+Build.CPU_ABI,null));
        }else if(settingsSection.equals("playback")){
            rows.add(new Option("默认画质\n"+qualityName(api.prefs.getInt("quality",64)),()->{
                List<Option> values=new ArrayList<>();
                for(int quality:new int[]{16,32,64,80,120,BlStreams.QUALITY_8K})values.add(new Option(qualityName(quality),()->{api.prefs.edit().putInt("quality",quality).apply();render();}));
                options("默认画质",values);
            }));
            int audioTrack=api.prefs.getInt("audio_track",DEFAULT_AUDIO_TRACK);
            rows.add(new Option("默认音轨\n"+audioTrackLabel(audioTrack),()->{
                List<Option> choices=new ArrayList<>();
                for(int track:new int[]{DEFAULT_AUDIO_TRACK,30232,30216,BlStreams.AUDIO_ATMOS,BlStreams.AUDIO_HI_RES,BlStreams.AUDIO_DOLBY}){
                    choices.add(new Option(audioTrackLabel(track),()->{
                        api.prefs.edit().putInt("audio_track",track).apply();
                        render();
                    },track==audioTrack));
                }
                options("默认音轨",choices);
            }));
            rows.add(new Option("默认播放速度\n"+api.prefs.getFloat("play_speed",1)+"x",()->{
                List<Option> choices=new ArrayList<>();
                for(float speed:new float[]{.5f,.75f,1,1.25f,1.5f,2})choices.add(new Option(speed+"x",()->{api.prefs.edit().putFloat("play_speed",speed).apply();render();}));
                options("默认播放速度",choices);
            }));
            rows.add(new Option("播放完成后\n"+api.prefs.getString("play_order","顺序播放"),this::playOrder));
            rows.add(settingToggle("都播完后退出播放器","exit_after_play",false));
            rows.add(settingToggle("显示快进快退按钮","show_re_ff",true));
            String codec=api.prefs.getString("video_codec","AVC");
            rows.add(new Option("视频编码\n"+codec,()->{
                List<Option> choices=new ArrayList<>();
                for(String name:new String[]{"AVC","HEVC","自动"})choices.add(new Option(name,()->{api.prefs.edit().putString("video_codec",name).apply();render();}));
                options("视频编码",choices);
            }));
            rows.add(settingToggle("默认启用字幕","subtitle_default",false));
            rows.add(subtitleSizeOption());
            rows.add(settingToggle("显示视频调试信息","player_debug",false));
            rows.add(settingToggle("显示视频详情页","show_video_details",true));
            rows.add(settingToggle("显示底部常驻进度条","bottom_progress",true));
            rows.add(settingToggle("显示上下集切换按钮","show_next_previous",true));
            rows.add(settingToggle("显示弹幕开关按钮","show_dm_switch",true));
            rows.add(settingToggle("硬件解码","hardware",true));
            rows.add(settingToggle("自动播放下一分 P","autoplay",true));
            rows.add(settingToggle("DASH 音视频分离播放","prefer_dash",true));
            rows.add(settingToggle("使用系统播放器","systemPlayer",false));
        }else{
            String cacheSize=android.text.format.Formatter.formatFileSize(context,images.cachedBytes());
            rows.add(new Option("清除缓存\n"+cacheSize,()->confirm("图片缓存","确认清理图片缓存",()->{
                images.clearCache();render();
            })));
            int cacheLimit=api.prefs.getInt("image_cache_mb",0);
            rows.add(new Option("缓存限制\n"+cacheLimitLabel(cacheLimit),()->{
                List<Option> choices=new ArrayList<>();
                for(int limit:new int[]{0,2,4,6})choices.add(new Option(cacheLimitLabel(limit),()->{
                    api.prefs.edit().putInt("image_cache_mb",limit).apply();images.updateCacheLimit();render();
                }));
                options("缓存限制",choices);
            }));
            rows.add(new Option("检查更新\nBL v"+BlDiagnostics.VERSION,this::checkPluginUpdate));
            Map<String,String> destinations=new LinkedHashMap<>();
            for(Route target:homeSections.values())destinations.put(target.kind,target.title);
            for(String key:new String[]{"region","dynamic","live","search"})destinations.put(key,tabs.get(key).getText().toString());
            for(Route target:mineSections.values())destinations.put(target.kind,target.title);
            String target=destinations.get(api.prefs.getString("home_section","home"));
            rows.add(new Option("默认启动页面\n"+(target==null?"推荐":target),()->{
                List<Option> choices=new ArrayList<>();
                for(Map.Entry<String,String> entry:destinations.entrySet())choices.add(new Option(entry.getValue(),()->{api.prefs.edit().putString("home_section",entry.getKey()).apply();render();}));
                options("默认启动页面",choices);
            }));
            int quality=images.coverWidth();
            rows.add(new Option("图片质量\n"+imageQualityLabel(quality),()->{
                List<Option> choices=new ArrayList<>();
                for(int width:new int[]{256,384,640})choices.add(new Option(imageQualityLabel(width),()->{
                    api.prefs.edit().putInt("image_quality",width).apply();render();
                }));
                options("图片质量",choices);
            }));
            BlTheme theme=preferredTheme();
            rows.add(new Option("主题\n"+theme.label,()->themeOptions(theme)));
            rows.add(fullscreenSetting());
        }
        return rows;
    }
    private Option fullscreenSetting(){
        try{
            if(host instanceof ChannelPlugin.FullscreenHost){
                ChannelPlugin.FullscreenHost window=(ChannelPlugin.FullscreenHost)host;
                boolean enabled=window.isFullscreenEnabled();
                return new Option("以全屏模式运行\n"+(enabled?"开":"关"),()->{
                    window.setFullscreenEnabled(!enabled);
                    render();
                });
            }
        }catch(LinkageError ignored){
            // Already-installed hosts may provide only the original NativeHost interface.
        }
        return new Option("以全屏模式运行\n由 nTv 宿主管理",null);
    }
    private static String audioTrackLabel(int track){
        switch(track){
            case DEFAULT_AUDIO_TRACK:return "192Kbps";
            case 30232:return "132Kbps";
            case 30216:return "64Kbps";
            default:String premium=BlStreams.premiumAudioLabel(track);return premium.isEmpty()?"自动":premium;
        }
    }
    private void confirm(String title,String action,Runnable handler){options(title,Collections.singletonList(new Option(action,handler)));}
    private void checkPluginUpdate(){
        setStatus("正在查询发布版本…");
        submit(api::publishedBlVersion,generation,value->{
            int published=(Integer)value;
            String text=published>BlDiagnostics.VERSION?"有新版可用，请在 nTv 更新频道订阅":"当前版本无需更新";
            setStatus("");
            options("检查更新",Collections.singletonList(new Option(text+"\n当前 v"+BlDiagnostics.VERSION+" · 发布 v"+published,null)));
        });
    }
    private void themeOptions(BlTheme selected){
        List<Option> choices=new ArrayList<>();
        for(BlTheme theme:BlTheme.values())choices.add(new Option(theme.label,()->{
            api.prefs.edit().putString("theme",theme.name()).apply();applyTheme();render();
        },theme==selected,theme));
        Panel themes=new Panel("主题",choices,openingPanel);
        themes.selection=selected.ordinal();
        FrameLayout.LayoutParams bounds=ui.frame(640,620,Gravity.CENTER);
        if(root.getHeight()>0)bounds.height=Math.max(1,Math.min(bounds.height,root.getHeight()-ui.px(24)));
        showPanel(themes,bounds);
    }
    private String imageQualityLabel(int width){return width==256?"小尺寸":width==640?"大尺寸":"中尺寸";}
    private String cacheLimitLabel(int limit){return limit>0?limit+" MB":"自动";}
    private Option settingToggle(String title,String key,boolean fallback){
        boolean enabled=api.prefs.getBoolean(key,fallback);
        return new Option(title+"\n"+(enabled?"开":"关"),()->{api.prefs.edit().putBoolean(key,!enabled).apply();render();});
    }
    private void renderDetail(final LinearLayout page){
        if(route.data!=null){detailViews(page,route.data);return;}
        final Route target=route;setStatus("正在加载详情…");
        submit(()->api.detail(target.arg),generation,value->{
            target.data=(JSONObject)value;
            if(target.playFromDetail&&startVideo(target.data,0,0,false))return;
            detailViews(page,target.data);
        });
    }
    private void detailViews(final LinearLayout page,final JSONObject data){
        setStatus(BlUi.count(data.optJSONObject("stat")==null?0:data.optJSONObject("stat").optLong("view"))+" 播放");
        LinearLayout summary=ui.horizontal();summary.setPadding(0,ui.px(18),0,ui.px(14));ImageView cover=new ImageView(context);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);images.load(cover,data.optString("pic"),true);summary.addView(cover,new LinearLayout.LayoutParams(ui.px(300),ui.px(169)));ui.gap(summary,24);
        LinearLayout text=ui.vertical();TextView title=ui.title(data.optString("title"));title.setMaxLines(2);text.addView(title);JSONObject owner=data.optJSONObject("owner");TextView author=ui.text("UP · "+(owner==null?"":owner.optString("name")),18,ui.theme.accent);author.setPadding(0,ui.px(14),0,ui.px(12));text.addView(author);TextView description=ui.text(data.optString("desc"),16,ui.theme.muted);description.setMaxLines(3);description.setEllipsize(TextUtils.TruncateAt.END);text.addView(description);summary.addView(text,new LinearLayout.LayoutParams(0,-2,1));page.addView(summary);
        HorizontalScrollView actions=new HorizontalScrollView(context);actions.setHorizontalScrollBarEnabled(false);LinearLayout row=ui.horizontal();actions.addView(row);final JSONArray parts=BlApi.array(data,"pages");
        Button play=ui.button("播放");row.addView(play);play.setOnClickListener(new View.OnClickListener(){public void onClick(View v){startVideo(data,0,0);}});
        action(row,"选集 ("+parts.length()+")",new Runnable(){public void run(){episodeOptions(data,0);}});
        action(row,"评论",new Runnable(){public void run(){comments(data.optLong("aid"),1);}});
        action(row,"收藏",new Runnable(){public void run(){favorite(data);}});
        action(row,"稍后再看",new Runnable(){public void run(){mutation("/x/v2/history/toview/add",BlApi.map("aid",""+data.optLong("aid")));}});
        action(row,"点赞",new Runnable(){public void run(){mutation("/x/web-interface/archive/like",BlApi.map("aid",""+data.optLong("aid"),"like","1"));}});
        action(row,"投币",new Runnable(){public void run(){List<Option> choices=new ArrayList<>();for(final int n:new int[]{1,2})choices.add(new Option("确认投 "+n+" 枚硬币",new Runnable(){public void run(){mutation("/x/web-interface/coin/add",BlApi.map("aid",""+data.optLong("aid"),"multiply",""+n,"select_like","0"));}}));options("投币",choices);}});
        if(owner!=null){final JSONObject up=owner;action(row,"UP 主",new Runnable(){public void run(){navigate(new Route("uploads",up.optString("name"),up.optString("mid")),true);}});}page.addView(actions);
        TextView related=ui.text("相关推荐",20,ui.theme.text);related.setPadding(0,ui.px(16),0,0);page.addView(related);makeGrid(page);final int token=generation;
        submit(new Job(){public Object run()throws Exception{return api.related(data.optString("bvid"));}},token,new Result(){public void show(Object value){route.more=false;bind((JSONArray)value,false);}});
        play.requestFocusFromTouch();
    }
    private void action(LinearLayout row,String text,final Runnable run){ui.gap(row,8);Button b=ui.button(text);b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(16));b.setPadding(ui.px(10),ui.px(8),ui.px(10),ui.px(8));row.addView(b);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){run.run();}});}
    private void renderSeason(final LinearLayout page){
        final Route target=route;final int token=generation;submit(new Job(){public Object run()throws Exception{return api.season(Long.parseLong(target.arg));}},token,new Result(){public void show(Object value)throws Exception{JSONObject season=(JSONObject)value;target.data=season;List<Option> values=new ArrayList<>();JSONArray episodes=BlApi.array(season,"episodes");for(int i=0;i<episodes.length();i++){final JSONObject ep=episodes.getJSONObject(i);final JSONObject data=new JSONObject().put("bvid",ep.optString("bvid")).put("aid",ep.optLong("aid")).put("title",season.optString("title")+" · "+ep.optString("long_title",ep.optString("title"))).put("pic",ep.optString("cover")).put("pages",new JSONArray().put(new JSONObject().put("cid",ep.optLong("cid")).put("part",ep.optString("long_title",ep.optString("title")))));values.add(new Option(ep.optString("title")+"  "+ep.optString("long_title"),new Runnable(){public void run(){startVideo(data,0,ep.optLong("id"));}}));}page.addView(optionList(values),new LinearLayout.LayoutParams(-1,0,1));setStatus(episodes.length()+" 集");}});
    }
    private void startVideo(JSONObject data,int index,long episodeId){startVideo(data,index,episodeId,true);}
    private boolean startVideo(JSONObject data,int index,long episodeId,boolean push){
        JSONObject part=BlApi.array(data,"pages").optJSONObject(index);
        long contentId=part==null?data.optLong("cid"):part.optLong("cid");
        if(contentId<=0){message("视频分 P 信息缺失",true);return false;}
        Route next=new Route("player",data.optString("title"),data.optString("bvid"));
        next.data=data;next.playbackCid=contentId;next.playbackEpisode=episodeId;next.playbackPart=index;
        navigate(next,push);return true;
    }
    private void renderPlayer(){
        liveLegacy=false;progressiveFallback=false;playData=null;selectedStream=null;
        video=route.data;cid=route.playbackCid;episode=route.playbackEpisode;partIndex=route.playbackPart;final boolean live=video.optBoolean("bl_live");if(live)cid=Long.parseLong(route.arg);
        BlUi playerUi=new BlUi(context);
        if(ui.theme==BlTheme.CLASSIC)playerUi.theme=ui.theme;
        player=new BlPlayer(playerUi,proxy,api,images,route.title,live,BlApi.array(video,"pages").length(),api.prefs.getBoolean("hardware",true),api.prefs.getBoolean("systemPlayer",false),new BlPlayer.Actions(){
            public void settings(){playerSettings();}public void episodes(){episodeOptions(video,episode);}public void comments(){BlPlugin.this.comments(video.optLong("aid"),1);}public void subtitle(){subtitles();}
            public void more(){playerMore();}public void diagnostics(){showDiagnostics();}public void related(){playerRelated();}public void up(){playerUp();}public void step(int delta){stepPart(delta);}public void order(){playOrder();}public void exit(){leavePlayer();}public void position(long at){if(dmFeed!=null)dmFeed.update(at);}
            public void complete(){String order=api.prefs.getString("play_order","顺序播放");if(!live&&order.equals("单集循环")){loadPlayback(0);return;}if(!live&&api.prefs.getBoolean("autoplay",true)&&(!order.equals("播完停止"))){if(stepPart(order.equals("倒序播放")?-1:1))return;}if(!live&&api.prefs.getBoolean("exit_after_play",false)){leavePlayer();return;}message("播放结束",false);}
            public void failure(String error,BlStreams.Track track){
                if(player==null)return;
                int request=playingGeneration;
                long at=player.position();
                main.post(()->{if(player!=null&&request==playbackGeneration)recoverPlayback(live,at,error,track);});
            }
        });
        player.title(route.title,playbackDetails(live));
        content.addView(player,new FrameLayout.LayoutParams(-1,-1));
        loadPlayback(live?0:api.prefs.getLong("position:"+video.optString("bvid")+":"+cid,0));
        if(!live){api.remember(video,cid);resetPage(mineSections.get("history"));}else{liveDanmaku=new BlLiveDanmaku(api,player.danmaku,cid);if(!active)liveDanmaku.active(false);}
    }
    private void loadPlayback(final long at){
        route.playbackCid=cid;route.playbackEpisode=episode;route.playbackPart=partIndex;
        int request=++playbackGeneration;
        boolean live=video.optBoolean("bl_live"),legacy=liveLegacy;
        String bvid=video.optString("bvid"),codec=api.prefs.getString("video_codec","AVC");
        long selectedCid=cid,selectedEpisode=episode;
        int quality=api.prefs.getInt("quality",64),audio=api.prefs.getInt("audio_track",DEFAULT_AUDIO_TRACK);
        int liveQuality=api.prefs.getInt("live_quality",0);
        boolean system=Build.VERSION.SDK_INT<16||api.prefs.getBoolean("systemPlayer",false);
        boolean dash=!progressiveFallback&&!system&&api.prefs.getBoolean("prefer_dash",true);
        if(dash)rejectedDashTracks.clear();
        submit(()->{
            if(live){
                JSONObject data=legacy?api.liveLegacy(selectedCid,liveQuality):api.livePlay(selectedCid,liveQuality,system);
                return new Playback(data,BlApi.array(data,"durl"));
            }
            if(dash){
                try{
                    JSONObject data=selectedEpisode>0?api.episodeDash(selectedEpisode,selectedCid):api.playDash(bvid,selectedCid);
                    JSONArray streams=BlStreams.select(data,quality,codec,audio);
                    if(streams.length()>0)return new Playback(data,streams);
                    throw new java.io.IOException("接口未返回可用的 DASH 音视频");
                }catch(Exception error){
                    api.diagnostics.record("DASH 接口回退 MP4/FLV",error,"BV="+bvid+"; cid="+selectedCid+"; 请求画质="+quality);
                }
            }
            int fallbackQuality=quality>=BlStreams.QUALITY_HDR?BlStreams.QUALITY_1080P:quality;
            JSONObject data=selectedEpisode>0?api.episodePlay(selectedEpisode,selectedCid,fallbackQuality):api.play(bvid,selectedCid,fallbackQuality);
            return new Playback(data,BlApi.array(data,"durl"));
        },generation,value->{
            if(request!=playbackGeneration||selectedCid!=cid||player==null)return;
            Playback playback=(Playback)value;
            if(playback.streams.length()==0)throw new java.io.IOException("接口未返回可播放媒体，请登录或选择较低画质");
            playData=playback.data;
            playSelection(playback.streams,at,live);
            if(!live&&!progressiveFallback){
                if(quality==BlStreams.QUALITY_8K&&currentQuality()!=quality)message("当前视频未提供可用 8K 流，已选择 "+qualityName(currentQuality()),false);
                else if(!BlStreams.premiumAudioLabel(audio).isEmpty()&&selectedStream.optInt("bl_audio_id")!=audio)message("当前视频未提供所选音轨，已使用可用音轨",false);
            }
        });
        if(!live&&player!=null&&dmCid!=cid){if(dmFeed!=null)dmFeed.close();dmCid=cid;dmFeed=new BlDanmakuFeed(api,player.danmaku);dmFeed.reset(cid,video.optLong("aid"));player.setSubtitle(new JSONArray());if(api.prefs.getBoolean("subtitle_default",false))defaultSubtitle();api.remember(video,cid);resetPage(mineSections.get("history"));}
    }

    private void playSelection(JSONArray streams,long at,boolean live){
        selectedStream=streams.optJSONObject(0);
        playingGeneration=playbackGeneration;
        player.title(route.title,playbackDetails(live));
        player.play(streams,at);
    }

    private void recoverPlayback(boolean live,long at,String error,BlStreams.Track track){
        if(live&&!liveLegacy){
            liveLegacy=true;player.recovering("正在切换兼容直播线路…");loadPlayback(0);
            return;
        }
        if(!live&&!progressiveFallback&&playData!=null&&selectedStream!=null&&selectedStream.has("bl_audio")){
            try{
                JSONArray compatible=rejectedDashTracks.size()<2
                        ?BlStreams.compatibleDash(playData,selectedStream,track,rejectedDashTracks):new JSONArray();
                if(compatible.length()>0){
                    JSONObject replacement=compatible.getJSONObject(0);
                    String choice=selectedStream.optInt("bl_audio_id")!=replacement.optInt("bl_audio_id")
                            ?replacement.optString("bl_audio_label"):qualityName(replacement.optInt("bl_quality"));
                    api.diagnostics.record("DASH 轨道回退",new java.io.IOException(error),"使用 "+choice);
                    playbackGeneration++;
                    playSelection(compatible,at,false);
                    return;
                }
            }catch(JSONException invalid){api.diagnostics.record("兼容 DASH 选择失败",invalid,"");}
            progressiveFallback=true;
            api.diagnostics.record("DASH 媒体回退 MP4/FLV",new java.io.IOException(error),"重新请求兼容媒体，保留原始错误记录");
            player.recovering("正在切换兼容点播线路…");
            loadPlayback(at);
            return;
        }
        player.terminalFailure(error);
        message(error,true);
    }

    private int currentQuality(){
        int quality=playData==null?api.prefs.getInt("quality",64):playData.optInt("quality",64);
        return selectedStream==null?quality:selectedStream.optInt("bl_quality",quality);
    }

    private void reloadVodSelection(){
        progressiveFallback=false;
        if(player!=null)loadPlayback(player.position());
    }
    private String playbackDetails(boolean live){
        if(live)return playData==null?"直播":"直播 · "+BlLiveStreams.quality(playData,playData.optInt("quality"));

        List<String> labels=new ArrayList<>();
        JSONArray parts=BlApi.array(video,"pages");
        JSONObject part=parts.optJSONObject(partIndex);
        if(parts.length()>1&&part!=null)labels.add(("P"+(partIndex+1)+" "+part.optString("part")).trim());
        JSONObject owner=video.optJSONObject("owner");
        if(owner!=null&&!owner.optString("name").isEmpty())labels.add(owner.optString("name"));
        JSONObject stat=video.optJSONObject("stat");
        if(stat!=null&&stat.has("view"))labels.add(BlUi.count(stat.optLong("view"))+"次观看");
        long published=video.optLong("pubdate");
        if(published>0)labels.add("发布于 "+android.text.format.DateFormat.format("M月d日 HH:mm",published*1000L));
        return TextUtils.join(" · ",labels);
    }

    private void leavePlayer(){saveRoute();closePlayer();hideModal();route=history.isEmpty()?new Route("home","推荐",""):history.pop();render();}
    private boolean stepPart(int delta){if(video==null||video.optBoolean("bl_live"))return false;JSONArray parts=BlApi.array(video,"pages");int next=partIndex+delta;String order=api.prefs.getString("play_order","顺序播放");if(order.equals("随机播放")&&parts.length()>1)next=(partIndex+1+new Random().nextInt(parts.length()-1))%parts.length();if(next<0||next>=parts.length()){message(delta>0?"已经是最后一集":"已经是第一集",false);return false;}savePlaybackPosition();partIndex=next;cid=parts.optJSONObject(next).optLong("cid");progressiveFallback=false;loadPlayback(0);return true;}
    private void playOrder(){
        if(player!=null&&video.optBoolean("bl_live")){message("直播不支持播放顺序",false);return;}
        String current=api.prefs.getString("play_order","顺序播放");
        List<Option> rows=new ArrayList<>();
        for(String value:new String[]{"顺序播放","倒序播放","单集循环","随机播放","播完停止"}){
            rows.add(new Option(value,()->{
                api.prefs.edit().putString("play_order",value).apply();
                if(player!=null)message(value,false);else render();
            },value.equals(current)));
        }
        options(player==null?"播放完成后":"播放顺序",rows);
    }
    private Option subtitleSizeOption(){
        return new Option("字幕字号\n"+api.prefs.getInt("subtitle_size",27),
                ()->integerChoices("字幕字号","subtitle_size",new int[]{20,24,27,32,36}));
    }
    private void playerSettings(){
        boolean live=video.optBoolean("bl_live");
        List<Option> rows=new ArrayList<>();
        String quality=live?"直播画质\n"+BlLiveStreams.quality(playData,playData==null?0:playData.optInt("quality"))
                :"画质\n"+qualityName(currentQuality());
        rows.add(new Option(quality,this::qualityOptions));
        rows.add(new Option("播放速度\n"+api.prefs.getFloat("play_speed",1)+"x",()->{
            List<Option> choices=new ArrayList<>();
            for(float speed:new float[]{.25f,.5f,.75f,1,1.25f,1.5f,2})choices.add(new Option(speed+"x",()->{
                if(player!=null&&!player.speed(speed))message("当前系统播放器仅支持 1 倍速",true);
            }));
            options("播放速度",choices);
        }));
        if(!live)rows.add(new Option("字幕",this::subtitles));
        rows.add(new Option("视频编码\n"+api.prefs.getString("video_codec","AVC"),()->{
            List<Option> choices=new ArrayList<>();
            for(String codec:new String[]{"AVC","HEVC","自动"})choices.add(new Option(codec,()->{
                api.prefs.edit().putString("video_codec",codec).apply();
                if(live){if(player!=null)loadPlayback(player.position());}
                else reloadVodSelection();
            }));
            options("视频编码",choices);
        }));
        String audioLabel=selectedStream==null?"":selectedStream.optString("bl_audio_label");
        rows.add(new Option(audioLabel.isEmpty()?"音轨":"音轨\n"+audioLabel,this::audioTracks));
        rows.add(new Option("画面缩放\n"+api.prefs.getString("screen_ratio","自适应"),()->{
            List<Option> choices=new ArrayList<>();
            for(String mode:new String[]{"自适应","宽度适应","高度适应","拉伸","放大"})choices.add(new Option(mode,()->{
                api.prefs.edit().putString("screen_ratio",mode).apply();
                if(player!=null)player.refreshSettings();
            }));
            options("画面缩放",choices);
        }));
        rows.add(new Option("弹幕设置\n"+(dmFeed!=null?dmFeed.status:liveDanmaku!=null?liveDanmaku.status:"正在加载"),this::danmakuSettings));
        rows.add(new Option("播放线路",new Runnable(){public void run(){List<Option> choices=new ArrayList<>();List<String> urls=player==null?Collections.<String>emptyList():player.lines();for(int i=0;i<urls.size();i++){final int index=i;choices.add(new Option(i==0?"主线路":"备用线路 "+i,new Runnable(){public void run(){if(player!=null)player.line(index);}}));}options("播放线路",choices);}}));
        if(!live){
            rows.add(subtitleSizeOption());
            rows.add(new Option("底部常驻进度条\n"+(api.prefs.getBoolean("bottom_progress",true)?"开":"关"),new Runnable(){public void run(){api.prefs.edit().putBoolean("bottom_progress",!api.prefs.getBoolean("bottom_progress",true)).apply();if(player!=null)player.refreshSettings();}}));
        }
        rows.add(new Option("显示视频调试信息\n"+(api.prefs.getBoolean("player_debug",false)?"开":"关"),new Runnable(){public void run(){api.prefs.edit().putBoolean("player_debug",!api.prefs.getBoolean("player_debug",false)).apply();}}));
        rows.add(new Option("控制栏与播放偏好",this::playerPreferences));
        rows.add(new Option("错误详情",this::showDiagnostics));
        options(live?"直播设置":"设置",rows);
    }
    private void playerPreferences(){List<Option> rows=new ArrayList<>();for(final String[] item:new String[][]{{"show_next_previous","显示上下集切换按钮"},{"show_re_ff","显示快进快退按钮"},{"show_dm_switch","显示弹幕开关按钮"},{"subtitle_default","默认启用字幕"},{"exit_after_play","都播完后退出播放器"}}){final boolean fallback=item[0].startsWith("show_");rows.add(new Option(item[1]+"\n"+(api.prefs.getBoolean(item[0],fallback)?"开":"关"),new Runnable(){public void run(){boolean value=!api.prefs.getBoolean(item[0],fallback);api.prefs.edit().putBoolean(item[0],value).apply();if(player!=null){player.refreshSettings();if(item[0].equals("subtitle_default")){if(value)defaultSubtitle();else player.setSubtitle(new JSONArray());}}}}));}options("控制栏与播放偏好",rows);}
    private void defaultSubtitle(){if(video==null||video.optBoolean("bl_live"))return;final long selectedCid=cid;final String bvid=video.optString("bvid");final int token=generation;submit(new Job(){public Object run()throws Exception{JSONArray list=api.subtitles(bvid,selectedCid);if(list.length()==0)return new JSONArray();JSONObject chosen=list.optJSONObject(0);for(int i=0;i<list.length();i++){JSONObject s=list.optJSONObject(i);if(s!=null&&s.optString("lan").startsWith("zh")){chosen=s;break;}}if(chosen==null)return new JSONArray();String url=chosen.optString("subtitle_url");if(url.startsWith("//"))url="https:"+url;return new JSONObject(new String(api.bytes(url,2*1024*1024),"UTF-8")).optJSONArray("body");}},token,new Result(){public void show(Object value){if(player!=null&&selectedCid==cid&&api.prefs.getBoolean("subtitle_default",false))player.setSubtitle(value instanceof JSONArray?(JSONArray)value:new JSONArray());}},false);}
    private void audioTracks(){
        List<Option> rows=new ArrayList<>();
        int selected=selectedStream==null?0:selectedStream.optInt("bl_audio_id");
        for(BlStreams.AudioTrack track:BlStreams.audioTracks(playData)){
            rows.add(new Option(track.label(),()->{
                api.prefs.edit().putInt("audio_track",track.id).apply();
                reloadVodSelection();
            },track.id==selected));
        }
        if(rows.isEmpty())rows.add(new Option("当前媒体仅有一条音轨",null));
        options("音轨",rows);
    }
    private void danmakuSettings(){options("弹幕设置",danmakuOptions());}
    private List<Option> danmakuOptions(){List<Option> rows=new ArrayList<>();
        rows.add(new Option("弹幕开关\n"+(api.prefs.getBoolean("dm_enabled",true)?"开":"关"),()->{
            if(player!=null)player.danmaku.enabled(!player.danmaku.enabled());
            else{api.prefs.edit().putBoolean("dm_enabled",!api.prefs.getBoolean("dm_enabled",true)).apply();render();}
        }));
        rows.add(new Option("弹幕字体大小\n"+Math.round(api.prefs.getFloat("dm_size",1)*100)+"%",new Runnable(){public void run(){floatChoices("弹幕字体大小","dm_size",new float[]{.7f,.85f,1,1.2f,1.5f});}}));
        rows.add(new Option("弹幕透明度\n"+Math.round(api.prefs.getFloat("dm_alpha",.9f)*100)+"%",new Runnable(){public void run(){floatChoices("弹幕透明度","dm_alpha",new float[]{.25f,.5f,.75f,.9f,1});}}));
        rows.add(new Option("弹幕速度\n"+api.prefs.getFloat("dm_speed",1)+"x",new Runnable(){public void run(){floatChoices("弹幕速度","dm_speed",new float[]{.5f,.75f,1,1.25f,1.5f,2});}}));
        rows.add(new Option("弹幕占屏比\n"+Math.round(api.prefs.getFloat("dm_area",.5f)*100)+"%",new Runnable(){public void run(){floatChoices("弹幕占屏比","dm_area",new float[]{.25f,.5f,.75f,1});}}));
        rows.add(new Option("弹幕密度\n"+api.prefs.getInt("dm_density",24),new Runnable(){public void run(){integerChoices("弹幕密度","dm_density",new int[]{8,16,24,32,48});}}));
        rows.add(new Option("弹幕智能屏蔽等级\n"+api.prefs.getInt("dm_weight",0),new Runnable(){public void run(){integerChoices("弹幕智能屏蔽等级","dm_weight",new int[]{0,2,4,6,8});}}));
        for(final String key:new String[]{"dm_top","dm_bottom"})rows.add(new Option((key.equals("dm_top")?"允许顶部悬停弹幕":"允许底部悬停弹幕")+"\n"+(api.prefs.getBoolean(key,true)?"开":"关"),new Runnable(){public void run(){api.prefs.edit().putBoolean(key,!api.prefs.getBoolean(key,true)).apply();refreshPreferenceViews();}}));
        rows.add(new Option("关键词屏蔽\n"+api.prefs.getString("dm_block","未设置"),this::editKeywords));return rows;
    }
    private void refreshPreferenceViews(){if(player!=null)player.refreshSettings();else if(route.kind.equals("settings"))render();}
    private void floatChoices(String title,final String key,float[] values){List<Option> rows=new ArrayList<>();for(final float value:values)rows.add(new Option(key.equals("dm_speed")?value+"x":Math.round(value*100)+"%",new Runnable(){public void run(){api.prefs.edit().putFloat(key,value).apply();refreshPreferenceViews();}}));options(title,rows);}
    private void integerChoices(String title,final String key,int[] values){List<Option> rows=new ArrayList<>();for(final int value:values)rows.add(new Option(""+value,new Runnable(){public void run(){api.prefs.edit().putInt(key,value).apply();refreshPreferenceViews();}}));options(title,rows);}
    private void editKeywords(){hideModal();modal=new FrameLayout(context);modal.setBackgroundColor(0xbb000000);LinearLayout card=ui.vertical();card.setPadding(ui.px(24),ui.px(20),ui.px(24),ui.px(20));card.setBackgroundDrawable(ui.fill(ui.theme.surface,12));card.addView(ui.title("关键词屏蔽"));final EditText edit=new EditText(context);edit.setTextColor(ui.theme.text);edit.setHintTextColor(ui.theme.muted);edit.setHint("多个关键词用逗号分隔");edit.setText(api.prefs.getString("dm_block",""));card.addView(edit,new LinearLayout.LayoutParams(-1,ui.px(130)));Button save=ui.button("保存");card.addView(save);save.setOnClickListener(new View.OnClickListener(){public void onClick(View v){api.prefs.edit().putString("dm_block",edit.getText().toString()).apply();hideModal();refreshPreferenceViews();}});modal.addView(card,ui.frame(640,-2,Gravity.CENTER));root.addView(modal,new FrameLayout.LayoutParams(-1,-1));edit.requestFocus();}
    private void playerMore(){List<Option> rows=new ArrayList<>();if(video.optBoolean("bl_live")){rows.add(new Option("直播信息\n"+route.title,new Runnable(){public void run(){}}));rows.add(new Option("直播画质",new Runnable(){public void run(){qualityOptions();}}));rows.add(new Option("弹幕设置",new Runnable(){public void run(){danmakuSettings();}}));rows.add(new Option("重新加载直播",new Runnable(){public void run(){loadPlayback(0);}}));options("直播",rows);return;}rows.add(new Option("视频详情\n"+video.optString("desc"),new Runnable(){public void run(){List<Option> detail=new ArrayList<>();detail.add(new Option(video.optString("desc","暂无简介"),new Runnable(){public void run(){}}));options("视频详情",detail);}}));
        rows.add(new Option("评论",new Runnable(){public void run(){comments(video.optLong("aid"),1);}}));rows.add(new Option("点赞",new Runnable(){public void run(){mutation("/x/web-interface/archive/like",BlApi.map("aid",""+video.optLong("aid"),"like","1"));}}));
        rows.add(new Option("投币",new Runnable(){public void run(){List<Option> choices=new ArrayList<>();for(final int n:new int[]{1,2})choices.add(new Option("确认投 "+n+" 枚硬币",new Runnable(){public void run(){mutation("/x/web-interface/coin/add",BlApi.map("aid",""+video.optLong("aid"),"multiply",""+n,"select_like","0"));}}));options("投币",choices);}}));
        rows.add(new Option("收藏",new Runnable(){public void run(){favorite(video);}}));rows.add(new Option("稍后再看",new Runnable(){public void run(){mutation("/x/v2/history/toview/add",BlApi.map("aid",""+video.optLong("aid")));}}));
        rows.add(new Option("一键三连",new Runnable(){public void run(){List<Option> choices=new ArrayList<>();choices.add(new Option("确认点赞、投币并收藏",new Runnable(){public void run(){mutation("/x/web-interface/archive/like/triple",BlApi.map("aid",""+video.optLong("aid")));}}));options("一键三连",choices);}}));options("更多",rows);
    }
    private void playerRelated(){
        if(player==null||video.optBoolean("bl_live"))return;
        String bvid=video.optString("bvid");
        int panelToken=modalGeneration;
        submit(()->api.related(bvid),generation,value->{
            if(panelToken==modalGeneration)showRelatedCards((JSONArray)value);
        });
    }
    private void showRelatedCards(JSONArray related){
        hideModal();
        modalFocus=root.findFocus();
        modal=new FrameLayout(context);
        modal.setBackgroundColor(0x66000000);
        modal.setOnClickListener(v->backPanel());

        LinearLayout shelf=ui.vertical();
        shelf.setPadding(ui.px(20),ui.px(14),ui.px(20),ui.px(12));
        shelf.setBackgroundDrawable(ui.fill(ui.theme.surface,12));
        shelf.setClickable(true);
        LinearLayout header=ui.horizontal();
        header.addView(ui.title("相关视频"),new LinearLayout.LayoutParams(0,ui.px(46),1));
        ImageButton close=new ImageButton(context);
        close.setContentDescription("关闭相关视频");
        close.setBackgroundDrawable(ui.focus(ui.buttonColor()));
        close.setImageDrawable(tintedIcon("icon_close",30,ui.theme.text));
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int closePadding=ui.px(6);
        close.setPadding(closePadding,closePadding,closePadding,closePadding);
        close.setOnClickListener(v->backPanel());
        header.addView(close,new LinearLayout.LayoutParams(ui.px(46),ui.px(46)));
        shelf.addView(header);

        LinearLayout cards=ui.horizontal();
        HorizontalScrollView strip=new HorizontalScrollView(context);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(cards);
        View first=null;
        for(int i=0;i<Math.min(related.length(),24);i++){
            JSONObject row=related.optJSONObject(i);
            if(row==null)continue;
            BlItem item=new BlItem(row,"video");
            if(item.title.isEmpty()||item.id.isEmpty()||item.id.equals("0"))continue;
            BlCard card=new BlCard(ui,images);
            card.bind(item);
            card.setFocusable(true);
            card.setOnClickListener(v->openItem(item));
            card.setOnKeyListener((v,key,event)->{
                if(key!=KeyEvent.KEYCODE_DPAD_UP)return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN)close.requestFocusFromTouch();
                return true;
            });
            cards.addView(card,new LinearLayout.LayoutParams(ui.px(274),-2));
            if(first==null)first=card;
        }
        if(first==null)shelf.addView(ui.text("暂无相关视频",18,ui.theme.muted));
        else shelf.addView(strip);
        modal.addView(shelf,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        root.addView(modal,new FrameLayout.LayoutParams(-1,-1));
        (first==null?close:first).requestFocusFromTouch();
    }
    private void playerUp(){final JSONObject owner=video==null?null:video.optJSONObject("owner");if(owner==null){message("当前内容没有 UP 主信息",false);return;}List<Option> rows=new ArrayList<>();rows.add(new Option("查看投稿\n"+owner.optString("name"),new Runnable(){public void run(){navigate(new Route("uploads",owner.optString("name"),owner.optString("mid")),true);}}));rows.add(new Option("关注 UP 主",new Runnable(){public void run(){List<Option> confirm=new ArrayList<>();confirm.add(new Option("确认关注 "+owner.optString("name"),new Runnable(){public void run(){mutation("/x/relation/modify",BlApi.map("fid",owner.optString("mid"),"act","1","re_src","11"));}}));options("关注",confirm);}}));rows.add(new Option("取消关注",new Runnable(){public void run(){List<Option> confirm=new ArrayList<>();confirm.add(new Option("确认取消关注 "+owner.optString("name"),new Runnable(){public void run(){mutation("/x/relation/modify",BlApi.map("fid",owner.optString("mid"),"act","2","re_src","11"));}}));options("取消关注",confirm);}}));options(owner.optString("name"),rows);}
    private void qualityOptions(){
        boolean live=video!=null&&video.optBoolean("bl_live");
        List<Integer> choices=BlStreams.qualities(playData);
        if(choices.isEmpty()){
            choices=new ArrayList<>();
            JSONArray accepted=BlApi.array(playData,"accept_quality");
            for(int i=0;i<accepted.length();i++){
                int quality=accepted.optInt(i);
                if(quality>0&&(live||quality<BlStreams.QUALITY_HDR)&&!choices.contains(quality))choices.add(quality);
            }
            if(choices.isEmpty()){
                if(live){message("接口未返回其他直播画质",false);return;}
                choices=Arrays.asList(16,32,64,80);
            }
        }
        int selected=currentQuality();
        List<Option> values=new ArrayList<>();
        for(int quality:choices){
            values.add(new Option(live?BlLiveStreams.quality(playData,quality):qualityName(quality),()->{
                api.prefs.edit().putInt(live?"live_quality":"quality",quality).apply();
                if(live){liveLegacy=false;loadPlayback(0);}
                else reloadVodSelection();
            },quality==selected));
        }
        options(live?"直播画质":"画质（可用画质由账号和接口决定）",values);
    }
    private static String qualityName(int q){switch(q){case 16:return "360P";case 32:return "480P";case 64:return "720P";case 74:return "720P 60帧";case BlStreams.QUALITY_1080P:return "1080P";case 112:return "1080P+";case 116:return "1080P 60帧";case 120:return "4K";case BlStreams.QUALITY_HDR:return "HDR";case 126:return "杜比视界";case BlStreams.QUALITY_8K:return "8K";default:return "画质 "+q;}}
    private void episodeOptions(JSONObject data,long ep){
        List<Option> values=new ArrayList<>();
        JSONArray parts=BlApi.array(data,"pages");
        int selected=0;
        for(int index=0;index<parts.length();index++){
            JSONObject part=parts.optJSONObject(index);
            if(part==null)continue;
            int next=index;
            boolean current=player!=null&&index==partIndex;
            if(current)selected=values.size();
            values.add(new Option("P"+(index+1)+" · "+part.optString("part"),()->{
                if(player==null){startVideo(data,next,ep);return;}
                savePlaybackPosition();
                partIndex=next;
                cid=part.optLong("cid");
                loadPlayback(0);
            },current));
        }
        Panel picker=new Panel("选集",values,openingPanel);
        picker.selection=selected;
        showPanel(picker);
    }
    private void subtitles(){
        if(video==null||player==null||video.optBoolean("bl_live")){message("当前内容没有字幕",false);return;}
        final int token=generation;final String bvid=video.optString("bvid");final long atCid=cid;final BlPlayer target=player;final Panel parent=openingPanel;
        submit(new Job(){public Object run()throws Exception{return api.subtitles(bvid,atCid);}},token,new Result(){public void show(Object value)throws Exception{
            if(player!=target||atCid!=cid)return;JSONArray list=(JSONArray)value;List<Option> choices=new ArrayList<>();choices.add(new Option("关闭字幕",new Runnable(){public void run(){if(player==target&&atCid==cid)player.setSubtitle(new JSONArray());}}));
            for(int i=0;i<list.length();i++){final JSONObject s=list.getJSONObject(i);choices.add(new Option(s.optString("lan_doc",s.optString("lan")),new Runnable(){public void run(){if(player!=target||atCid!=cid)return;String u=s.optString("subtitle_url");final String url=u.startsWith("//")?"https:"+u:u;submit(new Job(){public Object run()throws Exception{return new JSONObject(new String(api.bytes(url,2*1024*1024),"UTF-8")).optJSONArray("body");}},token,new Result(){public void show(Object value){if(player==target&&atCid==cid)player.setSubtitle(value instanceof JSONArray?(JSONArray)value:new JSONArray());}});}}));}
            openingPanel=parent;try{options("字幕",choices);}finally{openingPanel=null;}
        }});
    }
    private void comments(final long aid,final int page){if(aid<=0){message("当前内容无视频评论",false);return;}final int token=generation;submit(new Job(){public Object run()throws Exception{return api.comments(aid,page);}},token,new Result(){public void show(Object value)throws Exception{JSONArray replies=(JSONArray)value;List<Option> values=new ArrayList<>();for(int i=0;i<replies.length();i++){JSONObject reply=replies.getJSONObject(i),member=reply.optJSONObject("member"),body=reply.optJSONObject("content");values.add(new Option((member==null?"":member.optString("uname"))+" · "+reply.optLong("like")+" 赞\n"+(body==null?"":body.optString("message")),new Runnable(){public void run(){}}));}if(replies.length()>=20)values.add(new Option("下一页",new Runnable(){public void run(){comments(aid,page+1);}}));options("热门评论 · 第 "+page+" 页",values);}});}
    private void favorite(final JSONObject data){final int token=generation;submit(new Job(){public Object run()throws Exception{return api.favoriteFolders();}},token,new Result(){public void show(Object value)throws Exception{JSONArray folders=(JSONArray)value;List<Option> values=new ArrayList<>();for(int i=0;i<folders.length();i++){final JSONObject f=folders.getJSONObject(i);values.add(new Option("收藏到 · "+f.optString("title"),new Runnable(){public void run(){mutation("/x/v3/fav/resource/deal",BlApi.map("rid",""+data.optLong("aid"),"type","2","add_media_ids",f.optString("id")));}}));}options("选择收藏夹",values);}});}
    private void mutation(final String path,final Map<String,String> data){final int token=generation;submit(new Job(){public Object run()throws Exception{List<String> pairs=new ArrayList<>();for(Map.Entry<String,String> p:data.entrySet()){pairs.add(p.getKey());pairs.add(p.getValue());}return api.post(path,pairs.toArray(new String[0]));}},token,new Result(){public void show(Object value){message("操作成功",false);}});}
    private void renderLogin(final LinearLayout page){
        final ImageView qr=new ImageView(context);qr.setBackgroundColor(0xffffffff);LinearLayout.LayoutParams image=new LinearLayout.LayoutParams(ui.px(250),ui.px(250));image.gravity=Gravity.CENTER_HORIZONTAL;image.topMargin=ui.px(20);page.addView(qr,image);final TextView hint=ui.text("正在获取二维码…",20,ui.theme.text);hint.setGravity(Gravity.CENTER);hint.setPadding(0,ui.px(16),0,ui.px(16));page.addView(hint);
        final int token=generation;submit(new Job(){public Object run()throws Exception{return api.qr();}},token,new Result(){public void show(Object value)throws Exception{JSONObject q=(JSONObject)value;String address=q.getString("url"),key=q.getString("qrcode_key");BitMatrix matrix=new QRCodeWriter().encode(address,BarcodeFormat.QR_CODE,320,320);int[] pixels=new int[320*320];for(int y=0;y<320;y++)for(int x=0;x<320;x++)pixels[y*320+x]=matrix.get(x,y)?0xff000000:0xffffffff;Bitmap b=Bitmap.createBitmap(pixels,320,320,Bitmap.Config.ARGB_8888);qr.setImageBitmap(b);hint.setText("使用哔哩哔哩手机客户端扫码并确认登录");poll(key,hint,token,0);}});
    }
    private void poll(final String key,final TextView hint,final int token,final int count){if(closed||generation!=token)return;if(count>=90){hint.setText("二维码已过期，请刷新");return;}main.postDelayed(new Runnable(){public void run(){if(closed||!active||generation!=token)return;submit(new Job(){public Object run()throws Exception{return api.poll(key);}},token,new Result(){public void show(Object value)throws Exception{JSONObject d=(JSONObject)value;int code=d.optInt("code");if(code==0&&api.signedIn()){hint.setText("登录成功");Route destination=history.isEmpty()?mineSections.get(mineSection):history.pop();navigate(destination,false);}else if(code==86038)hint.setText("二维码已过期，请刷新");else{hint.setText(code==86090?"已扫码，请在手机上确认":"等待扫码…");poll(key,hint,token,count+1);}}});}},2000);}
    private Drawable tintedIcon(String asset,int size,int color){
        Drawable icon=images.iconDrawable(context.getResources(),asset,ui.px(size));
        if(icon!=null)icon.setColorFilter(color,PorterDuff.Mode.SRC_IN);
        return icon;
    }
    private ListView optionList(final List<Option> values){
        ListView list=new ListView(context);
        boolean stackedValues=player!=null;
        list.setDivider(new android.graphics.drawable.ColorDrawable(ui.theme.tint(20)));
        list.setDividerHeight(ui.px(1));
        list.setPadding(0,ui.px(10),0,ui.px(8));
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return values.size();}
            public Object getItem(int position){return values.get(position);}
            public long getItemId(int position){return position;}
            public boolean areAllItemsEnabled(){return false;}
            public boolean isEnabled(int position){return values.get(position).action!=null;}
            public View getView(int position,View reuse,ViewGroup parent){
                Option option=values.get(position);
                LinearLayout row=ui.horizontal();
                row.setPadding(ui.px(18),ui.px(12),ui.px(14),ui.px(12));
                row.setBackgroundDrawable(ui.fill(ui.theme.tint(6),8));
                String[] text=option.title.split("\\n",2);
                boolean stacked=stackedValues&&text.length>1;
                row.setMinimumHeight(ui.px(stacked?76:60));
                TextView title=ui.text(text[0],21,ui.theme.text);
                title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
                TextView value=text.length>1?ui.text(text[1],17,ui.theme.muted):null;
                if(value!=null){
                    value.setMaxWidth(ui.px(330));value.setMaxLines(3);value.setEllipsize(TextUtils.TruncateAt.END);
                }
                if(stacked){
                    LinearLayout labels=ui.vertical();
                    labels.addView(title);
                    value.setPadding(0,ui.px(4),0,0);
                    labels.addView(value);
                    row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                }else{
                    row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
                    if(value!=null)row.addView(value);
                }
                if(option.action!=null){
                    ImageView indicator=new ImageView(context);
                    indicator.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    String asset=option.checked?"icon_check":"icon_arrow_right";
                    indicator.setImageDrawable(tintedIcon(asset,22,ui.theme.muted));
                    LinearLayout.LayoutParams dimensions=new LinearLayout.LayoutParams(ui.px(22),ui.px(22));
                    dimensions.leftMargin=ui.px(8);
                    row.addView(indicator,dimensions);
                }
                if(option.preview!=null){
                    BlTheme palette=option.preview.resolve(context.getResources().getConfiguration());
                    View sample=new View(context);
                    android.graphics.drawable.GradientDrawable swatch=ui.backdrop(palette,6);
                    swatch.setStroke(ui.px(2),palette.accent);
                    sample.setBackgroundDrawable(swatch);
                    LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(ui.px(68),ui.px(28));
                    size.rightMargin=ui.px(14);
                    row.addView(sample,0,size);
                }
                return row;
            }
        });
        android.graphics.drawable.GradientDrawable highlight=ui.fill(ui.theme.tint(54),8);
        highlight.setStroke(ui.px(2),ui.theme.accent);
        list.setSelector(highlight);list.setDrawSelectorOnTop(true);
        list.setOnItemClickListener((parent,view,position,id)->{
            Option option=values.get(position);
            if(option.action==null)return;
            Panel from=panel;if(from!=null)from.selection=position;
            openingPanel=from;hideModal();try{option.action.run();}finally{openingPanel=null;}
        });
        list.requestFocusFromTouch();list.setSelection(0);return list;
    }
    private void options(String title,List<Option> values){showPanel(new Panel(title,values,openingPanel));}
    private void showPanel(Panel value){
        FrameLayout.LayoutParams bounds=player!=null&&!value.title.equals("选集")
                ?ui.frame(440,-1,Gravity.RIGHT):ui.frame(player!=null?680:860,player!=null?410:500,Gravity.CENTER);
        showPanel(value,bounds);
    }
    private void showPanel(Panel value,FrameLayout.LayoutParams bounds){
        hideModal();
        panel=value;
        modalGeneration++;
        modalFocus=root.findFocus();
        modal=new FrameLayout(context);
        modal.setBackgroundColor(player==null?0xcc101010:0x66101010);
        LinearLayout card=ui.vertical();
        card.setPadding(ui.px(20),ui.px(20),ui.px(20),ui.px(16));
        card.setBackgroundDrawable(ui.fill(ui.theme.surface,player==null||value.title.equals("选集")?10:0));
        TextView title=ui.title(value.title);
        title.setMaxLines(2);
        card.addView(title);
        ListView list=optionList(value.rows);
        card.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        Button back=ui.button("返回");
        back.setOnClickListener(v->backPanel());
        card.addView(back);
        modal.addView(card,bounds);
        root.addView(modal,new FrameLayout.LayoutParams(-1,-1));
        list.requestFocusFromTouch();
        list.setSelection(value.selection);
    }
    private void backPanel(){Panel parent=panel==null?null:panel.parent;if(parent==null)hideModal();else showPanel(parent);}
    private void hideModal(){panel=null;modalGeneration++;if(modal!=null){root.removeView(modal);modal=null;if(modalFocus!=null)modalFocus.requestFocus();modalFocus=null;}}
    private String diagnosticContext(){return "页面="+(route==null?"启动":route.kind)+"; 登录="+api.signedIn()+(video==null?"":"; BV="+video.optString("bvid")+"; cid="+cid+"; P="+(partIndex+1))+"; 请求画质="+api.prefs.getInt("quality",64);}
    private void reportFailure(String stage,Throwable error,boolean visible){BlDiagnostics.Entry entry=api.diagnostics.record(stage,error,diagnosticContext());if(visible){if(player!=null)player.requestFailure(entry.summary);message(entry.summary+" · 查看错误详情",true);}}
    private void showDiagnostics(){
        List<BlDiagnostics.Entry> entries=api.diagnostics.snapshot();StringBuilder text=new StringBuilder();if(entries.isEmpty())text.append("暂无错误记录。\nBL v").append(BlDiagnostics.VERSION).append(" · Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n').append(diagnosticContext());
        for(BlDiagnostics.Entry entry:entries){if(text.length()>48000)break;if(text.length()>0)text.append("\n\n────────────────\n\n");text.append(entry.details);}final String details=text.toString();
        Panel parent=openingPanel;hideModal();panel=new Panel("错误详情",new ArrayList<Option>(),parent);modalGeneration++;modalFocus=root.findFocus();modal=new FrameLayout(context);modal.setBackgroundColor(0xcc101010);
        LinearLayout card=ui.vertical();card.setPadding(ui.px(22),ui.px(18),ui.px(22),ui.px(16));card.setBackgroundDrawable(ui.fill(ui.theme.surface,10));card.addView(ui.title("错误详情 · 最近 "+entries.size()+" 条"));
        TextView body=ui.text(details,17,ui.theme.text);body.setGravity(Gravity.TOP|Gravity.LEFT);body.setPadding(ui.px(6),ui.px(12),ui.px(6),ui.px(12));ScrollView scroll=ui.scroll(body);scroll.setContentDescription("错误详情内容");scroll.setId(0x424c10);scroll.setFocusable(true);scroll.setFocusableInTouchMode(true);scroll.setNextFocusDownId(0x424c11);card.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout buttons=ui.horizontal();Button copy=ui.button("复制详情");copy.setId(0x424c11);copy.setNextFocusUpId(0x424c10);copy.setNextFocusRightId(0x424c12);buttons.addView(copy,new LinearLayout.LayoutParams(0,-2,1));ui.gap(buttons,12);Button back=ui.button("返回");back.setId(0x424c12);back.setNextFocusUpId(0x424c10);back.setNextFocusLeftId(0x424c11);buttons.addView(back,new LinearLayout.LayoutParams(0,-2,1));card.addView(buttons);
        copy.setOnClickListener(new View.OnClickListener(){public void onClick(View v){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null){clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BL 错误详情",details));Toast.makeText(context,"已复制错误详情",Toast.LENGTH_SHORT).show();}}});back.setOnClickListener(new View.OnClickListener(){public void onClick(View v){backPanel();}});
        modal.addView(card,ui.frame(1060,600,Gravity.CENTER));root.addView(modal,new FrameLayout.LayoutParams(-1,-1));copy.requestFocusFromTouch();
    }
    private void message(String text,boolean error){
        statusError=error;
        String safe=BlDiagnostics.clean(text);
        if(error&&!safe.contains("[BL-"))safe=api.diagnostics.record("界面提示",new java.io.IOException(safe),diagnosticContext()).summary;
        setStatus(safe);
        if(status!=null)status.setTextColor(error?(ui.theme.dark?0xffffb0b0:0xff8b243d):ui.theme.muted);
        if(headerActions!=null)headerActions.setVisibility(error?View.VISIBLE:View.GONE);
        if(error||player!=null)Toast.makeText(context,safe,Toast.LENGTH_LONG).show();
    }
    private void submit(final Job job,final int token,final Result result){submit(job,token,result,true);}
    private void submit(final Job job,final int token,final Result result,final boolean report){
        if(closed)return;
        jobs.execute(new Runnable(){public void run(){
            if(closed||token!=generation)return;
            try{
                final Object value=job.run();
                main.post(new Runnable(){public void run(){
                    if(closed||token!=generation)return;
                    try{result.show(value);}catch(Exception e){loading=false;reportFailure("内容处理",e,report);}catch(LinkageError e){loading=false;reportFailure("宿主接口不兼容",e,report);}
                }});
            }catch(final Exception error){
                main.post(new Runnable(){public void run(){
                    if(closed||token!=generation)return;
                    loading=false;reportFailure("页面请求",error,report);
                    if(report&&error instanceof BlApi.ApiException&&((BlApi.ApiException)error).code==-101){
                        List<Option> values=new ArrayList<>();values.add(new Option("扫码登录",new Runnable(){public void run(){navigate(new Route("login","扫码登录",""),true);}}));options("此功能需要登录",values);
                    }
                }});
            }catch(final LinkageError error){main.post(new Runnable(){public void run(){if(closed||token!=generation)return;loading=false;reportFailure("宿主接口不兼容",error,report);}});}
        }});
    }
    private void savePlaybackPosition(){if(player!=null&&video!=null&&!video.optBoolean("bl_live"))api.savePosition(video.optString("bvid"),cid,player.position());}
    private void closePlayer(){playbackGeneration++;if(liveDanmaku!=null){liveDanmaku.close();liveDanmaku=null;}if(dmFeed!=null){dmFeed.close();dmFeed=null;}dmCid=0;if(player!=null){savePlaybackPosition();player.close();retired.add(player);player=null;for(Iterator<BlPlayer> i=retired.iterator();i.hasNext();)try{if(i.next().awaitRelease(0))i.remove();}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    /** Back first dismisses an in-View modal, then pops the native route stack. */
    public boolean onBack(){if(modal!=null){backPanel();return true;}if(player!=null&&player.back())return true;if(history.isEmpty())return false;saveRoute();closePlayer();route=history.pop();render();return true;}
    /** Host visibility drives media and timers without an Activity inside this JAR. */
    public void setActive(boolean active){if(this.active!=active&&liveDanmaku!=null)liveDanmaku.active(active);this.active=active;if(player!=null)player.active(active);if(active&&!closed){BlTheme resolved=preferredTheme().resolve(context.getResources().getConfiguration());if(player==null&&modal==null&&ui.theme!=resolved){saveRoute();applyTheme();render();}else if(route!=null&&route.kind.equals("login"))render();}}
    public void update(long positionMs,boolean playing){}
    /** Stop playback, sockets, downloads, polling and bitmap workers on channel exit. */
    public void dispose(){if(closed)return;closed=true;generation++;main.removeCallbacksAndMessages(null);closePlayer();hideModal();jobs.shutdownNow();if(images!=null)images.close();history.clear();if(root!=null)root.removeAllViews();final List<BlPlayer> release=new ArrayList<>(retired);Thread cleanup=new Thread(new Runnable(){public void run(){for(BlPlayer p:release)try{p.awaitRelease(5);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(proxy!=null)proxy.close();if(api!=null)api.close();}},"bl-channel-cleanup");cleanup.setDaemon(true);cleanup.start();}
    private final class Root extends FrameLayout {
        Root(Context c){super(c);}
        public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&findFocus()==null&&modal==null&&player==null&&grid!=null&&adapter.getCount()>0)focusGrid();}
        public boolean dispatchKeyEvent(KeyEvent event){
            if(player!=null&&modal==null&&player.key(event))return true;
            int code=event.getKeyCode();
            if(player==null&&modal==null&&findFocus()==null&&grid!=null&&adapter!=null&&adapter.getCount()>0&&(code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)){if(event.getAction()==KeyEvent.ACTION_DOWN){grid.requestFocusFromTouch();grid.setSelection(Math.min(route.selection,adapter.getCount()-1));}return true;}
            if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_CENTER||event.getKeyCode()==KeyEvent.KEYCODE_ENTER){View focus=findFocus();if(focus instanceof AdapterView){if(event.getAction()==KeyEvent.ACTION_UP&&!event.isCanceled()){AdapterView<?> list=(AdapterView<?>)focus;int p=Math.max(0,list.getSelectedItemPosition());if(list.getAdapter()!=null&&p<list.getAdapter().getCount())list.performItemClick(list.getSelectedView(),p,list.getAdapter().getItemId(p));}return true;}}
            if(event.getKeyCode()>=KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE&&event.getKeyCode()<=KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)return true;
            if(super.dispatchKeyEvent(event))return true;
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            switch(code){
                case KeyEvent.KEYCODE_DPAD_UP:return moveFocus(View.FOCUS_UP);
                case KeyEvent.KEYCODE_DPAD_DOWN:return moveFocus(View.FOCUS_DOWN);
                case KeyEvent.KEYCODE_DPAD_LEFT:return moveFocus(View.FOCUS_LEFT);
                case KeyEvent.KEYCODE_DPAD_RIGHT:return moveFocus(View.FOCUS_RIGHT);
                default:return false;
            }
        }
        private boolean moveFocus(int direction){
            // The host dispatches directly to this view, outside ViewRoot's default focus navigation.
            ViewGroup scope=modal==null?this:modal;
            View focused=scope.findFocus();
            if(scope==this&&focused!=null&&focused==sidebar.findFocus()
                    &&(direction==View.FOCUS_UP||direction==View.FOCUS_DOWN))scope=sidebar;
            View next=FocusFinder.getInstance().findNextFocus(scope,focused,direction);
            if(next==null||next==focused)return false;
            if(focused==null)return next.requestFocus(direction);
            Rect previous=new Rect();
            focused.getFocusedRect(previous);
            scope.offsetDescendantRectToMyCoords(focused,previous);
            scope.offsetRectIntoDescendantCoords(next,previous);
            return next.requestFocus(direction,previous);
        }
    }
}
