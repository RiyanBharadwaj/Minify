# 1. FFmpeg Kit - Keep only the entry points and JNI bridges
-keep class com.arthenica.ffmpegkit.FFmpegKit { *; }
-keep class com.arthenica.ffmpegkit.Session { *; }
-keep class com.arthenica.ffmpegkit.ReturnCode { *; }
-keep class com.arthenica.ffmpegkit.FFmpegKitConfig { *; }
-keep class com.moizhassan.ffmpeg.** { *; }

# 2. Media3 / ExoPlayer - Keep core playback and UI
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class androidx.media3.common.** { *; }

# 3. Optimization - Allow R8 to rename and remove unused code aggressively
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# 4. Handle Native Methods
-keepclasseswithmembernames class * {
    native <methods>;
}
