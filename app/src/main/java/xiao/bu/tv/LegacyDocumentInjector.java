package xiao.bu.tv;

import java.io.*;
import java.nio.charset.Charset;
import java.util.regex.*;

/** Insert before site scripts, preserving doctype and streaming the rest unchanged. */
final class LegacyDocumentInjector {
    private static final Pattern HEAD = Pattern.compile("(?i)<head(?:\\s[^>]*|)>");
    private static final Pattern SCRIPT = Pattern.compile("(?i)<script(?:\\s|>)");
    private static final Pattern DOCTYPE = Pattern.compile("(?is)<!doctype[^>]*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|\\z)");
    static InputStream prepend(InputStream body, Charset charset, String prelude) throws IOException {
        if (prelude == null || prelude.isEmpty() || charset.name().startsWith("UTF-16")) return body;
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();byte[] block = new byte[1024];int offset = -1;
        while (prefix.size() < 16384) {
            int n = body.read(block, 0, Math.min(block.length, 16384 - prefix.size()));
            if (n < 0) break;
            prefix.write(block, 0, n);
            // ASCII-compatible HTML markup maps one byte to one character here;
            // original UTF-8/GBK bytes are never decoded and re-encoded.
            String text = visibleMarkup(prefix.toString("ISO-8859-1"));
            Matcher head = HEAD.matcher(text),script = SCRIPT.matcher(text);
            boolean hasHead=head.find(),hasScript=script.find();
            if(hasScript&&(!hasHead||script.start()<head.start())){offset=script.start();break;}
            if(hasHead){offset=head.end();break;}
        }
        byte[] original = prefix.toByteArray();
        if (offset < 0) {
            Matcher type = DOCTYPE.matcher(visibleMarkup(new String(original, "ISO-8859-1")));
            offset = type.find() ? type.end() : (original.length>=3&&original[0]==(byte)0xef&&original[1]==(byte)0xbb&&original[2]==(byte)0xbf?3:0);
        }
        byte[] injection = prelude.getBytes(charset);
        return new SequenceInputStream(new ByteArrayInputStream(original, 0, offset),
                new SequenceInputStream(new ByteArrayInputStream(injection),
                        new SequenceInputStream(new ByteArrayInputStream(original, offset, original.length-offset), body)));
    }
    private static String visibleMarkup(String text){
        Matcher comments=COMMENT.matcher(text);StringBuilder masked=new StringBuilder(text);
        while(comments.find())for(int i=comments.start();i<comments.end();i++)masked.setCharAt(i,' ');
        return masked.toString();
    }
    private LegacyDocumentInjector() { }
}
