package xiao.bu.tv;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;import org.json.*;import java.util.*;
/** Synthetic local form only; never submits an account or a verification code. */
public final class LiteTextInputInstrumentation extends Instrumentation {
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){List<String> results=new ArrayList<>();Bundle out=new Bundle();int status=-1;
 try(java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))){
  Thread peer=new Thread(()->{try(java.net.Socket socket=server.accept();java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));java.io.OutputStream output=socket.getOutputStream()){
   String line;while((line=input.readLine())!=null&&!line.isEmpty()){}byte[] body=fixture().getBytes("UTF-8");
   output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));output.write(body);
  }catch(Exception ignored){}});peer.start();run(this,"http://127.0.0.1:"+server.getLocalPort()+"/",results);
 }catch(Throwable e){status=0;results.add(android.util.Log.getStackTraceString(e));}finally{out.putString("stream",android.text.TextUtils.join("\n",results)+"\n");finish(status,out);}}
 static String fixture(){return "<style>input{display:block;width:220px;height:32px}#hidden{display:none}</style><input id='phone' type='tel'><input id='secret' type='PASSWORD' value='initial-private'><div id='hidden'>HIDDEN_VISIBLE_SENTINEL</div><script>var phone=document.getElementById('phone'),secret=document.getElementById('secret');phone.addEventListener('input',function(){console.log('INPUT_PHONE')});phone.addEventListener('change',function(){console.log('CHANGE_PHONE')});secret.addEventListener('change',function(){console.log('CHANGE_SECRET')});console.log('FORM_READY')</script>";}

 interface Ready{boolean get();}
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 static void await(Ready r,String s)throws Exception{long end=SystemClock.uptimeMillis()+12000;while(!r.get()&&SystemClock.uptimeMillis()<end)Thread.sleep(30);check(r.get(),s);}
 static EditText editor(android.view.View root){if(root instanceof EditText)return (EditText)root;if(root instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)root;for(int i=0;i<g.getChildCount();i++){EditText e=editor(g.getChildAt(i));if(e!=null)return e;}}return null;}
 static void run(Instrumentation test,String url,List<String> result)throws Exception{
  Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  List<String> messages=Collections.synchronizedList(new ArrayList<>());List<JSONObject> frames=Collections.synchronizedList(new ArrayList<>());final FrameLayout[] root={null};final Dialog[] dialog={null};final LiteCanvas[] canvas={null};final JSONObject[] state={null};
  LiteEngine engine=new LiteEngine(test.getTargetContext(),new LiteEngine.Listener(){public void frame(JSONObject p){frames.add(p);if(canvas[0]!=null)canvas[0].frame(p);}public void message(String s){messages.add(s);}public void navigate(String s){}public void media(String s,Map<String,String> h){}public void textInput(JSONObject p){state[0]=p;if(canvas[0]!=null)canvas[0].textInput(p);}});
  try{
   test.runOnMainSync(()->{dialog[0]=new Dialog(activity);root[0]=new FrameLayout(activity);canvas[0]=new LiteCanvas(activity);canvas[0].engine=engine;canvas[0].fixedViewportWidth=640;canvas[0].fixedViewportHeight=480;root[0].addView(canvas[0],new FrameLayout.LayoutParams(-1,-1));dialog[0].setContentView(root[0]);dialog[0].show();dialog[0].getWindow().setLayout(-1,-1);});
   engine.viewport(640,480,0);engine.load(url);await(()->messages.contains("FORM_READY")&&!frames.isEmpty(),"Fixture and native frame ready");
   engine.evaluate("document.getElementById('phone').focus()");await(()->state[0]!=null&&state[0].optBoolean("active"),"DOM focus reaches native editor");
   test.runOnMainSync(()->{EditText e=editor(root[0]);check(e!=null&&e.hasFocus(),"Real Android editor focused");InputConnection connection=e.onCreateInputConnection(new EditorInfo());check(connection!=null&&connection.commitText("12345",1),"Real IME text commit");});
   await(()->messages.contains("INPUT_PHONE"),"Actual Android IME produces DOM input event");
   JSONObject old=new JSONObject(state[0].toString());engine.evaluate("document.getElementById('secret').focus()");await(()->state[0].optInt("id")!=old.optInt("id"),"Focus advances field identity");
   test.runOnMainSync(()->{EditText e=editor(root[0]);check(e!=null&&e.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod,"Password native transformation");InputConnection c=e.onCreateInputConnection(new EditorInfo());c.setSelection(0,e.length());c.setComposingText("fixture",1);c.finishComposingText();c.commitText("-only",1);});
   engine.editTextInput(old.optLong("owner"),old.optInt("id"),old.optInt("epoch"),"stale",5,5,false);
   engine.evaluate("setTimeout(function(){var p=document.getElementById('phone'),s=document.getElementById('secret');if(p.value==='12345'&&s.value==='fixture-only'&&s.getAttribute('value')==='initial-private')console.log('VALUES_OK');else throw Error('Input value lifecycle');s.blur();document.getElementById('hidden').style.display='block';console.log('VISIBLE_WIDTH '+document.getElementById('hidden').getBoundingClientRect().width)},100)");
   await(()->messages.contains("VALUES_OK")&&messages.contains("CHANGE_PHONE")&&messages.contains("CHANGE_SECRET"),"DOM values, blur/change and stale edit isolation");
   await(()->{for(JSONObject p:frames){String s=p.toString();if(s.contains("HIDDEN_VISIBLE_SENTINEL")&&!s.contains("initial-private")&&!s.contains("fixture-only"))return true;}return false;},"Hidden text becomes visible without exposing password in paint commands");
   int n=frames.size();engine.evaluate("document.getElementById('hidden').style.display='none';document.getElementById('hidden').getBoundingClientRect();console.log('HIDDEN_AGAIN')");await(()->frames.size()>n,"Hidden state repainted");check(!frames.get(frames.size()-1).toString().contains("HIDDEN_VISIBLE_SENTINEL"),"Hidden text removed from actual paint");
   result.add("PASS actual Android InputConnection commits/composition, DOM input/change, password transformation, stale focus isolation, no password paint, hidden-visible-hidden native layout");
   for(String s:messages)if(s.startsWith("JS：")||s.startsWith("运行错误："))throw new AssertionError(s);
  }finally{result.addAll(messages);test.runOnMainSync(()->{if(canvas[0]!=null)canvas[0].endTextInput();if(dialog[0]!=null)dialog[0].dismiss();});engine.close();}
 }
}
