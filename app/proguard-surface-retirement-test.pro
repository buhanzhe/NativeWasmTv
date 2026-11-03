# Reflection for the opt-in Surface lifecycle regression only.
-keep class xiao.bu.tv.MainActivity { *; }
-keep class xiao.bu.tv.DirectVideoView { *; }
-keep class xiao.bu.tv.ChannelCatalog { *; }
-keep class xiao.bu.tv.ChannelCatalog$* { *; }
-keep class xiao.bu.tv.Channel { *; }
-keep class xiao.bu.tv.WebSourceView { *; }
-keep class xiao.bu.tv.WebChannelSwipe { *; }
-keepclassmembers class xiao.bu.tv.PlaylistManager { private static *** parse(...); }
