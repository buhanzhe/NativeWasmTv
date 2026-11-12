package xiao.bu.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Preserves TS packet order and only transforms complete video PES units. */
final class ReferenceCmgTsStream {
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
    ReferenceCmgTsStream(Decoder decoder, int maxUnitBytes) { this(decoder, maxUnitBytes, true); }
    ReferenceCmgTsStream(Decoder decoder, int maxUnitBytes, boolean normalizeContinuity) {
        this.decoder = decoder; this.maxUnitBytes = maxUnitBytes; this.normalizeContinuity = normalizeContinuity;
    }

    void copy(InputStream input, Sink sink) throws IOException {
        byte[] packet = new byte[188]; int position = 0, count;
        while ((count = input.read(packet, position, packet.length - position)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("CMG stream cancelled");
            if (count == 0) continue;
            position += count;
            if (position < packet.length) continue;
            position = 0;
            if (packet[0] != 0x47) throw new IOException("Invalid CMG TS sync");
            int pid = ((packet[1] & 31) << 8) | (packet[2] & 255);
            int control = (packet[3] >> 4) & 3;
            if (control == 0) throw new IOException("Invalid CMG TS adaptation control");
            boolean start = (packet[1] & 64) != 0 && (control & 1) != 0;
            if (pid == videoPid && start) {
                flush(sink);
                videoStarted = true;
            }
            unit.write(packet, 0, packet.length);
            if (unit.size() > maxUnitBytes) throw new IOException("CMG PES exceeds limit");
            if (videoPid < 0 && start) {
                videoPid = decoder.videoPid(unit.toByteArray());
                if (videoPid >= 0) flush(sink); // PAT/PMT can reach demux before the first frame.
            }
            if (videoPid < 0 && unit.size() > 256 * 1024) throw new IOException("CMG video PMT missing");
        }
        if (position != 0) throw new IOException("Truncated CMG TS packet");
        if (videoPid < 0 || !videoStarted) throw new IOException("CMG video PES missing");
        flush(sink);
    }
    private void flush(Sink sink) throws IOException {
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
