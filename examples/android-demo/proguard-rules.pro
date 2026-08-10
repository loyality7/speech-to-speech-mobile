# Keep Llamatik native bridge
-keep class com.llamatik.** { *; }
-keep class com.s2s.mobile.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
