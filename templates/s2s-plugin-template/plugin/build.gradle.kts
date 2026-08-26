plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    // Rename to your plugin's own namespace.
    namespace = "com.example.s2splugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
                // repository owner. Match speech-to-speech-mobile's own
                // publishing block — see its bindings/android/build.gradle.kts.
                groupId = "com.github.YOUR_GITHUB_USERNAME"
                artifactId = "YOUR_PLUGIN_REPO_NAME"
                version = project.findProperty("VERSION_NAME")?.toString() ?: "0.1.0"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The only required dependency: core contracts. Pin to a real tag once
    // speech-to-speech-mobile has one; "main-SNAPSHOT" is for local development.
    implementation("com.github.loyality7:speech-to-speech-mobile:main-SNAPSHOT")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
