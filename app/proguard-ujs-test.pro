# Test-only boundary: real HTTP fixtures verify the parsed private UJS option.
-keep class xiao.bu.tv.WebSourceUjsHost { *; }
-keep class xiao.bu.tv.Ku9PageResultTest { *; }
-keep class xiao.bu.tv.WebSourceOptions {
    java.lang.String url;
    java.lang.String script;
    static xiao.bu.tv.WebSourceOptions parse(java.lang.String,java.lang.String);
}

-keep class xiao.bu.tv.BrowserSessionChecks { *; }
-keep class xiao.bu.tv.WebTvPlaybackChecks { *; }
