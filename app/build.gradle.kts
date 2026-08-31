plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.github.justson.figma-svg")
}

android {
    namespace = "io.github.justson.figmasvg.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.justson.figmasvg.demo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    implementation(project(":figma-svg-view"))
    implementation(kotlin("stdlib"))
}
