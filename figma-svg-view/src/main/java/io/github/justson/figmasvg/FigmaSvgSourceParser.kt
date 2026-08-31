package io.github.justson.figmasvg

import android.graphics.RectF
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

internal data class RenderSpec(
    val viewportLeft: Float,
    val viewportTop: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val clipBounds: RectF,
    val ellipses: List<EllipseSpec>
)

internal data class EllipseSpec(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val color: Int,
    val blurSigma: Float,
    val filterBounds: RectF?
)

/** Parses either a generated JSON render spec or the supported Figma SVG subset. */
internal object FigmaSvgSourceParser {
    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun intersect(other: Rect, sourceName: String): Rect {
            val result = Rect(
                maxOf(left, other.left),
                maxOf(top, other.top),
                minOf(right, other.right),
                minOf(bottom, other.bottom)
            )
            if (result.right <= result.left || result.bottom <= result.top) {
                fail(sourceName, "SVG mask does not intersect its viewBox.")
            }
            return result
        }

        fun toRectF(): RectF = RectF(left, top, right, bottom)
    }

    private data class ParsedEllipse(
        val centerX: Float,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val color: Int,
        val filterId: String?
    )

    private data class SvgFilter(val blurSigma: Float, val bounds: Rect)
    private data class RenderContext(val filterId: String? = null, val maskId: String? = null)

    fun parse(source: String, sourceName: String): RenderSpec {
        val content = source.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when (content.firstOrNull()) {
            '{' -> parseJson(content, sourceName)
            '<' -> parseSvg(content, sourceName)
            else -> throw IllegalArgumentException(
                "Unsupported Figma SVG source '$sourceName': expected SVG or generated JSON."
            )
        }
    }

    private fun parseJson(jsonText: String, sourceName: String): RenderSpec = try {
        val root = JSONObject(jsonText)
        require(root.getInt("version") == 1) { "Unsupported spec version." }
        val viewport = root.getJSONArray("viewport")
        val clip = root.getJSONArray("clip")
        val ellipseValues = root.getJSONArray("ellipses")
        val ellipses = buildList {
            for (index in 0 until ellipseValues.length()) {
                val ellipse = ellipseValues.getJSONArray(index)
                add(
                    EllipseSpec(
                        centerX = ellipse.getDouble(0).toFloat(),
                        centerY = ellipse.getDouble(1).toFloat(),
                        radiusX = ellipse.getDouble(2).toFloat(),
                        radiusY = ellipse.getDouble(3).toFloat(),
                        color = android.graphics.Color.parseColor(ellipse.getString(4)),
                        blurSigma = ellipse.getDouble(5).toFloat(),
                        filterBounds = if (ellipse.length() >= 10) {
                            RectF(
                                ellipse.getDouble(6).toFloat(),
                                ellipse.getDouble(7).toFloat(),
                                ellipse.getDouble(8).toFloat(),
                                ellipse.getDouble(9).toFloat()
                            )
                        } else {
                            null
                        }
                    )
                )
            }
        }
        RenderSpec(
            viewportLeft = viewport.getDouble(0).toFloat(),
            viewportTop = viewport.getDouble(1).toFloat(),
            viewportWidth = viewport.getDouble(2).toFloat(),
            viewportHeight = viewport.getDouble(3).toFloat(),
            clipBounds = RectF(
                clip.getDouble(0).toFloat(),
                clip.getDouble(1).toFloat(),
                clip.getDouble(2).toFloat(),
                clip.getDouble(3).toFloat()
            ),
            ellipses = ellipses
        ).also(::validateSpec)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Figma SVG JSON source '$sourceName'.", error)
    }

    private fun parseSvg(svgText: String, sourceName: String): RenderSpec {
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
            throw IllegalArgumentException("Unable to parse Figma SVG source '$sourceName'.", error)
        }
        val root = document.documentElement
        requireElement(root, "svg", sourceName)
        rejectAttribute(root, "transform", sourceName)

        val viewBoxValues = parseNumberList(requiredAttribute(root, "viewBox", sourceName), "viewBox", sourceName)
        if (viewBoxValues.size != 4 || viewBoxValues[2] <= 0f || viewBoxValues[3] <= 0f) {
            fail(sourceName, "viewBox must contain four numbers with a positive width and height.")
        }
        val viewport = Rect(
            viewBoxValues[0],
            viewBoxValues[1],
            viewBoxValues[0] + viewBoxValues[2],
            viewBoxValues[1] + viewBoxValues[3]
        )
        val filters = parseFilters(root, sourceName)
        val masks = parseMasks(root, sourceName)
        val usedMasks = linkedSetOf<String>()
        val parsedEllipses = mutableListOf<ParsedEllipse>()
        childElements(root).forEach { child ->
            when (localName(child)) {
                "defs", "mask" -> Unit
                "g", "ellipse" -> parseRenderable(
                    child,
                    RenderContext(),
                    filters,
                    masks,
                    usedMasks,
                    parsedEllipses,
                    sourceName
                )
                else -> fail(sourceName, "Unsupported top-level SVG element <${localName(child)}>.")
            }
        }
        if (parsedEllipses.isEmpty()) fail(sourceName, "No supported <ellipse> elements were found.")
        if (usedMasks.size > 1) fail(sourceName, "Only one shared rectangular alpha mask is supported per SVG.")

        val clip = usedMasks.firstOrNull()
            ?.let { viewport.intersect(masks.getValue(it), sourceName) }
            ?: viewport
        return RenderSpec(
            viewportLeft = viewport.left,
            viewportTop = viewport.top,
            viewportWidth = viewport.right - viewport.left,
            viewportHeight = viewport.bottom - viewport.top,
            clipBounds = clip.toRectF(),
            ellipses = parsedEllipses.map { ellipse ->
                val filter = ellipse.filterId?.let(filters::getValue)
                EllipseSpec(
                    ellipse.centerX,
                    ellipse.centerY,
                    ellipse.radiusX,
                    ellipse.radiusY,
                    ellipse.color,
                    filter?.blurSigma ?: 0f,
                    filter?.bounds?.toRectF()
                )
            }
        ).also(::validateSpec)
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
                Rect(x, y, x + width, y + height)
            )
        }
        return result
    }

    private fun parseMasks(root: Element, sourceName: String): Map<String, Rect> {
        val result = linkedMapOf<String, Rect>()
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
            result[id] = Rect(maskX, maskY, maskX + maskWidth, maskY + maskHeight)
                .intersect(Rect(x, y, x + width, y + height), sourceName)
        }
        return result
    }

    private fun parseRenderable(
        element: Element,
        inheritedContext: RenderContext,
        filters: Map<String, SvgFilter>,
        masks: Map<String, Rect>,
        usedMasks: MutableSet<String>,
        ellipses: MutableList<ParsedEllipse>,
        sourceName: String
    ) {
        when (localName(element)) {
            "g" -> requireOnlyAttributes(element, setOf("filter", "mask"), sourceName)
            "ellipse" -> requireOnlyAttributes(
                element,
                setOf("id", "cx", "cy", "rx", "ry", "fill", "fill-opacity", "opacity", "filter", "mask"),
                sourceName
            )
        }
        rejectAttribute(element, "transform", sourceName)
        rejectAttribute(element, "clip-path", sourceName)
        val ownFilter = parseUrlReference(element.getAttribute("filter"), "filter", sourceName)
        if (ownFilter != null && inheritedContext.filterId != null) fail(sourceName, "Nested SVG filters are not supported.")
        if (ownFilter != null && localName(element) == "g" && countEllipses(element) != 1) {
            fail(sourceName, "A filtered <g> must contain exactly one ellipse.")
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
                        sourceName
                    )
                    else -> fail(sourceName, "Unsupported SVG element <${localName(child)}> inside <g>.")
                }
            }
            "ellipse" -> {
                val radiusX = numberAttribute(element, "rx", sourceName)
                val radiusY = numberAttribute(element, "ry", sourceName)
                if (radiusX <= 0f || radiusY <= 0f) fail(sourceName, "Ellipse radii must be positive.")
                ellipses += ParsedEllipse(
                    optionalNumberAttribute(element, "cx", 0f, sourceName),
                    optionalNumberAttribute(element, "cy", 0f, sourceName),
                    radiusX,
                    radiusY,
                    parseColor(
                        requiredAttribute(element, "fill", sourceName),
                        optionalNumberAttribute(element, "fill-opacity", 1f, sourceName) *
                            optionalNumberAttribute(element, "opacity", 1f, sourceName),
                        sourceName
                    ),
                    filterId
                )
            }
        }
    }

    private fun validateSpec(spec: RenderSpec) {
        require(spec.viewportWidth > 0f && spec.viewportHeight > 0f) {
            "Viewport dimensions must be positive."
        }
        require(spec.ellipses.isNotEmpty()) { "No ellipses found in render source." }
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, enabled: Boolean) {
        runCatching { setFeature(name, enabled) }
    }

    private fun parseColor(value: String, opacity: Float, sourceName: String): Int {
        if (opacity !in 0f..1f) fail(sourceName, "SVG opacity must be between 0 and 1.")
        val hex = value.trim()
        val channels = when {
            Regex("#[0-9a-fA-F]{3}").matches(hex) -> intArrayOf(
                "${hex[1]}${hex[1]}".toInt(16),
                "${hex[2]}${hex[2]}".toInt(16),
                "${hex[3]}${hex[3]}".toInt(16),
                255
            )
            Regex("#[0-9a-fA-F]{4}").matches(hex) -> intArrayOf(
                "${hex[1]}${hex[1]}".toInt(16),
                "${hex[2]}${hex[2]}".toInt(16),
                "${hex[3]}${hex[3]}".toInt(16),
                "${hex[4]}${hex[4]}".toInt(16)
            )
            Regex("#[0-9a-fA-F]{6}").matches(hex) -> intArrayOf(
                hex.substring(1, 3).toInt(16),
                hex.substring(3, 5).toInt(16),
                hex.substring(5, 7).toInt(16),
                255
            )
            Regex("#[0-9a-fA-F]{8}").matches(hex) -> intArrayOf(
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

    private fun countEllipses(element: Element): Int = descendants(element, "ellipse").size
    private fun numberAttribute(element: Element, name: String, sourceName: String): Float =
        parseNumber(requiredAttribute(element, name, sourceName), name, sourceName)

    private fun optionalNumberAttribute(element: Element, name: String, default: Float, sourceName: String): Float =
        element.getAttribute(name).takeIf { it.isNotBlank() }
            ?.let { parseNumber(it, name, sourceName) }
            ?: default

    private fun parseNumber(value: String, attribute: String, sourceName: String): Float {
        if (!SVG_NUMBER.matches(value.trim())) fail(sourceName, "Unsupported numeric value '$value' for $attribute.")
        return value.trim().toFloatOrNull() ?: fail(sourceName, "Invalid numeric value '$value' for $attribute.")
    }

    private fun parseNumberList(value: String, attribute: String, sourceName: String): List<Float> =
        value.trim().split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .map { parseNumber(it, attribute, sourceName) }

    private fun requiredAttribute(element: Element, name: String, sourceName: String): String =
        element.getAttribute(name).takeIf { it.isNotBlank() }
            ?: fail(sourceName, "Element <${localName(element)}> is missing required attribute '$name'.")

    private fun rejectAttribute(element: Element, name: String, sourceName: String) {
        if (element.hasAttribute(name)) fail(sourceName, "Attribute '$name' is not supported on <${localName(element)}>.")
    }

    private fun requireOnlyAttributes(element: Element, allowed: Set<String>, sourceName: String) {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
            val name = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (name !in allowed) fail(sourceName, "Attribute '$name' is not supported on <${localName(element)}>.")
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

    private fun localName(element: Element): String = element.localName ?: element.tagName.substringAfter(':')

    private fun fail(sourceName: String, message: String): Nothing =
        throw IllegalArgumentException("Unsupported Figma SVG source '$sourceName': $message")

    private val SVG_NUMBER = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val URL_REFERENCE = Regex("url\\(\\s*#([A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\)")
}
