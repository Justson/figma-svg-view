package io.github.justson.figmasvg.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the supported subset of Figma-exported SVG into a [RenderSpec].
 *
 * This is plain JVM code with no Android dependencies so that the Gradle plugin and the runtime
 * View share one implementation. Anything outside the supported subset raises
 * [FigmaSvgException] naming the offending node.
 */
object FigmaSvgParser {

    private data class SvgFilter(val blurSigma: Float, val bounds: Bounds)

    private sealed class Geometry {
        data class Ellipse(
            val centerX: Float,
            val centerY: Float,
            val radiusX: Float,
            val radiusY: Float
        ) : Geometry()

        data class Path(val pathData: String, val evenOdd: Boolean) : Geometry()
    }

    private data class ParsedShape(
        val geometry: Geometry,
        val color: Int,
        val filterId: String?,
        val transform: Transform?
    )

    private data class RenderContext(
        val filterId: String? = null,
        val maskId: String? = null,
        val transform: Transform? = null
    )

    fun parse(svgText: String, sourceName: String): RenderSpec {
        if (svgText.contains("<!DOCTYPE", ignoreCase = true)) {
            fail(sourceName, "DOCTYPE declarations are not supported.")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(svgText)))
        } catch (error: Exception) {
            throw FigmaSvgException("Unable to parse Figma SVG source '$sourceName'.", error)
        }

        val root = document.documentElement
        requireElement(root, "svg", sourceName)
        rejectAttribute(root, "transform", sourceName)

        val viewBoxValues = parseNumberList(requiredAttribute(root, "viewBox", sourceName), "viewBox", sourceName)
        if (viewBoxValues.size != 4 || viewBoxValues[2] <= 0f || viewBoxValues[3] <= 0f) {
            fail(sourceName, "viewBox must contain four numbers with a positive width and height.")
        }
        val viewport = Bounds(
            viewBoxValues[0],
            viewBoxValues[1],
            viewBoxValues[0] + viewBoxValues[2],
            viewBoxValues[1] + viewBoxValues[3]
        )

        val filters = parseFilters(root, sourceName)
        val masks = parseMasks(root, sourceName)
        val usedMasks = linkedSetOf<String>()
        val parsedShapes = mutableListOf<ParsedShape>()
        childElements(root).forEach { child ->
            when (localName(child)) {
                "defs", "mask" -> Unit
                "g", "ellipse", "path" -> parseRenderable(
                    child, RenderContext(), filters, masks, usedMasks, parsedShapes, sourceName
                )
                else -> fail(sourceName, "Unsupported top-level SVG element <${localName(child)}>.")
            }
        }
        if (parsedShapes.isEmpty()) fail(sourceName, "No supported <ellipse> or <path> elements were found.")
        if (usedMasks.size > 1) fail(sourceName, "Only one shared rectangular alpha mask is supported per SVG.")

        val clip = usedMasks.firstOrNull()
            ?.let { maskId -> intersectOrFail(viewport, masks.getValue(maskId), sourceName) }
            ?: viewport

        // Built outside the try below so that a rejection here keeps its own message instead of
        // being re-wrapped by the RenderSpec catch.
        val shapes = parsedShapes.map { shape ->
            val filter = shape.filterId?.let(filters::getValue)
            val blurSigma = filter?.blurSigma ?: 0f
            // A Gaussian blur is isotropic in user space. Only a similarity transform
            // keeps it isotropic, and BlurMaskFilter cannot express anything else.
            if (blurSigma > 0f && shape.transform?.isSimilarity == false) {
                fail(
                    sourceName,
                    "A blurred shape may only use translate, rotate and uniform scale; " +
                        "skew and non-uniform scale would need an anisotropic blur."
                )
            }
            when (val geometry = shape.geometry) {
                is Geometry.Ellipse -> EllipseSpec(
                    centerX = geometry.centerX,
                    centerY = geometry.centerY,
                    radiusX = geometry.radiusX,
                    radiusY = geometry.radiusY,
                    color = shape.color,
                    blurSigma = blurSigma,
                    filterBounds = filter?.bounds,
                    transform = shape.transform
                )
                is Geometry.Path -> PathSpec(
                    pathData = geometry.pathData,
                    evenOdd = geometry.evenOdd,
                    color = shape.color,
                    blurSigma = blurSigma,
                    filterBounds = filter?.bounds,
                    transform = shape.transform
                )
            }
        }

        return try {
            RenderSpec(viewport = viewport, clip = clip, shapes = shapes)
        } catch (error: FigmaSvgException) {
            fail(sourceName, error.message ?: "Invalid render spec.")
        }
    }

    private fun parseFilters(root: Element, sourceName: String): Map<String, SvgFilter> {
        val result = linkedMapOf<String, SvgFilter>()
        descendants(root, "filter").forEach { filter ->
            val id = requiredAttribute(filter, "id", sourceName)
            if (filter.getAttribute("filterUnits").ifBlank { "objectBoundingBox" } != "userSpaceOnUse") {
                fail(sourceName, "Filter '$id' must use filterUnits=userSpaceOnUse.")
            }
            val x = parseNumber(requiredAttribute(filter, "x", sourceName), "filter x", sourceName)
            val y = parseNumber(requiredAttribute(filter, "y", sourceName), "filter y", sourceName)
            val width = parseNumber(requiredAttribute(filter, "width", sourceName), "filter width", sourceName)
            val height = parseNumber(requiredAttribute(filter, "height", sourceName), "filter height", sourceName)
            if (width <= 0f || height <= 0f) fail(sourceName, "Filter '$id' must have positive dimensions.")
            val colorSpace = filter.getAttribute("color-interpolation-filters")
            if (colorSpace.isNotBlank() && colorSpace != "sRGB") {
                fail(sourceName, "Only sRGB filter interpolation is supported in filter '$id'.")
            }
            var blurSigma: Float? = null
            childElements(filter).forEach { primitive ->
                when (localName(primitive)) {
                    "feFlood" -> if (
                        parseNumber(primitive.getAttribute("flood-opacity").ifBlank { "1" }, "flood-opacity", sourceName) != 0f
                    ) {
                        fail(sourceName, "Only transparent feFlood is supported in filter '$id'.")
                    }
                    "feBlend" -> if (primitive.getAttribute("mode").ifBlank { "normal" } != "normal") {
                        fail(sourceName, "Only normal feBlend mode is supported in filter '$id'.")
                    }
                    "feGaussianBlur" -> {
                        if (blurSigma != null) fail(sourceName, "Filter '$id' has multiple feGaussianBlur nodes.")
                        val values = parseNumberList(
                            requiredAttribute(primitive, "stdDeviation", sourceName),
                            "stdDeviation",
                            sourceName
                        )
                        if (values.isEmpty() || values.size > 2 || values[0] < 0f) {
                            fail(sourceName, "Invalid stdDeviation in filter '$id'.")
                        }
                        if (values.size == 2 && values[0] != values[1]) {
                            fail(sourceName, "Different X/Y Gaussian blur radii are not supported in filter '$id'.")
                        }
                        blurSigma = values[0]
                    }
                    else -> fail(sourceName, "Unsupported filter primitive <${localName(primitive)}> in '$id'.")
                }
            }
            result[id] = SvgFilter(
                blurSigma ?: fail(sourceName, "Filter '$id' has no feGaussianBlur."),
                Bounds(x, y, x + width, y + height)
            )
        }
        return result
    }

    private fun parseMasks(root: Element, sourceName: String): Map<String, Bounds> {
        val result = linkedMapOf<String, Bounds>()
        descendants(root, "mask").forEach { mask ->
            val id = requiredAttribute(mask, "id", sourceName)
            if (mask.getAttribute("maskUnits").ifBlank { "objectBoundingBox" } != "userSpaceOnUse") {
                fail(sourceName, "Mask '$id' must use maskUnits=userSpaceOnUse.")
            }
            val style = mask.getAttribute("style")
            if (style.isNotBlank() && !style.replace(" ", "").contains("mask-type:alpha")) {
                fail(sourceName, "Mask '$id' must use mask-type:alpha.")
            }
            val children = childElements(mask)
            if (children.size != 1 || localName(children.single()) != "rect") {
                fail(sourceName, "Mask '$id' must contain exactly one rectangular alpha mask.")
            }
            val rect = children.single()
            val maskX = parseNumber(requiredAttribute(mask, "x", sourceName), "mask x", sourceName)
            val maskY = parseNumber(requiredAttribute(mask, "y", sourceName), "mask y", sourceName)
            val maskWidth = parseNumber(requiredAttribute(mask, "width", sourceName), "mask width", sourceName)
            val maskHeight = parseNumber(requiredAttribute(mask, "height", sourceName), "mask height", sourceName)
            val x = optionalNumberAttribute(rect, "x", 0f, sourceName)
            val y = optionalNumberAttribute(rect, "y", 0f, sourceName)
            val width = parseNumber(requiredAttribute(rect, "width", sourceName), "width", sourceName)
            val height = parseNumber(requiredAttribute(rect, "height", sourceName), "height", sourceName)
            if (maskWidth <= 0f || maskHeight <= 0f || width <= 0f || height <= 0f) {
                fail(sourceName, "Mask '$id' must have positive dimensions.")
            }
            val maskColor = parseColor(
                rect.getAttribute("fill").ifBlank { "#000000" },
                optionalNumberAttribute(rect, "fill-opacity", 1f, sourceName) *
                    optionalNumberAttribute(rect, "opacity", 1f, sourceName),
                sourceName
            )
            if ((maskColor ushr 24) != 0xFF) fail(sourceName, "Mask '$id' rectangle must be fully opaque.")
            result[id] = intersectOrFail(
                Bounds(maskX, maskY, maskX + maskWidth, maskY + maskHeight),
                Bounds(x, y, x + width, y + height),
                sourceName
            )
        }
        return result
    }

    private fun parseRenderable(
        element: Element,
        inheritedContext: RenderContext,
        filters: Map<String, SvgFilter>,
        masks: Map<String, Bounds>,
        usedMasks: MutableSet<String>,
        shapes: MutableList<ParsedShape>,
        sourceName: String
    ) {
        val name = localName(element)
        when (name) {
            "g" -> requireOnlyAttributes(element, setOf("filter", "mask", "transform"), sourceName)
            "ellipse" -> requireOnlyAttributes(
                element,
                setOf(
                    "id", "cx", "cy", "rx", "ry", "fill", "fill-opacity", "opacity",
                    "filter", "mask", "transform"
                ),
                sourceName
            )
            "path" -> requireOnlyAttributes(
                element,
                setOf(
                    "id", "d", "fill", "fill-opacity", "fill-rule", "opacity",
                    "filter", "mask", "transform"
                ),
                sourceName
            )
        }
        rejectAttribute(element, "clip-path", sourceName)

        val ownTransform = element.getAttribute("transform").takeIf { it.isNotBlank() }
            ?.let { SvgTransform.parse(it, sourceName, name) }
        val transform = when {
            ownTransform == null -> inheritedContext.transform
            inheritedContext.transform == null -> ownTransform
            else -> inheritedContext.transform.concat(ownTransform)
        }

        val ownFilter = parseUrlReference(element.getAttribute("filter"), "filter", sourceName)
        if (ownFilter != null && inheritedContext.filterId != null) {
            fail(sourceName, "Nested SVG filters are not supported.")
        }
        if (ownFilter != null && name == "g" && countShapes(element) != 1) {
            fail(sourceName, "A filtered <g> must contain exactly one shape.")
        }
        val filterId = ownFilter ?: inheritedContext.filterId
        if (filterId != null && filterId !in filters) fail(sourceName, "Unknown SVG filter '#$filterId'.")
        val ownMask = parseUrlReference(element.getAttribute("mask"), "mask", sourceName)
        if (ownMask != null && inheritedContext.maskId != null && ownMask != inheritedContext.maskId) {
            fail(sourceName, "Nested SVG masks are not supported.")
        }
        val maskId = ownMask ?: inheritedContext.maskId
        if (maskId != null) {
            if (maskId !in masks) fail(sourceName, "Unknown SVG mask '#$maskId'.")
            usedMasks += maskId
        }
        val context = RenderContext(filterId, maskId, transform)
        when (name) {
            "g" -> childElements(element).forEach { child ->
                when (localName(child)) {
                    "g", "ellipse", "path" -> parseRenderable(
                        child, context, filters, masks, usedMasks, shapes, sourceName
                    )
                    else -> fail(sourceName, "Unsupported SVG element <${localName(child)}> inside <g>.")
                }
            }
            "ellipse" -> {
                val radiusX = numberAttribute(element, "rx", sourceName)
                val radiusY = numberAttribute(element, "ry", sourceName)
                if (radiusX <= 0f || radiusY <= 0f) fail(sourceName, "Ellipse radii must be positive.")
                shapes += ParsedShape(
                    geometry = Geometry.Ellipse(
                        optionalNumberAttribute(element, "cx", 0f, sourceName),
                        optionalNumberAttribute(element, "cy", 0f, sourceName),
                        radiusX,
                        radiusY
                    ),
                    color = fillColor(element, sourceName),
                    filterId = filterId,
                    transform = transform
                )
            }
            "path" -> {
                val pathData = requiredAttribute(element, "d", sourceName)
                SvgPathData.validate(pathData, sourceName, "path")
                val fillRule = element.getAttribute("fill-rule").ifBlank { "nonzero" }
                if (fillRule != "nonzero" && fillRule != "evenodd") {
                    fail(sourceName, "Unsupported fill-rule '$fillRule'.")
                }
                shapes += ParsedShape(
                    geometry = Geometry.Path(pathData.trim(), evenOdd = fillRule == "evenodd"),
                    color = fillColor(element, sourceName),
                    filterId = filterId,
                    transform = transform
                )
            }
        }
    }

    private fun fillColor(element: Element, sourceName: String): Int = parseColor(
        requiredAttribute(element, "fill", sourceName),
        optionalNumberAttribute(element, "fill-opacity", 1f, sourceName) *
            optionalNumberAttribute(element, "opacity", 1f, sourceName),
        sourceName
    )

    private fun intersectOrFail(first: Bounds, second: Bounds, sourceName: String): Bounds = try {
        first.intersect(second)
    } catch (error: FigmaSvgException) {
        fail(sourceName, error.message ?: "SVG mask does not intersect its viewBox.")
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, enabled: Boolean) {
        runCatching { setFeature(name, enabled) }
    }

    /** Returns packed ARGB. Only hexadecimal fills are supported, matching Figma's output. */
    internal fun parseColor(value: String, opacity: Float, sourceName: String): Int {
        if (opacity !in 0f..1f) fail(sourceName, "SVG opacity must be between 0 and 1.")
        val hex = value.trim()
        val channels = when {
            HEX_3.matches(hex) -> intArrayOf(
                "${hex[1]}${hex[1]}".toInt(16),
                "${hex[2]}${hex[2]}".toInt(16),
                "${hex[3]}${hex[3]}".toInt(16),
                255
            )
            HEX_4.matches(hex) -> intArrayOf(
                "${hex[1]}${hex[1]}".toInt(16),
                "${hex[2]}${hex[2]}".toInt(16),
                "${hex[3]}${hex[3]}".toInt(16),
                "${hex[4]}${hex[4]}".toInt(16)
            )
            HEX_6.matches(hex) -> intArrayOf(
                hex.substring(1, 3).toInt(16),
                hex.substring(3, 5).toInt(16),
                hex.substring(5, 7).toInt(16),
                255
            )
            HEX_8.matches(hex) -> intArrayOf(
                hex.substring(1, 3).toInt(16),
                hex.substring(3, 5).toInt(16),
                hex.substring(5, 7).toInt(16),
                hex.substring(7, 9).toInt(16)
            )
            else -> fail(sourceName, "Only hexadecimal SVG fill colors are supported, found '$value'.")
        }
        val alpha = (channels[3] * opacity).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    private fun parseUrlReference(value: String, attribute: String, sourceName: String): String? {
        if (value.isBlank()) return null
        val match = URL_REFERENCE.matchEntire(value.trim())
            ?: fail(sourceName, "Unsupported $attribute reference '$value'.")
        return match.groupValues[1]
    }

    private fun countShapes(element: Element): Int =
        descendants(element, "ellipse").size + descendants(element, "path").size

    private fun numberAttribute(element: Element, name: String, sourceName: String): Float =
        parseNumber(requiredAttribute(element, name, sourceName), name, sourceName)

    private fun optionalNumberAttribute(
        element: Element,
        name: String,
        default: Float,
        sourceName: String
    ): Float = element.getAttribute(name).takeIf { it.isNotBlank() }
        ?.let { parseNumber(it, name, sourceName) }
        ?: default

    private fun parseNumber(value: String, attribute: String, sourceName: String): Float {
        if (!SVG_NUMBER.matches(value.trim())) {
            fail(sourceName, "Unsupported numeric value '$value' for $attribute.")
        }
        return value.trim().toFloatOrNull() ?: fail(sourceName, "Invalid numeric value '$value' for $attribute.")
    }

    private fun parseNumberList(value: String, attribute: String, sourceName: String): List<Float> =
        value.trim().split(NUMBER_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { parseNumber(it, attribute, sourceName) }

    private fun requiredAttribute(element: Element, name: String, sourceName: String): String =
        element.getAttribute(name).takeIf { it.isNotBlank() }
            ?: fail(sourceName, "Element <${localName(element)}> is missing required attribute '$name'.")

    private fun rejectAttribute(element: Element, name: String, sourceName: String) {
        if (element.hasAttribute(name)) {
            fail(sourceName, "Attribute '$name' is not supported on <${localName(element)}>.")
        }
    }

    private fun requireOnlyAttributes(element: Element, allowed: Set<String>, sourceName: String) {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
            val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (name !in allowed) {
                fail(sourceName, "Attribute '$name' is not supported on <${localName(element)}>.")
            }
        }
    }

    private fun requireElement(element: Element, expected: String, sourceName: String) {
        if (localName(element) != expected) {
            fail(sourceName, "Expected <$expected> root but found <${localName(element)}>.")
        }
    }

    private fun childElements(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) result += child as Element
        }
        return result
    }

    private fun descendants(element: Element, name: String): List<Element> {
        val nodes = element.getElementsByTagNameNS("*", name)
        return buildList {
            for (index in 0 until nodes.length) add(nodes.item(index) as Element)
        }
    }

    private fun localName(element: Element): String =
        element.localName ?: element.tagName.substringAfter(':')

    private fun fail(sourceName: String, message: String): Nothing =
        throw FigmaSvgException("Unsupported Figma SVG source '$sourceName': $message")

    private val SVG_NUMBER = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val NUMBER_SEPARATOR = Regex("[\\s,]+")
    private val URL_REFERENCE = Regex("url\\(\\s*#([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\)")
    private val HEX_3 = Regex("#[0-9a-fA-F]{3}")
    private val HEX_4 = Regex("#[0-9a-fA-F]{4}")
    private val HEX_6 = Regex("#[0-9a-fA-F]{6}")
    private val HEX_8 = Regex("#[0-9a-fA-F]{8}")
}
