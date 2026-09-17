package xiao.bu.tv;

import org.json.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class VodSearchTest {
    static int assertions;
    static void check(boolean value, String name) { assertions++; if (!value) throw new AssertionError(name); }
    static JSONObject item(String source, String id, String name, String year, String type) throws Exception {
        return new JSONObject().put("sourceKey",source).put("id",id).put("name",name).put("year",year).put("type",type);
    }
    static JSONObject done(VodSearch job) throws Exception {
        long until=System.currentTimeMillis()+5000;
        while(job.running() && System.currentTimeMillis()<until) Thread.sleep(5);
        check(!job.running(),"job terminates"); return job.state();
    }
    public static void main(String[] args) throws Exception {
        JSONObject a=item("a","1","仙剑 奇侠传三","2009","tv"), b=item("b","2","仙剑奇侠传三","2009","tv");
        check(VodSearch.groupKey(a).equals(VodSearch.groupKey(b)),"same work merges");
        check(!VodSearch.groupKey(a).equals(VodSearch.groupKey(item("b","2","仙剑奇侠传三","2025","tv"))),"remake separate");
        check(!VodSearch.groupKey(a).equals(VodSearch.groupKey(item("b","2","仙剑奇侠传三","2009","movie"))),"movie separate");
        check(!VodSearch.groupKey(a).equals(VodSearch.groupKey(item("b","2","仙剑奇侠传三第二季","2009","tv"))),"season separate");
        check(!VodSearch.groupKey(item("a","1","同名","","tv")).equals(VodSearch.groupKey(item("b","2","同名","","tv"))),"unknown year conservative");
        JSONArray sites=new JSONArray(); for(int i=0;i<8;i++)sites.put(new JSONObject().put("id",i).put("name","源"+i));
        final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();
        VodSearch job=new VodSearch("r","仙剑",sites,2,new VodSearch.Fetcher(){
            public JSONObject search(int site,String word,int page)throws Exception {
                int n=active.incrementAndGet(); synchronized(peak){peak.set(Math.max(peak.get(),n));}calls.incrementAndGet();
                try{Thread.sleep(site==0?80:5);if(site==2)throw new java.io.IOException("bad");
                    return new JSONObject().put("pagecount",5).put("items",new JSONArray().put(item("s"+site,"same","同剧","2009","tv")));
                }finally{active.decrementAndGet();}
            }
        });
        job.start();JSONObject state=done(job);
        check(peak.get()==2,"bounded concurrency");check(calls.get()==15,"page bound");
        check(state.getInt("completed")==8,"all sources reported");check(state.getInt("count")==7,"page dedup");
        check(state.getJSONArray("groups").length()==1,"multi source aggregation");
        int failures=0,truncated=0;for(int i=0;i<8;i++){JSONObject s=state.getJSONArray("statuses").getJSONObject(i);if(s.getString("status").equals("failed"))failures++;if(s.getBoolean("truncated"))truncated++;}
        check(failures==1 && truncated==7,"failure and partial coverage visible");
        final AtomicInteger disconnects=new AtomicInteger();
        VodSearch cancelJob=new VodSearch("r","x",sites,1,new VodSearch.Fetcher(){
            public JSONObject search(int site,String word,int page)throws Exception {
                final VodSearch current=VodSearch.CURRENT.get();
                HttpURLConnection connection=new HttpURLConnection(new URL("https://example.invalid")){
                    public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnects.incrementAndGet();}
                };
                current.attach(connection);
                try{while(!current.stopped())Thread.sleep(5);throw new java.io.IOException("cancel");}finally{current.detach(connection);}
            }
        });
        cancelJob.start();Thread.sleep(80);cancelJob.cancel();JSONObject cancelled=done(cancelJob);
        check(disconnects.get()==2,"cancel disconnects active requests");check(cancelled.getBoolean("cancelled"),"cancel status");
        check(cancelled.getInt("count")==0,"cancel rejects late results");
        final AtomicInteger timedOut=new AtomicInteger();
        HttpURLConnection blocked=new HttpURLConnection(new URL("https://example.invalid")){
            public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){timedOut.incrementAndGet();}
        };
        VodDeadline budget=new VodDeadline(blocked,30);Thread.sleep(80);
        boolean expired=false;try{budget.check();}catch(java.net.SocketTimeoutException expected){expired=true;}finally{budget.close();}
        check(expired&&timedOut.get()==1,"total deadline closes connection");
        VodSearch capped=new VodSearch("r","x",new JSONArray().put(new JSONObject().put("id",0)),1,new VodSearch.Fetcher(){
            public JSONObject search(int site,String word,int page)throws Exception{
                JSONArray items=new JSONArray();for(int i=0;i<2100;i++)items.put(item("s",String.valueOf(i),"同剧","2009","tv"));
                return new JSONObject().put("items",items).put("pagecount",1);
            }
        });capped.start();JSONObject cap=done(capped);check(cap.getInt("count")==2000&&cap.getBoolean("limited"),"candidate memory cap");
        System.out.println("PASS "+assertions+" aggregation assertions");
    }
}
