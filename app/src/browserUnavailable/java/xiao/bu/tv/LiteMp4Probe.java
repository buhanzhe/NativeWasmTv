package xiao.bu.tv;

/** Bounded ISO-BMFF sniffing: ftyp alone is not an independently playable file. */
public final class LiteMp4Probe {
  private LiteMp4Probe() { }
  private static long u32(byte[] bytes,int offset){return ((long)(bytes[offset]&255)<<24)|((long)(bytes[offset+1]&255)<<16)|((long)(bytes[offset+2]&255)<<8)|(bytes[offset+3]&255);}
  public static boolean isMp4(byte[] bytes,int length){return length>=12&&bytes[4]=='f'&&bytes[5]=='t'&&bytes[6]=='y'&&bytes[7]=='p';}
  public static boolean initializationOnly(byte[] bytes,int length,boolean complete){
    if(!complete||!isMp4(bytes,length))return false;
    boolean moov=false;
    for(int offset=0;offset+8<=length;){
      long size=u32(bytes,offset);int header=8;
      if(size==1){if(offset+16>length||u32(bytes,offset+8)!=0)return false;size=u32(bytes,offset+12);header=16;}
      else if(size==0)size=length-offset;
      if(size<header||size>length-offset)return false;
      String type=new String(bytes,offset+4,4,java.nio.charset.Charset.forName("US-ASCII"));
      if(type.equals("mdat")&&size>header)return false;
      if(type.equals("moov"))moov=true;
      offset+=(int)size;
    }
    return moov;
  }
}
