package io.github.justson.figmasvg.core

/**
 * Raised for any Figma SVG source that falls outside the supported subset.
 *
 * The Gradle plugin turns this into a build failure; the runtime turns it into an
 * IllegalArgumentException. Both paths report the offending node rather than rendering
 * silently wrong UI.
 */
class FigmaSvgException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** An axis-aligned rectangle in SVG user space. */
data class Bounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersect(other: Bounds): Bounds {
        val result = Bounds(
            maxOf(left, other.left),
            maxOf(top, other.top),
            minOf(right, other.right),
            minOf(bottom, other.bottom)
        )
        if (result.right <= result.left || result.bottom <= result.top) {
            throw FigmaSvgException("SVG mask does not intersect its viewBox.")
        }
        return result
    }
}

/**
 * A single filled ellipse.
 *
 * [color] is packed ARGB. [blurSigma] is the SVG feGaussianBlur stdDeviation, which the runtime
 * converts to an Android BlurMaskFilter radius. [filterBounds] is the filter's userSpaceOnUse
 * region, used to clip the blur the same way the SVG filter region does.
 */
data class EllipseSpec(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val color: Int,
    val blurSigma: Float,
    val filterBounds: Bounds?
)

/** Everything needed to draw one Figma SVG, with no XML or Android types involved. */
data class RenderSpec(
    val viewport: Bounds,
    val clip: Bounds,
    val ellipses: List<EllipseSpec>
) {
    val viewportLeft: Float get() = viewport.left
    val viewportTop: Float get() = viewport.top
    val viewportWidth: Float get() = viewport.width
    val viewportHeight: Float get() = viewport.height

    init {
        if (viewportWidth <= 0f || viewportHeight <= 0f) {
            throw FigmaSvgException("Viewport dimensions must be positive.")
        }
        if (ellipses.isEmpty()) {
            throw FigmaSvgException("No ellipses found in render source.")
        }
    }
}
