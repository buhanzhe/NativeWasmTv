package xiao.bu.tv;
import android.app.Instrumentation;
import android.os.Bundle;
import android.database.Cursor;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

/** Checks the same gallery writer used by the media controller against MediaStore. */
public final class ScreenshotGalleryInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            File input=new File("/sdcard/Download/ntv-gallery-test.png");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(FileInputStream in=new FileInputStream(input)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            byte[] png=out.toByteArray();Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,png.length);
            if(bitmap==null||bitmap.getWidth()!=640||bitmap.getHeight()!=360)throw new Exception("Invalid input PNG");bitmap.recycle();
            long started=System.currentTimeMillis();ScreenshotGallery.save(getTargetContext(),png);
            String found=null;
            for(int i=0;i<30&&found==null;i++){
                try(Cursor c=getTargetContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Images.Media._ID,MediaStore.Images.Media.DATA},
                        MediaStore.Images.Media.DATA+" LIKE ?",new String[]{"%/Pictures/nTv/nTv-%"},MediaStore.Images.Media._ID+" DESC")){
                    while(c!=null&&c.moveToNext()){
                        File f=new File(c.getString(1));if(f.lastModified()>=started-1000&&f.length()==png.length){found=f.getAbsolutePath();break;}
                    }
                }
                if(found==null)Thread.sleep(200);
            }
            if(found==null)throw new Exception("No saved image registered in MediaStore");
            result.putString("stream","PASS gallery PNG + MediaStore: "+found+" bytes="+png.length+"\n");finish(-1,result);
        }catch(Throwable e){result.putString("stream","FAIL "+e+"\n");finish(0,result);}
    }
}
