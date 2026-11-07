# Keep Shizuku and Hidden API reflection classes
-keep class dev.rikka.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-keepclassmembers class * {
    @org.lsposed.hiddenapibypass.** *;
}
