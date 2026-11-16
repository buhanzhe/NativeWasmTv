package xiao.bu.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import okhttp3.Dns;

/** Small UDP DNS client used by OkHttp; failures fall back to Android's resolver. */
final class PublicDns implements Dns {
    private static final int DNS_PORT = 53;
    private static final int TIMEOUT_MS = 1200;
    private static final int MAX_PACKET = 1500;

    private final String[] servers;
    private final int port;
    private final int timeoutMs;
    private final Dns fallback;
    private final Random random = new Random();
    private final ThreadLocal<Long> responseTtl=new ThreadLocal<Long>();
    long responseTtlSeconds(){Long value=responseTtl.get();return value==null?0:value;}
    final java.util.concurrent.atomic.AtomicLong queries=new java.util.concurrent.atomic.AtomicLong(), failures=new java.util.concurrent.atomic.AtomicLong(), fallbacks=new java.util.concurrent.atomic.AtomicLong();

    PublicDns(String mode) {
        this(mode,TIMEOUT_MS);
    }
    PublicDns(String mode,int timeoutMs) {this(mode,timeoutMs,Dns.SYSTEM);}
    PublicDns(String mode,int timeoutMs,Dns fallback) {
        port=DNS_PORT;
        this.fallback=fallback;
        this.timeoutMs=Math.max(100,Math.min(TIMEOUT_MS,timeoutMs));
        if ("tencent".equals(mode)) {
            servers = new String[] {"119.29.29.29", "182.254.116.116"};
        } else if ("114".equals(mode)) {
            servers = new String[] {"114.114.114.114", "114.114.115.115"};
        } else if ("baidu".equals(mode)) {
            servers = new String[] {"180.76.76.76"};
        } else {
            servers = new String[] {"223.5.5.5", "223.6.6.6"};
        }
    }
    PublicDns(String[] resolvers,int port){this(resolvers,port,Dns.SYSTEM);}
    PublicDns(String[] resolvers,int port,Dns fallback){this.servers=resolvers.clone();this.port=port;this.timeoutMs=TIMEOUT_MS;this.fallback=fallback;}

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        responseTtl.set(0L);
        if (hostname == null || hostname.length() == 0) {
            throw new UnknownHostException("hostname is empty");
        }
        // LAN control and media URLs frequently use literal addresses. Sending those to a
        // public resolver adds a timeout and can expose private addresses unnecessarily.
        if (hostname.indexOf(':') >= 0 || hostname.matches("[0-9.]+")) {
            return Collections.singletonList(InetAddress.getByName(hostname));
        }
        List<InetAddress> addresses = new ArrayList<InetAddress>();
        IOException lastError = null;
        for (String server : servers) {
            try {
                addresses.addAll(query(server, hostname, 1));
                // A usable IPv4 result must not wait for an unsupported/filtered
                // AAAA query. IPv6-only hosts still fall back to AAAA below.
                if (addresses.isEmpty()) addresses.addAll(query(server, hostname, 28));
                if (!addresses.isEmpty()) {
                    break;
                }
            } catch (IOException error) {
                failures.incrementAndGet();
                lastError = error;
            }
        }
        if (addresses.isEmpty()) {
            fallbacks.incrementAndGet();
            responseTtl.set(-1L);
            try {
                // KitKat's libc resolver may retain an unusable AAAA/NXDOMAIN
                // result after the network resolver changes. Use the device's
                // current A-first resolver before libc. Explicit resolver
                // instances already used by that adapter must not recurse.
                addresses.addAll(fallback.lookup(hostname));
            } catch (UnknownHostException systemError) {
                UnknownHostException result = new UnknownHostException(
                        "DNS lookup failed for " + hostname);
                result.initCause(lastError == null ? systemError : lastError);
                throw result;
            }
        }
        return Collections.unmodifiableList(addresses);
    }

    private List<InetAddress> query(String server, String hostname, int type)
            throws IOException {
        queries.incrementAndGet();
        String ascii = IDN.toASCII(hostname);
        int id = random.nextInt(65536);
        ByteArrayOutputStream output = new ByteArrayOutputStream(64);
        write16(output, id);
        write16(output, 0x0100);
        write16(output, 1);
        write16(output, 0);
        write16(output, 0);
        write16(output, 0);
        for (String label : ascii.split("\\.")) {
            byte[] bytes = label.getBytes("US-ASCII");
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IOException("Invalid DNS name");
            }
            output.write(bytes.length);
            output.write(bytes);
        }
        output.write(0);
        write16(output, type);
        write16(output, 1);
        byte[] request = output.toByteArray();
        byte[] response = new byte[MAX_PACKET];
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.connect(InetAddress.getByName(server), port);
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(request, request.length,
                    InetAddress.getByName(server), port));
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return parse(response, packet.getLength(), id);
        } finally {
            socket.close();
        }
    }

    private List<InetAddress> parse(byte[] data, int length, int id)
            throws IOException {
        if (length < 12 || read16(data, 0) != id || (read16(data, 2) & 0x8000) == 0
                || (read16(data, 2) & 0x020f) != 0) {
            throw new IOException("Invalid DNS response");
        }
        int questions = read16(data, 4);
        int answers = read16(data, 6);
        int offset = 12;
        for (int index = 0; index < questions; index++) {
            offset = skipName(data, length, offset);
            if (offset + 4 > length) throw new IOException("Truncated DNS question");
            offset += 4;
        }
        List<InetAddress> result = new ArrayList<InetAddress>();
        long ttl=Long.MAX_VALUE;
        for (int index = 0; index < answers; index++) {
            offset = skipName(data, length, offset);
            if (offset + 10 > length) throw new IOException("Truncated DNS answer");
            int type = read16(data, offset);
            if(type==1||type==28||type==5){long seconds=0;for(int j=4;j<8;j++)seconds=(seconds<<8)|(data[offset+j]&255L);ttl=Math.min(ttl,seconds);}
            int dataLength = read16(data, offset + 8);
            offset += 10;
            if (offset + dataLength > length) throw new IOException("Truncated DNS data");
            if ((type == 1 && dataLength == 4) || (type == 28 && dataLength == 16)) {
                byte[] address = new byte[dataLength];
                System.arraycopy(data, offset, address, 0, dataLength);
                result.add(InetAddress.getByAddress(address));
            }
            offset += dataLength;
        }
        responseTtl.set(result.isEmpty()||ttl==Long.MAX_VALUE?0:ttl);
        return result;
    }

    private static int skipName(byte[] data, int length, int offset) throws IOException {
        while (offset < length) {
            int size = data[offset] & 0xff;
            if (size == 0) return offset + 1;
            if ((size & 0xc0) == 0xc0) {
                if (offset + 1 >= length) throw new IOException("Truncated DNS pointer");
                return offset + 2;
            }
            if ((size & 0xc0) != 0 || offset + 1 + size > length) {
                throw new IOException("Invalid DNS name");
            }
            offset += 1 + size;
        }
        throw new IOException("Truncated DNS name");
    }

    private static int read16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static void write16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

}
