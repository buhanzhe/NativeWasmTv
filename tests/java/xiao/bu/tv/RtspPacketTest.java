package xiao.bu.tv;

import java.io.*;
import java.net.*;
import java.util.Arrays;

/** Wire-level round trip: reconstruct fragmented NALs and AAC access units. */
public final class RtspPacketTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void request(Socket socket, String method, String track, String transport)
            throws Exception {
        socket.getOutputStream().write((method + " rtsp://127.0.0.1/cast" + track
                + " RTSP/1.0\r\nCSeq: 1\r\n" + transport + "\r\n").getBytes("US-ASCII"));
        DataInputStream in = new DataInputStream(socket.getInputStream());
        check(in.readLine().contains("200"), "RTSP response");
        while (!in.readLine().isEmpty()) { }
    }
    private static byte[] packet(Socket socket, DatagramSocket udp, int channel) throws Exception {
        if (udp != null) {
            byte[] data = new byte[10000];
            DatagramPacket packet = new DatagramPacket(data, data.length);
            udp.receive(packet);
            return Arrays.copyOf(data, packet.getLength());
        }
        DataInputStream in = new DataInputStream(socket.getInputStream());
        check(in.readUnsignedByte() == '$' && in.readUnsignedByte() == channel, "interleaved header");
        byte[] data = new byte[in.readUnsignedShort()];
        in.readFully(data);
        return data;
    }
    private static void run(final boolean hevc, boolean udp) throws Exception {
        final RtspCastServer server = new RtspCastServer(true, 4000000, hevc ? "h265" : "h264", 30);
        server.start();
        try (Socket socket = new Socket("127.0.0.1", server.port());
                DatagramSocket video = new DatagramSocket();
                DatagramSocket audio = new DatagramSocket()) {
            socket.setSoTimeout(3000); video.setSoTimeout(3000); audio.setSoTimeout(3000);
            video.setReceiveBufferSize(256 * 1024);
            request(socket, "SETUP", "/trackID=0", "Transport: " + (udp
                    ? "RTP/AVP;unicast;client_port=" + video.getLocalPort() + "-" + (video.getLocalPort()+1)
                    : "RTP/AVP/TCP;unicast;interleaved=0-1") + "\r\n");
            request(socket, "SETUP", "/trackID=1", "Transport: " + (udp
                    ? "RTP/AVP;unicast;client_port=" + audio.getLocalPort() + "-" + (audio.getLocalPort()+1)
                    : "RTP/AVP/TCP;unicast;interleaved=2-3") + "\r\n");
            request(socket, "PLAY", "", "");
            for (int i=0; i<100 && !server.hasVideoClient(); i++) Thread.sleep(1);
            check(server.hasVideoClient(), "playing client");
            final byte[] nal = new byte[48000];
            Arrays.fill(nal, (byte) 73); nal[0] = (byte)(hevc ? 0x26 : 0x65);
            if (hevc) nal[1] = 1;
            final byte[] unit = new byte[nal.length+4]; unit[3]=1;
            System.arraycopy(nal,0,unit,4,nal.length);
            Thread writer = new Thread(new Runnable() { public void run() {
                server.sendVideo(unit,123456,1);
            }});
            writer.start();
            ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
            int previous = -1;
            for (;;) {
                byte[] p = packet(socket, udp ? video : null, 0);
                int seq = ((p[2]&255)<<8) | (p[3]&255);
                if (previous >= 0) check(seq == ((previous+1)&65535), "RTP sequence");
                previous=seq;
                int offset = hevc ? 15 : 14;
                int fu = p[hevc ? 14 : 13]&255;
                if ((fu&128)!=0) {
                    if (hevc) { rebuilt.write((p[12]&0x81)|((fu&63)<<1)); rebuilt.write(p[13]); }
                    else rebuilt.write((p[12]&0xe0)|(fu&31));
                }
                rebuilt.write(p,offset,p.length-offset);
                if ((p[1]&128)!=0) { check((fu&64)!=0,"last fragment"); break; }
            }
            writer.join(3000); check(!writer.isAlive(), "writer finished");
            check(Arrays.equals(nal,rebuilt.toByteArray()), "NAL byte-for-byte round trip");
            byte[] aac = new byte[500]; Arrays.fill(aac,(byte)91);
            server.sendAudio(aac,123456);
            byte[] p = packet(socket,udp ? audio : null,2);
            check((p[1]&127)==97 && p[12]==0 && p[13]==16, "AAC RTP/AU header");
            check((((p[14]&255)<<5)|((p[15]&255)>>3))==aac.length,"AAC AU size");
            check(Arrays.equals(aac,Arrays.copyOfRange(p,16,p.length)),"AAC payload");
        } finally { server.close(); }
    }
    public static void main(String[] args) throws Exception {
        run(false,false); run(true,false); run(false,true); run(true,true);
        System.out.println("RtspPacketTest passed: H264/H265/AAC over TCP and UDP");
    }
}
