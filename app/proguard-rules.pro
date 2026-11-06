# Preserve the classes looked up by libijkplayer/libsdl and their JNI signatures.
# The unused AndroidMediaPlayer/TextureMediaPlayer wrappers and Java-only helpers
# may be shrunk normally; keeping the entire dependency prevented their removal.
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer { *; }
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer$* { *; }
# Downloaded JARs call loadLibrariesOnce(IjkLibLoader); its parameter is part of the ABI.
-keep interface tv.danmaku.ijk.media.player.IjkLibLoader { *; }
-keep class tv.danmaku.ijk.media.player.exceptions.IjkMediaException { *; }
-keep class tv.danmaku.ijk.media.player.ffmpeg.FFmpegApi { *; }
-keep interface tv.danmaku.ijk.media.player.misc.IAndroidIO { *; }
-keep interface tv.danmaku.ijk.media.player.misc.IMediaDataSource { *; }
-keep class xiao.bu.tv.DolbyAudioOutput { public *; }
-keep class xiao.bu.tv.IjkCodecPerformance { public *; }
-keep class xiao.bu.tv.AppLog { *; }

# Bugly reflection/JNI entry points and retraceable crash source locations.
-keep class com.tencent.bugly.** { *; }
-keepnames class xiao.bu.tv.PlayerErrorReports$PlaybackFailure
-dontwarn com.tencent.bugly.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Internal names may be shortened; preserve dynamically invoked entry points below.
-keep class xiao.bu.tv.PlayerRootLayout { public <init>(...); }
-keep interface xiao.bu.tv.radio.RadioUi { *; }

# Ku9, web resolvers and the in-app remote invoke these methods from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Android 4.x calls this undocumented WebChromeClient callback reflectively.
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
}

-keep class xiao.bu.tv.NativeQuickJs { *; }
-keep class xiao.bu.tv.LegacyTlsSocket { *; }
-keep interface xiao.bu.tv.NativeQuickJs$Host { *; }
-keepclassmembers class * implements xiao.bu.tv.NativeQuickJs$Host {
    public java.lang.String invoke(int, java.lang.String[]);
    public boolean isCancelled();
}

# OkHttp 3.12 uses optional annotations and probes Conscrypt before loading it.
# Keep the package-relative publicsuffixes.gz lookup working after obfuscation.
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Called from the native MediaCodec output bridge.
-keep class xiao.bu.tv.NativeHdrOutput { public *; }

# ABI shared with downloaded channel DEX JARs.
-keep interface xiao.bu.tv.jar.ChannelPlugin { *; }
-keep interface xiao.bu.tv.jar.ChannelPlugin$Host { *; }
-keep interface xiao.bu.tv.jar.ChannelPlugin$BrowserHost { *; }
-keep interface xiao.bu.tv.jar.ChannelPlugin$DirectoryHost { *; }
-keep interface xiao.bu.tv.jar.ChannelPlugin$NativeHost { *; }
-keep interface xiao.bu.tv.jar.ChannelPlugin$NativePage { *; }
-keepclassmembers class * implements xiao.bu.tv.jar.ChannelPlugin$Host { public *; }

# Downloaded native channels call these public APIs outside the host's reachability graph.
-keep class okhttp3.** { public protected *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { public protected *; }
-keep interface okio.** { *; }
-keep class tv.danmaku.ijk.media.player.AbstractMediaPlayer { *; }
-keep interface tv.danmaku.ijk.media.player.IMediaPlayer { *; }
-keep interface tv.danmaku.ijk.media.player.IMediaPlayer$* { *; }
-keep class tv.danmaku.ijk.media.player.MediaInfo { *; }
-keep class tv.danmaku.ijk.media.player.IjkTimedText { *; }
-keep interface tv.danmaku.ijk.media.player.misc.ITrackInfo { *; }
-keep interface tv.danmaku.ijk.media.player.misc.IMediaFormat { *; }
# TVBus upstream JNI names and child-loaded bridge must stay stable.
-keep class com.tvbus.engine.TVCore { *; }
-keep interface com.tvbus.engine.TVListener { *; }
-keepclassmembers class * implements com.tvbus.engine.TVListener { public *; }

-keep interface com.tvbus.engine.Listener { *; }
-keepclassmembers class * implements com.tvbus.engine.Listener { public *; }
