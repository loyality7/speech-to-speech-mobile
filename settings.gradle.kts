pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes its Android AAR through JitPack rather than
        // Maven Central. Consumers of a published S2S artifact need this line too.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SpeechToSpeechMobile"

include(":bindings:android")
include(":examples:android-demo")
include(":examples:test-plugin")
