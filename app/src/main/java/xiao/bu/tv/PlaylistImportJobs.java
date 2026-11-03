package xiao.bu.tv;

import org.json.*;
import java.util.concurrent.*;

/** One bounded background import. Polling never holds the catalog/network worker. */
final class PlaylistImportJobs {
    interface Work { String run() throws Exception; }
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"playlist-import");t.setDaemon(true);return t;});
    private String id="",state="idle",result="",error="";private long sequence;
    synchronized String start(Work work)throws Exception {
        if("working".equals(state))throw new ControlRequestException(409,"频道列表正在导入，请稍候");
        id=Long.toHexString(System.nanoTime())+"-"+(++sequence);state="working";result="";error="";
        worker.execute(()->{
            try{String response=work.run();synchronized(this){result=response;state="done";}}
            catch(Exception|LinkageError|OutOfMemoryError failure){synchronized(this){error=failure instanceof OutOfMemoryError?"设备内存不足，请减少频道数量":failure.getMessage()==null?"频道导入失败":failure.getMessage();state="failed";}}
        });
        return new JSONObject().put("ok",true).put("jobId",id).put("state","working").toString();
    }
    synchronized String status(String requested)throws Exception {
        if(!id.equals(requested)||id.isEmpty())throw new ControlRequestException(404,"导入任务不存在，请刷新频道列表");
        JSONObject response=new JSONObject().put("ok",true).put("jobId",id).put("state",state);
        if("done".equals(state))response.put("result",new JSONObject(result));
        if("failed".equals(state))response.put("message",error);
        return response.toString();
    }
}
