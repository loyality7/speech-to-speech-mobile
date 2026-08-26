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
        // speech-to-speech-mobile — and, for a real tools plugin, whatever
        // generic library you're adapting (fetch, webdroid, ...) — are all
        // resolved from here until each publishes to Maven Central.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "S2SToolsPluginTemplate"

include(":tools")

// LOCAL DEVELOPMENT ONLY — see s2s-plugin-template/settings.gradle.kts for why
// this exists and why a real, separately-hosted plugin repo should not keep it.
includeBuild("../../") {
    dependencySubstitution {
        substitute(module("com.github.loyality7:speech-to-speech-mobile"))
            .using(project(":bindings:android"))
    }
}
