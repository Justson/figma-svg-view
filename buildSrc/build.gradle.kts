plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.13.2")
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
