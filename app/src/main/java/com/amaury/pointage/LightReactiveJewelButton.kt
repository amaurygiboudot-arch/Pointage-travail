package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.Button
import kotlin.math.min

/**
 * Boutons d'action HP composés de ressources Android natives.
 * Les anciens PNG séparés ont été remplacés par des drawables XML afin
 * d'éviter les erreurs de compilation AAPT2 tout en gardant les 3 couleurs.
 */
open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val pauseGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundLayer: Drawable? = null
    private var iconLayer: Drawable? = null
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

    @Suppress("DEPRECATION")
    private fun drawable(resId: Int): Drawable? =
        if (android.os.Build.VERSION.SDK_INT >= 21) resources.getDrawable(resId, context.theme)
        else resources.getDrawable(resId)

    private fun ensureLayers() {
        if (loadedForId == id && backgroundLayer != null) return
        loadedForId = id
        backgroundLayer = when (id) {
            R.id.entryButton -> drawable(R.drawable.hp_button_bg_green)
            R.id.pauseButton -> drawable(R.drawable.hp_button_bg_orange)
            R.id.exitButton -> drawable(R.drawable.hp_button_bg_red)
            else -> null
        }
        iconLayer = if (id == R.id.entryButton || id == R.id.exitButton) {
            drawable(R.drawable.hp_button_icon_entry)
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
        val left = (cx - radius).toInt()
        val top = (cy - radius).toInt()
        val right = (cx + radius).toInt()
        val bottom = (cy + radius).toInt()

        backgroundLayer?.let {
            it.alpha = if (isPressed) 220 else 255
            it.setBounds(left, top, right, bottom)
            it.draw(canvas)
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
        icon.alpha = if (isPressed) 205 else 245
        icon.setBounds((cx-r).toInt(), (cy-r).toInt(), (cx+r).toInt(), (cy+r).toInt())
        canvas.save()
        if (mirror) canvas.scale(-1f, 1f, cx, cy)
        icon.draw(canvas)
        canvas.restore()
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
        canvas.drawRoundRect(cx-gap-barW, top, cx-gap, bottom, corner, corner, pauseGlyphPaint)
        canvas.drawRoundRect(cx+gap, top, cx+gap+barW, bottom, corner, corner, pauseGlyphPaint)
    }

    protected fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f

    private companion object { const val ViewIdNone = -1 }
}
