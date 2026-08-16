plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.s2s.mobile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.loyality7"
                artifactId = "speech-to-speech-mobile"
                version = "1.0.0"
            }
        }
        repositories {
            maven {
                name = "buildDir"
                url = uri(layout.buildDirectory.dir("repo"))
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Archive extraction for bzip2 model archives
    implementation("org.apache.commons:commons-compress:1.26.2")

    // sherpa-onnx: Silero VAD + streaming Zipformer STT + Kokoro/Piper TTS.
    // Resolved from JitPack (declared in settings.gradle.kts). Previously this was
    // a local .aar fetched by a gradle task, which could not travel in a published
    // POM and made the library impossible to package as an AAR at all.
    api("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")

    // llama.cpp runtime for on-device LLM generation.
    api("com.llamatik:library:1.7.0")

    testImplementation("junit:junit:4.13.2")

    // Android ships org.json in the platform, but android.jar's copy is a stub that
    // throws on every call, so registry parsing would be untestable on the JVM.
    // This is the upstream implementation Android's is derived from.
    testImplementation("org.json:json:20240303")
}
