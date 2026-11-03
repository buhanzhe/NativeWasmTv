package xiao.bu.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.IOException;

/** Decode uploads off the UI thread, bounding decoded pixels independently of file size. */
final class QuickImage {
    static boolean isImageName(String name) {
        return QuickMediaFile.isImageName(name);
    }
    static int sampleSize(int width,int height) throws IOException {
        if(width<=0||height<=0)throw new ControlRequestException(400,"无法识别图片尺寸");
        for(int sample=1;;sample*=2){
            long w=(width+(long)sample-1)/sample,h=(height+(long)sample-1)/sample;
            if(w<=2048 && h<=2048 && w*h<=4L*1024*1024)return sample;
        }
    }
    static Bitmap decode(File file) throws IOException {
        try {
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(!"image/jpeg".equals(options.outMimeType)&&!"image/png".equals(options.outMimeType)
                    &&!"image/webp".equals(options.outMimeType)&&!"image/bmp".equals(options.outMimeType)
                    &&!"image/x-ms-bmp".equals(options.outMimeType))
                throw new ControlRequestException(400,"文件内容不是有效的 JPG、PNG、WebP 或 BMP 图片");
            options.inSampleSize=sampleSize(options.outWidth,options.outHeight);
            long pixelBudget=Math.min(4L*1024*1024,Runtime.getRuntime().maxMemory()/32);
            while(((options.outWidth+(long)options.inSampleSize-1)/options.inSampleSize)
                    *((options.outHeight+(long)options.inSampleSize-1)/options.inSampleSize)>pixelBudget)
                options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(bitmap==null)throw new ControlRequestException(400,"图片解码失败");
            return bitmap;
        } catch(OutOfMemoryError error) {
            throw new ControlRequestException(413,"设备内存不足，无法显示此图片");
        }
    }
    private QuickImage() { }
}
