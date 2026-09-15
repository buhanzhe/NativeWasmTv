package xiao.bu.tv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class Id3ArtworkTest {
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
    private static byte[] frame(int version, int type, boolean unicode, byte[] picture) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(unicode ? 1 : 0);
        payload.write((version == 2 ? "PNG" : "image/png\0").getBytes("ISO-8859-1"));
        payload.write(type);
        payload.write(unicode ? new byte[] {(byte) 0xff, (byte) 0xfe, 'A', 0, 0, 0} : new byte[] {0});
        payload.write(picture);
        byte[] data = payload.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write((version == 2 ? "PIC" : "APIC").getBytes("ISO-8859-1"));
        if (version == 2) { frame.write(data.length >> 16); frame.write(data.length >> 8); frame.write(data.length); }
        else { frame.write(length(data.length, version == 4)); frame.write(new byte[2]); }
        frame.write(data);
        return frame.toByteArray();
    }
    private static byte[] length(int size, boolean sync) {
        int bits = sync ? 7 : 8, mask = sync ? 127 : 255;
        return new byte[] {(byte) ((size >> (3 * bits)) & mask), (byte) ((size >> (2 * bits)) & mask),
                (byte) ((size >> bits) & mask), (byte) (size & mask)};
    }
    private static byte[] tag(int version, byte[]... frames) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] frame : frames) body.write(frame);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {'I','D','3',(byte) version,0,0});
        out.write(length(body.size(), true)); out.write(body.toByteArray()); return out.toByteArray();
    }
    private static byte[] read(byte[] value) throws Exception { return Id3Artwork.read(new ByteArrayInputStream(value)); }
    public static void main(String[] args) throws Exception {
        byte[] art = new byte[200]; Arrays.fill(art, (byte) 37);
        for (int version = 2; version <= 4; version++) {
            check(Arrays.equals(art, read(tag(version, frame(version, 3, false, art)))), "ID3v2." + version);
            check(Arrays.equals(art, read(tag(version, frame(version, 3, true, art)))), "UTF16 description " + version);
            check(Arrays.equals(art, read(tag(version, frame(version, 4, false, new byte[] {1}),
                    frame(version, 3, false, art)))), "Front-cover preference " + version);
        }
        check(read(new byte[10]) == null, "Live/no ID3");
        byte[] good = tag(3, frame(3, 3, false, art));
        check(read(Arrays.copyOf(good, good.length - 1)) == null, "Truncated tag");
        byte[] huge = {'I','D','3',3,0,0,127,127,127,127};
        check(read(huge) == null, "Bounded allocation");
        huge[6] = (byte) 255; check(read(huge) == null, "Invalid sync-safe size");
        System.out.println("PASS ID3v2.2/2.3/2.4, UTF16, front-cover selection, missing/truncated/oversized tags");
    }
}
