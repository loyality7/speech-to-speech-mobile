package com.s2s.demo.plugin

import android.content.Context
import com.s2s.demo.JarvisRuntime

/**
 * One [JarvisRuntime] per process, shared by every screen.
 *
 * Needed because plugin state (what's registered, installed, enabled,
 * selected) must be the same object the Plugins screen edits and the voice
 * screen composes from — two independently-built runtimes would each hold
 * their own registry, and a plugin enabled in one would be invisible to the
 * other. It also prevents a second `LlamaLanguageModel` init, which
 * llama.cpp refuses process-globally.
 *
 * Application-context only, deliberately: this outlives any Activity, so
 * holding an Activity here would leak it. That is the exact reason the
 * previous `JarvisHost` static singleton was removed — this one stores no
 * Activity, no View, and no engine, only the runtime keyed to the
 * application.
 */
object JarvisRuntimeHolder {
    @Volatile private var instance: JarvisRuntime? = null

    fun get(context: Context): JarvisRuntime =
        instance ?: synchronized(this) {
            instance ?: JarvisRuntime(context.applicationContext).also { instance = it }
        }
}
