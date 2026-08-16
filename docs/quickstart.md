# Quickstart Guide

A complete, working Kotlin example demonstrating `S2SEngine` setup, event collection, and state handling.

---

## Complete Kotlin Example

```kotlin
package com.example.s2s

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.S2SEvent
import com.s2s.mobile.S2SState
import com.s2s.mobile.config.ModelPaths
import com.s2s.mobile.config.S2SConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VoiceActivity : AppCompatActivity() {

    private lateinit var engine: S2SEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modelDir = File(filesDir, "models")
        val config = S2SConfig(
            models = ModelPaths(
                vadModel = File(modelDir, "silero_vad.onnx").absolutePath,
                sttDir = File(modelDir, "stt").absolutePath,
                llmModel = File(modelDir, "qwen2.5-0.5b-instruct.gguf").absolutePath,
                ttsDir = File(modelDir, "tts").absolutePath
            )
        )

        engine = S2SEngine(context = this, config = config)

        // Collect pipeline events
        lifecycleScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is S2SEvent.StateChanged -> updateUiState(event.state)
                    is S2SEvent.UserTranscript -> println("User: ${event.text}")
                    is S2SEvent.AssistantDelta -> print(event.text)
                    is S2SEvent.BargeIn -> println("User interrupted turn ${event.turn}")
                    is S2SEvent.Error -> println("Pipeline Error: ${event.message}")
                    else -> Unit
                }
            }
        }

        // Initialize engine on background thread
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { engine.initialize() }
            if (result.isSuccess) {
                engine.start()
            }
        }
    }

    private fun updateUiState(state: S2SState) {
        println("Current Engine State: $state")
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        engine.release()
    }
}
```
