package xiao.bu.tv;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class ScreenshotGallery {
    static void save(Context context, byte[] png) throws IOException {
        String name = "nTv-" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/nTv");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法创建相册图片");
            try {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法写入相册");
                    out.write(png);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
            } catch (Exception error) {
                context.getContentResolver().delete(uri, null, null);
                throw new IOException("保存相册失败", error);
            }
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "nTv");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建截图目录");
            File file = new File(directory, name);
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(png);
            } catch (IOException error) {
                file.delete();
                throw error;
            }
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()},
                    new String[]{"image/png"}, null);
        }
    }
}
