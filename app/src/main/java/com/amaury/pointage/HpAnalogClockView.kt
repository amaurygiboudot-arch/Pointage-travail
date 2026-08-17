package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Transparent overlay used on top of luxury_hero.webp.
 * It deliberately draws only the moving hands so the original watch artwork
 * remains untouched.
 */
class HpAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gold = Color.parseColor("#D6A84B")
    private val lightGold = Color.parseColor("#F3D58A")
    private val black = Color.parseColor("#090909")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // The watch in luxury_hero is centred in the hero artwork.
        // Keep the overlay transparent: only the live hands are painted.
        val cx = width / 2f
        val cy = height / 2f
        val faceRadius = min(width, height) * 0.31f

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = (now.get(Calendar.HOUR) % 12) + minutes / 60f

        drawHand(canvas, cx, cy, faceRadius * 0.52f, hours * 30f - 90f, dpF(4.2f), lightGold)
        drawHand(canvas, cx, cy, faceRadius * 0.76f, minutes * 6f - 90f, dpF(3.0f), lightGold)
        drawHand(canvas, cx, cy, faceRadius * 0.84f, seconds * 6f - 90f, dpF(1.1f), gold)

        // Small centre cap, matching the gold/black visual language of the hero.
        paint.style = Paint.Style.FILL
        paint.color = gold
        canvas.drawCircle(cx, cy, dpF(5.2f), paint)
        paint.color = black
        canvas.drawCircle(cx, cy, dpF(2.0f), paint)

        // Smooth second-hand movement while the view is visible.
        postInvalidateDelayed(50L)
    }

    private fun drawHand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        length: Float,
        angle: Float,
        strokeWidth: Float,
        color: Int
    ) {
        val radians = Math.toRadians(angle.toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = strokeWidth
        paint.color = color
        paint.setShadowLayer(dpF(2f), 0f, dpF(1f), Color.BLACK)
        canvas.drawLine(
            cx,
            cy,
            cx + cos(radians).toFloat() * length,
            cy + sin(radians).toFloat() * length,
            paint
        )
        paint.clearShadowLayer()
    }

    private fun dpF(value: Float) = value * resources.displayMetrics.density
}
