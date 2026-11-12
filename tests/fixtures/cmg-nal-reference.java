package xiao.bu.tv;import java.io.*;class Old{static int start(byte[] b,int i){return startCodeLength(b,i);}
 static final Object TAG=new Object();static boolean cmgLiveVideoDecodeEnabled=true;static int cmgVclSinceRuntimeRestart;
 static final java.util.List<Integer> calls=new java.util.ArrayList<Integer>();
 static void restartCmgRuntimeAtAccessUnitBoundary(int t){}static void advanceCmgSessionForNal(int t){calls.add(t);}
 static boolean needsCmgNalDecode(int t){return t==1||t==5;}static boolean needsCmgStateDecode(int t){return t==7;}
 static void updateCmgLiveVideoFlag(int t,byte[] b){}static void sanitizeH264SpsMarker(byte[] d,int a,int z,boolean v){if(v&&z-a>=3)d[a+2]&=0xfc;}
 static boolean bytesDiffer(byte[] a,byte[] b){return !java.util.Arrays.equals(a,b);}
 static final class SystemClock{static long elapsedRealtime(){return 0;}}
 static final class AppLog{static void w(Object t,String s){}static void i(Object t,String s){}}
 static final class NativeCmgDecryptor{
  static byte[] decodeNalForProbe(byte[] b,boolean a,boolean c){return b.clone();}
  static int decodeNalRangeInPlace(byte[] b,int o,int n,boolean a,boolean c){
   int mode=n>1?(b[o+1]&255)%6:0;if(mode==4)return -1;if(mode==5)return -2;
   int len=Math.max(0,n-mode);for(int i=1;i<len;i++)b[o+i]^=0x55;return len;
  }
 }
 static final class PesBuffer{byte[] b,result;int length;PesBuffer(byte[] in){length=in.length;b=java.util.Arrays.copyOf(in,in.length+1000);}byte[] data(){return b;}int payloadSize(){return length;}void copyPayloadToTransportStream(byte[] ts,byte[] b,int n){result=java.util.Arrays.copyOf(b,n);}}
    static int startCodeLength(byte[] data, int offset) {
        if (offset + 3 < data.length && data[offset] == 0 && data[offset + 1] == 0) {
            if (data[offset + 2] == 1) {
                return 3;
            }
            if (offset + 4 < data.length && data[offset + 2] == 0 && data[offset + 3] == 1) {
                return 4;
            }
        }
        return 0;
    }

    static void decryptPesNals(byte[] ts, PesBuffer pes, boolean spsCompatibility) throws IOException {
        byte[] data = pes.data();
        int dataLength = pes.payloadSize();
        ByteArrayOutputStream rebuilt = null;
        int writeOffset = 0;
        for (int offset = 0; offset < dataLength - 4; offset++) {
            int prefix = startCodeLength(data, offset);
            if (prefix == 0) {
                continue;
            }
            int nalStart = offset + prefix;
            if (nalStart >= dataLength) {
                continue;
            }
            int nalEnd = dataLength;
            for (int next = nalStart + 1; next < dataLength - 4; next++) {
                if (startCodeLength(data, next) > 0) {
                    nalEnd = next;
                    break;
                }
            }
            int nalType = data[nalStart] & 0x1f;
            int replacementLength = -1;
            boolean replaceNal = needsCmgNalDecode(nalType);
            boolean stateOnlyNal = needsCmgStateDecode(nalType);
            restartCmgRuntimeAtAccessUnitBoundary(nalType);
            advanceCmgSessionForNal(nalType);
            if (stateOnlyNal) {
                byte[] nal = new byte[nalEnd - nalStart];
                System.arraycopy(data, nalStart, nal, 0, nal.length);
                updateCmgLiveVideoFlag(nalType, nal);
                byte[] decoded = NativeCmgDecryptor.decodeNalForProbe(nal, true, true);
                if (decoded == null) {
                    AppLog.w(TAG, "CMG state NAL rejected type=" + nalType + " len=" + nal.length);
                } else if (decoded.length > nal.length) {
                    AppLog.w(TAG, "Skipping CMG state NAL replacement because length grew type="
                            + nalType + " before=" + nal.length + " after=" + decoded.length);
                } else if (decoded.length != nal.length || bytesDiffer(decoded, nal)) {
                    AppLog.w(TAG, "CMG state NAL changed type=" + nalType
                            + " before=" + nal.length + " after=" + decoded.length
                            + "; keeping original bytes");
                }
                sanitizeH264SpsMarker(data, nalStart, nalEnd, spsCompatibility);
            } else if (replaceNal) {
                int nalLength = nalEnd - nalStart;
                long nalStartedAt = SystemClock.elapsedRealtime();
                int decodedLength = NativeCmgDecryptor.decodeNalRangeInPlace(
                        data, nalStart, nalLength, true, true);
                long nalElapsed = SystemClock.elapsedRealtime() - nalStartedAt;
                if (nalElapsed > 500L) {
                    AppLog.i(TAG, "CMG decoded NAL type=" + nalType + " len=" + nalLength
                            + " out=" + decodedLength + " mode=live-in-place"
                            + " elapsed=" + nalElapsed + "ms");
                }
                if (decodedLength == -2) {
                    AppLog.w(TAG, "Skipping CMG NAL replacement because length grew type="
                            + nalType + " before=" + nalLength);
                } else if (decodedLength < 0) {
                    AppLog.w(TAG, "Skipping CMG NAL replacement because native rejected type="
                            + nalType + " len=" + nalLength);
                } else {
                    replacementLength = decodedLength;
                }
            }
            if (nalType == 1 || nalType == 5) {
                cmgVclSinceRuntimeRestart++;
            }
            if (replacementLength >= 0 && replacementLength < nalEnd - nalStart
                    && rebuilt == null) {
                rebuilt = new ByteArrayOutputStream(dataLength);
            }
            if (rebuilt != null) {
                rebuilt.write(data, writeOffset, nalStart - writeOffset);
                if (replacementLength < 0) {
                    rebuilt.write(data, nalStart, nalEnd - nalStart);
                } else {
                    rebuilt.write(data, nalStart, replacementLength);
                }
                writeOffset = nalEnd;
            }
            offset = nalEnd - 1;
        }
        if (rebuilt == null) {
            pes.copyPayloadToTransportStream(ts, data, dataLength);
        } else {
            rebuilt.write(data, writeOffset, dataLength - writeOffset);
            byte[] repacked = rebuilt.toByteArray();
            pes.copyPayloadToTransportStream(ts, repacked, repacked.length);
        }
    }

}