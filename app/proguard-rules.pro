-keep class tv.danmaku.ijk.media.player.** { *; }

-keep class xiao.bu.tv.NativeQuickJs { *; }
-keep interface xiao.bu.tv.NativeQuickJs$Host { *; }
-keepclassmembers class * implements xiao.bu.tv.NativeQuickJs$Host {
    public java.lang.String invoke(int, java.lang.String[]);
    public boolean isCancelled();
}

