package xiao.bu.tv;

/** Fixed, allocation-free window. An owner change never inherits another stream's bytes. */
final class PlaybackTrafficSampler {
  private final long[] bytes=new long[6],times=new long[6];
  private Object owner;
  private int next,count;
  private long speed=-1;
  long sample(Object current,long total,long now){
    if(current==null||total<0){reset();return -1;}
    if(current!=owner){reset();owner=current;}
    if(count>0){int last=(next+bytes.length-1)%bytes.length;
      if(total<bytes[last]||now<times[last]||now-times[last]>15000){reset();owner=current;}
      else if(now-times[last]<500)return speed;
    }
    bytes[next]=total;times[next]=now;next=(next+1)%bytes.length;if(count<bytes.length)count++;
    if(count<2)return -1;
    int oldest=(next-count+bytes.length)%bytes.length;
    long elapsed=now-times[oldest],delta=total-bytes[oldest];
    if(elapsed<=0||delta<0||delta>Long.MAX_VALUE/1000)return -1;
    long value=delta*1000/elapsed;
    speed=speed<0?value:(speed*2+value*3)/5;
    // Do not leave a nonzero rate slowly decaying after the entire window is idle.
    if(delta==0)speed=0;
    return speed;
  }
  void reset(){owner=null;next=count=0;speed=-1;}
}
