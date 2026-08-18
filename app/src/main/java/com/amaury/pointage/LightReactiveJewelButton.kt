package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
    protected var jewelLightAngle = -55f
    protected var jewelAccent = Color.parseColor("#D6A84B")
    protected var jewelAccentLight = Color.parseColor("#F3D58A")
    private var nightLight = false

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        isAllCaps = false
    }

    open fun setLightAngle(angle: Float) {
        val normalized = ((angle % 360f) + 360f) % 360f
        if (kotlin.math.abs(shortestDelta(jewelLightAngle, normalized)) < 0.6f) return
        jewelLightAngle = normalized
        invalidate()
    }

    open fun setJewelAccent(color: Int, lightColor: Int) {
        jewelAccent = color
        jewelAccentLight = lightColor
        invalidate()
    }

    open fun setNightLight(enabled: Boolean) {
        if (nightLight == enabled) return
        nightLight = enabled
        invalidate()
    }

    private fun ensureBitmap() {
        if (bitmap != null) return
        val resId = when (id) {
            R.id.entryButton -> R.drawable.luxury_entry
            R.id.pauseButton -> R.drawable.luxury_entry
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

        val isPause = id == R.id.pauseButton
        if (isPause) {
            imagePaint.colorFilter = PorterDuffColorFilter(Color.parseColor("#F28C28"), PorterDuff.Mode.SRC_ATOP)
        }

        val save = canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawBitmap(source, null, dst, imagePaint)
        imagePaint.colorFilter = null

        val rad = Math.toRadians(jewelLightAngle.toDouble())
        val lx = cx + (cos(rad) * radius * 0.42).toFloat()
        val ly = cy + (sin(rad) * radius * 0.42).toFloat()

        val lightColors = if (nightLight) {
            intArrayOf(
                Color.argb(if (isPressed) 95 else 170, 238, 246, 255),
                Color.argb(if (isPressed) 42 else 88, 165, 195, 235),
                Color.TRANSPARENT
            )
        } else {
            intArrayOf(
                Color.argb(if (isPressed) 105 else 190, 255, 255, 245),
                Color.argb(if (isPressed) 48 else 92, 255, 231, 165),
                Color.TRANSPARENT
            )
        }

        lightPaint.shader = RadialGradient(
            lx, ly, radius * 0.72f,
            lightColors,
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
                Color.argb(if (isPressed) 92 else if (nightLight) 92 else 74, 0, 0, 0),
                Color.argb(if (nightLight) 38 else 28, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, shadePaint)
        shadePaint.shader = null

        canvas.restoreToCount(save)

        val normalAccent = if (isPause) Color.parseColor("#F57C00") else jewelAccent
        val normalAccentLight = if (isPause) Color.parseColor("#FFB74D") else jewelAccentLight
        ringPaint.strokeWidth = 2.4f * density
        ringPaint.color = if (nightLight && !isPause) Color.parseColor("#AFC7E8") else normalAccent
        canvas.drawCircle(cx, cy, radius - 1.2f * density, ringPaint)
        ringPaint.strokeWidth = 0.9f * density
        ringPaint.color = if (nightLight && !isPause) Color.parseColor("#EAF2FF") else normalAccentLight
        canvas.drawCircle(cx, cy, radius - 4.2f * density, ringPaint)

        super.onDraw(canvas)
    }

    protected fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
}
