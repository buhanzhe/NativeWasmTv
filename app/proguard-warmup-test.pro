# Diagnostic-build-only boundary: release builds without this flag remain fully optimized.
-keep class xiao.bu.tv.ChannelNetworkWarmup { *; }
-keep class xiao.bu.tv.ChannelNetworkWarmup$* { *; }
-keep class xiao.bu.tv.Ku9WarmCache { *; }
-keep class xiao.bu.tv.Ku9SiteCache { *; }
-keep class xiao.bu.tv.Ku9ScriptResolver { *; }
-keep class xiao.bu.tv.Ku9ScriptResolver$Result { *; }
-keep interface xiao.bu.tv.Ku9ScriptResolver$Callback { *; }
