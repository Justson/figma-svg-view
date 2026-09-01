package io.github.justson.figmasvg.core

import kotlin.math.abs
import kotlin.math.sqrt

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
 * A 2D affine transform matching SVG's `matrix(a b c d e f)`:
 *
 * ```
 * | a c e |
 * | b d f |
 * | 0 0 1 |
 * ```
 *
 * The runtime maps this straight onto android.graphics.Matrix.
 */
data class Transform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float
) {
    /** Returns `this` followed by [inner], i.e. the matrix product `this * inner`. */
    fun concat(inner: Transform): Transform = Transform(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        e = a * inner.e + c * inner.f + e,
        f = b * inner.e + d * inner.f + f
    )

    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

    /**
     * True when the transform is only translation, rotation and uniform scale.
     *
     * A Gaussian blur is isotropic in user space. Under a similarity transform it stays a
     * (scaled) isotropic blur, which BlurMaskFilter can reproduce exactly. Under skew or
     * non-uniform scale it would have to become anisotropic, which BlurMaskFilter cannot do —
     * so the parser rejects that combination instead of rendering it wrong.
     */
    val isSimilarity: Boolean
        get() {
            val scaleX = sqrt(a * a + b * b)
            val scaleY = sqrt(c * c + d * d)
            if (scaleX <= 0f || scaleY <= 0f) return false
            if (abs(scaleX - scaleY) > SIMILARITY_TOLERANCE * maxOf(scaleX, scaleY)) return false
            // Columns must stay perpendicular (no skew).
            return abs(a * c + b * d) <= SIMILARITY_TOLERANCE * scaleX * scaleY
        }

    companion object {
        val IDENTITY = Transform(1f, 0f, 0f, 1f, 0f, 0f)
        private const val SIMILARITY_TOLERANCE = 1e-3f
    }
}

/** A shape to fill, in SVG user space. */
sealed class ShapeSpec {
    /** Packed ARGB. */
    abstract val color: Int

    /** SVG feGaussianBlur stdDeviation; 0 means no blur. */
    abstract val blurSigma: Float

    /** The filter's userSpaceOnUse region, clipped the same way the SVG filter region is. */
    abstract val filterBounds: Bounds?

    /** Accumulated transform from the ancestors and the element itself; null means identity. */
    abstract val transform: Transform?
}

data class EllipseSpec(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    override val color: Int,
    override val blurSigma: Float,
    override val filterBounds: Bounds?,
    override val transform: Transform? = null
) : ShapeSpec()

/**
 * A filled path. [pathData] keeps the original SVG `d` string — the runtime hands it to
 * androidx PathParser, and the build already validated its syntax.
 */
data class PathSpec(
    val pathData: String,
    val evenOdd: Boolean,
    override val color: Int,
    override val blurSigma: Float,
    override val filterBounds: Bounds?,
    override val transform: Transform? = null
) : ShapeSpec()

/** Everything needed to draw one Figma SVG, with no XML or Android types involved. */
data class RenderSpec(
    val viewport: Bounds,
    val clip: Bounds,
    val shapes: List<ShapeSpec>
) {
    val viewportLeft: Float get() = viewport.left
    val viewportTop: Float get() = viewport.top
    val viewportWidth: Float get() = viewport.width
    val viewportHeight: Float get() = viewport.height

    init {
        if (viewportWidth <= 0f || viewportHeight <= 0f) {
            throw FigmaSvgException("Viewport dimensions must be positive.")
        }
        if (shapes.isEmpty()) {
            throw FigmaSvgException("No drawable shapes found in render source.")
        }
    }
}
