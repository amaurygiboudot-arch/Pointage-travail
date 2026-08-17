package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class HpAnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gold = Color.parseColor("#D6A84B")
    private val lightGold = Color.parseColor("#F3D58A")
    private val black = Color.parseColor("#090909")
    private val white = Color.parseColor("#F4EFE3")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = RectF()

    init {
        minimumHeight = dp(300)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = min(width - dp(24), dp(340)).coerceAtLeast(dp(250))
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.44f

        paint.style = Paint.Style.FILL
        paint.color = black
        paint.setShadowLayer(dpF(16f), 0f, dpF(6f), Color.argb(180, 0, 0, 0))
        canvas.drawCircle(cx, cy, radius, paint)
        paint.clearShadowLayer()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpF(5f)
        paint.color = gold
        canvas.drawCircle(cx, cy, radius, paint)
        paint.strokeWidth = dpF(1.5f)
        paint.color = lightGold
        canvas.drawCircle(cx, cy, radius - dpF(8f), paint)

        for (i in 0 until 60) {
            val a = Math.toRadians((i * 6 - 90).toDouble())
            val major = i % 5 == 0
            val outer = radius - dpF(14f)
            val inner = outer - if (major) dpF(20f) else dpF(8f)
            paint.strokeWidth = if (major) dpF(3f) else dpF(1f)
            paint.color = if (major) lightGold else gold
            canvas.drawLine(
                cx + cos(a).toFloat() * inner,
                cy + sin(a).toFloat() * inner,
                cx + cos(a).toFloat() * outer,
                cy + sin(a).toFloat() * outer,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.color = gold
        paint.textSize = radius * 0.13f
        paint.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
        canvas.drawText("♛", cx, cy - radius * 0.34f, paint)
        paint.textSize = radius * 0.12f
        canvas.drawText("H P", cx, cy - radius * 0.18f, paint)

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = (now.get(Calendar.HOUR) % 12) + minutes / 60f

        drawHand(canvas, cx, cy, radius * 0.48f, hours * 30f - 90f, dpF(7f), lightGold)
        drawHand(canvas, cx, cy, radius * 0.70f, minutes * 6f - 90f, dpF(5f), lightGold)
        drawHand(canvas, cx, cy, radius * 0.76f, seconds * 6f - 90f, dpF(1.5f), gold)

        paint.style = Paint.Style.FILL
        paint.color = gold
        canvas.drawCircle(cx, cy, dpF(8f), paint)
        paint.color = black
        canvas.drawCircle(cx, cy, dpF(3f), paint)

        postInvalidateDelayed(250L)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, length: Float, angle: Float, width: Float, color: Int) {
        val a = Math.toRadians(angle.toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width
        paint.color = color
        paint.setShadowLayer(dpF(3f), 0f, dpF(1f), Color.BLACK)
        canvas.drawLine(cx, cy, cx + cos(a).toFloat() * length, cy + sin(a).toFloat() * length, paint)
        paint.clearShadowLayer()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float) = v * resources.displayMetrics.density
}
