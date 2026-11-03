# Tests call the integration session directly. Normal releases can remove its
# audit-only helpers and shorten names; the native boundary lives in litecore.
-keep class xiao.bu.tv.LiteWebPlayback { *; }
-keep interface xiao.bu.tv.LiteWebPlayback$Host { *; }
