package xiao.bu.tv;

import android.os.Debug;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** Device-local test of the actual sender packetizer. No screen/audio permission. */
final class CastTransportProbe {
    static JSONObject run(int mbps) throws Exception {
        RtspCastServer server;
        try { server=RtspCastServer.class.getDeclaredConstructor(boolean.class,int.class)
                .newInstance(false,mbps*1000000); }
        catch(NoSuchMethodException baseline){ server=new RtspCastServer(false); }
        server.start();
        final AtomicLong bytes=new AtomicLong(),packets=new AtomicLong(),frames=new AtomicLong();
        Thread reader=null;
        try(final Socket socket=new Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(3000);
            command(socket,"SETUP","Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            command(socket,"PLAY","");
            reader=new Thread(()->{try {
                DataInputStream in=new DataInputStream(socket.getInputStream());
                while(in.readUnsignedByte()=='$') {
                    in.readUnsignedByte();int size=in.readUnsignedShort();
                    byte[] data=new byte[size];in.readFully(data);
                    bytes.addAndGet(size+4);packets.incrementAndGet();
                    if((data[1]&128)!=0)frames.incrementAndGet();
                }
            }catch(Exception ignored){}});reader.start();
            byte[] frame=new byte[mbps*1000000/8/30];Arrays.fill(frame,(byte)0x55);
            frame[0]=frame[1]=frame[2]=0;frame[3]=1;
            long cpu=Debug.threadCpuTimeNanos(),start=System.nanoTime();
            ArrayList<Double> times=new ArrayList<Double>();
            for(int i=0;i<120;i++) {
                frame[4]=(byte)(i%30==0?0x65:0x41);
                long now=System.nanoTime();server.sendVideo(frame,i*33333L,i%30==0?1:0);
                times.add((System.nanoTime()-now)/1e6);
                long wait=start+(i+1)*33333333L-System.nanoTime();
                if(wait>0)Thread.sleep(wait/1000000,(int)(wait%1000000));
            }
            cpu=Debug.threadCpuTimeNanos()-cpu;
            long end=System.nanoTime(),deadline=end+2_000_000_000L;
            while(frames.get()<120&&System.nanoTime()<deadline)Thread.sleep(10);
            Collections.sort(times);
            return new JSONObject().put("mbps",mbps).put("submittedFrames",120)
                    .put("receivedFrames",frames.get()).put("receivedPackets",packets.get())
                    .put("receivedBytes",bytes.get()).put("elapsedMs",(end-start)/1e6)
                    .put("senderThreadCpuMs",cpu/1e6).put("writeP50Ms",times.get(60))
                    .put("writeP95Ms",times.get(114)).put("writeMaxMs",times.get(119));
        }finally{server.close();if(reader!=null)reader.join(3000);}
    }
    static void command(Socket s,String command,String extra)throws IOException {
        s.getOutputStream().write((command+" rtsp://127.0.0.1/cast/trackID=0 RTSP/1.0\r\nCSeq: 1\r\n"+extra+"\r\n").getBytes("US-ASCII"));
        if(!line(s.getInputStream()).contains("200"))throw new IOException("RTSP handshake failed");
        while(!line(s.getInputStream()).isEmpty()){}
    }
    static String line(InputStream in)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1&&c!='\n')if(c!='\r')b.write(c);
        if(c<0)throw new EOFException();return b.toString("US-ASCII");
    }
}
