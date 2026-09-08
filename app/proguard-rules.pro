# ===================================================================
# Route B: R8 Extreme Obfuscation & Hardening Profile
# ===================================================================

# 1. Extreme Code Transformation & Obfuscation
-optimizationpasses 5
-allowaccessmodification
-overloadaggressively
-repackageclasses ''
-dontusemixedcaseclassnames
-verbose

# 2. Complete Metadata & Debug Symbol Stripping
-renamesourcefileattribute ''
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod

# 3. Android Framework & Entry Points
-keep public class com.android.proot.sample.MainActivity { *; }
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 4. Termux PTY Terminal & JNI (Required by libtermux.so)
-keep class com.termux.terminal.JNI {
    native <methods>;
    *;
}
-keep class com.termux.** {
    *;
}

# 5. Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# 6. ProcessUtil Linux PID Reflection
-keep class com.android.proot.ProcessUtil {
    *;
}
-keepclassmembers class * {
    int pid;
}

# 7. Strip Verbose/Debug Logs in Release Builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
