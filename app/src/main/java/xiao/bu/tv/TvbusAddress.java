package xiao.bu.tv;

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Address decoding only; broker authentication messages are never rewritten. */
final class TvbusAddress {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    static boolean matches(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("tvbus://") || lower.startsWith("sop://");
    }
    static String decode(String value) throws IOException {
        String text = value.trim();
        if (!text.regionMatches(true, 0, "tvbus://", 0, 8)) return text;
        text = text.substring(8);
        if (text.isEmpty() || text.length() > 4096) throw new IOException("TVBus 地址长度无效");
        try {
            BigInteger number = BigInteger.ZERO;
            for (char ch : text.toCharArray()) {
                int digit = ALPHABET.indexOf(ch);
                if (digit < 0) throw new IOException("TVBus 地址编码无效");
                number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
            }
            byte[] bytes = number.signum() == 0 ? new byte[0] : number.toByteArray();
            if (bytes.length > 1 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
            int leading = 0;
            while (leading < text.length() && text.charAt(leading) == '1') leading++;
            byte[] raw = new byte[leading + bytes.length];
            System.arraycopy(bytes, 0, raw, leading, bytes.length);
            if (raw.length <= 7) throw new IOException("TVBus 地址过短");
            if ((raw.length - 4) % 8 != 0) raw = Arrays.copyOfRange(raw, 1, raw.length);
            byte[] seed = new byte[4]; int[] positions = {2, 3, 6, 8};
            for (int i = 0; i < positions.length; i++) {
                int position = positions[i]; seed[i] = raw[position];
                byte[] next = new byte[raw.length - 1];
                System.arraycopy(raw, 0, next, 0, position);
                System.arraycopy(raw, position + 1, next, position, raw.length - position - 1);
                raw = next;
            }
            if (raw.length == 0 || raw.length % 8 != 0) throw new IOException("TVBus 地址块长度无效");
            byte[] key = new byte[8]; int[] targets = {6, 4, 2, 0};
            for (int i = 0; i < 4; i++) {
                String hex = String.format(Locale.US, "%02x", seed[i] & 255);
                key[targets[i]] = (byte) hex.charAt(1); key[targets[i] + 1] = (byte) hex.charAt(0);
            }
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "DES"));
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            for (int offset = 0; offset < raw.length; offset += 8) {
                byte[] block = cipher.doFinal(raw, offset, 8); int length = 0;
                while (length < block.length && block[length] != 0) length++;
                plain.write(block, 0, length);
            }
            String result = new String(plain.toByteArray(), "UTF-8");
            if (!result.startsWith("sop://")) throw new IOException("TVBus 地址内容无效");
            return result;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("TVBus 地址解析失败", error); }
    }
    static final class Route {
        final String broker, path;
        Route(String broker, String path) { this.broker = broker; this.path = path; }
    }
    static Route route(String input, String providedBroker) throws IOException {
        try {
            URI uri = new URI(decode(input));
            String host = uri.getHost();
            if (!"sop".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || uri.getFragment() != null || uri.getPort()>65535 || uri.getRawPath() == null || uri.getRawPath().isEmpty())
                throw new IOException("TVBus/SOP 地址无效");
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (uri.getRawPath().lastIndexOf('@') > uri.getRawPath().lastIndexOf('/'))
                throw new IOException("此 TVBus 源需要提供方的应用鉴权环境");
            boolean local = "localhost".equalsIgnoreCase(host) || host.equals("::1") || host.equals("[::1]") || host.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");
            String broker = providedBroker == null ? "" : providedBroker.trim();
            if (broker.isEmpty()) {
                int port = uri.getPort();
                if (local) {
                    // Exported lists can legitimately refer to a helper still running
                    // on this device. Reuse only that exact reachable loopback port;
                    // never substitute an unrelated remote broker or channel number.
                    if (port <= 0) throw new IOException("TVBus 本机源缺少服务端口");
                    try (java.net.Socket probe = new java.net.Socket()) {
                        probe.connect(new java.net.InetSocketAddress(host, port), 500);
                    } catch (IOException absent) {
                        throw new IOException("该列表依赖本机 " + port + " 服务，当前未运行且缺少上游 broker");
                    }
                }
                broker = new URI(port == 443 ? "https" : "http", null, host, port, null, null, null).toString();
            }
            TvbusLiveConfig.online(broker);
            URI base = new URI(broker);
            if (base.getRawUserInfo() != null || base.getRawQuery() != null || base.getFragment() != null)
                throw new IOException("TVBus broker 地址无效");
            while (broker.endsWith("/")) broker = broker.substring(0, broker.length() - 1);
            return new Route(broker, path);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("TVBus/SOP 地址无效", error); }
    }
}
