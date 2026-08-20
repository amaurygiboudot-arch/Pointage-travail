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
import android.util.AttributeSet
import android.util.Base64
import android.widget.Button
import kotlin.math.min

/**
 * Boutons d'action HP utilisant les visuels générés d'origine.
 * Le fond gemme reste celui sauvegardé, tandis que les pictogrammes sont
 * tracés en vectoriel à la résolution de l'écran pour rester parfaitement nets.
 */
open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pauseGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

        val radius = min(w, h) * 0.50f
        val cx = w * 0.50f
        val cy = h * 0.50f
        val inset = radius * 0.018f
        val dst = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)

        backgroundLayer?.let { bitmap ->
            val contrast = 1.10f
            val offset = -128f * contrast + 128f + 2f
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
            bitmapPaint.alpha = if (isPressed) 215 else 255
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
            bitmapPaint.alpha = 255
            bitmapPaint.colorFilter = null
        }

        when (id) {
            R.id.entryButton -> drawCelestialIcon(canvas, cx, cy, radius, false)
            R.id.exitButton -> drawCelestialIcon(canvas, cx, cy, radius, true)
            R.id.pauseButton -> drawPauseGlyph(canvas, cx, cy, radius)
        }
        super.onDraw(canvas)
    }

    /** Icône nette dessinée à la résolution réelle de l'écran. */
    private fun drawCelestialIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, mirror: Boolean) {
        val iconRadius = radius * 0.285f
        val alpha = if (isPressed) 215 else 255

        iconPaint.style = Paint.Style.FILL
        iconPaint.shader = RadialGradient(
            cx - iconRadius * 0.22f,
            cy - iconRadius * 0.25f,
            iconRadius * 1.30f,
            intArrayOf(
                Color.argb(alpha, 18, 92, 172),
                Color.argb(alpha, 4, 35, 92),
                Color.argb(alpha, 1, 12, 35)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, iconRadius, iconPaint)
        iconPaint.shader = null

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeCap = Paint.Cap.ROUND
        detailPaint.color = Color.argb(alpha, 246, 202, 91)
        detailPaint.strokeWidth = radius * 0.030f
        canvas.drawCircle(cx, cy, iconRadius * 0.96f, detailPaint)

        detailPaint.color = Color.argb(alpha, 255, 231, 148)
        detailPaint.strokeWidth = radius * 0.012f
        canvas.drawCircle(cx, cy, iconRadius * 0.78f, detailPaint)

        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, cx, cy)

        val arrow = Path().apply {
            val x0 = cx - iconRadius * 0.50f
            val x1 = cx + iconRadius * 0.18f
            val tip = cx + iconRadius * 0.58f
            val half = iconRadius * 0.18f
            val head = iconRadius * 0.33f
            moveTo(x0, cy - half)
            lineTo(x1, cy - half)
            lineTo(x1, cy - head)
            lineTo(tip, cy)
            lineTo(x1, cy + head)
            lineTo(x1, cy + half)
            lineTo(x0, cy + half)
            close()
        }

        iconPaint.style = Paint.Style.FILL
        iconPaint.color = Color.argb(alpha, 232, 171, 49)
        canvas.drawPath(arrow, iconPaint)

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeJoin = Paint.Join.ROUND
        detailPaint.strokeWidth = radius * 0.020f
        detailPaint.color = Color.argb(alpha, 255, 236, 159)
        canvas.drawPath(arrow, detailPaint)
        canvas.restore()

        // Petit éclat discret, fixe et propre plutôt qu'un halo flou.
        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = radius * 0.010f
        detailPaint.color = Color.argb(if (nightLight) 150 else 205, 255, 239, 181)
        val sparkleX = cx + iconRadius * 0.63f
        val sparkleY = cy - iconRadius * 0.61f
        canvas.drawLine(sparkleX - radius * 0.05f, sparkleY, sparkleX + radius * 0.05f, sparkleY, detailPaint)
        canvas.drawLine(sparkleX, sparkleY - radius * 0.05f, sparkleX, sparkleY + radius * 0.05f, detailPaint)
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val alpha = if (isPressed) 210 else 255
        val barW = radius * 0.145f
        val barH = radius * 0.54f
        val gap = radius * 0.105f
        val top = cy - barH * 0.50f
        val bottom = cy + barH * 0.50f
        val corner = barW * 0.30f

        // Liseré sombre très fin pour que le symbole reste lisible sur toutes les facettes.
        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = radius * 0.026f
        detailPaint.color = Color.argb(alpha, 112, 67, 12)
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, detailPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, detailPaint)

        pauseGlyphPaint.style = Paint.Style.FILL
        pauseGlyphPaint.color = Color.argb(alpha, 239, 181, 61)
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, pauseGlyphPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, pauseGlyphPaint)

        detailPaint.style = Paint.Style.STROKE
        detailPaint.strokeWidth = radius * 0.010f
        detailPaint.color = Color.argb(alpha, 255, 232, 153)
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, detailPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, detailPaint)
    }

    protected fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    private companion object { const val ViewIdNone = -1 }
}
