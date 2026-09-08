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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import okhttp3.Dns;

/** Small UDP DNS client used by OkHttp; failures fall back to Android's resolver. */
final class PublicDns implements Dns {
    private static final int DNS_PORT = 53;
    private static final int TIMEOUT_MS = 1200;
    private static final int MAX_PACKET = 1500;
    private static final long CACHE_MS = 120000L;
    private static final int MAX_CACHE = 128;

    private final String[] servers;
    private final Random random = new Random();
    private final Map<String, CacheEntry> cache =
            new LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    PublicDns(String mode) {
        if (NetworkClient.DNS_TENCENT.equals(mode)) {
            servers = new String[] {"119.29.29.29", "182.254.116.116"};
        } else if (NetworkClient.DNS_114.equals(mode)) {
            servers = new String[] {"114.114.114.114", "114.114.115.115"};
        } else if (NetworkClient.DNS_BAIDU.equals(mode)) {
            servers = new String[] {"180.76.76.76"};
        } else {
            servers = new String[] {"223.5.5.5", "223.6.6.6"};
        }
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (hostname == null || hostname.length() == 0) {
            throw new UnknownHostException("hostname is empty");
        }
        // LAN control and media URLs frequently use literal addresses. Sending those to a
        // public resolver adds a timeout and can expose private addresses unnecessarily.
        if (hostname.indexOf(':') >= 0 || hostname.matches("[0-9.]+")) {
            return Collections.singletonList(InetAddress.getByName(hostname));
        }
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CacheEntry hit = cache.get(hostname);
            if (hit != null && hit.expiresAt > now) {
                return hit.addresses;
            }
        }
        List<InetAddress> addresses = new ArrayList<InetAddress>();
        IOException lastError = null;
        for (String server : servers) {
            try {
                addresses.addAll(query(server, hostname, 1));
                addresses.addAll(query(server, hostname, 28));
                if (!addresses.isEmpty()) {
                    break;
                }
            } catch (IOException error) {
                lastError = error;
            }
        }
        if (addresses.isEmpty()) {
            try {
                addresses.addAll(Dns.SYSTEM.lookup(hostname));
            } catch (UnknownHostException systemError) {
                UnknownHostException result = new UnknownHostException(
                        "DNS lookup failed for " + hostname);
                result.initCause(lastError == null ? systemError : lastError);
                throw result;
            }
        }
        List<InetAddress> immutable = Collections.unmodifiableList(addresses);
        synchronized (cache) {
            cache.put(hostname, new CacheEntry(immutable, now + CACHE_MS));
        }
        return immutable;
    }

    private List<InetAddress> query(String server, String hostname, int type)
            throws IOException {
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
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(request, request.length,
                    InetAddress.getByName(server), DNS_PORT));
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return parse(response, packet.getLength(), id);
        } finally {
            socket.close();
        }
    }

    private static List<InetAddress> parse(byte[] data, int length, int id)
            throws IOException {
        if (length < 12 || read16(data, 0) != id || (read16(data, 2) & 0x8000) == 0
                || (read16(data, 2) & 0x000f) != 0) {
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
        for (int index = 0; index < answers; index++) {
            offset = skipName(data, length, offset);
            if (offset + 10 > length) throw new IOException("Truncated DNS answer");
            int type = read16(data, offset);
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

    private static final class CacheEntry {
        final List<InetAddress> addresses;
        final long expiresAt;

        CacheEntry(List<InetAddress> addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }
}
