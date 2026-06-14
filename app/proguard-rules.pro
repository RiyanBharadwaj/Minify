# --- Native Method Handling ---
# CRITICAL: Keeps the link between Java and C++ code
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Media3 / ExoPlayer ---
# Keeps necessary components for media playback
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- General Optimizations ---
# Use the default number of passes (1) unless you specifically need more
-optimizationpasses 1
-allowaccessmodification
-dontpreverify

# --- Jetpack / Lifecycle ---
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep attributes needed for debugging and generic types
-keepattributes Signature, Exceptions, *Annotation*, SourceFile, LineNumberTable
