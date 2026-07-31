# --- Native Method Handling ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Media3 / ExoPlayer ---
# Refined: let R8 shrink parts of Media3 not reachable by code paths.
# Keep necessary components for playback/transform reflection where applicable.
-keep class androidx.media3.common.util.UnstableApi
-keep @androidx.media3.common.util.UnstableApi class *
-keepclassmembers class * {
    @androidx.media3.common.util.UnstableApi <methods>;
    @androidx.media3.common.util.UnstableApi <fields>;
}

# --- General Optimizations ---
-optimizationpasses 5
-allowaccessmodification
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# --- Jetpack / Lifecycle ---
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Entity
-keep class * extends androidx.room.Dao
-keep class androidx.room.RoomDatabase

# --- WorkManager ---
# Keep generated WorkDatabase implementation
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.impl.background.systemalarm.RescheduleReceiver
-keep class androidx.work.impl.background.systemalarm.ConstraintProxy$*
-keep class androidx.work.impl.background.systemjob.SystemJobService
-keep class androidx.work.impl.foreground.SystemForegroundService
-keep class androidx.work.impl.diagnostics.DiagnosticsReceiver

# --- App Startup ---
-keep class androidx.startup.InitializationProvider
-keep class * implements androidx.startup.Initializer

-dontwarn androidx.room.**
-dontwarn androidx.work.**
-dontwarn androidx.startup.**
-dontwarn androidx.media3.**
