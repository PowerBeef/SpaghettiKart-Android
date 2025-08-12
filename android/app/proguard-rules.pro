# Spaghetti Kart ProGuard Rules

# Keep SDL classes
-keep class org.libsdl.app.** { *; }

# Keep our main activity and native methods
-keep class com.izzy.kart.MainActivity { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep DocumentFile for SAF
-keep class androidx.documentfile.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Optimize
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
