plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// k2-fsa publishes no Maven artifact for sherpa-onnx, only a GitHub release AAR.
// It is fetched rather than committed so the repository stays light.
val sherpaVersion = "1.13.4"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile

val fetchSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx Android AAR if it is not present."
    outputs.file(sherpaAar)
    onlyIf { !sherpaAar.exists() }
    doLast {
        sherpaAar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        logger.lifecycle("Downloading sherpa-onnx $sherpaVersion (~48 MB)…")
        uri(url).toURL().openStream().use { input ->
            sherpaAar.outputStream().use { input.copyTo(it) }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchSherpaOnnx) }

android {
    namespace = "com.s2s.mobile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // sherpa-onnx: Silero VAD + streaming Zipformer STT + Kokoro TTS.
    // ponytail: local AAR — k2-fsa publishes no Maven artifact, so consumers of a
    // published S2S AAR must add this file themselves. Swap to a Maven coordinate
    // if k2-fsa ever ships one.
    api(files("libs/sherpa-onnx-1.13.4.aar"))

    // llama.cpp runtime for on-device LLM generation.
    api("com.llamatik:library:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
