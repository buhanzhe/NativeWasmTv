package xiao.bu.tv;

import java.io.ByteArrayOutputStream;

/** Android TrueHD outputs consume batches of sync frames, not individual tiny packets. */
final class TrueHdFrames {
    private final ByteArrayOutputStream data = new ByteArrayOutputStream(32768);
    private int count;
    private boolean synchronizedFrame;

    boolean add(byte[] packet, int length) {
        if (length < 0 || length > packet.length || data.size() + length > 262144)
            throw new IllegalArgumentException("Invalid TrueHD frame size");
        if (!synchronizedFrame) {
            if (length < 10 || (packet[4] & 255) != 0xf8 || (packet[5] & 255) != 0x72
                    || (packet[6] & 255) != 0x6f || (packet[7] & 0xfe) != 0xba) return false;
            synchronizedFrame = true;
        }
        data.write(packet, 0, length);
        count++;
        return count >= 16;
    }

    boolean hasPending() { return count > 0; }

    byte[] take() {
        byte[] result = data.toByteArray();
        data.reset();
        count = 0;
        return result;
    }

    void reset() {
        data.reset();
        count = 0;
        synchronizedFrame = false;
    }
}
