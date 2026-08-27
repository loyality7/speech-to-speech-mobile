# Consumer ProGuard / R8 rules for speech-to-speech-mobile SDK

# Keep sherpa-onnx JNI native bridge classes and methods
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# Preserve Kotlin Function1 interface callbacks used across JNI boundaries
-keep class kotlin.jvm.functions.Function1 { *; }

# Preserve public SDK API classes and configurations
-keep class com.s2s.mobile.S2SEngine { *; }
-keep class com.s2s.mobile.S2SState { *; }
-keep class com.s2s.mobile.S2SEvent { *; }
-keep class com.s2s.mobile.config.** { *; }
-keep class com.s2s.mobile.model.** { *; }
-keep class com.s2s.mobile.pipeline.** { *; }
