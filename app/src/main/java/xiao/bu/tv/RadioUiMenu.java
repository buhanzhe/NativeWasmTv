package xiao.bu.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native picker over the same downloaded artwork catalog used by web settings. */
final class RadioUiMenu implements xiao.bu.tv.skin.TvUi.Events {
    private final Activity activity;
    private final TvUiMenu menu;
    private Runnable back;
    private View content;
    private String[] ids=new String[0],names=new String[0];
    private String selected="";
    private boolean loading=true,applying;
    private int pending=-1;

    private RadioUiMenu(Activity activity,TvUiMenu menu){this.activity=activity;this.menu=menu;}
    static void show(Activity activity,View anchor,TvUiMenu menu,Runnable back){
        RadioUiMenu model=new RadioUiMenu(activity,menu);model.back=back;Bundle state=new Bundle();
        state.putStringArray("categories",new String[]{"唱片界面"});
        menu.showSubmenu(anchor,state,model);model.content=menu.contentView();model.load();
    }
    private void load(){
        loading=true;refresh();
        RadioUiPlugin.execute(()->{
            try{JSONObject state=new JSONObject(RadioUiPlugin.status(activity));
                JSONArray choices=state.getJSONArray("choices");String[] nextIds=new String[choices.length()],nextNames=new String[choices.length()];
                for(int i=0;i<nextIds.length;i++){JSONObject item=choices.getJSONObject(i);nextIds[i]=item.getString("id");nextNames[i]=item.getString("name");}
                activity.runOnUiThread(()->{loading=false;ids=nextIds;names=nextNames;selected=state.optString("selected");refresh();});
            }catch(Exception error){activity.runOnUiThread(()->{loading=false;refresh();toast("读取唱片界面失败："+error.getMessage());});}
        });
    }
    private void refresh(){if(content!=null&&menu.contentView()==content&&!activity.isFinishing())menu.refresh();}
    private void toast(String text){if(!activity.isFinishing()&&(activity.hasWindowFocus()||menu.contentView()==content))Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    public Bundle options(int category){
        Bundle state=new Bundle();String[] labels=names.clone();int index=-1;
        if(loading)labels=new String[]{"正在读取…"};
        else if(ids.length==0)labels=new String[]{"重新加载选项"};
        else for(int i=0;i<ids.length;i++){if(ids[i].equals(selected))index=i;if(applying&&i==pending)labels[i]+=" · 正在应用…";}
        state.putStringArray("items",labels);state.putInt("selected",index);return state;
    }
    public void action(int category,int option){
        if(loading||applying)return;
        if(ids.length==0){load();return;}
        if(option<0||option>=ids.length)return;
        applying=true;pending=option;refresh();toast("正在切换唱片界面…");
        String id=ids[option],name=names[option];
        RadioUiPlugin.execute(()->{
            try{JSONObject state=new JSONObject(RadioUiPlugin.configure(activity,new JSONObject().put("id",id)));
                activity.runOnUiThread(()->{selected=state.optString("selected");applying=false;pending=-1;refresh();toast("唱片界面已切换为"+name);});
            }catch(Exception error){activity.runOnUiThread(()->{applying=false;pending=-1;refresh();toast("唱片界面切换失败："+error.getMessage());});}
        });
    }
    public void dismiss(){menu.dismiss();if(back!=null)back.run();}
}
