"""Check production screenshot detection against corrupt and valid image samples."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/xiao/bu/tv/VideoScreenshot.java').read_text(encoding='utf8')
methods = source[source.index('    static boolean needsSegmentCache()'):source.index('    interface Source {')]
java = r'''
public class VideoScreenshotCheck {
    static class Build {
        static String HARDWARE;
        static class VERSION { static int SDK_INT; }
    }
    static class Bitmap {
        final int width, height; final java.util.function.IntBinaryOperator pixel;
        Bitmap(int w, int h, java.util.function.IntBinaryOperator p) { width=w; height=h; pixel=p; }
        int getWidth() { return width; }
        int getHeight() { return height; }
        int getPixel(int x,int y) { return pixel.applyAsInt(x,y); }
    }
    METHODS
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        Build.HARDWARE="amlogic"; Build.VERSION.SDK_INT=28;
        check(needsSegmentCache(),"Android 9 Amlogic needs a fallback source");
        Build.VERSION.SDK_INT=29;
        check(!needsSegmentCache(),"Do not expand the device policy to newer Amlogic builds");
        Build.HARDWARE="other"; Build.VERSION.SDK_INT=28;
        check(!needsSegmentCache(),"Other PixelCopy devices retain existing memory policy");
        Build.VERSION.SDK_INT=19;
        check(needsSegmentCache(),"Legacy capture still has its source");
        for(int[] size:new int[][]{{640,360},{1920,1080},{1,1}}) {
            check(isGreenPlaceholder(new Bitmap(size[0],size[1],(x,y)->0xff008700)),"Empty YUV green");
            check(!isGreenPlaceholder(new Bitmap(size[0],size[1],(x,y)->0xff000000)),"Valid black");
            check(!isGreenPlaceholder(new Bitmap(size[0],size[1],(x,y)->0xff00ff00)),"Valid green title card");
        }
        check(isGreenPlaceholder(new Bitmap(1920,1080,(x,y)->y<4?0xff00ff00:0xff008700)),"Thin vendor edge");
        check(!isGreenPlaceholder(new Bitmap(640,360,(x,y)->0xff000000|((90+y%130)<<8))),"Detailed green scene");
        check(!isGreenPlaceholder(new Bitmap(640,360,(x,y)->x<100?0xffffffff:0xff008700)),"Visible content");
        System.out.println("PASS screenshot cache policy, empty YUV detection and valid dark/green scenes");
    }
}
'''.replace('METHODS', methods)
out = root / '.codex-tmp/video-screenshot-test'
out.mkdir(exist_ok=True)
path = out / 'VideoScreenshotCheck.java'
path.write_text(java, encoding='utf8')
subprocess.run(['javac', '-d', str(out), str(path)], check=True)
subprocess.run(['java', '-cp', str(out), 'VideoScreenshotCheck'], check=True)
