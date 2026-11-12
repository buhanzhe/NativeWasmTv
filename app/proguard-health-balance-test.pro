# The independent instrumentation APK calls integration methods and reflects
# fields not otherwise visible to application shrinking. Keep that test-only
# boundary while allowing R8 to optimize method bodies.
-keep,allowoptimization class xiao.bu.tv.** { *; }
