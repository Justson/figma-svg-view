plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.github.Justson.figma-svg-view"
version = (findProperty("VERSION") as String? ?: System.getenv("VERSION"))
    ?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "1.0.0-SNAPSHOT"

dependencies {
    compileOnly("com.android.tools.build:gradle:8.13.2")
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        register("figmaSvg") {
            id = "io.github.justson.figma-svg"
            implementationClass = "io.github.justson.figmasvg.gradle.FigmaSvgPlugin"
        }
    }
}
