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

/**
 * Build the sibling s2s-* repos from LOCAL SOURCE when they are checked out
 * next to this one, instead of resolving them from JitPack.
 *
 * Why this exists, and it is not a convenience: the demo app depended on five
 * published artifacts (s2s-agent, s2s-host, s2s-tools, s2s-context, s2s-llm).
 * A change made to a sibling repo's source therefore had NO EFFECT on the
 * installed APK — Gradle kept resolving the last published version. The
 * symptom is the worst kind: it compiles, it installs, it runs, and the change
 * is simply absent, with nothing logged to say so. The same class of bug
 * already cost a full session when the published engine AAR shadowed
 * :bindings:android.
 *
 * `includeBuild` cannot infer the mapping here because none of the sibling
 * modules declare an explicit groupId/artifactId — they rely on JitPack
 * deriving coordinates from the repo and module names at publish time. So each
 * substitution is stated by hand below.
 *
 * Absent sibling directories are skipped, so a fresh clone of only this repo
 * still builds against the published artifacts exactly as before.
 *
 * Composite builds also resolve the reverse direction: every sibling depends on
 * the PUBLISHED `com.github.loyality7:speech-to-speech-mobile`, and inside a
 * composite that request is substituted for `:bindings:android` too — so there
 * is one copy of the engine classes on the classpath, from this repo, instead
 * of the local project and a published AAR competing.
 */
val siblingBuilds = mapOf(
    "s2s-agent" to listOf("com.github.loyality7:s2s-agent" to ":core"),
    "s2s-host" to listOf("com.github.loyality7:s2s-host" to ":core"),
    "s2s-tools" to listOf("com.github.loyality7:s2s-tools" to ":core"),
    "s2s-context" to listOf("com.github.loyality7.s2s-context:local" to ":local"),
    "s2s-llm" to listOf(
        "com.github.loyality7.s2s-llm:llama-cpp" to ":llama-cpp",
        "com.github.loyality7.s2s-llm:remote" to ":remote",
    ),
)

siblingBuilds.forEach { (directory, substitutions) ->
    val dir = file("../$directory")
    if (!dir.isDirectory) {
        logger.lifecycle("s2s composite: ../$directory not present — using the published artifact")
        return@forEach
    }
    includeBuild(dir) {
        dependencySubstitution {
            substitutions.forEach { (coordinate, projectPath) ->
                substitute(module(coordinate)).using(project(projectPath))
            }
        }
    }
    logger.lifecycle("s2s composite: ../$directory built from source")
}

include(":bindings:android")
include(":examples:android-demo")
include(":examples:test-plugin")
include(":examples:s1-normalizer-plugin")
