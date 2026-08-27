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
                // JitPack builds from a git tag and derives the group from the
                // repository owner, so consumers resolve
                //   com.github.loyality7:speech-to-speech-mobile:<tag>
                // No repositories block: JitPack runs publishToMavenLocal and
                // collects the artifact from there. Declaring a local directory
                // repository, as an earlier version did, only wrote into build/
                // and left nothing anyone could resolve.
                groupId = "com.github.loyality7"
                artifactId = "speech-to-speech-mobile"
                version = project.findProperty("VERSION_NAME")?.toString() ?: "1.0.0"

                pom {
                    name.set("speech-to-speech-mobile")
                    description.set("On-device speech-to-speech for Android: VAD, ASR, LLM and TTS with nothing leaving the device.")
                    url.set("https://github.com/loyality7/speech-to-speech-mobile")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
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

    // LLM backends (llama.cpp, remote OpenAI-compat, ...) moved to s2s-llm —
    // core depends only on the LanguageModel interface, not any implementation.

    testImplementation("junit:junit:4.13.2")

    // Android ships org.json in the platform, but android.jar's copy is a stub that
    // throws on every call, so registry parsing would be untestable on the JVM.
    // This is the upstream implementation Android's is derived from.
    testImplementation("org.json:json:20240303")

    // S2SEngine.initialize()/start() always build a real android.media.AudioTrack
    // -backed SpeakerOutput internally (not injectable) — constructing a real
    // engine on the plain JVM stub throws. Robolectric provides a simulated
    // Android runtime so single-shot generation/speaking behavior can be
    // exercised end to end without an emulator. Same tool s2s-context already
    // uses for its own SQLite tests.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
