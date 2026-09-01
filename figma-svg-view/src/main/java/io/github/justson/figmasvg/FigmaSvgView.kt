package io.github.justson.figmasvg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import androidx.annotation.RawRes
import io.github.justson.figmasvg.core.RenderSpec
import io.github.justson.figmasvg.view.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders either raw SVG resources or compact JSON specs generated from the supported subset.
 *
 * The build-time converter currently supports ellipses, a shared rectangular alpha mask and
 * Figma's transparent flood + normal blend + Gaussian blur filter pattern.
 */
class FigmaSvgView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val ovalBounds = RectF()
    private val destinationBounds = RectF()
    private val blurFilters = mutableMapOf<Float, BlurMaskFilter>()

    private var renderSpec: RenderSpec? = null
    private var renderedBitmap: Bitmap? = null
    private var scaleType = ScaleType.FIT_XY

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // 不能用 TypedArray.use：TypedArray 直到 API 31 才实现 AutoCloseable，
        // 更低的系统上会抛 NoSuchMethodError。
        val array = context.obtainStyledAttributes(attrs, R.styleable.FigmaSvgView, defStyleAttr, 0)
        try {
            scaleType = ScaleType.fromValue(
                array.getInt(R.styleable.FigmaSvgView_figmaSvgScaleType, ScaleType.FIT_XY.value)
            )
            val legacySpecResource = array.getResourceId(R.styleable.FigmaSvgView_figmaSvgSpec, 0)
            val sourceResource = array.getResourceId(
                R.styleable.FigmaSvgView_figmaSvgSource,
                legacySpecResource
            )
            if (sourceResource != 0) setSourceResource(sourceResource)
        } finally {
            array.recycle()
        }
    }

    fun setSourceResource(@RawRes resourceId: Int) {
        val newSpec = if (resourceId == 0) null else parseSource(resourceId)
        // Do not recycle the old bitmap: it may still be shared by another view.
        renderSpec = newSpec
        renderedBitmap = newSpec?.let { obtainBitmap(resourceId, it) }
        requestLayout()
        invalidate()
    }

    /** Backward-compatible alias for JSON-only callers. */
    fun setSpecResource(@RawRes resourceId: Int) = setSourceResource(resourceId)

    /** Shares identical rendered specs across views in the current process. */
    private fun obtainBitmap(@RawRes resourceId: Int, spec: RenderSpec): Bitmap {
        val scale = rasterScaleFor(spec)
        val w = ceil(spec.viewportWidth * scale).toInt().coerceAtLeast(1)
        val h = ceil(spec.viewportHeight * scale).toInt().coerceAtLeast(1)
        val key = "$resourceId@${w}x$h"
        bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        val bitmap = renderToBitmap(spec)
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    fun setScaleType(value: ScaleType) {
        if (scaleType == value) return
        scaleType = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = renderSpec
        val density = resources.displayMetrics.density
        val desiredWidth = spec?.viewportWidth?.times(density)?.let(::ceil)?.toInt()
            ?: suggestedMinimumWidth
        val desiredHeight = spec?.viewportHeight?.times(density)?.let(::ceil)?.toInt()
            ?: suggestedMinimumHeight
        setMeasuredDimension(
            resolveSize(max(desiredWidth, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(max(desiredHeight, suggestedMinimumHeight), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spec = renderSpec ?: return
        val bitmap = renderedBitmap ?: return
        if (width == 0 || height == 0) return

        val widthScale = width / spec.viewportWidth
        val heightScale = height / spec.viewportHeight
        when (scaleType) {
            ScaleType.FIT_XY -> {
                destinationBounds.set(0f, 0f, width.toFloat(), height.toFloat())
            }
            ScaleType.FIT_CENTER -> {
                val uniformScale = min(widthScale, heightScale)
                val drawWidth = spec.viewportWidth * uniformScale
                val drawHeight = spec.viewportHeight * uniformScale
                val left = (width - drawWidth) / 2f
                val top = (height - drawHeight) / 2f
                destinationBounds.set(left, top, left + drawWidth, top + drawHeight)
            }
            ScaleType.CENTER_CROP -> {
                val uniformScale = max(widthScale, heightScale)
                val drawWidth = spec.viewportWidth * uniformScale
                val drawHeight = spec.viewportHeight * uniformScale
                val left = (width - drawWidth) / 2f
                val top = (height - drawHeight) / 2f
                destinationBounds.set(left, top, left + drawWidth, top + drawHeight)
            }
        }
        canvas.drawBitmap(bitmap, null, destinationBounds, bitmapPaint)
    }

    /**
     * Chooses a smaller raster for heavily blurred artwork while keeping sharp shapes at the
     * source resolution. The final bitmap is still scaled by the hardware canvas.
     */
    private fun rasterScaleFor(spec: RenderSpec): Float {
        val longEdge = max(spec.viewportWidth, spec.viewportHeight)
        val sizeLimited = min(1f, MAX_RASTER_DIMENSION / longEdge)
        val hasSharpShape = spec.ellipses.any { it.blurSigma <= 0f }
        val minSigma = spec.ellipses.filter { it.blurSigma > 0f }.minOfOrNull { it.blurSigma }
        if (hasSharpShape || minSigma == null) return sizeLimited
        val blurLimited = min(1f, MIN_BLUR_SPAN_PX / minSigma)
        // Keep enough pixels to preserve the position of large blurred shapes.
        val floorScale = min(1f, MIN_RASTER_DIMENSION / longEdge)
        return max(min(sizeLimited, blurLimited), floorScale)
    }

    private fun renderToBitmap(spec: RenderSpec): Bitmap {
        val rasterScale = rasterScaleFor(spec)
        val bitmapWidth = ceil(spec.viewportWidth * rasterScale).toInt().coerceAtLeast(1)
        val bitmapHeight = ceil(spec.viewportHeight * rasterScale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val bitmapCanvas = Canvas(bitmap)
        bitmapCanvas.scale(rasterScale, rasterScale)
        bitmapCanvas.translate(-spec.viewportLeft, -spec.viewportTop)
        bitmapCanvas.clipRect(spec.clip.left, spec.clip.top, spec.clip.right, spec.clip.bottom)

        spec.ellipses.forEach { ellipse ->
            val saveCount = bitmapCanvas.save()
            ellipse.filterBounds?.let { bitmapCanvas.clipRect(it.left, it.top, it.right, it.bottom) }
            shapePaint.color = ellipse.color
            shapePaint.maskFilter = if (ellipse.blurSigma > 0f) {
                blurFilters.getOrPut(ellipse.blurSigma) {
                    BlurMaskFilter(
                        svgSigmaToAndroidRadius(ellipse.blurSigma),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }
            } else {
                null
            }
            ovalBounds.set(
                ellipse.centerX - ellipse.radiusX,
                ellipse.centerY - ellipse.radiusY,
                ellipse.centerX + ellipse.radiusX,
                ellipse.centerY + ellipse.radiusY
            )
            bitmapCanvas.drawOval(ovalBounds, shapePaint)
            bitmapCanvas.restoreToCount(saveCount)
        }
        shapePaint.maskFilter = null
        return bitmap
    }

    private fun parseSource(@RawRes resourceId: Int): RenderSpec {
        val resourceName = runCatching { resources.getResourceName(resourceId) }
            .getOrDefault(resourceId.toString())
        val source = resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
        return FigmaSvgSourceParser.parse(source, resourceName)
    }

    enum class ScaleType(internal val value: Int) {
        FIT_XY(0),
        FIT_CENTER(1),
        CENTER_CROP(2);

        internal companion object {
            fun fromValue(value: Int): ScaleType = values().firstOrNull { it.value == value }
                ?: FIT_XY
        }
    }

    private fun svgSigmaToAndroidRadius(sigma: Float): Float =
        max((sigma - ANDROID_BLUR_SIGMA_OFFSET) / ANDROID_BLUR_SIGMA_SCALE, MIN_BLUR_RADIUS)

    private companion object {
        // Android converts BlurMaskFilter radius to sigma as radius * 0.57735 + 0.5.
        const val ANDROID_BLUR_SIGMA_SCALE = 0.57735f
        const val ANDROID_BLUR_SIGMA_OFFSET = 0.5f
        const val MIN_BLUR_RADIUS = 0.001f
        const val MAX_RASTER_DIMENSION = 2048f

        /** Minimum number of raster pixels spanning the smallest blur sigma. */
        const val MIN_BLUR_SPAN_PX = 16f
        /** Lower bound for the raster long edge. */
        const val MIN_RASTER_DIMENSION = 64f

        /** Process-wide cache keyed by resource and raster dimensions. */
        val bitmapCache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
}
