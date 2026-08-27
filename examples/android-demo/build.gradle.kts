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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":bindings:android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // sherpa ships models as .tar.bz2 and the JDK cannot read bzip2.
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Demo registers both llama.cpp and remote LLM backends from s2s-llm as
    // s2s-host plugins — core itself has no concrete LanguageModel of its
    // own. Proves the host can switch between real (not fake) providers
    // without touching speech-to-speech-mobile or s2s-agent at all.
    implementation("com.github.loyality7.s2s-llm:llama-cpp:0.2.0")
    implementation("com.github.loyality7.s2s-llm:remote:0.2.0")

    // Same story for context — core has no concrete ContextEngine of its own.
    implementation("com.github.loyality7.s2s-context:local:0.1.0")

    // Same story for tools — core defaults to NoopTools, nothing concrete.
    // s2s-tools has only one module today, so JitPack publishes it under the
    // plain repo-name coordinate (no ".s2s-tools" groupId suffix, no module
    // name) rather than the multi-module convention s2s-llm/s2s-context use.
    implementation("com.github.loyality7:s2s-tools:0.1.0")

    // s2s-host: PluginRegistry/HostComposer — the composition root that
    // replaces this app's own hardcoded LlamaLanguageModel(...)/
    // SqliteContextEngine(...) construction.
    implementation("com.github.loyality7:s2s-host:0.1.0")

    // s2s-agent: AgentRuntime — owns the model/tool/context loop that
    // S2SEngine deliberately does not. The demo drives voice input through
    // this instead of S2SEngine's own single-shot generate() path (see
    // MainActivity's use of S2SEngine's externalTurnHandler).
    implementation("com.github.loyality7:s2s-agent:0.1.0")
}
