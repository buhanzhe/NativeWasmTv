# Preserve the classes looked up by libijkplayer/libsdl and their JNI signatures.
# The unused AndroidMediaPlayer/TextureMediaPlayer wrappers and Java-only helpers
# may be shrunk normally; keeping the entire dependency prevented their removal.
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer { *; }
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer$* { *; }
-keep class tv.danmaku.ijk.media.player.exceptions.IjkMediaException { *; }
-keep class tv.danmaku.ijk.media.player.ffmpeg.FFmpegApi { *; }
-keep interface tv.danmaku.ijk.media.player.misc.IAndroidIO { *; }
-keep interface tv.danmaku.ijk.media.player.misc.IMediaDataSource { *; }
-keep class xiao.bu.tv.DolbyAudioOutput { public *; }

# Bugly reflection/JNI entry points and retraceable crash source locations.
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release uses standard shrinking and short-name obfuscation. The selected default
# Android profile deliberately disables bytecode optimization, avoiding deep call
# rewriting and class merging on old TV runtimes while still reducing DEX size.

# Ku9, web resolvers and the in-app remote invoke these methods from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Android 4.x calls this undocumented WebChromeClient callback reflectively.
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
}

# WebKit's API 33 helper is SDK-gated internally; this project compiles against 30.
# Do not suppress warnings for the rest of AndroidX or our application code.
-dontwarn androidx.webkit.internal.ApiHelperForTiramisu
-dontwarn androidx.webkit.internal.StartupApiFeature

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
