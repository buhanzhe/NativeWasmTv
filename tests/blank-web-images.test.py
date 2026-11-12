"""Decode the production blank image and exercise request destination safeguards."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
java = r'''package xiao.bu.tv;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
class Check {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static boolean match(String path,String method,boolean doc,Map<String,String> headers){return BlankWebImage.matches("https://site.example"+path,method,doc,headers);}
 public static void main(String[] args)throws Exception{
  BufferedImage image=ImageIO.read(BlankWebImage.open());
  check(image!=null&&image.getWidth()==1&&image.getHeight()==1&&(image.getRGB(0,0)>>>24)==0);
  byte[] copy=BlankWebImage.bytes();copy[0]=0;check(BlankWebImage.open().read()==137&&BlankWebImage.length()==70);
  for(String path:new String[]{"/poster.png?token=1","/image.JPG","/v.svg","/pixel.gif","/cover.webp","/cover.avif"})check(match(path,"GET",false,null));
  for(String path:new String[]{"/api?file=poster.png","/video.mp4","/live.m3u8","/fragment.ts","/api/image","/code.js"})check(!match(path,"GET",false,null));
  check(!match("/poster.png","POST",false,null)&&!match("/poster.png","GET",true,null));
  check(!match("/poster.png","GET",false,Collections.singletonMap("range","bytes=0-7")));
  check(match("/api/image","GET",false,Collections.singletonMap("sec-fetch-dest","image")));
  check(match("/api/image","GET",false,Collections.singletonMap("accept","image/avif,image/webp,*/*;q=0.8")));
  check(!match("/misleading.png","GET",false,Collections.singletonMap("Sec-Fetch-Dest","script")));
  check(!BlankWebImage.matches("data:image/png;base64,eA==","GET",false,null));
  check(!BlankWebImage.matches("invalid url","GET",false,null));
  System.out.println("PASS transparent PNG decoding, immutable payload, image destinations and document/API/video/method/range isolation");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    source=Path(tmp)/'Check.java';source.write_text(java,encoding='utf8')
    jdk=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(jdk/'javac'), '-encoding', 'UTF-8', '-d', tmp, str(root/'browser-core/litebrowser/src/main/java/xiao/bu/tv/BlankWebImage.java'), str(source)], check=True)
    subprocess.run([str(jdk/'java'), '-Djava.awt.headless=true', '-cp', tmp, 'xiao.bu.tv.Check'], check=True)
