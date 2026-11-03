package xiao.bu.tv;

/** Bounded PMT inspection only; no native decoder, network request or full metadata probe. */
final class HlsVideoCodecProbe {
    static final class Stream {
        private final java.io.ByteArrayOutputStream prefix=new java.io.ByteArrayOutputStream(376);
        private int next=376;
        private String value="";
        void accept(byte[] body,int offset,int count){
            int limit=188*256;
            if(!value.isEmpty()||prefix.size()>=limit)return;
            prefix.write(body,offset,Math.min(count,limit-prefix.size()));
            if(prefix.size()>=next){
                value=HlsVideoCodecProbe.mime(prefix.toByteArray());
                next=Math.max(next*2,prefix.size()+188);
            }
        }
        String mime(){return value;}
    }
    static String mime(byte[] ts){
        if(ts==null)return "";
        int pmt=-1,limit=Math.min(ts.length,188*256);
        for(int offset=0;offset+188<=limit;offset+=188){
            if((ts[offset]&255)!=0x47||(ts[offset+1]&0x40)==0)continue;
            int pid=((ts[offset+1]&31)<<8)|(ts[offset+2]&255);
            if(pid!=0&&pid!=pmt)continue;
            int control=(ts[offset+3]>>4)&3;
            if(control==0||control==2)continue;
            int pos=offset+4;
            if(control==3)pos+=1+(ts[pos]&255);
            int end=offset+188;
            if(pos>=end)continue;
            pos+=1+(ts[pos]&255);
            if(pos+3>end)continue;
            int length=3+((ts[pos+1]&15)<<8)+(ts[pos+2]&255),stop=pos+length;
            if(length<12||stop>end||!validCrc(ts,pos,stop))continue;
            if(pid==0&&(ts[pos]&255)==0){
                for(int i=pos+8;i+4<=stop-4;i+=4)if(ts[i]!=0||ts[i+1]!=0){
                    pmt=((ts[i+2]&31)<<8)|(ts[i+3]&255);break;
                }
            }else if(pid==pmt&&(ts[pos]&255)==2&&length>=16){
                int i=pos+12+((ts[pos+10]&15)<<8)+(ts[pos+11]&255);
                while(i+5<=stop-4){
                    int type=ts[i]&255;
                    if(type==0x24)return "video/hevc";
                    if(type==0x1b)return "video/avc";
                    if(type==0x02)return "video/mpeg2";
                    i+=5+((ts[i+3]&15)<<8)+(ts[i+4]&255);
                }
            }
        }
        return "";
    }
    private static boolean validCrc(byte[] data,int start,int end){
        int crc=-1;
        for(int i=start;i<end;i++){
            crc^=(data[i]&255)<<24;
            for(int b=0;b<8;b++)crc=(crc<<1)^((crc<0)?0x04c11db7:0);
        }
        return crc==0;
    }
}
