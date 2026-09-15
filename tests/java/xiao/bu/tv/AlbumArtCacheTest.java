package xiao.bu.tv;

import java.io.File;
import java.nio.file.Files;

public final class AlbumArtCacheTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static long bytes(File directory) {
        long size = 0;
        for (File file : directory.listFiles()) size += file.length();
        return size;
    }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("ntv-cover-fifo-").toFile();
        String a = AlbumArtCache.key("A"), b = AlbumArtCache.key("B"), c = AlbumArtCache.key("C"), d = AlbumArtCache.key("D");
        byte[] image = new byte[9000];
        try {
            try (AlbumArtCache cache = new AlbumArtCache(directory, 32768)) {
                cache.put(a, image); cache.put(b, image); cache.put(c, image);
                check(cache.get(a) != null, "A missing before eviction");
                cache.put(d, image);
                check(cache.get(a) == null && cache.get(b) != null && cache.get(d) != null,
                        "Read promoted A: cache is LRU instead of FIFO");
                check(bytes(directory) <= 32768, "Directory exceeds configured limit");
            }
            try (AlbumArtCache cache = new AlbumArtCache(directory, 32768)) {
                check(cache.get(b) != null, "Cache did not survive reopen");
                cache.put(a, image);
                check(cache.get(b) == null && cache.get(c) != null, "FIFO order lost on reopen");
            }
        } finally { for (File file : directory.listFiles()) file.delete(); directory.delete(); }

        directory = Files.createTempDirectory("ntv-cover-20mb-").toFile();
        try (AlbumArtCache cache = new AlbumArtCache(directory, AlbumArtCache.MAX_BYTES)) {
            byte[] large = new byte[1536 * 1024];
            for (int i = 0; i < 18; i++) {
                cache.put(AlbumArtCache.key("cover" + i), large);
                cache.get(AlbumArtCache.key("cover0"));
                check(bytes(directory) <= AlbumArtCache.MAX_BYTES, "20 MB directory limit exceeded");
            }
            check(cache.get(AlbumArtCache.key("cover0")) == null, "Oldest large cover retained");
            check(cache.get(AlbumArtCache.key("cover17")) != null, "Newest cover evicted");
        } finally { for (File file : directory.listFiles()) file.delete(); directory.delete(); }
        System.out.println("PASS FIFO despite cache hits, persisted order, 20 MB including journal");
    }
}
