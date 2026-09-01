package io.github.justson.figmasvg

import android.graphics.Color
import io.github.justson.figmasvg.core.Bounds
import io.github.justson.figmasvg.core.EllipseSpec
import io.github.justson.figmasvg.core.FigmaSvgException
import io.github.justson.figmasvg.core.FigmaSvgParser
import io.github.justson.figmasvg.core.PathSpec
import io.github.justson.figmasvg.core.RenderSpec
import io.github.justson.figmasvg.core.ShapeSpec
import io.github.justson.figmasvg.core.Transform
import org.json.JSONArray
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
        val version = root.getInt("version")
        val viewport = root.getJSONArray("viewport")
        val clip = root.getJSONArray("clip")
        val shapes = when (version) {
            1 -> readLegacyEllipses(root.getJSONArray("ellipses"))
            2 -> readShapes(root.getJSONArray("shapes"))
            else -> throw IllegalArgumentException("Unsupported spec version $version.")
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
            shapes = shapes
        )
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Figma SVG JSON source '$sourceName'.", error)
    }

    private fun readShapes(values: JSONArray): List<ShapeSpec> = buildList {
        for (index in 0 until values.length()) {
            val shape = values.getJSONObject(index)
            val color = Color.parseColor(shape.getString("c"))
            val blurSigma = shape.getDouble("s").toFloat()
            val filterBounds = shape.optJSONArray("fb")?.let { bounds ->
                Bounds(
                    bounds.getDouble(0).toFloat(),
                    bounds.getDouble(1).toFloat(),
                    bounds.getDouble(2).toFloat(),
                    bounds.getDouble(3).toFloat()
                )
            }
            val transform = shape.optJSONArray("m")?.let { matrix ->
                Transform(
                    matrix.getDouble(0).toFloat(),
                    matrix.getDouble(1).toFloat(),
                    matrix.getDouble(2).toFloat(),
                    matrix.getDouble(3).toFloat(),
                    matrix.getDouble(4).toFloat(),
                    matrix.getDouble(5).toFloat()
                )
            }
            add(
                when (val type = shape.getString("t")) {
                    "e" -> EllipseSpec(
                        centerX = shape.getDouble("cx").toFloat(),
                        centerY = shape.getDouble("cy").toFloat(),
                        radiusX = shape.getDouble("rx").toFloat(),
                        radiusY = shape.getDouble("ry").toFloat(),
                        color = color,
                        blurSigma = blurSigma,
                        filterBounds = filterBounds,
                        transform = transform
                    )
                    "p" -> PathSpec(
                        pathData = shape.getString("d"),
                        evenOdd = shape.optBoolean("eo", false),
                        color = color,
                        blurSigma = blurSigma,
                        filterBounds = filterBounds,
                        transform = transform
                    )
                    else -> throw IllegalArgumentException("Unknown shape type '$type'.")
                }
            )
        }
    }

    /** Version 1 specs: a positional ellipse array with no transforms. */
    private fun readLegacyEllipses(values: JSONArray): List<ShapeSpec> = buildList {
        for (index in 0 until values.length()) {
            val ellipse = values.getJSONArray(index)
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
}
