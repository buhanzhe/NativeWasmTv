package xiao.bu.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Preserves TS packet order and only transforms complete video PES units. */
final class CmgTsStream {
    interface Decoder {
        int videoPid(byte[] prefix);
        byte[] decode(byte[] packets, int videoPid) throws IOException;
    }
    interface Sink { void append(byte[] packets) throws IOException; }
    private final Decoder decoder;
    private final int maxUnitBytes;
    private final boolean normalizeContinuity;
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(64 * 1024);
    private int videoPid = -1, videoCounter = -1;
    private boolean videoStarted;
    CmgTsStream(Decoder decoder, int maxUnitBytes) { this(decoder, maxUnitBytes, true); }
    CmgTsStream(Decoder decoder, int maxUnitBytes, boolean normalizeContinuity) {
        this.decoder = decoder; this.maxUnitBytes = maxUnitBytes; this.normalizeContinuity = normalizeContinuity;
    }

    void copy(InputStream input, Sink sink) throws IOException {
        // Read what is available, never wait to fill a batch. Keep a partial
        // packet between reads and append contiguous packet runs at PES edges.
        byte[] packets = new byte[188 * 128]; int pending = 0, count;
        while ((count = input.read(packets, pending, packets.length - pending)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CMG stream cancelled");
            if (count == 0) continue;
            int total = pending + count, end = total - total % 188;
            int from = 0, unitLength = unit.size();
            for (int offset = 0; offset < end; offset += 188) {
                if (packets[offset] != 0x47) throw new IOException("Invalid CMG TS sync");
                int pid = ((packets[offset + 1] & 31) << 8) | (packets[offset + 2] & 255);
                int control = (packets[offset + 3] >> 4) & 3;
                if (control == 0) throw new IOException("Invalid CMG TS adaptation control");
                boolean start = (packets[offset + 1] & 64) != 0 && (control & 1) != 0;
                if (pid == videoPid && start) {
                    unit.write(packets, from, offset - from);
                    flush(sink); unitLength = 0; from = offset;
                    videoStarted = true;
                }
                int length = unitLength + offset + 188 - from;
                if (length > maxUnitBytes) throw new IOException("CMG PES exceeds limit");
                if (videoPid < 0 && start) {
                    unit.write(packets, from, offset + 188 - from);
                    unitLength = length; from = offset + 188;
                    videoPid = decoder.videoPid(unit.toByteArray());
                    if (videoPid >= 0) { flush(sink); unitLength = 0; }
                }
                if (videoPid < 0 && unitLength + offset + 188 - from > 256 * 1024)
                    throw new IOException("CMG video PMT missing");
            }
            unit.write(packets, from, end - from);
            pending = total - end;
            if (pending > 0) System.arraycopy(packets, end, packets, 0, pending);
        }
        if (pending != 0) throw new IOException("Truncated CMG TS packet");
        if (videoPid < 0 || !videoStarted) throw new IOException("CMG video PES missing");
        flush(sink);
    }
    private void flush(Sink sink) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CMG stream cancelled");
        if (unit.size() == 0) return;
        byte[] body = unit.toByteArray(); unit.reset();
        // No PES is split across these calls. Audio/PCR/PSI packets retain their order.
        int packetBytes = body.length;
        body = decoder.decode(body, videoPid);
        if (body == null || body.length != packetBytes)
            throw new IOException("TS transformer changed packet slots");
        for (int i = 0; normalizeContinuity && i + 188 <= body.length; i += 188) {
            int pid = ((body[i + 1] & 31) << 8) | (body[i + 2] & 255);
            if (pid != videoPid) continue;
            boolean payload = (body[i + 3] & 0x10) != 0;
            int cc = body[i + 3] & 15;
            if ((body[i + 3] & 0x20) != 0 && (body[i + 4] & 255) > 0 && (body[i + 5] & 0x80) != 0)
                videoCounter = -1;
            videoCounter = videoCounter < 0 ? cc : payload ? (videoCounter + 1) & 15 : videoCounter;
            body[i + 3] = (byte)((body[i + 3] & 0xf0) | videoCounter);
        }
        sink.append(body);
    }
}
