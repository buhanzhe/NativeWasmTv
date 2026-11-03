package xiao.bu.tv;

import com.bu.cc.tv.NativeH5eDecryptor;
import java.io.*;
import java.util.*;

/** Run in a separate dalvikvm process; never loads over the app's installed plugin. */
public final class H5eStreamCheck {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static byte[] read(String path) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(path)) {
            byte[] block = new byte[16384];
            int count;
            while ((count = input.read(block)) >= 0) output.write(block, 0, count);
        }
        return output.toByteArray();
    }

    private static void checkPesHeaders(byte[] ts) {
        int videoPid = H5eFixturePid.findAvcVideoPid(ts);
        for (int i = 0; i + 188 <= ts.length; i += 188) {
            int pid = ((ts[i + 1] & 31) << 8) | (ts[i + 2] & 255);
            if (pid != videoPid || (ts[i + 1] & 64) == 0 || (ts[i + 3] & 16) == 0) continue;
            int offset = i + 4 + ((ts[i + 3] & 32) != 0 ? 1 + (ts[i + 4] & 255) : 0);
            check(offset + 9 <= i + 188 && ts[offset] == 0 && ts[offset + 1] == 0
                    && ts[offset + 2] == 1 && (ts[offset + 3] & 0xf0) == 0xe0,
                    "PUSI lost PES header at packet " + i / 188);
        }
    }

    private static void checkTransportMetadata(byte[] input, byte[] clear) {
        check(input.length == clear.length, "TS packet count changed");
        int videoPid = H5eFixturePid.findAvcVideoPid(input);
        for (int i = 0; i + 188 <= input.length; i += 188) {
            int pid = ((input[i + 1] & 31) << 8) | (input[i + 2] & 255);
            if (pid != videoPid) {
                for (int j = i; j < i + 188; j++)
                    check(input[j] == clear[j], "Audio/PSI/other PID changed at " + j);
                continue;
            }
            check(input[i] == clear[i] && input[i + 1] == clear[i + 1]
                    && input[i + 2] == clear[i + 2]
                    && (input[i + 3] & 0xcf) == (clear[i + 3] & 0xcf),
                    "TS identity/continuity changed at " + i);
            if ((input[i + 3] & 32) != 0) {
                int size = input[i + 4] & 255;
                check((clear[i + 3] & 32) != 0 && (clear[i + 4] & 255) >= size,
                        "Adaptation field shrank at " + i);
                for (int j = i + 5; j < i + 5 + size; j++)
                    check(input[j] == clear[j], "PCR/adaptation content changed at " + j);
            }
            if ((input[i + 1] & 64) != 0 && (input[i + 3] & 16) != 0) {
                int oldOffset = i + 4 + ((input[i + 3] & 32) != 0 ? 1 + (input[i + 4] & 255) : 0);
                int newOffset = i + 4 + ((clear[i + 3] & 32) != 0 ? 1 + (clear[i + 4] & 255) : 0);
                int size = 9 + (input[oldOffset + 8] & 255);
                for (int j = 0; j < size; j++) if (j != 4 && j != 5)
                    check(input[oldOffset + j] == clear[newOffset + j], "PES timestamps/header changed");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        check(NativeH5eDecryptor.supportsStreamingTransportStream(), "Streaming unavailable");
        for (int i = 1; i < args.length; i++) {
            byte[] input = read(args[i]);
            long begin = System.nanoTime();
            byte[] whole = NativeH5eDecryptor.decryptTransportStream(input.clone());
            long wholeMs = (System.nanoTime() - begin) / 1000000;
            check(whole != null, "Whole segment rejected: " + args[i]);
            checkPesHeaders(whole);
            checkTransportMetadata(input, whole);
            NativeH5eDecryptor.releaseThreadContext();
            for (final int fragment : new int[]{188, 37}) {
                final boolean[] first = {true};
                final int[] parts = {0};
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                InputStream fragmented = new ByteArrayInputStream(input) {
                    public synchronized int read(byte[] b, int offset, int count) {
                        return super.read(b, offset, Math.min(fragment, count));
                    }
                };
                begin = System.nanoTime();
                new CmgTsStream(new CmgTsStream.Decoder() {
                    public int videoPid(byte[] prefix) { return H5eFixturePid.findAvcVideoPid(prefix); }
                    public byte[] decode(byte[] packets, int pid) throws IOException {
                        byte[] clear = NativeH5eDecryptor.decryptTransportStreamPart(packets, first[0]);
                        first[0] = false;
                        parts[0]++;
                        if (clear == null) throw new IOException("Rejected part " + parts[0]);
                        return clear;
                    }
                }, 8 * 1024 * 1024, false).copy(fragmented, output::write);
                check(parts[0] > 2, "Expected multiple PES units");
                check(Arrays.equals(whole, output.toByteArray()), "Plaintext differs: " + args[i]);
                System.out.println(args[i] + " fragment=" + fragment + " parts=" + parts[0]
                        + " wholeMs=" + wholeMs + " streamMs=" + (System.nanoTime() - begin) / 1000000
                        + " bytes=" + whole.length + " exact=true");
                // Cancellation must reject subsequent chunks; a new first chunk can restart.
                NativeH5eDecryptor.cancelPendingDecrypts();
                check(NativeH5eDecryptor.decryptTransportStreamPart(new byte[188], false) == null,
                        "Cancelled stream accepted another chunk");
                NativeH5eDecryptor.releaseThreadContext();
                check(NativeH5eDecryptor.decryptTransportStreamPart(new byte[188], false) == null,
                        "Released stream accepted another chunk");
            }
            check(NativeH5eDecryptor.decryptTransportStreamPart(new byte[187], true) == null,
                    "Partial packet accepted");
            check(NativeH5eDecryptor.decryptTransportStreamPart(new byte[188], false) == null,
                    "Invalid stream remained active");
            check(NativeH5eDecryptor.decryptTransportStream(input.clone()) != null, "Legacy restart failed");
            check(NativeH5eDecryptor.decryptTransportStreamPart(new byte[188], false) == null,
                    "Legacy call did not invalidate stream");
            NativeH5eDecryptor.releaseThreadContext();
            try (OutputStream file = new FileOutputStream(args[i] + ".clear")) { file.write(whole); }
        }
        System.out.println("PASS H5E exact plaintext, fragmented input, cancellation, restart, legacy interop");
    }
}
