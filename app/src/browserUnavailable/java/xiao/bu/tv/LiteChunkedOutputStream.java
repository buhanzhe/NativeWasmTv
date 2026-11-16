package xiao.bu.tv;
import java.io.*;
/** Explicit HTTP/1.1 EOF for unknown-length finite/streamed media. */
public final class LiteChunkedOutputStream extends OutputStream {
  private static final byte[] CRLF={13,10},END={48,13,10,13,10};
  private final OutputStream output;
  private boolean finished;
  public LiteChunkedOutputStream(OutputStream output){this.output=output;}
  @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
  @Override public void write(byte[] bytes,int offset,int length)throws IOException{
    if(finished)throw new IOException("Chunked response finished");
    if(length==0)return;
    if(offset<0||length<0||offset>bytes.length-length)throw new IndexOutOfBoundsException();
    output.write(Integer.toHexString(length).getBytes("US-ASCII"));output.write(CRLF);output.write(bytes,offset,length);output.write(CRLF);
  }
  public void finish()throws IOException{if(!finished){finished=true;output.write(END);}}
  @Override public void flush()throws IOException{output.flush();}
}
