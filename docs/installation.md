# Installation Guide

This guide details all verified installation methods and requirements for incorporating the `speech-to-speech-mobile` SDK into your Android application.

---

## 1. Requirements

- **Android OS**: Android 8.0 (API Level 26) or higher.
- **Architecture**: ARM64 (`arm64-v8a`).
- **RAM**: Minimum 3 GB resident RAM recommended for 0.5B GGUF models.

---

## 2. Dependency Resolution

Declare JitPack in your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the library dependency to your module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.loyality7:speech-to-speech-mobile:1.0.0")
}
```

---

## 3. Android Permissions

Declare required microphone and notification permissions in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```
