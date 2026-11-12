package xiao.bu.tv;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Bounded live HLS-to-TS transport. Compressed packets are copied, never decoded. */
final class LegacyTsStream {
    static java.util.Map<String,String> playerHeaders() {
        // Bound the vendor HTTP prefetch allocation; this does not bypass its
        // separate elementary-stream Prefetcher (unsupported on some API15 ROMs).
        // Stagefright consumes this private header locally (KiB/KiB/seconds).
        return java.util.Collections.singletonMap("x-cache-config","64/256/5");
    }
    static final class UnsupportedMediaException extends IOException {
        UnsupportedMediaException(String message){super(message);}
    }
    interface Source {
        String playlist(String url) throws IOException;
        byte[] segment(String url) throws IOException;
        default boolean streamFirstSegment(String url,OutputStream output)throws IOException { return false; }
        boolean active();
        long now();
        void waitForUpdate() throws IOException;
    }
    static final class Segment {
        final long sequence; final String url;
        Segment(long sequence, String url) { this.sequence=sequence;this.url=url; }
    }
    static final class Playlist {
        final String url; final List<Segment> segments;
        Playlist(String url,List<Segment> segments){this.url=url;this.segments=segments;}
    }
    static Playlist media(Source source,String url) throws IOException {
        for(int depth=0;depth<4;depth++) {
            String body=source.playlist(url);
            if(!body.startsWith("#EXTM3U") || body.length()>2*1024*1024)
                throw new IOException("Invalid legacy live playlist");
            if(body.contains("#EXT-X-ENDLIST")||body.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
                throw new UnsupportedMediaException("Legacy TS transport requires live media");
            if(body.contains("#EXT-X-MAP:")||body.contains("#EXT-X-BYTERANGE:"))
                throw new UnsupportedMediaException("Legacy TS transport does not support fragmented MP4/byte ranges");
            boolean master=body.contains("#EXT-X-STREAM-INF:");
            List<Segment> entries=new ArrayList<>();long sequence=0;
            try {
                for(String raw:body.split("\\r?\\n")) {
                    String line=raw.trim();
                    if(line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))sequence=Long.parseLong(line.substring(22));
                    if(line.isEmpty()||line.charAt(0)=='#')continue;
                    if(entries.size()>=256)throw new IOException("Legacy live playlist too large");
                    entries.add(new Segment(sequence++,new URI(url).resolve(line).toString()));
                    if(master)break; // Proxy already selected the requested rendition.
                }
            } catch(IllegalArgumentException|java.net.URISyntaxException error) {
                throw new IOException("Invalid legacy live resource",error);
            }
            if(entries.isEmpty())throw new IOException("Empty legacy live playlist");
            if(!master)return new Playlist(url,entries);
            url=entries.get(0).url;
        }
        throw new IOException("Legacy live playlist redirect limit");
    }
    static void pipe(Source source,String url,OutputStream output) throws IOException {
        long last=-1,lastProgress=source.now();
        while(source.active()) {
            Playlist playlist=media(source,url);url=playlist.url;
            for(Segment segment:playlist.segments) {
                if(!source.active())return;
                if(segment.sequence<=last)continue;
                if(last<0 && source.streamFirstSegment(segment.url,output)) {
                    output.flush();last=segment.sequence;lastProgress=source.now();continue;
                }
                byte[] body=readSegment(source,segment.url);
                // Do not send ciphertext, MP4 headers or an HTTP error as video.
                if(body==null||body.length<188*3||body.length%188!=0)
                    throw new IOException("Invalid legacy TS segment length");
                for(int i=0;i<Math.min(8,body.length/188);i++)
                    if(body[i*188]!=0x47)throw new IOException("Invalid legacy TS packet sync");
                for(int offset=0;offset<body.length;offset+=16384) {
                    if(!source.active())return;
                    output.write(body,offset,Math.min(16384,body.length-offset));
                }
                output.flush();last=segment.sequence;lastProgress=source.now();
            }
            if(source.now()-lastProgress>=30000)throw new IOException("Legacy live playlist stopped advancing");
            source.waitForUpdate();
        }
    }
    private static byte[] readSegment(Source source,String url)throws IOException {
        for(int attempt=0;;attempt++)try{return source.segment(url);}
        catch(IOException error){
            boolean transientFailure=false;
            Throwable cause=error;
            for(int depth=0;cause!=null&&depth<12;depth++,cause=cause.getCause())
                if(cause instanceof java.io.EOFException||cause instanceof java.net.SocketException
                        ||cause instanceof java.net.SocketTimeoutException)transientFailure=true;
            if(attempt>=2||!transientFailure||!source.active()||Thread.currentThread().isInterrupted())throw error;
            // Nothing from an incomplete segment has reached the decoder yet.
            source.waitForUpdate();
        }
    }
}
