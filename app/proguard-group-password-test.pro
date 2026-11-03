# Local password/remote/skin checks only; keep reflection boundaries out of normal releases.
-keep class xiao.bu.tv.TvUiPlugin { *; }
-keep class xiao.bu.tv.Channel { *; }
-keep class xiao.bu.tv.ChannelCatalog$Group { *; }
-keep class xiao.bu.tv.MainActivity { *; }
-keepclassmembers class xiao.bu.tv.skin.SharedTvUi {
    public static android.os.Bundle dialogPalette(java.lang.String);
}
-keepclassmembers class xiao.bu.tv.PlaylistManager {
    private static xiao.bu.tv.ChannelCatalog$Group[] parse(byte[],java.lang.String);
}
