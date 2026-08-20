package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Base64
import android.widget.Button
import kotlin.math.min

/**
 * Boutons d'action HP utilisant les visuels générés d'origine.
 * Les PNG sont stockés encodés dans res/raw puis décodés à l'exécution afin
 * qu'AAPT2 ne tente pas de compiler directement les fichiers image.
 */
open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pauseGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundLayer: Bitmap? = null
    private var iconLayer: Bitmap? = null
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
        iconLayer = if (id == R.id.entryButton || id == R.id.exitButton) {
            decodeRawBitmap(R.raw.hp_button_icon_entry_b64)
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        ensureLayers()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = min(w, h) * 0.50f
        val cx = w * 0.50f
        val cy = h * 0.50f
        val dst = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        backgroundLayer?.let { bitmap ->
            bitmapPaint.alpha = if (isPressed) 220 else 255
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
            bitmapPaint.alpha = 255
        }

        when (id) {
            R.id.entryButton -> drawCelestialIcon(canvas, cx, cy, radius, false)
            R.id.exitButton -> drawCelestialIcon(canvas, cx, cy, radius, true)
            R.id.pauseButton -> drawPauseGlyph(canvas, cx, cy, radius)
        }
        super.onDraw(canvas)
    }

    private fun drawCelestialIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, mirror: Boolean) {
        val icon = iconLayer ?: return
        val r = radius * 0.55f
        val dst = RectF(cx - r, cy - r, cx + r, cy + r)
        bitmapPaint.alpha = if (isPressed) 205 else 245
        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, cx, cy)
        canvas.drawBitmap(icon, null, dst, bitmapPaint)
        canvas.restore()
        bitmapPaint.alpha = 255
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        pauseGlyphPaint.style = Paint.Style.FILL
        pauseGlyphPaint.color = Color.argb(if (isPressed) 205 else 245, 224, 170, 72)
        val barW = radius * 0.15f
        val barH = radius * 0.58f
        val gap = radius * 0.11f
        val top = cy - barH * 0.50f
        val bottom = cy + barH * 0.50f
        val corner = barW * 0.28f
        canvas.drawRoundRect(cx - gap - barW, top, cx - gap, bottom, corner, corner, pauseGlyphPaint)
        canvas.drawRoundRect(cx + gap, top, cx + gap + barW, bottom, corner, corner, pauseGlyphPaint)
    }

    protected fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    private companion object { const val ViewIdNone = -1 }
}
