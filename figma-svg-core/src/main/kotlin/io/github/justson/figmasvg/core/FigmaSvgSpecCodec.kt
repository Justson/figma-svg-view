package io.github.justson.figmasvg.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Writes the compact JSON spec consumed by FigmaSvgView.
 *
 * Format (version 1), all numbers rounded to [MAX_OUTPUT_DECIMALS] decimals:
 *
 * ```
 * {"version":1,
 *  "viewport":[left,top,width,height],
 *  "clip":[left,top,right,bottom],
 *  "ellipses":[[cx,cy,rx,ry,"#AARRGGBB",blurSigma(,fLeft,fTop,fRight,fBottom)?],...]}
 * ```
 *
 * The trailing filter bounds are present only when the ellipse is blurred. Decoding lives on the
 * Android side, which has org.json available; keeping the writer here keeps the format itself
 * defined in one place.
 */
object FigmaSvgSpecCodec {

    const val VERSION = 1

    fun encode(spec: RenderSpec): String = buildString {
        append("{\"version\":").append(VERSION).append(",\"viewport\":[")
        appendNumber(spec.viewport.left)
        append(',')
        appendNumber(spec.viewport.top)
        append(',')
        appendNumber(spec.viewport.width)
        append(',')
        appendNumber(spec.viewport.height)
        append("],\"clip\":[")
        appendNumber(spec.clip.left)
        append(',')
        appendNumber(spec.clip.top)
        append(',')
        appendNumber(spec.clip.right)
        append(',')
        appendNumber(spec.clip.bottom)
        append("],\"ellipses\":[")
        spec.ellipses.forEachIndexed { index, ellipse ->
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
            append(formatColor(ellipse.color))
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

    fun formatColor(color: Int): String = String.format(
        Locale.US,
        "#%02X%02X%02X%02X",
        (color ushr 24) and 0xFF,
        (color ushr 16) and 0xFF,
        (color ushr 8) and 0xFF,
        color and 0xFF
    )

    private fun StringBuilder.appendNumber(value: Float) {
        append(
            BigDecimal(value.toString())
                .setScale(MAX_OUTPUT_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        )
    }

    private const val MAX_OUTPUT_DECIMALS = 4
}
