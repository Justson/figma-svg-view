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

    // 共享源码而不是独立 artifact：解析与校验逻辑只有一份，
    // 同时 aar 不需要多带一个传递依赖。见 figma-svg-core/README.md。
    sourceSets["main"].java.srcDir("../figma-svg-core/src/main/kotlin")
    compileSdk = 36

    defaultConfig {
        minSdk = 21
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
    // 全部 compileOnly：这两者都只在编译期需要，不写进 aar 的 pom，
    // 使用方（尤其是 Kotlin 1.7 / annotation 1.1 的老工程）继续用自己的版本。
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly(kotlin("stdlib"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "figma-svg-view"
        }
    }
}
