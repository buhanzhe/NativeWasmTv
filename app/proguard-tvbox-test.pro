# Preserve the host entry points called by the separate protocol instrumentation APK.
-keep,allowobfuscation class xiao.bu.tv.InstalledPlugins {
    static java.io.File stagingFile(android.content.Context);
    static java.io.File installed(android.content.Context, java.lang.String);
    static dalvik.system.DexClassLoader loader(android.content.Context, java.io.File);
    static java.lang.String sha256(java.io.File);
    static org.json.JSONObject python(android.content.Context, org.json.JSONObject);
}
-keep,allowobfuscation class xiao.bu.tv.RadioUiCatalog {
    static byte[] read(java.io.InputStream, int);
    static void downloadVerifiedRelease(java.lang.String, java.io.File, int, java.lang.String, int);
}
-keep,allowobfuscation class xiao.bu.tv.PluginCatalog {
    static android.content.SharedPreferences preferences(android.content.Context);
    static xiao.bu.tv.PluginCatalog$Snapshot loadStored(android.content.Context);
}
-keep,allowobfuscation class xiao.bu.tv.PluginCatalog$Snapshot {
    java.lang.String release;
    boolean saved;
}
-keep,allowobfuscation class xiao.bu.tv.TvbusBundle {
    static java.lang.String sha(byte[]);
}
-keep,allowobfuscation class xiao.bu.tv.Ku9Python {
    static java.lang.String load(android.content.Context, java.lang.String);
    static org.json.JSONObject run(android.content.Context, java.lang.String, java.lang.String, org.json.JSONObject);
}
-keep,allowobfuscation class xiao.bu.tv.NetworkClient {
    static okhttp3.OkHttpClient sharedClient();
}
