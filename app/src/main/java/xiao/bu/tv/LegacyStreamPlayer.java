package xiao.bu.tv;

import android.os.*;
import android.view.Surface;
import java.io.*;
import java.lang.reflect.Method;
import java.util.concurrent.*;

/** API14/15 AOSP IStreamSource transport; no codec loading, transcoding or HTTP cache. */
final class LegacyStreamPlayer implements Closeable {
    interface Listener {void prepared();void size(int width,int height);void ended();void error(String error);default void info(int what,int extra){}}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private PushSource source;
    private final IBinder client=new Binder(){
        {attachInterface(null,"android.media.IMediaPlayerClient");}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code!=1)return super.onTransact(code,data,reply,flags);
            data.enforceInterface("android.media.IMediaPlayerClient");
            int what=data.readInt(),a=data.readInt(),b=data.readInt();
            main.post(()->{if(closed)return;
                if(what==1)listener.prepared();else if(what==2)listener.ended();
                else if(what==5){width=a;height=b;listener.size(a,b);}
                else if(what==100)listener.error("Stream player error "+a+"/"+b);
                else if(what==200)listener.info(a,b);
            });return true;
        }
    };
    private IBinder player;
    private volatile boolean closed;
    private volatile int width,height;
    LegacyStreamPlayer(Surface surface,Listener listener)throws IOException {
        this.listener=listener;
        if(Build.VERSION.SDK_INT<14||Build.VERSION.SDK_INT>15)throw new IOException("StreamSource bridge is API14/15 only");
        try {
            source=new PushSource();
            IBinder service=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"media.player");
            if(service==null)throw new IOException("No media service");
            android.media.MediaPlayer session=new android.media.MediaPlayer();int audioSession=session.getAudioSessionId();session.release();
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {data.writeInterfaceToken("android.media.IMediaPlayerService");data.writeInt(android.os.Process.myPid());data.writeStrongBinder(client);data.writeInt(audioSession);
                if(!service.transact(1,data,reply,0))throw new IOException("Create player unsupported");player=reply.readStrongBinder();
            }finally{data.recycle();reply.recycle();}
            if(player==null)throw new IOException("No stream player");
            call(4,p->p.writeStrongBinder(source));
            Parcel parcel=Parcel.obtain();IBinder texture;
            try{surface.writeToParcel(parcel,0);parcel.setDataPosition(0);parcel.readStrongBinder();texture=parcel.readStrongBinder();}
            finally{parcel.recycle();}
            if(texture==null||!texture.getInterfaceDescriptor().contains("SurfaceTexture"))throw new IOException("Unsupported legacy Surface parcel");
            final IBinder output=texture;call(22,p->p.writeStrongBinder(output));
            call(14,p->p.writeInt(3));call(16,p->{p.writeFloat(0);p.writeFloat(0);});
        }catch(Exception e){close();if(e instanceof IOException)throw (IOException)e;throw new IOException("StreamSource unavailable",e);}
    }
    private interface Args {void write(Parcel p);}
    private void call(int code,Args args)throws IOException {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken("android.media.IMediaPlayer");if(args!=null)args.write(data);
            if(player==null||!player.transact(code,data,reply,0))throw new IOException("Player transaction "+code+" unsupported");
            if(code!=1){int status=reply.readInt();if(status!=0)throw new IOException("Player transaction "+code+" status="+status);}
        }catch(RemoteException e){throw new IOException(e);}finally{data.recycle();reply.recycle();}
    }
    void prepare(InputStream input)throws IOException {
        Thread producer=new Thread(()->{
            try{source.feed(input);if(!closed)source.eos();}
            catch(Exception e){if(!closed)main.post(()->listener.error("TS push failed: "+e));}
            finally{try{input.close();}catch(IOException ignored){}}
        },"legacy-ts-push");producer.setDaemon(true);producer.start();
        call(5,null);
    }
    void start()throws IOException{call(6,null);}
    void pause()throws IOException{call(9,null);}
    void volume(float left,float right)throws IOException{call(16,p->{p.writeFloat(left);p.writeFloat(right);});}
    int position()throws IOException {
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken("android.media.IMediaPlayer");IBinder active=player;if(active==null||!active.transact(11,data,reply,0))throw new IOException("Position unsupported");int position=reply.readInt();int status=reply.readInt();if(status!=0)throw new IOException("Position status="+status);return position;}
        catch(RemoteException e){throw new IOException(e);}finally{data.recycle();reply.recycle();}
    }
    int width(){return width;}int height(){return height;}
    @Override public void close(){
        if(closed)return;closed=true;
        try{if(player!=null)call(1,null);}catch(IOException ignored){}
        player=null;if(source!=null)source.close();
    }
    private final class PushSource extends Binder {
        private final ArrayBlockingQueue<Integer> available=new ArrayBlockingQueue<>(32);
        private final CountDownLatch configured=new CountDownLatch(1);
        private final Object lock=new Object();
        private IBinder sink;
        private Region[] regions;
        private final Method mmap,write,munmap;
        PushSource(){
            attachInterface(null,"android.hardware.IStreamSource");
            try{Class<?> type=Class.forName("android.os.MemoryFile");
                mmap=type.getDeclaredMethod("native_mmap",FileDescriptor.class,int.class,int.class);
                write=type.getDeclaredMethod("native_write",FileDescriptor.class,int.class,byte[].class,int.class,int.class,int.class,boolean.class);
                munmap=type.getDeclaredMethod("native_munmap",int.class,int.class);
                mmap.setAccessible(true);write.setAccessible(true);munmap.setAccessible(true);
            }catch(Exception e){throw new IllegalStateException(e);}
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code<1||code>3)return super.onTransact(code,data,reply,flags);
            data.enforceInterface("android.hardware.IStreamSource");
            try{
                if(code==1)sink=data.readStrongBinder();
                else if(code==2){
                    int count=data.readInt();if(count<1||count>32)throw new IOException("Invalid shared buffer count");
                    synchronized(lock){if(closed)return true;regions=new Region[count];int bytes=0;
                        for(int i=0;i<count;i++){regions[i]=new Region(data.readStrongBinder());bytes+=regions[i].heapSize;if(bytes>8*1024*1024)throw new IOException("Shared buffers exceed budget");}
                    }configured.countDown();
                }else{int index=data.readInt();if(!closed&&!available.offer(index))throw new IOException("Duplicate stream buffer callback");}
            }catch(Exception e){AppLog.w("LegacyStreamPlayer","Shared TS buffer setup failed",e);main.post(()->{if(!closed)listener.error("Stream buffers failed: "+e);});}
            return true;
        }
        void feed(InputStream input)throws Exception {
            if(!configured.await(15,TimeUnit.SECONDS))throw new IOException("Stream buffers not configured");
            byte[] block=new byte[64*1024];
            while(!closed){
                // Pause applies backpressure; close wakes this wait with the sentinel.
                Integer index=available.take();if(index<0||closed)return;
                Region region=regions[index];int target=Math.min(block.length,region.size)/188*188,count=0;
                while(count<target&&!closed){int n=input.read(block,count,target-count);if(n<0)break;count+=n;}
                if(count==0)return;
                if(count%188!=0)throw new IOException("Truncated TS push packet");
                synchronized(lock){if(closed)return;write.invoke(null,region.fd.getFileDescriptor(),region.address,block,0,region.offset,count,false);}
                Parcel data=Parcel.obtain();try{data.writeInterfaceToken("android.hardware.IStreamListener");data.writeInt(index);data.writeInt(count);
                    if(!sink.transact(4,data,null,IBinder.FLAG_ONEWAY))throw new IOException("Queue TS buffer unsupported");
                }finally{data.recycle();}
                if(count<target)return;
            }
        }
        void eos()throws RemoteException{
            if(sink==null)return;Parcel p=Parcel.obtain();try{p.writeInterfaceToken("android.hardware.IStreamListener");p.writeInt(0);p.writeInt(0);p.writeInt(0);sink.transact(5,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}
        }
        void close(){available.offer(-1);configured.countDown();synchronized(lock){if(regions!=null)for(Region r:regions)if(r!=null)r.close();regions=null;}}
        private final class Region {
            final ParcelFileDescriptor fd;final int heapSize,offset,size,address;
            Region(IBinder memory)throws Exception {
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();IBinder heap;
                try{data.writeInterfaceToken("android.utils.IMemory");if(!memory.transact(1,data,reply,0))throw new IOException("Memory query unsupported");heap=reply.readStrongBinder();offset=reply.readInt();size=reply.readInt();}
                finally{data.recycle();reply.recycle();}
                data=Parcel.obtain();reply=Parcel.obtain();
                try{data.writeInterfaceToken("android.utils.IMemoryHeap");if(!heap.transact(1,data,reply,0))throw new IOException("Heap query unsupported");fd=reply.readFileDescriptor();heapSize=reply.readInt();reply.readInt();int heapOffset=reply.readInt();
                    if(fd==null||heapOffset!=0||heapSize<=0||heapSize>1024*1024||offset<0||size<188||offset>heapSize-size)throw new IOException("Invalid shared region");
                    address=(Integer)mmap.invoke(null,fd.getFileDescriptor(),heapSize,3);
                }finally{data.recycle();reply.recycle();}
            }
            void close(){try{munmap.invoke(null,address,heapSize);}catch(Exception ignored){}try{fd.close();}catch(IOException ignored){}}
        }
    }
}
