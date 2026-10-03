plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.s2s.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.s2s.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // ponytail: arm64 only. The sherpa and llamatik AARs ship four ABIs and
            // the other three are dead weight on any modern phone. Add them back
            // when a release actually needs 32-bit or emulator support.
            abiFilters.add("arm64-v8a")
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

    packaging {
        resources {
            pickFirsts += "models_registry.json"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.github.loyality7:speech-to-speech-mobile"))
            .using(project(":bindings:android"))
            .because("local engine source must not be shadowed by the published AAR")
    }
}

dependencies {
    implementation(project(":bindings:android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.apache.commons:commons-compress:1.26.2")

    // On-device LLM backend (llama.cpp)
    implementation("com.github.loyality7.s2s-llm:llama-cpp:0.3.3")

    // On-device SQLite conversation memory
    implementation("com.github.loyality7.s2s-context:local:0.2.2")
}
