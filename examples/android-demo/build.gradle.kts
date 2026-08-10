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
}
