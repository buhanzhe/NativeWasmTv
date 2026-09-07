# Instrumentation shares the app package and directly tests package-private APIs.
# These are library members from the test shrinker's point of view.
-dontskipnonpubliclibraryclassmembers
-dontwarn com.google.devtools.build.android.desugar.runtime.ThrowableExtension
