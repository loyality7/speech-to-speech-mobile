plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.library") version "8.9.3" apply false
    // Pinned to 2.1.20, not 2.2.0: 2.2.0 was only needed for the now-removed
    // LiteRT-LM backend, whose Kotlin metadata (2.3.0) forced a matching bump
    // and broke Expo/RN host builds pinned to 2.1.x. Do not bump this without
    // a real reason — see PROJECT_NOTES.txt.
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
