plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * "S1-mini" by "Superwhisper" as a standalone plugin APK.
 *
 * Depends on NO s2s artifact — not speech-to-speech-mobile, not s2s-host,
 * not s2s-agent. Its entire contract with the host is the copied AIDL file
 * plus manifest metadata. That independence is the point: if this app can
 * be installed, discovered and used without the host changing, the plugin
 * architecture is real.
 *
 * It does depend on llamatik, because it runs its own inference. Two
 * separately-installed apps each get their own process and therefore their
 * own llama.cpp runtime — which is precisely why this is a separate APK
 * rather than a module inside the host.
 */
android {
    namespace = "com.s2s.plugin.s1"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.s2s.plugin.s1"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // arm64 only, matching the host demo: the other ABIs are dead
            // weight on any modern phone and this APK already carries a
            // native inference runtime.
            abiFilters.add("arm64-v8a")
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // This plugin's own inference runtime, in this plugin's own process.
    implementation("com.llamatik:library:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
