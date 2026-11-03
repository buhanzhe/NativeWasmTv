# Local HTTP fixture boundary in the exact optimized integration-test APK only.
-keepclassmembers class xiao.bu.tv.HlsProxyServer { <init>(...); void start(); java.lang.String mediaUrl(java.lang.String); }
-keep class xiao.bu.tv.SniffedMediaProbe { *; }
-keep class xiao.bu.tv.SniffedMediaProbe$* { *; }
