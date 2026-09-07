-keep class tv.danmaku.ijk.media.player.** { *; }
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
