package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.util.Base64
import android.widget.Button
import kotlin.math.min

/**
 * Boutons d'action HP utilisant les visuels générés d'origine.
 * Le fond gemme reste celui sauvegardé. Le cerclage céleste est rendu sous
 * le cristal. Les couches sont pré-rendues en haute définition puis réduites
 * proprement pour maximiser la netteté à l'écran.
 */
open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = false
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private val pauseGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = false }
    private var backgroundLayer: Bitmap? = null
    private var loadedForId: Int = ViewIdNone

    protected var jewelLightAngle = -55f
    protected var jewelAccent = Color.parseColor("#D6A84B")
    protected var jewelAccentLight = Color.parseColor("#F3D58A")
    private var nightLight = false

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        isAllCaps = false
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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

    private fun decodeRawBitmap(resId: Int): Bitmap? = runCatching {
        val encoded = resources.openRawResource(resId).bufferedReader().use { it.readText() }
        val bytes = Base64.decode(encoded.trim(), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun ensureLayers() {
        if (loadedForId == id && backgroundLayer != null) return
        loadedForId = id
        backgroundLayer = when (id) {
            R.id.entryButton -> decodeRawBitmap(R.raw.hp_button_bg_green_b64)
            R.id.pauseButton -> decodeRawBitmap(R.raw.hp_button_bg_orange_b64)
            R.id.exitButton -> decodeRawBitmap(R.raw.hp_button_bg_red_b64)
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        ensureLayers()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Sur-échantillonnage x2 : contours, cerclage et pictogrammes sont calculés
        // sur deux fois plus de pixels puis réduits par Android.
        val save = canvas.save()
        canvas.scale(0.5f, 0.5f)
        val rw = w * 2f
        val rh = h * 2f
        val radius = min(rw, rh) * 0.50f
        val cx = rw * 0.50f
        val cy = rh * 0.50f

        drawCelestialFrame(canvas, cx, cy, radius)

        val jewelRadius = radius * 0.885f
        val dst = RectF(cx - jewelRadius, cy - jewelRadius, cx + jewelRadius, cy + jewelRadius)

        backgroundLayer?.let { bitmap ->
            // Contraste et micro-accentuation plus francs pour faire ressortir les facettes.
            val contrast = 1.24f
            val offset = -128f * contrast + 128f + 4f
            bitmapPaint.colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
            bitmapPaint.alpha = if (isPressed) 220 else 255
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
            bitmapPaint.alpha = 255
            bitmapPaint.colorFilter = null
        }

        when (id) {
            R.id.entryButton -> drawCelestialIcon(canvas, cx, cy, jewelRadius, false)
            R.id.exitButton -> drawCelestialIcon(canvas, cx, cy, jewelRadius, true)
            R.id.pauseButton -> drawPauseGlyph(canvas, cx, cy, jewelRadius)
        }
        canvas.restoreToCount(save)
        super.onDraw(canvas)
    }

    private fun drawCelestialFrame(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val alpha = if (isPressed) 225 else 255

        framePaint.style = Paint.Style.STROKE
        framePaint.strokeCap = Paint.Cap.ROUND
        framePaint.strokeWidth = radius * 0.090f
        framePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(alpha, 82, 42, 2), Color.argb(alpha, 255, 226, 116),
                Color.argb(alpha, 160, 88, 6), Color.argb(alpha, 255, 247, 184),
                Color.argb(alpha, 91, 47, 3), Color.argb(alpha, 255, 218, 85),
                Color.argb(alpha, 82, 42, 2)
            ), null
        )
        canvas.drawCircle(cx, cy, radius * 0.950f, framePaint)
        framePaint.shader = null

        framePaint.strokeWidth = radius * 0.082f
        framePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(alpha, 0, 8, 35), Color.argb(alpha, 5, 91, 220),
                Color.argb(alpha, 0, 15, 62), Color.argb(alpha, 24, 126, 255),
                Color.argb(alpha, 0, 13, 51), Color.argb(alpha, 4, 80, 202),
                Color.argb(alpha, 0, 8, 35)
            ), null
        )
        canvas.drawCircle(cx, cy, radius * 0.915f, framePaint)
        framePaint.shader = null

        framePaint.strokeWidth = radius * 0.027f
        framePaint.color = Color.argb(alpha, 255, 202, 62)
        canvas.drawCircle(cx, cy, radius * 0.878f, framePaint)
        framePaint.strokeWidth = radius * 0.009f
        framePaint.color = Color.argb(alpha, 255, 248, 190)
        canvas.drawCircle(cx, cy, radius * 0.893f, framePaint)

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeCap = Paint.Cap.SQUARE
        detailPaint.strokeWidth = radius * 0.010f
        detailPaint.color = Color.argb(if (nightLight) 180 else 245, 255, 245, 184)
        val d = radius * 0.952f
        val sparkle = radius * 0.046f
        drawSparkle(canvas, cx, cy - d, sparkle)
        drawSparkle(canvas, cx + d, cy, sparkle * 0.84f)
        drawSparkle(canvas, cx, cy + d, sparkle * 0.80f)
        drawSparkle(canvas, cx - d, cy, sparkle * 0.84f)
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, size: Float) {
        canvas.drawLine(x - size, y, x + size, y, detailPaint)
        canvas.drawLine(x, y - size, x, y + size, detailPaint)
    }

    private fun drawCelestialIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, mirror: Boolean) {
        val iconRadius = radius * 0.285f
        val alpha = if (isPressed) 220 else 255

        iconPaint.style = Paint.Style.FILL
        iconPaint.shader = RadialGradient(
            cx - iconRadius * 0.22f, cy - iconRadius * 0.25f, iconRadius * 1.30f,
            intArrayOf(
                Color.argb(alpha, 24, 112, 214),
                Color.argb(alpha, 2, 35, 104),
                Color.argb(alpha, 0, 7, 28)
            ), floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, iconRadius, iconPaint)
        iconPaint.shader = null

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeCap = Paint.Cap.ROUND
        detailPaint.color = Color.argb(alpha, 255, 205, 70)
        detailPaint.strokeWidth = radius * 0.032f
        canvas.drawCircle(cx, cy, iconRadius * 0.96f, detailPaint)
        detailPaint.color = Color.argb(alpha, 255, 242, 168)
        detailPaint.strokeWidth = radius * 0.010f
        canvas.drawCircle(cx, cy, iconRadius * 0.78f, detailPaint)

        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, cx, cy)
        val arrow = Path().apply {
            val x0 = cx - iconRadius * 0.50f
            val x1 = cx + iconRadius * 0.18f
            val tip = cx + iconRadius * 0.58f
            val half = iconRadius * 0.18f
            val head = iconRadius * 0.33f
            moveTo(x0, cy - half); lineTo(x1, cy - half); lineTo(x1, cy - head)
            lineTo(tip, cy); lineTo(x1, cy + head); lineTo(x1, cy + half)
            lineTo(x0, cy + half); close()
        }
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = Color.argb(alpha, 244, 180, 43)
        canvas.drawPath(arrow, iconPaint)
        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeJoin = Paint.Join.ROUND
        detailPaint.strokeWidth = radius * 0.018f
        detailPaint.color = Color.argb(alpha, 255, 244, 174)
        canvas.drawPath(arrow, detailPaint)
        canvas.restore()
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val alpha = if (isPressed) 215 else 255
        val barW = radius * 0.145f
        val barH = radius * 0.54f
        val gap = radius * 0.105f
        val top = cy - barH * 0.50f
        val bottom = cy + barH * 0.50f
        val corner = barW * 0.26f

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = radius * 0.024f
        detailPaint.color = Color.argb(alpha, 88, 45, 4)
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, detailPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, detailPaint)

        pauseGlyphPaint.style = Paint.Style.FILL
        pauseGlyphPaint.color = Color.argb(alpha, 248, 183, 48)
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, pauseGlyphPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, pauseGlyphPaint)

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = radius * 0.009f
        detailPaint.color = Color.argb(alpha, 255, 244, 176)
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, detailPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, detailPaint)
    }

    protected fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
    private companion object { const val ViewIdNone = -1 }
}
