package xiao.bu.tv;

import android.content.Context;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.media.ExifInterface;
import android.os.Build;
import java.io.*;

/** Local content URI, or an owned temporary file received from an external browser. */
final class MultimediaInput {
    final Context context;
    final Uri uri;
    private final File temporary;
    MultimediaInput(Context context, Uri uri) { this.context=context.getApplicationContext(); this.uri=uri; temporary=null; }
    MultimediaInput(Context context, File file) { this.context=context.getApplicationContext(); uri=Uri.fromFile(file); temporary=file; }
    Bitmap decode(BitmapFactory.Options options) throws IOException {
        try(InputStream input=context.getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("无法读取所选文件");
            return BitmapFactory.decodeStream(input,null,options);
        }
    }
    void configure(MediaExtractor extractor) throws IOException { extractor.setDataSource(context,uri,null); }
    void configure(MediaMetadataRetriever retriever) { retriever.setDataSource(context,uri); }
    int orientation() {
        try {
            if("file".equals(uri.getScheme())) return new ExifInterface(uri.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
            if(Build.VERSION.SDK_INT>=24)try(InputStream input=context.getContentResolver().openInputStream(uri)) {
                return new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
            }
        } catch(Exception ignored) {}
        return 1;
    }
    void delete() { if(temporary!=null)temporary.delete(); }
}
