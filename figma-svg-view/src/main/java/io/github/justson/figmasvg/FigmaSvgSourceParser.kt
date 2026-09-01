package io.github.justson.figmasvg

import android.graphics.Color
import io.github.justson.figmasvg.core.Bounds
import io.github.justson.figmasvg.core.EllipseSpec
import io.github.justson.figmasvg.core.FigmaSvgException
import io.github.justson.figmasvg.core.FigmaSvgParser
import io.github.justson.figmasvg.core.RenderSpec
import org.json.JSONObject

/**
 * Reads either a generated JSON spec or raw SVG.
 *
 * SVG parsing lives in the shared core module, which the Gradle plugin uses as well, so the
 * supported subset is defined exactly once. Only the JSON reader is here, because it relies on
 * org.json and is the mirror image of [io.github.justson.figmasvg.core.FigmaSvgSpecCodec].
 */
internal object FigmaSvgSourceParser {

    fun parse(source: String, sourceName: String): RenderSpec {
        val content = source.trimStart('﻿', ' ', '\t', '\r', '\n')
        return when (content.firstOrNull()) {
            '{' -> parseJson(content, sourceName)
            '<' -> try {
                FigmaSvgParser.parse(content, sourceName)
            } catch (error: FigmaSvgException) {
                throw IllegalArgumentException(error.message, error)
            }
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
                        color = Color.parseColor(ellipse.getString(4)),
                        blurSigma = ellipse.getDouble(5).toFloat(),
                        filterBounds = if (ellipse.length() >= 10) {
                            Bounds(
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
        val viewportLeft = viewport.getDouble(0).toFloat()
        val viewportTop = viewport.getDouble(1).toFloat()
        RenderSpec(
            viewport = Bounds(
                viewportLeft,
                viewportTop,
                viewportLeft + viewport.getDouble(2).toFloat(),
                viewportTop + viewport.getDouble(3).toFloat()
            ),
            clip = Bounds(
                clip.getDouble(0).toFloat(),
                clip.getDouble(1).toFloat(),
                clip.getDouble(2).toFloat(),
                clip.getDouble(3).toFloat()
            ),
            ellipses = ellipses
        )
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Figma SVG JSON source '$sourceName'.", error)
    }
}
