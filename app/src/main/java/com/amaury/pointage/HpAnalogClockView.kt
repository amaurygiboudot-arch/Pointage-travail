package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/** Horloge HP modulaire : cadran, aiguilles et Terre indépendants. */
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
    private val earthShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val earthGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val faceBitmap: Bitmap by lazy { HpDesignAssets.clockFace }
    private val handBitmap: Bitmap by lazy { HpDesignAssets.hand }
    private val secondBitmap: Bitmap by lazy { HpDesignAssets.secondHand }
    private val earthBitmap: Bitmap by lazy { EarthDesignAsset.bitmap }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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

        drawHandPng(canvas, handBitmap, cx, cy, hours * 30f, faceRadius * 0.48f, 0.90f)
        drawHandPng(canvas, handBitmap, cx, cy, minutes * 6f, faceRadius * 0.70f, 0.90f)
        drawHandPng(canvas, secondBitmap, cx, cy, seconds * 6f, faceRadius * 0.78f, 0.88f)

        drawEarthPng(canvas, earthBitmap, cx, cy, max(faceRadius * 0.16f, 13f))

        postInvalidateDelayed(50L)
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

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
        canvas.drawBitmap(faceBitmap, null, rect, facePaint)
        facePaint.colorFilter = null
    }

    private fun drawEarthPng(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, radius: Float) {
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

        val rect = RectF(cx - dstWidth / 2f, cy - dstHeight / 2f, cx + dstWidth / 2f, cy + dstHeight / 2f)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)

        // Migration sans rupture : la pendule lit d'abord la nouvelle source centrale.
        // Tant qu'un écran ancien n'a pas encore publié son snapshot, l'ancien relais
        // reste disponible comme filet de sécurité temporaire.
        val centralDirection = CelestialStateStore.current().sunScreenDirection
        val dirX = centralDirection?.x
            ?: if (CelestialLightingState.hasSunDirection) CelestialLightingState.sunDirX else 0f
        val dirY = centralDirection?.y
            ?: if (CelestialLightingState.hasSunDirection) CelestialLightingState.sunDirY else -1f
        val visualRadius = max(dstWidth, dstHeight) * 0.5f

        val sunSideX = cx + dirX * visualRadius
        val sunSideY = cy + dirY * visualRadius
        val nightSideX = cx - dirX * visualRadius
        val nightSideY = cy - dirY * visualRadius

        val earthClip = Path().apply { addOval(rect, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(earthClip)

        earthShadePaint.shader = LinearGradient(
            sunSideX, sunSideY, nightSideX, nightSideY,
            intArrayOf(
                Color.argb(0, 0, 0, 0),
                Color.argb(35, 0, 0, 0),
                Color.argb(185, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, earthShadePaint)

        earthGlowPaint.shader = LinearGradient(
            sunSideX, sunSideY, cx, cy,
            intArrayOf(Color.argb(80, 255, 238, 188), Color.argb(0, 255, 238, 188)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, earthGlowPaint)

        canvas.restore()
        earthShadePaint.shader = null
        earthGlowPaint.shader = null
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
}
