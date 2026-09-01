@file:Suppress("DEPRECATION")

package io.github.justson.figmasvg.gradle

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import io.github.justson.figmasvg.core.FigmaSvgException
import io.github.justson.figmasvg.core.FigmaSvgParser
import io.github.justson.figmasvg.core.FigmaSvgSpecCodec
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Converts the supported subset of Figma SVG files into compact raw resources consumed by
 * FigmaSvgView. Source SVG files are build inputs only and are not packaged in the APK.
 */
class FigmaSvgPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            configureAndroidProject(project)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAndroidProject(project)
        }
    }

    private fun configureAndroidProject(project: Project) {
        val android = project.extensions.getByType(BaseExtension::class.java)
        val aggregateTask = project.tasks.register("generateFigmaSvgSpecs") {
            group = "build"
            description = "Generate render specs from Figma SVG files."
        }

        android.sourceSets.all(object : Action<AndroidSourceSet> {
            override fun execute(sourceSet: AndroidSourceSet) {
                val sourceSetName = sourceSet.name
                val taskSuffix = sourceSetName.substring(0, 1).toUpperCase(Locale.US) +
                    sourceSetName.substring(1)
                val sourceDirectory = project.file("src/$sourceSetName/figmaSvg")
                if (!sourceDirectory.isDirectory) return
                val generatedResDirectory = project.layout.buildDirectory.dir(
                    "generated/figmaSvg/$sourceSetName/res"
                )
                val generateTask = project.tasks.register(
                    "generate${taskSuffix}FigmaSvgSpecs",
                    GenerateFigmaSvgSpecsTask::class.java
                ) {
                    group = "build"
                    description = "Generate Figma SVG specs for the $sourceSetName source set."
                    this.sourceSetName.set(sourceSetName)
                    buildRootDirectory.set(project.layout.buildDirectory)
                    svgFiles.from(project.fileTree(sourceDirectory) {
                        include("*.svg")
                    })
                    outputDirectory.set(generatedResDirectory)
                }

                sourceSet.res.srcDir(generatedResDirectory)
                aggregateTask.configure { dependsOn(generateTask) }
            }
        })

        project.tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(aggregateTask)
        }
    }
}

@CacheableTask
abstract class GenerateFigmaSvgSpecsTask : DefaultTask() {
    @get:Incremental
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val svgFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val sourceSetName: Property<String>

    @get:Internal
    abstract val buildRootDirectory: DirectoryProperty

    @TaskAction
    fun generate(inputChanges: InputChanges) {
        val outputRoot = outputDirectory.get().asFile
        verifyGeneratedOutput(outputRoot)

        val outputNames = svgFiles.files.groupBy { outputFileName(it) }
        val duplicate = outputNames.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw GradleException(
                "Figma SVG files in '${sourceSetName.get()}' generate the same Android resource " +
                    "'${duplicate.key}': ${duplicate.value.joinToString { it.name }}"
            )
        }

        if (!inputChanges.isIncremental) {
            if (outputRoot.exists() && !outputRoot.deleteRecursively()) {
                throw GradleException("Unable to clear generated directory: $outputRoot")
            }
        }

        val rawDirectory = File(outputRoot, "raw").apply { mkdirs() }
        inputChanges.getFileChanges(svgFiles).forEach { change ->
            if (change.fileType == FileType.DIRECTORY ||
                change.file.extension.toLowerCase(Locale.US) != "svg"
            ) {
                return@forEach
            }

            val outputFile = File(rawDirectory, outputFileName(change.file))
            if (change.changeType == ChangeType.REMOVED) {
                outputFile.delete()
            } else {
                outputFile.writeText(convert(change.file), StandardCharsets.UTF_8)
            }
        }
    }

    /** Unsupported nodes fail the build here rather than rendering wrong UI at runtime. */
    private fun convert(svgFile: File): String = try {
        FigmaSvgSpecCodec.encode(
            FigmaSvgParser.parse(svgFile.readText(StandardCharsets.UTF_8), svgFile.path)
        )
    } catch (error: FigmaSvgException) {
        throw GradleException(error.message ?: "Unable to convert Figma SVG '${svgFile.path}'.", error)
    }

    private fun verifyGeneratedOutput(outputRoot: File) {
        val buildRoot = buildRootDirectory.get().asFile.toPath()
            .toAbsolutePath().normalize()
        val resolvedOutput = outputRoot.toPath().toAbsolutePath().normalize()
        if (!resolvedOutput.startsWith(buildRoot)) {
            throw GradleException("Figma SVG output must stay inside the module build directory: $resolvedOutput")
        }
    }

    private fun outputFileName(svgFile: File): String {
        val baseName = svgFile.nameWithoutExtension
        if (!ANDROID_RESOURCE_NAME.matches(baseName)) {
            throw GradleException(
                "Figma SVG filename '${svgFile.name}' is invalid. Use lowercase ASCII Android " +
                    "resource names such as 'profile_background.svg'."
            )
        }
        return "figma_svg_$baseName.json"
    }

    private companion object {
        val ANDROID_RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")
    }
}
