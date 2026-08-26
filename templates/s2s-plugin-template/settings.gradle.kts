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
        // speech-to-speech-mobile is resolved from here until it publishes to
        // Maven Central. Any plugin depending on it needs this line too.
        maven { url = uri("https://jitpack.io") }
    }
}

// Rename before publishing — this name becomes part of the JitPack coordinate.
rootProject.name = "S2SPluginTemplate"

include(":plugin")

// LOCAL DEVELOPMENT ONLY — delete this block once speech-to-speech-mobile has
// a real published tag your plugin can depend on via JitPack.
//
// Templates live inside the speech-to-speech-mobile repo (as ../../ from
// here) purely so they can be verified against the CURRENT, possibly
// unpublished, core source — not a stale JitPack tag that may not match
// what you're actually coding against. Substitutes the JitPack coordinate
// declared in plugin/build.gradle.kts with the live :bindings:android module.
// A real, separately-hosted plugin repo has no ../../ to point at and must
// rely on JitPack from the start — this include is not part of the pattern
// a copied-out plugin should keep.
includeBuild("../../") {
    dependencySubstitution {
        substitute(module("com.github.loyality7:speech-to-speech-mobile"))
            .using(project(":bindings:android"))
    }
}
