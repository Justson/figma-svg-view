plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "com.github.Justson.figma-svg-view"
version = (findProperty("VERSION") as String? ?: System.getenv("VERSION"))
    ?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "1.0.0-SNAPSHOT"

android {
    namespace = "io.github.justson.figmasvg.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api("androidx.annotation:annotation:1.9.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "figma-svg-view"
        }
    }
}
