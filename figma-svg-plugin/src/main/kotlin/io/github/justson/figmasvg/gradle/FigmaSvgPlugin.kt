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
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

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
                val spec = FigmaSvgSpecConverter.convert(change.file)
                outputFile.writeText(spec, StandardCharsets.UTF_8)
            }
        }
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

private object FigmaSvgSpecConverter {
    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun intersect(other: Rect): Rect {
            val result = Rect(
                maxOf(left, other.left),
                maxOf(top, other.top),
                minOf(right, other.right),
                minOf(bottom, other.bottom)
            )
            require(result.right > result.left && result.bottom > result.top) {
                "SVG mask does not intersect its viewBox."
            }
            return result
        }
    }

    private data class Ellipse(
        val centerX: Float,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val color: String,
        val blurSigma: Float,
        val filterBounds: Rect?
    )

    private data class SvgFilter(val blurSigma: Float, val bounds: Rect)

    private data class RenderContext(
        val filterId: String? = null,
        val maskId: String? = null
    )

    fun convert(svgFile: File): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

        val document = try {
            factory.newDocumentBuilder().parse(svgFile)
        } catch (error: Exception) {
            throw GradleException("Unable to parse Figma SVG '${svgFile.path}': ${error.message}", error)
        }

        val root = document.documentElement
        requireElement(root, "svg", svgFile)
        rejectAttribute(root, "transform", svgFile)

        val viewBoxValues = parseNumberList(requiredAttribute(root, "viewBox", svgFile), "viewBox", svgFile)
        if (viewBoxValues.size != 4 || viewBoxValues[2] <= 0f || viewBoxValues[3] <= 0f) {
            fail(svgFile, "viewBox must contain four numbers with a positive width and height.")
        }
        val viewport = Rect(
            viewBoxValues[0],
            viewBoxValues[1],
            viewBoxValues[0] + viewBoxValues[2],
            viewBoxValues[1] + viewBoxValues[3]
        )

        val filters = parseFilters(root, svgFile)
        val masks = parseMasks(root, svgFile)
        val usedMasks = linkedSetOf<String>()
        val ellipses = mutableListOf<Ellipse>()
        childElements(root).forEach { child ->
            when (localName(child)) {
                "defs", "mask" -> Unit
                "g", "ellipse" -> parseRenderable(
                    child,
                    RenderContext(),
                    filters,
                    masks,
                    usedMasks,
                    ellipses,
                    svgFile
                )
                else -> fail(svgFile, "Unsupported top-level SVG element <${localName(child)}>.")
            }
        }

        if (ellipses.isEmpty()) {
            fail(svgFile, "No supported <ellipse> elements were found.")
        }
        if (usedMasks.size > 1) {
            fail(svgFile, "Only one shared rectangular alpha mask is supported per SVG.")
        }

        val clip = usedMasks.firstOrNull()?.let { viewport.intersect(masks.getValue(it)) } ?: viewport
        return buildJson(viewport, clip, ellipses)
    }

    private fun parseFilters(root: Element, svgFile: File): Map<String, SvgFilter> {
        val result = linkedMapOf<String, SvgFilter>()
        descendants(root, "filter").forEach { filter ->
            val id = requiredAttribute(filter, "id", svgFile)
            val filterUnits = filter.getAttribute("filterUnits").ifBlank { "objectBoundingBox" }
            if (filterUnits != "userSpaceOnUse") {
                fail(svgFile, "Filter '$id' must use filterUnits=userSpaceOnUse.")
            }
            val x = parseNumber(requiredAttribute(filter, "x", svgFile), "filter x", svgFile)
            val y = parseNumber(requiredAttribute(filter, "y", svgFile), "filter y", svgFile)
            val width = parseNumber(requiredAttribute(filter, "width", svgFile), "filter width", svgFile)
            val height = parseNumber(requiredAttribute(filter, "height", svgFile), "filter height", svgFile)
            if (width <= 0f || height <= 0f) {
                fail(svgFile, "Filter '$id' must have a positive width and height.")
            }
            val colorSpace = filter.getAttribute("color-interpolation-filters")
            if (colorSpace.isNotBlank() && colorSpace != "sRGB") {
                fail(svgFile, "Only sRGB filter interpolation is supported in filter '$id'.")
            }
            var blurSigma: Float? = null
            childElements(filter).forEach { primitive ->
                when (localName(primitive)) {
                    "feFlood" -> {
                        val opacity = primitive.getAttribute("flood-opacity").ifBlank { "1" }
                        if (parseNumber(opacity, "flood-opacity", svgFile) != 0f) {
                            fail(svgFile, "Only transparent Figma feFlood primitives are supported in filter '$id'.")
                        }
                    }
                    "feBlend" -> {
                        val mode = primitive.getAttribute("mode").ifBlank { "normal" }
                        if (mode != "normal") {
                            fail(svgFile, "Only normal feBlend mode is supported in filter '$id'.")
                        }
                    }
                    "feGaussianBlur" -> {
                        if (blurSigma != null) {
                            fail(svgFile, "Filter '$id' contains more than one feGaussianBlur.")
                        }
                        val values = parseNumberList(
                            requiredAttribute(primitive, "stdDeviation", svgFile),
                            "stdDeviation",
                            svgFile
                        )
                        if (values.isEmpty() || values.size > 2 || values[0] < 0f) {
                            fail(svgFile, "Invalid stdDeviation in filter '$id'.")
                        }
                        if (values.size == 2 && values[0] != values[1]) {
                            fail(svgFile, "Different X/Y Gaussian blur radii are not supported in filter '$id'.")
                        }
                        blurSigma = values[0]
                    }
                    else -> fail(svgFile, "Unsupported filter primitive <${localName(primitive)}> in '$id'.")
                }
            }
            result[id] = SvgFilter(
                blurSigma ?: fail(svgFile, "Filter '$id' has no feGaussianBlur."),
                Rect(x, y, x + width, y + height)
            )
        }
        return result
    }

    private fun parseMasks(root: Element, svgFile: File): Map<String, Rect> {
        val result = linkedMapOf<String, Rect>()
        descendants(root, "mask").forEach { mask ->
            val id = requiredAttribute(mask, "id", svgFile)
            val maskUnits = mask.getAttribute("maskUnits").ifBlank { "objectBoundingBox" }
            if (maskUnits != "userSpaceOnUse") {
                fail(svgFile, "Mask '$id' must use maskUnits=userSpaceOnUse.")
            }
            val style = mask.getAttribute("style")
            if (style.isNotBlank() && !style.replace(" ", "").contains("mask-type:alpha")) {
                fail(svgFile, "Mask '$id' must use mask-type:alpha.")
            }
            val children = childElements(mask)
            if (children.size != 1 || localName(children.single()) != "rect") {
                fail(svgFile, "Mask '$id' must contain exactly one rectangular alpha mask.")
            }
            val rect = children.single()
            val maskX = parseNumber(requiredAttribute(mask, "x", svgFile), "mask x", svgFile)
            val maskY = parseNumber(requiredAttribute(mask, "y", svgFile), "mask y", svgFile)
            val maskWidth = parseNumber(
                requiredAttribute(mask, "width", svgFile),
                "mask width",
                svgFile
            )
            val maskHeight = parseNumber(
                requiredAttribute(mask, "height", svgFile),
                "mask height",
                svgFile
            )
            val x = optionalNumberAttribute(rect, "x", 0f, svgFile)
            val y = optionalNumberAttribute(rect, "y", 0f, svgFile)
            val width = parseNumber(requiredAttribute(rect, "width", svgFile), "width", svgFile)
            val height = parseNumber(requiredAttribute(rect, "height", svgFile), "height", svgFile)
            if (maskWidth <= 0f || maskHeight <= 0f || width <= 0f || height <= 0f) {
                fail(svgFile, "Mask '$id' must have a positive width and height.")
            }
            val maskColor = parseColor(
                rect.getAttribute("fill").ifBlank { "#000000" },
                optionalNumberAttribute(rect, "fill-opacity", 1f, svgFile) *
                    optionalNumberAttribute(rect, "opacity", 1f, svgFile),
                svgFile
            )
            if (!maskColor.startsWith("#FF")) {
                fail(svgFile, "Mask '$id' rectangle must be fully opaque.")
            }
            result[id] = Rect(maskX, maskY, maskX + maskWidth, maskY + maskHeight)
                .intersect(Rect(x, y, x + width, y + height))
        }
        return result
    }

    private fun parseRenderable(
        element: Element,
        inheritedContext: RenderContext,
        filters: Map<String, SvgFilter>,
        masks: Map<String, Rect>,
        usedMasks: MutableSet<String>,
        ellipses: MutableList<Ellipse>,
        svgFile: File
    ) {
        when (localName(element)) {
            "g" -> requireOnlyAttributes(element, setOf("filter", "mask"), svgFile)
            "ellipse" -> requireOnlyAttributes(
                element,
                setOf("id", "cx", "cy", "rx", "ry", "fill", "fill-opacity", "opacity", "filter", "mask"),
                svgFile
            )
        }
        rejectAttribute(element, "transform", svgFile)
        rejectAttribute(element, "clip-path", svgFile)

        val ownFilter = parseUrlReference(element.getAttribute("filter"), "filter", svgFile)
        if (ownFilter != null && inheritedContext.filterId != null) {
            fail(svgFile, "Nested SVG filters are not supported.")
        }
        if (ownFilter != null && localName(element) == "g" && countEllipses(element) != 1) {
            fail(svgFile, "A filtered <g> must contain exactly one ellipse.")
        }
        val filterId = ownFilter ?: inheritedContext.filterId
        if (filterId != null && filterId !in filters) {
            fail(svgFile, "Unknown SVG filter '#$filterId'.")
        }

        val ownMask = parseUrlReference(element.getAttribute("mask"), "mask", svgFile)
        if (ownMask != null && inheritedContext.maskId != null && ownMask != inheritedContext.maskId) {
            fail(svgFile, "Nested SVG masks are not supported.")
        }
        val maskId = ownMask ?: inheritedContext.maskId
        if (maskId != null) {
            if (maskId !in masks) fail(svgFile, "Unknown SVG mask '#$maskId'.")
            usedMasks += maskId
        }

        val context = RenderContext(filterId, maskId)
        when (localName(element)) {
            "g" -> childElements(element).forEach { child ->
                when (localName(child)) {
                    "g", "ellipse" -> parseRenderable(
                        child,
                        context,
                        filters,
                        masks,
                        usedMasks,
                        ellipses,
                        svgFile
                    )
                    else -> fail(svgFile, "Unsupported SVG element <${localName(child)}> inside <g>.")
                }
            }
            "ellipse" -> {
                val centerX = optionalNumberAttribute(element, "cx", 0f, svgFile)
                val centerY = optionalNumberAttribute(element, "cy", 0f, svgFile)
                val radiusX = numberAttribute(element, "rx", svgFile)
                val radiusY = numberAttribute(element, "ry", svgFile)
                if (radiusX <= 0f || radiusY <= 0f) {
                    fail(svgFile, "Ellipse radii must be positive.")
                }
                val color = parseColor(
                    requiredAttribute(element, "fill", svgFile),
                    optionalNumberAttribute(element, "fill-opacity", 1f, svgFile) *
                        optionalNumberAttribute(element, "opacity", 1f, svgFile),
                    svgFile
                )
                val svgFilter = filterId?.let(filters::getValue)
                ellipses += Ellipse(
                    centerX,
                    centerY,
                    radiusX,
                    radiusY,
                    color,
                    svgFilter?.blurSigma ?: 0f,
                    svgFilter?.bounds
                )
            }
            else -> fail(svgFile, "Unsupported render element <${localName(element)}>.")
        }
    }

    private fun buildJson(viewport: Rect, clip: Rect, ellipses: List<Ellipse>): String = buildString {
        append("{\"version\":1,\"viewport\":[")
        appendNumber(viewport.left)
        append(',')
        appendNumber(viewport.top)
        append(',')
        appendNumber(viewport.right - viewport.left)
        append(',')
        appendNumber(viewport.bottom - viewport.top)
        append("],\"clip\":[")
        appendNumber(clip.left)
        append(',')
        appendNumber(clip.top)
        append(',')
        appendNumber(clip.right)
        append(',')
        appendNumber(clip.bottom)
        append("],\"ellipses\":[")
        ellipses.forEachIndexed { index, ellipse ->
            if (index > 0) append(',')
            append('[')
            appendNumber(ellipse.centerX)
            append(',')
            appendNumber(ellipse.centerY)
            append(',')
            appendNumber(ellipse.radiusX)
            append(',')
            appendNumber(ellipse.radiusY)
            append(",\"")
            append(ellipse.color)
            append("\",")
            appendNumber(ellipse.blurSigma)
            ellipse.filterBounds?.let { bounds ->
                append(',')
                appendNumber(bounds.left)
                append(',')
                appendNumber(bounds.top)
                append(',')
                appendNumber(bounds.right)
                append(',')
                appendNumber(bounds.bottom)
            }
            append(']')
        }
        append("]}")
    }

    private fun StringBuilder.appendNumber(value: Float) {
        append(
            BigDecimal(value.toString())
                .setScale(MAX_OUTPUT_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        )
    }

    private fun parseColor(value: String, opacity: Float, svgFile: File): String {
        if (opacity !in 0f..1f) fail(svgFile, "SVG opacity must be between 0 and 1.")
        val hex = value.trim()
        val (red, green, blue, sourceAlpha) = when {
            Regex("#[0-9a-fA-F]{3}").matches(hex) -> listOf(
                "${hex[1]}${hex[1]}".toInt(16),
                "${hex[2]}${hex[2]}".toInt(16),
                "${hex[3]}${hex[3]}".toInt(16),
                255
            )
            Regex("#[0-9a-fA-F]{4}").matches(hex) -> listOf(
                "${hex[1]}${hex[1]}".toInt(16),
                "${hex[2]}${hex[2]}".toInt(16),
                "${hex[3]}${hex[3]}".toInt(16),
                "${hex[4]}${hex[4]}".toInt(16)
            )
            Regex("#[0-9a-fA-F]{6}").matches(hex) -> listOf(
                hex.substring(1, 3).toInt(16),
                hex.substring(3, 5).toInt(16),
                hex.substring(5, 7).toInt(16),
                255
            )
            Regex("#[0-9a-fA-F]{8}").matches(hex) -> listOf(
                hex.substring(1, 3).toInt(16),
                hex.substring(3, 5).toInt(16),
                hex.substring(5, 7).toInt(16),
                hex.substring(7, 9).toInt(16)
            )
            else -> fail(svgFile, "Only hexadecimal SVG fill colors are supported, found '$value'.")
        }
        val alpha = (sourceAlpha * opacity).toInt().coerceIn(0, 255)
        return String.format(Locale.US, "#%02X%02X%02X%02X", alpha, red, green, blue)
    }

    private fun parseUrlReference(value: String, attribute: String, svgFile: File): String? {
        if (value.isBlank()) return null
        val match = URL_REFERENCE.matchEntire(value.trim())
            ?: fail(svgFile, "Unsupported $attribute reference '$value'.")
        return match.groupValues[1]
    }

    private fun countEllipses(element: Element): Int = descendants(element, "ellipse").size

    private fun numberAttribute(element: Element, name: String, svgFile: File): Float =
        parseNumber(requiredAttribute(element, name, svgFile), name, svgFile)

    private fun optionalNumberAttribute(
        element: Element,
        name: String,
        defaultValue: Float,
        svgFile: File
    ): Float = element.getAttribute(name).takeIf { it.isNotBlank() }
        ?.let { parseNumber(it, name, svgFile) }
        ?: defaultValue

    private fun parseNumber(value: String, attribute: String, svgFile: File): Float {
        if (!SVG_NUMBER.matches(value.trim())) {
            fail(svgFile, "Unsupported numeric value '$value' for $attribute.")
        }
        return value.trim().toFloatOrNull()
            ?: fail(svgFile, "Invalid numeric value '$value' for $attribute.")
    }

    private fun parseNumberList(value: String, attribute: String, svgFile: File): List<Float> =
        value.trim().split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .map { parseNumber(it, attribute, svgFile) }

    private fun requiredAttribute(element: Element, name: String, svgFile: File): String =
        element.getAttribute(name).takeIf { it.isNotBlank() }
            ?: fail(svgFile, "Element <${localName(element)}> is missing required attribute '$name'.")

    private fun rejectAttribute(element: Element, name: String, svgFile: File) {
        if (element.hasAttribute(name)) {
            fail(svgFile, "Attribute '$name' is not supported on <${localName(element)}>.")
        }
    }

    private fun requireOnlyAttributes(element: Element, allowed: Set<String>, svgFile: File) {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
            val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (name !in allowed) {
                fail(svgFile, "Attribute '$name' is not supported on <${localName(element)}>.")
            }
        }
    }

    private fun requireElement(element: Element, expected: String, svgFile: File) {
        if (localName(element) != expected) {
            fail(svgFile, "Expected <$expected> root but found <${localName(element)}>.")
        }
    }

    private fun childElements(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) result.add(child as Element)
        }
        return result
    }

    private fun descendants(element: Element, name: String): List<Element> {
        val nodes = element.getElementsByTagNameNS("*", name)
        val result = mutableListOf<Element>()
        for (index in 0 until nodes.length) {
            result.add(nodes.item(index) as Element)
        }
        return result
    }

    private fun localName(element: Element): String =
        element.localName ?: element.tagName.substringAfter(':')

    private fun fail(svgFile: File, message: String): Nothing =
        throw GradleException("Unsupported Figma SVG '${svgFile.path}': $message")

    private val SVG_NUMBER = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val URL_REFERENCE = Regex("url\\(\\s*#([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\)")
    private const val MAX_OUTPUT_DECIMALS = 4
}
