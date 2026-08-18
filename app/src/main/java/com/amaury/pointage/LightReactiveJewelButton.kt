package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.Button
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clipPath = Path()
    private val dst = RectF()

    private var bitmap: Bitmap? = null
    protected var lightAngle = -55f
    protected var accent = Color.parseColor("#D6A84B")
    protected var accentLight = Color.parseColor("#F3D58A")

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        isAllCaps = false
    }

    open fun setLightAngle(angle: Float) {
        val normalized = ((angle % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(lightAngle, normalized)) < 0.6f) return
        lightAngle = normalized
        invalidate()
    }

    open fun setJewelAccent(color: Int, lightColor: Int) {
        accent = color
        accentLight = lightColor
        invalidate()
    }

    private fun ensureBitmap() {
        if (bitmap != null) return
        val resId = when (id) {
            R.id.entryButton -> R.drawable.luxury_entry
            R.id.exitButton -> R.drawable.luxury_exit
            else -> 0
        }
        if (resId != 0) bitmap = BitmapFactory.decodeResource(resources, resId)
    }

    override fun onDraw(canvas: Canvas) {
        ensureBitmap()
        val source = bitmap ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val radius = min(w, h) / 2f - 2f * density
        val cx = w / 2f
        val cy = h / 2f
        dst.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawBitmap(source, null, dst, imagePaint)

        val rad = Math.toRadians(lightAngle.toDouble())
        val lx = cx + (cos(rad) * radius * 0.42).toFloat()
        val ly = cy + (sin(rad) * radius * 0.42).toFloat()

        lightPaint.shader = RadialGradient(
            lx, ly, radius * 0.72f,
            intArrayOf(
                Color.argb(if (isPressed) 105 else 190, 255, 255, 245),
                Color.argb(if (isPressed) 48 else 92, 255, 231, 165),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, lightPaint)
        lightPaint.shader = null

        val sx = cx - (cos(rad) * radius * 0.36).toFloat()
        val sy = cy - (sin(rad) * radius * 0.36).toFloat()
        shadePaint.shader = RadialGradient(
            sx, sy, radius * 0.95f,
            intArrayOf(
                Color.argb(if (isPressed) 92 else 74, 0, 0, 0),
                Color.argb(28, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, shadePaint)
        shadePaint.shader = null

        canvas.restoreToCount(save)

        ringPaint.strokeWidth = 2.4f * density
        ringPaint.color = accent
        canvas.drawCircle(cx, cy, radius - 1.2f * density, ringPaint)
        ringPaint.strokeWidth = 0.9f * density
        ringPaint.color = accentLight
        canvas.drawCircle(cx, cy, radius - 4.2f * density, ringPaint)

        super.onDraw(canvas)
    }

    protected fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
}
