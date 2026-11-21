package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sélecteur HSV circulaire sans dépendance externe.
 * La roue choisit teinte + saturation ; la luminosité est pilotée séparément.
 */
class ColorWheelPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 3f
        color = Color.WHITE
        setShadowLayer(context.resources.displayMetrics.density * 2f, 0f, 0f, Color.BLACK)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wheelBitmap: Bitmap? = null
    private var hue = 0f
    private var saturation = 0f
    private var value = 1f

    var onColorChanged: ((Int) -> Unit)? = null

    fun setColor(color: Int, notify: Boolean = false) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1].coerceIn(0f, 1f)
        value = hsv[2].coerceIn(0f, 1f)
        invalidate()
        if (notify) onColorChanged?.invoke(selectedColor())
    }

    fun setBrightness(brightness: Float, notify: Boolean = true) {
        value = brightness.coerceIn(0f, 1f)
        invalidate()
        if (notify) onColorChanged?.invoke(selectedColor())
    }

    fun brightness(): Float = value

    fun selectedColor(): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildWheelBitmap(w, h)
    }

    private fun buildWheelBitmap(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val side = min(w, h)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(side * side)
        val cx = (side - 1) / 2f
        val cy = (side - 1) / 2f
        val radius = side / 2f

        for (y in 0 until side) {
            val dy = y - cy
            for (x in 0 until side) {
                val dx = x - cx
                val distance = sqrt(dx * dx + dy * dy)
                val index = y * side + x
                if (distance <= radius) {
                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (angle < 0f) angle += 360f
                    pixels[index] = Color.HSVToColor(
                        floatArrayOf(angle, (distance / radius).coerceIn(0f, 1f), 1f)
                    )
                } else {
                    pixels[index] = Color.TRANSPARENT
                }
            }
        }
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        wheelBitmap?.recycle()
        wheelBitmap = bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = wheelBitmap ?: return
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + side, top + side), bitmapPaint)

        // Assombrit uniformément la roue pour refléter la luminosité sélectionnée.
        if (value < 1f) {
            val shade = ((1f - value) * 210f).toInt().coerceIn(0, 210)
            val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(shade, 0, 0, 0) }
            canvas.drawCircle(width / 2f, height / 2f, side / 2f, shadePaint)
        }

        val radius = side / 2f
        val angle = Math.toRadians(hue.toDouble())
        val markerRadius = radius * saturation
        val mx = width / 2f + (cos(angle) * markerRadius).toFloat()
        val my = height / 2f + (sin(angle) * markerRadius).toFloat()
        canvas.drawCircle(mx, my, context.resources.displayMetrics.density * 8f, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) {
            return event.action == MotionEvent.ACTION_UP || super.onTouchEvent(event)
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        val radius = min(width, height) / 2f
        val distance = sqrt(dx * dx + dy * dy)
        saturation = (distance / radius).coerceIn(0f, 1f)
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        hue = angle
        invalidate()
        onColorChanged?.invoke(selectedColor())
        return true
    }

    override fun onDetachedFromWindow() {
        wheelBitmap?.recycle()
        wheelBitmap = null
        super.onDetachedFromWindow()
    }
}
