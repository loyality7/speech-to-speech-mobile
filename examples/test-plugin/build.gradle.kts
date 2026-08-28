plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * A deliberately trivial, standalone Jarvis plugin APK.
 *
 * It exists to prove the plugin platform, not to be useful: it depends on
 * NOTHING from this repo — no s2s-host, no speech-to-speech-mobile, no
 * shared Gradle module. Its only contract with Jarvis is the AIDL file and
 * the manifest metadata, both duplicated here by design. If this app can be
 * installed, discovered, configured, enabled, selected and used without the
 * Jarvis APK changing, the plugin system is real.
 */
android {
    namespace = "com.s2s.testplugin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.s2s.testplugin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
