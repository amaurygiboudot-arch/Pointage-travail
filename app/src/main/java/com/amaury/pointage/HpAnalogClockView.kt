package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Horloge HP modulaire : cadran, aiguilles et Terre indépendants.
 *
 * La Terre centrale V2 est désormais un globe orthographique orienté par la
 * position GPS : le point de l'utilisateur est placé au centre de la sphère.
 */
class HpAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private val faceBitmap: Bitmap by lazy { HpDesignAssets.clockFace }
    private val handBitmap: Bitmap by lazy { HpDesignAssets.hand }
    private val secondBitmap: Bitmap by lazy { HpDesignAssets.secondHand }
    private val sharpHandBitmap: Bitmap by lazy {
        HighQualityBitmapScalerV2.upscale(handBitmap, factor = 4)
    }
    private val sharpSecondBitmap: Bitmap by lazy {
        HighQualityBitmapScalerV2.upscale(secondBitmap, factor = 4)
    }
    private val earthGlobeRenderer = EarthGlobeRendererV2()

    private var cachedFaceBitmap: Bitmap? = null
    private var cachedFaceDiameter = 0

    private val globeHandler = Handler(Looper.getMainLooper())
    private var celestialSnapshot: CelestialSnapshotV2? = null
    private val globeRefreshTask = object : Runnable {
        override fun run() {
            refreshGlobeSnapshot()
            globeHandler.postDelayed(this, GLOBE_LOCATION_REFRESH_MS)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        globeHandler.removeCallbacks(globeRefreshTask)
        refreshGlobeSnapshot()
        globeHandler.postDelayed(globeRefreshTask, GLOBE_LOCATION_REFRESH_MS)
    }

    override fun onDetachedFromWindow() {
        globeHandler.removeCallbacks(globeRefreshTask)
        earthGlobeRenderer.clearCache()
        celestialSnapshot = null
        cachedFaceBitmap?.takeIf { it !== faceBitmap }?.recycle()
        cachedFaceBitmap = null
        cachedFaceDiameter = 0
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cx = width * 0.50f
        val cy = height * 0.55f
        val faceRadius = min(width, height) * 0.40f

        drawFace(canvas, cx, cy, faceRadius)

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = (now.get(Calendar.HOUR) % 12) + minutes / 60f

        // Même géométrie et mêmes PNG : seule leur résolution de travail est augmentée
        // avant rotation afin d'éviter les marches d'escalier sur grand écran.
        drawHandPng(canvas, sharpHandBitmap, cx, cy, hours * 30f, faceRadius * 0.48f, 0.90f)
        drawHandPng(canvas, sharpHandBitmap, cx, cy, minutes * 6f, faceRadius * 0.70f, 0.90f)
        drawHandPng(canvas, sharpSecondBitmap, cx, cy, seconds * 6f, faceRadius * 0.78f, 0.88f)

        val earthRadius = max(faceRadius * 0.16f, 13f)
        drawEarthGlobe(canvas, cx, cy, earthRadius)

        postInvalidateDelayed(50L)
    }

    private fun refreshGlobeSnapshot() {
        celestialSnapshot = runCatching {
            CelestialTrackerV2.currentState(context).snapshot
        }.getOrNull()
        invalidate()
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val targetDiameter = max(2, (radius * 2f).roundToInt())

        if (cachedFaceBitmap == null || cachedFaceDiameter != targetDiameter) {
            cachedFaceBitmap?.takeIf { it !== faceBitmap }?.recycle()
            cachedFaceBitmap = HighQualityBitmapScalerV2.scale(
                source = faceBitmap,
                targetWidth = targetDiameter,
                targetHeight = targetDiameter
            )
            cachedFaceDiameter = targetDiameter
        }

        val contrast = 1.20f
        val translate = (-128f * contrast + 128f) + 4f
        facePaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        facePaint.alpha = 255
        canvas.drawBitmap(cachedFaceBitmap ?: faceBitmap, null, rect, facePaint)
        facePaint.colorFilter = null
    }

    /**
     * Globe GPS V2.
     *
     * Quand une localisation qualifiée existe, sa latitude/longitude est la face
     * avant du globe et le marqueur rouge est exactement au centre. Si aucune
     * localisation fiable n'est disponible, on garde temporairement l'ancien
     * symbole Terre plutôt que d'afficher un pays arbitraire comme position réelle.
     */
    private fun drawEarthGlobe(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val rendered = earthGlobeRenderer.draw(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            snapshot = celestialSnapshot
        )
        if (!rendered) {
            drawFallbackEarthPng(canvas, EarthDesignAsset.bitmap, cx, cy, radius)
        }
    }

    private fun drawFallbackEarthPng(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val diameter = radius * 2f
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstWidth: Float
        val dstHeight: Float
        if (aspect >= 1f) {
            dstWidth = diameter
            dstHeight = diameter / aspect
        } else {
            dstHeight = diameter
            dstWidth = diameter * aspect
        }

        val rect = RectF(
            cx - dstWidth / 2f,
            cy - dstHeight / 2f,
            cx + dstWidth / 2f,
            cy + dstHeight / 2f
        )
        bitmapPaint.alpha = 190
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    private fun drawHandPng(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        tipLength: Float,
        pivotYRatio: Float
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return

        val srcPivotX = bitmap.width * 0.50f
        val srcPivotY = bitmap.height * pivotYRatio
        val scale = tipLength / srcPivotY.coerceAtLeast(1f)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)

        val dst = RectF(
            -srcPivotX * scale,
            -srcPivotY * scale,
            (bitmap.width - srcPivotX) * scale,
            (bitmap.height - srcPivotY) * scale
        )
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        canvas.restore()
    }

    companion object {
        private const val GLOBE_LOCATION_REFRESH_MS = 30_000L
    }
}
