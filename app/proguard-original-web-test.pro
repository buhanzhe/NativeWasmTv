# Original-WebView instrumentation calls these host interfaces directly.
# Keep only in this diagnostic build; ordinary release shrinking is unchanged.
-keep class xiao.bu.tv.WebSourceView { *; }
-keep interface xiao.bu.tv.WebSourceView$Listener { *; }
-keep class xiao.bu.tv.WebSourceView$Listener$* { *; }
