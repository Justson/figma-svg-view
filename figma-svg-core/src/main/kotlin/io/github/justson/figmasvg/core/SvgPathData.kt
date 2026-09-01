package io.github.justson.figmasvg.core

/**
 * Validates SVG path `d` data at build time.
 *
 * The runtime hands the original string to androidx PathParser, so this does not build any
 * geometry — it only guarantees the string is well formed, and that malformed art fails the
 * build instead of throwing while inflating a layout.
 *
 * Tokenising mirrors the SVG grammar's awkward corners: numbers may run together without
 * separators (`1.5.3` is two numbers), and the arc flags may be packed against the next
 * number (`a1 1 0 011 1`).
 */
internal object SvgPathData {

    /** Argument count per command; arcs are handled specially because of their flags. */
    private val ARGUMENT_COUNT = mapOf(
        'M' to 2, 'L' to 2, 'T' to 2,
        'H' to 1, 'V' to 1,
        'C' to 6, 'S' to 4, 'Q' to 4,
        'A' to 7,
        'Z' to 0
    )

    /** Zero-based positions of the two arc flags inside an `A` command's arguments. */
    private val ARC_FLAG_INDICES = setOf(3, 4)

    fun validate(pathData: String, sourceName: String, elementName: String) {
        val data = pathData.trim()
        if (data.isEmpty()) {
            fail(sourceName, elementName, "path data is empty.")
        }

        var index = skipSeparators(data, 0)
        if (index >= data.length || data[index].uppercaseChar() != 'M') {
            fail(sourceName, elementName, "path data must start with a moveto command.")
        }

        var command = ' '
        while (index < data.length) {
            index = skipSeparators(data, index)
            if (index >= data.length) break

            val candidate = data[index]
            if (candidate.isLetter()) {
                command = candidate
                index++
            } else if (command == ' ') {
                fail(sourceName, elementName, "path data has arguments before any command.")
            } else if (command.uppercaseChar() == 'Z') {
                fail(sourceName, elementName, "closepath takes no arguments.")
            } else if (command.uppercaseChar() == 'M') {
                // Repeated moveto arguments are implicit linetos.
                command = if (command.isUpperCase()) 'L' else 'l'
            }

            val upper = command.uppercaseChar()
            val argumentCount = ARGUMENT_COUNT[upper]
                ?: fail(sourceName, elementName, "unsupported path command '$command'.")
            if (argumentCount == 0) continue

            for (argument in 0 until argumentCount) {
                index = skipSeparators(data, index)
                index = if (upper == 'A' && argument in ARC_FLAG_INDICES) {
                    readArcFlag(data, index, sourceName, elementName)
                } else {
                    readNumber(data, index, sourceName, elementName, command)
                }
            }
        }
    }

    private fun skipSeparators(data: String, start: Int): Int {
        var index = start
        while (index < data.length && (data[index].isWhitespace() || data[index] == ',')) index++
        return index
    }

    private fun readArcFlag(data: String, start: Int, sourceName: String, elementName: String): Int {
        if (start >= data.length || (data[start] != '0' && data[start] != '1')) {
            fail(sourceName, elementName, "arc flags must be 0 or 1.")
        }
        return start + 1
    }

    private fun readNumber(
        data: String,
        start: Int,
        sourceName: String,
        elementName: String,
        command: Char
    ): Int {
        var index = start
        if (index < data.length && (data[index] == '+' || data[index] == '-')) index++
        var digits = 0
        var seenDot = false
        while (index < data.length) {
            val character = data[index]
            when {
                character.isDigit() -> {
                    digits++
                    index++
                }
                character == '.' && !seenDot -> {
                    seenDot = true
                    index++
                }
                else -> break
            }
        }
        if (digits == 0) {
            fail(sourceName, elementName, "command '$command' is missing arguments.")
        }
        // Exponent, only when it is actually followed by digits.
        if (index < data.length && (data[index] == 'e' || data[index] == 'E')) {
            var lookahead = index + 1
            if (lookahead < data.length && (data[lookahead] == '+' || data[lookahead] == '-')) lookahead++
            if (lookahead < data.length && data[lookahead].isDigit()) {
                while (lookahead < data.length && data[lookahead].isDigit()) lookahead++
                index = lookahead
            }
        }
        return index
    }

    private fun fail(sourceName: String, elementName: String, message: String): Nothing =
        throw FigmaSvgException(
            "Unsupported Figma SVG source '$sourceName': <$elementName> $message"
        )
}
