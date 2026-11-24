package xiao.bu.tv;

import android.graphics.*;
import java.io.*;

/** Generated RGBA reflections, softly masked into two corners rather than an opaque frame. */
final class LiquidGlassTexture {
    private final Bitmap bitmap;
    private final Rect content=new Rect();
    private final String kind;
    private static final class Pass { final Paint paint;final RectF bounds;Pass(Paint paint,RectF bounds){this.paint=paint;this.bounds=bounds;} }
    private final android.util.LruCache<String,Pass[]> reflections=new android.util.LruCache<>(24);
    private LiquidGlassTexture(Bitmap bitmap,String kind){
        this.bitmap=bitmap;this.kind=kind;
        int width=bitmap.getWidth(),height=bitmap.getHeight();int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
        int left=width,top=height,right=0,bottom=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((pixels[y*width+x]>>>24)>=96){left=Math.min(left,x);right=Math.max(right,x+1);top=Math.min(top,y);bottom=Math.max(bottom,y+1);}
        if(right<=left||bottom<=top)content.set(0,0,width,height);else content.set(left,top,right,bottom);
    }
    static LiquidGlassTexture load(android.content.Context context,String name,int edge){
        String path="glass/liquid-"+name+"-v1.png";
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream in=context.getAssets().open(path)){if(in==null)return null;BitmapFactory.decodeStream(in,null,options);}
            options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/(options.inSampleSize*2)>=edge)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            try(InputStream in=context.getAssets().open(path)){Bitmap bitmap=BitmapFactory.decodeStream(in,null,options);return bitmap==null?null:new LiquidGlassTexture(bitmap,name);}
        }catch(IOException|RuntimeException unavailable){return null;}
    }
    void draw(Canvas canvas,RectF bounds,float radius,int alpha){
        String key=bounds.toString()+":"+radius;Pass[] passes=reflections.get(key);
        if(passes==null){
            boolean capsule=kind.equals("capsule"),player=kind.equals("player");
            float cap=Math.max(1,Math.min(content.width(),content.height())*(capsule?.49f:player?.48f:.24f));
            float corner=Math.min(Math.min(bounds.width(),bounds.height())*.49f,radius*(capsule?1f:player?1.3f:1.5f));
            passes=new Pass[2];
            for(int i=0;i<2;i++){
                RectF crop=i==0?new RectF(content.left,content.top,content.left+cap,content.top+cap):new RectF(content.right-cap,content.bottom-cap,content.right,content.bottom);
                RectF target=i==0?new RectF(bounds.left,bounds.top,bounds.left+corner,bounds.top+corner):new RectF(bounds.right-corner,bounds.bottom-corner,bounds.right,bounds.bottom);
                Matrix transform=new Matrix();transform.setRectToRect(crop,target,Matrix.ScaleToFit.FILL);
                BitmapShader image=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);image.setLocalMatrix(transform);
                float centerX=i==0?target.left+corner*.28f:target.right-corner*.28f,centerY=i==0?target.top+corner*.28f:target.bottom-corner*.28f;
                Shader fade=new RadialGradient(centerX,centerY,Math.max(1,corner*.65f),new int[]{i==0?0x75ffffff:0x48ffffff,0x2cffffff,0x00ffffff},new float[]{0,.30f,1},Shader.TileMode.CLAMP);
                Paint pass=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);pass.setShader(new ComposeShader(image,fade,PorterDuff.Mode.DST_IN));passes[i]=new Pass(pass,target);
            }
            reflections.put(key,passes);
        }
        for(Pass pass:passes){pass.paint.setAlpha(alpha);canvas.drawRect(pass.bounds,pass.paint);}
    }
}
