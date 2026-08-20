package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * Horloge HP modulaire.
 *
 * Le cadran et les aiguilles sont des PNG indépendants. Le code ne dessine
 * plus leur apparence : il ne gère que leur position, leur échelle et leur rotation.
 * La Terre est dessinée en dernier pour rester au centre et passer devant les aiguilles.
 */
class HpAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

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

        // Le PNG pointe naturellement vers 12 h : 0° = midi.
        drawHandPng(canvas, handBitmap, cx, cy, hours * 30f, faceRadius * 0.48f, 0.90f)
        drawHandPng(canvas, handBitmap, cx, cy, minutes * 6f, faceRadius * 0.70f, 0.90f)
        drawHandPng(canvas, secondBitmap, cx, cy, seconds * 6f, faceRadius * 0.78f, 0.88f)

        // Terre centrée, au-dessus de toutes les aiguilles.
        drawCenteredPng(canvas, earthBitmap, cx, cy, max(faceRadius * 0.16f, 13f))

        postInvalidateDelayed(50L)
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(faceBitmap, null, rect, bitmapPaint)
    }

    private fun drawCenteredPng(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, radius: Float) {
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
        bitmapPaint.alpha = 255
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(cx - dstWidth / 2f, cy - dstHeight / 2f, cx + dstWidth / 2f, cy + dstHeight / 2f),
            bitmapPaint
        )
    }

    /**
     * pivotYRatio indique où se trouve l'axe dans le PNG source.
     * Les PNG restent remplaçables sans toucher à la logique de l'horloge.
     */
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
