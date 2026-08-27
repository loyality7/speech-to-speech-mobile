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

// LOCAL-DEV-ONLY verification scaffolding — s2s-host/s2s-agent are not yet
// published to JitPack. Remove this block once they are, and depend on the
// real coordinates instead (see s2s-llm/s2s-context/s2s-tools for the pattern).
includeBuild("../s2s-host") {
    dependencySubstitution {
        substitute(module("com.github.loyality7:s2s-host")).using(project(":core"))
    }
}
includeBuild("../s2s-agent") {
    dependencySubstitution {
        substitute(module("com.github.loyality7:s2s-agent")).using(project(":core"))
    }
}
