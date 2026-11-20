package xiao.bu.channel.bl;

import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

final class BlSearchView extends LinearLayout {
    interface Submit { void search(String query); }

    private static final String[] DIGITS={"01","1","ABC2","DEF3","GHI4","JKL5","MNO6","PQRS7","TUV8","WXYZ9"};
    private final BlUi ui;
    private final EditText input;
    private final LinearLayout keys;
    private final Button numeric, alphabet;
    private final LinearLayout suggestions;
    private final Button popular, recent;
    private final List<String> history;
    private final Submit submit;
    private final Runnable clearHistory;
    private List<String> trending=Collections.emptyList();
    private boolean trendingLoading=true,showRecent;
    private int previousDigit=-1, letter, lastCursor;
    private long lastPress;
    private String lastEdit="";

    BlSearchView(BlUi ui,String draft,boolean grouped,List<String> history,Submit submit,Runnable clearHistory){
        super(ui.context);
        this.ui=ui;
        this.history=history;
        this.submit=submit;
        this.clearHistory=clearHistory;
        setOrientation(VERTICAL);

        input=new EditText(ui.context);
        input.setTextColor(ui.theme.text);
        input.setHintTextColor(ui.theme.muted);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(23));
        input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setContentDescription("搜索关键词");
        input.setHint("关键词、BV 号或视频链接");
        input.setPadding(ui.px(14),0,ui.px(14),0);
        input.setBackgroundDrawable(ui.focus(ui.theme.surface));
        setQuery(draft);
        addView(input,new LayoutParams(-1,ui.px(52)));
        Runnable run=()->submit.search(query());
        input.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH&&action!=EditorInfo.IME_ACTION_DONE)return false;
            run.run();return true;
        });

        LinearLayout body=ui.horizontal();
        body.setGravity(Gravity.TOP);
        body.setPadding(0,ui.px(16),0,0);
        LinearLayout keyboard=ui.vertical();
        body.addView(ui.scroll(keyboard),new LayoutParams(0,-1,2));
        LinearLayout edit=ui.horizontal();
        key(edit,"清空",()->setQuery(""));
        key(edit,"后退",this::erase);
        keyboard.addView(edit);
        keys=ui.vertical();
        keyboard.addView(keys,new LayoutParams(-1,-2));
        LinearLayout modes=ui.horizontal();
        numeric=key(modes,"T9键盘",()->showKeyboard(true));
        alphabet=key(modes,"全键盘",()->showKeyboard(false));
        key(modes,"搜索",run);
        keyboard.addView(modes);
        showKeyboard(grouped);

        ui.gap(body,24);
        LinearLayout side=ui.vertical();
        side.setPadding(ui.px(12),ui.px(8),ui.px(12),ui.px(12));
        side.setBackgroundDrawable(ui.fill(ui.theme.surface,12));
        LinearLayout picker=ui.horizontal();
        popular=key(picker,"热搜",()->selectSuggestions(false));
        recent=key(picker,"最近搜索",()->selectSuggestions(true));
        side.addView(picker);
        suggestions=ui.vertical();
        side.addView(ui.scroll(suggestions),new LayoutParams(-1,0,1));
        selectSuggestions(showRecent);
        body.addView(side,new LayoutParams(0,-1,3));
        addView(body,new LayoutParams(-1,0,1));
    }

    View entry(){return isGrouped()?numeric:alphabet;}
    boolean isGrouped(){return numeric.isSelected();}
    String query(){return input.getText().toString();}
    void setQuery(String query){
        previousDigit=-1;
        input.setText(query);
        input.setSelection(input.length());
    }

    void showTrending(List<String> words){
        trending=words;
        trendingLoading=false;
        if(!showRecent)selectSuggestions(false);
    }

    private void selectSuggestions(boolean historySelected){
        showRecent=historySelected;
        popular.setSelected(!showRecent);
        recent.setSelected(showRecent);
        suggestions.removeAllViews();
        List<String> words=showRecent?history:trending;
        if(words.isEmpty()){
            String text=showRecent?"暂无搜索记录":trendingLoading?"正在加载热搜…":"暂无热搜";
            TextView empty=ui.text(text,17,ui.theme.muted);
            empty.setPadding(ui.px(8),ui.px(12),0,0);
            suggestions.addView(empty);
            return;
        }
        for(String word:words){
            Button item=ui.button(word);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
            LayoutParams layout=new LayoutParams(-1,ui.px(46));
            layout.topMargin=ui.px(4);
            suggestions.addView(item,layout);
            item.setOnClickListener(v->submit.search(word));
        }
        if(showRecent){
            Button clear=ui.button("清除搜索记录");
            clear.setOnClickListener(v->clearHistory.run());
            suggestions.addView(clear);
        }
    }

    void hideIme(){
        InputMethodManager manager=(InputMethodManager)getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if(manager!=null)manager.hideSoftInputFromWindow(input.getWindowToken(),0);
    }

    private Button key(LinearLayout row,String text,Runnable action){
        Button button=ui.button(text);
        button.setPadding(ui.px(3),0,ui.px(3),0);
        LayoutParams lp=new LayoutParams(0,ui.px(48),1);
        lp.setMargins(ui.px(3),ui.px(3),ui.px(3),ui.px(3));
        row.addView(button,lp);
        button.setOnClickListener(v->action.run());
        return button;
    }

    private void showKeyboard(boolean grouped){
        previousDigit=-1;
        numeric.setSelected(grouped);
        alphabet.setSelected(!grouped);
        keys.removeAllViews();
        if(grouped){
            for(int start=1;start<=9;start+=3){
                LinearLayout row=ui.horizontal();
                for(int digit=start;digit<start+3;digit++){
                    final int value=digit;
                    if(digit==1)spaceOnLongPress(key(row,"0/1",()->pressDigit(0)));
                    else key(row,digit+"  "+DIGITS[digit].substring(0,DIGITS[digit].length()-1),()->pressDigit(value));
                }
                keys.addView(row);
            }
            return;
        }
        for(String line:new String[]{"ABCDEF","GHIJKL","MNOPQR","STUVWX","YZ1234","567890"}){
            LinearLayout row=ui.horizontal();
            for(int i=0;i<line.length();i++){
                String value=line.substring(i,i+1);
                Button digit=key(row,value,()->insert(value));
                if(value.equals("0"))spaceOnLongPress(digit);
            }
            keys.addView(row);
        }
    }
    private void spaceOnLongPress(Button button){
        button.setContentDescription(button.getText()+"，长按输入空格");
        button.setOnLongClickListener(v->{insert(" ");return true;});
    }

    private void pressDigit(int digit){
        int cursor=input.getSelectionStart();
        long now=SystemClock.uptimeMillis();
        String letters=DIGITS[digit];
        boolean cycling=previousDigit==digit&&now-lastPress<900&&cursor>0&&cursor==lastCursor
                &&cursor==input.getSelectionEnd()&&lastEdit.equals(query());
        if(cycling){
            letter=(letter+1)%letters.length();
            input.getText().replace(cursor-1,cursor,letters.substring(letter,letter+1));
        }else{
            letter=0;
            insert(letters.substring(0,1));
        }
        previousDigit=digit;
        lastPress=now;
        lastEdit=query();
        lastCursor=input.getSelectionStart();
    }

    private void insert(String value){
        previousDigit=-1;
        Editable text=input.getText();
        int start=input.getSelectionStart(),end=input.getSelectionEnd();
        if(start<0||end<0){start=text.length();end=start;}
        int left=Math.min(start,end),right=Math.max(start,end);
        int remaining=text.length()-(right-left);
        text.replace(left,right,value);
        input.setSelection(left+text.length()-remaining);
    }

    private void erase(){
        int start=input.getSelectionStart(),end=input.getSelectionEnd();
        if(start==end&&start>0)input.setSelection(Character.offsetByCodePoints(input.getText(),start,-1),end);
        insert("");
    }
}
