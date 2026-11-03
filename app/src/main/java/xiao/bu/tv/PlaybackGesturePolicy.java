package xiao.bu.tv;

/** Resolve once at touch-down; VOD always retains its fixed brightness/volume controls. */
final class PlaybackGesturePolicy {
    static final int BRIGHTNESS=0, VOLUME=1, CHANNEL=2;
    static final String LEFT_DEFAULT="brightness", RIGHT_DEFAULT="channel";
    static final String LEFT_KEY="gesture_left_vertical", RIGHT_KEY="gesture_right_vertical";
    static boolean validLeft(String value){return "brightness".equals(value)||"volume".equals(value);}
    static boolean validRight(String value){return validLeft(value)||"channel".equals(value);}
    static String left(String value){return validLeft(value)?value:LEFT_DEFAULT;}
    static String right(String value){return validRight(value)?value:RIGHT_DEFAULT;}
    static int resolve(String left,String right,boolean leftSide,boolean vod){
        if(vod)return leftSide?BRIGHTNESS:VOLUME;
        String value=leftSide?left(left):right(right);
        return "brightness".equals(value)?BRIGHTNESS:"volume".equals(value)?VOLUME:CHANNEL;
    }
}
