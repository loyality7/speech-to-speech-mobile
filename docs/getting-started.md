# Getting Started with Speech-to-Speech Mobile SDK

This guide covers the shortest path from scratch to a running Speech-to-Speech voice conversation session on Android.

---

## 1. Prerequisites

- **Android Studio**: Jellyfish (2023.3.1+) or Newer
- **Minimum SDK**: API 26 (Android 8.0)
- **Target / Compile SDK**: API 36 (Java 17 / Kotlin 1.9+)
- **Physical Device**: Recommended (ARM64 physical device with at least 3 GB RAM)

---

## 2. Installation

Add JitPack to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the SDK dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.loyality7:speech-to-speech-mobile:1.0.0")
}
```

---

## 3. Declare Android Permissions

In `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 4. Minimal Initialization & Start

```kotlin
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.S2SEvent
import com.s2s.mobile.config.ModelPaths
import com.s2s.mobile.config.S2SConfig
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var engine: S2SEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modelDir = File(filesDir, "models")
        val config = S2SConfig(
            models = ModelPaths(
                vadModel = File(modelDir, "silero_vad.onnx").absolutePath,
                sttDir = File(modelDir, "stt").absolutePath,
                llmModel = File(modelDir, "model.gguf").absolutePath,
                ttsDir = File(modelDir, "tts").absolutePath
            )
        )

        engine = S2SEngine(context = this, config = config)

        lifecycleScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is S2SEvent.UserTranscript -> println("User: ${event.text}")
                    is S2SEvent.AssistantDelta -> print(event.text)
                    is S2SEvent.StateChanged -> println("State: ${event.state}")
                    is S2SEvent.Error -> println("Error: ${event.message}")
                    else -> Unit
                }
            }
        }

        // Initialize engine off main thread
        lifecycleScope.launch {
            engine.initialize().getOrThrow()
            engine.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}
```

---

## 5. Next Steps

- Explore the **[Architecture Guide](architecture.md)** to understand turn lifecycle states.
- See **[Configuration Reference](configuration.md)** for tuning VAD sensitivity and LLM thread counts.
