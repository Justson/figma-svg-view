package io.github.justson.figmasvg.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Parses the SVG `transform` attribute into a single [Transform].
 *
 * Supports the full list of transform functions. Functions compose left to right, so
 * `translate(10,20) rotate(45)` rotates first and then translates, matching SVG semantics.
 */
internal object SvgTransform {

    fun parse(value: String, sourceName: String, elementName: String): Transform {
        var result = Transform.IDENTITY
        var index = 0
        val data = value.trim()
        var matched = false

        while (index < data.length) {
            while (index < data.length && (data[index].isWhitespace() || data[index] == ',')) index++
            if (index >= data.length) break

            val open = data.indexOf('(', index)
            if (open < 0) fail(sourceName, elementName, "malformed transform '$value'.")
            val close = data.indexOf(')', open)
            if (close < 0) fail(sourceName, elementName, "malformed transform '$value'.")

            val name = data.substring(index, open).trim()
            val arguments = data.substring(open + 1, close)
                .split(ARGUMENT_SEPARATOR)
                .filter { it.isNotBlank() }
                .map {
                    it.toFloatOrNull()
                        ?: fail(sourceName, elementName, "invalid number '$it' in transform '$value'.")
                }

            result = result.concat(function(name, arguments, value, sourceName, elementName))
            matched = true
            index = close + 1
        }

        if (!matched) fail(sourceName, elementName, "malformed transform '$value'.")
        return result
    }

    private fun function(
        name: String,
        arguments: List<Float>,
        value: String,
        sourceName: String,
        elementName: String
    ): Transform = when (name) {
        "matrix" -> {
            requireArguments(name, arguments, 6, value, sourceName, elementName)
            Transform(arguments[0], arguments[1], arguments[2], arguments[3], arguments[4], arguments[5])
        }
        "translate" -> when (arguments.size) {
            1 -> Transform(1f, 0f, 0f, 1f, arguments[0], 0f)
            2 -> Transform(1f, 0f, 0f, 1f, arguments[0], arguments[1])
            else -> fail(sourceName, elementName, "translate takes 1 or 2 arguments in '$value'.")
        }
        "scale" -> when (arguments.size) {
            1 -> Transform(arguments[0], 0f, 0f, arguments[0], 0f, 0f)
            2 -> Transform(arguments[0], 0f, 0f, arguments[1], 0f, 0f)
            else -> fail(sourceName, elementName, "scale takes 1 or 2 arguments in '$value'.")
        }
        "rotate" -> when (arguments.size) {
            1 -> rotation(arguments[0])
            3 -> Transform(1f, 0f, 0f, 1f, arguments[1], arguments[2])
                .concat(rotation(arguments[0]))
                .concat(Transform(1f, 0f, 0f, 1f, -arguments[1], -arguments[2]))
            else -> fail(sourceName, elementName, "rotate takes 1 or 3 arguments in '$value'.")
        }
        "skewX" -> {
            requireArguments(name, arguments, 1, value, sourceName, elementName)
            Transform(1f, 0f, tan(arguments[0].toRadians()), 1f, 0f, 0f)
        }
        "skewY" -> {
            requireArguments(name, arguments, 1, value, sourceName, elementName)
            Transform(1f, tan(arguments[0].toRadians()), 0f, 1f, 0f, 0f)
        }
        else -> fail(sourceName, elementName, "unsupported transform function '$name'.")
    }

    private fun rotation(degrees: Float): Transform {
        val radians = degrees.toRadians()
        val cosine = cos(radians)
        val sine = sin(radians)
        return Transform(cosine, sine, -sine, cosine, 0f, 0f)
    }

    private fun requireArguments(
        name: String,
        arguments: List<Float>,
        expected: Int,
        value: String,
        sourceName: String,
        elementName: String
    ) {
        if (arguments.size != expected) {
            fail(sourceName, elementName, "$name takes $expected arguments in '$value'.")
        }
    }

    private fun Float.toRadians(): Float = (this * Math.PI / 180.0).toFloat()

    private fun fail(sourceName: String, elementName: String, message: String): Nothing =
        throw FigmaSvgException(
            "Unsupported Figma SVG source '$sourceName': <$elementName> $message"
        )

    private val ARGUMENT_SEPARATOR = Regex("[\\s,]+")
}
