# ============================================================================
#  LAN FPS - R8 / ProGuard rules (P3-4 of docs/IMPROVEMENT_PLAN.md)
#
#  The app does not use reflection anywhere - no Class.forName, no JSON
#  mapping, no JNI - so the default Android rules are nearly sufficient.
#  What follows is belt and braces:
# ============================================================================

# Stack traces from the field must stay readable. com.lanfps.shared.* is the
# wire format + prediction code shared with the server; keeping its names costs
# a few KB and turns a bug report screenshot into an actual diagnosis.
-keepnames class com.lanfps.shared.** { *; }
-keepnames class com.lanfps.client.** { *; }

# Enums are switched on by name in logs; R8 may merge their valueOf plumbing.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The Kotlin stdlib probes for optional classes that simply do not exist on
# Android; these warnings are expected and harmless.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.jdk7.**
-dontwarn kotlin.jdk8.**
-dontwarn kotlin.coroutines.**
