plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.example.s2stools"
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
                groupId = "com.github.YOUR_GITHUB_USERNAME"
                artifactId = "YOUR_TOOLS_REPO_NAME"
                version = project.findProperty("VERSION_NAME")?.toString() ?: "0.1.0"
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.github.loyality7:speech-to-speech-mobile:main-SNAPSHOT")

    // A real tools plugin adds the generic library it's adapting here, e.g.:
    // implementation("com.github.loyality7:fetch:vX.Y.Z")
    // implementation("com.github.loyality7:webdroid:vX.Y.Z")
    // Those libraries stay generic — they must never depend on this plugin
    // or on speech-to-speech-mobile. The dependency arrow only ever points
    // from this plugin outward to them, never back.

    implementation("androidx.core:core-ktx:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
