package io.github.justson.figmasvg.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Writes the compact JSON spec consumed by FigmaSvgView.
 *
 * Format (version 2), numbers rounded to [MAX_OUTPUT_DECIMALS] decimals:
 *
 * ```
 * {"version":2,
 *  "viewport":[left,top,width,height],
 *  "clip":[left,top,right,bottom],
 *  "shapes":[
 *    {"t":"e","cx":_,"cy":_,"rx":_,"ry":_,"c":"#AARRGGBB","s":blurSigma,
 *     "fb":[l,t,r,b],"m":[a,b,c,d,e,f]},
 *    {"t":"p","d":"M...","eo":true,"c":"#AARRGGBB","s":blurSigma, ...}
 *  ]}
 * ```
 *
 * `fb` (filter bounds), `m` (transform) and `eo` (even-odd fill) are omitted when absent.
 * Shapes are objects rather than positional arrays because both optional tails are variable
 * length, which would otherwise be ambiguous to decode.
 *
 * Version 1 used a positional `"ellipses"` array and no transforms; the runtime still reads it.
 * Decoding lives on the Android side, which has org.json — keeping the writer here means the
 * format itself is still defined in exactly one place.
 */
object FigmaSvgSpecCodec {

    const val VERSION = 2

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
        append("],\"shapes\":[")
        spec.shapes.forEachIndexed { index, shape ->
            if (index > 0) append(',')
            appendShape(shape)
        }
        append("]}")
    }

    private fun StringBuilder.appendShape(shape: ShapeSpec) {
        append('{')
        when (shape) {
            is EllipseSpec -> {
                append("\"t\":\"e\",\"cx\":")
                appendNumber(shape.centerX)
                append(",\"cy\":")
                appendNumber(shape.centerY)
                append(",\"rx\":")
                appendNumber(shape.radiusX)
                append(",\"ry\":")
                appendNumber(shape.radiusY)
            }
            is PathSpec -> {
                append("\"t\":\"p\",\"d\":")
                appendString(shape.pathData)
                if (shape.evenOdd) append(",\"eo\":true")
            }
        }
        append(",\"c\":\"").append(formatColor(shape.color)).append("\",\"s\":")
        appendNumber(shape.blurSigma)
        shape.filterBounds?.let { bounds ->
            append(",\"fb\":[")
            appendNumber(bounds.left)
            append(',')
            appendNumber(bounds.top)
            append(',')
            appendNumber(bounds.right)
            append(',')
            appendNumber(bounds.bottom)
            append(']')
        }
        shape.transform?.takeIf { !it.isIdentity }?.let { transform ->
            append(",\"m\":[")
            appendNumber(transform.a)
            append(',')
            appendNumber(transform.b)
            append(',')
            appendNumber(transform.c)
            append(',')
            appendNumber(transform.d)
            append(',')
            appendNumber(transform.e)
            append(',')
            appendNumber(transform.f)
            append(']')
        }
        append('}')
    }

    fun formatColor(color: Int): String = String.format(
        Locale.US,
        "#%02X%02X%02X%02X",
        (color ushr 24) and 0xFF,
        (color ushr 16) and 0xFF,
        (color ushr 8) and 0xFF,
        color and 0xFF
    )

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character.code < 0x20 -> append(String.format(Locale.US, "\\u%04x", character.code))
                else -> append(character)
            }
        }
        append('"')
    }

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
