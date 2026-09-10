# Instrumentation shares the app package and directly tests package-private APIs.
# These are library members from the test shrinker's point of view.
-dontskipnonpubliclibraryclassmembers
-dontwarn com.google.devtools.build.android.desugar.runtime.ThrowableExtension
# Keep the exact offline decoder entry point used by the device compatibility test.
-keepclassmembers class xiao.bu.tv.HlsProxyServer {
    private static byte[] decryptYangshipinTransportStream(byte[]);
}
