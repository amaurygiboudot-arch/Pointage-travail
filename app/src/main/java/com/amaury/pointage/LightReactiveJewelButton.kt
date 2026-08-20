package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.Button
import kotlin.math.min

/**
 * Boutons d'action HP composés de calques PNG indépendants :
 * - fond vert / orange / rouge
 * - icône céleste séparée pour Entrée et Sortie
 * - symbole pause dessiné séparément
 *
 * Les anciens bijoux complets (luxury_entry / luxury_exit), leur recoloration et
 * leurs cerclages dessinés par Canvas ne sont plus utilisés ici.
 */
open class LightReactiveJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pauseGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundDst = RectF()
    private val iconDst = RectF()

    private var backgroundBitmap: Bitmap? = null
    private var iconBitmap: Bitmap? = null
    private var loadedForId: Int = ViewIdNone

    // Conservés pour rester compatibles avec le système d'éclairage existant.
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

    private fun ensureBitmaps() {
        if (loadedForId == id && backgroundBitmap != null) return

        loadedForId = id
        backgroundBitmap = null
        iconBitmap = null

        val backgroundRes = when (id) {
            R.id.entryButton -> R.drawable.hp_button_bg_green
            R.id.pauseButton -> R.drawable.hp_button_bg_orange
            R.id.exitButton -> R.drawable.hp_button_bg_red
            else -> 0
        }

        if (backgroundRes != 0) {
            backgroundBitmap = BitmapFactory.decodeResource(resources, backgroundRes)
        }

        if (id == R.id.entryButton || id == R.id.exitButton) {
            iconBitmap = BitmapFactory.decodeResource(resources, R.drawable.hp_button_icon_entry)
        }
    }

    override fun onDraw(canvas: Canvas) {
        ensureBitmaps()
        val source = backgroundBitmap

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = min(w, h) * 0.50f
        val cx = w * 0.50f
        val cy = h * 0.50f
        backgroundDst.set(cx - radius, cy - radius, cx + radius, cy + radius)

        if (source != null) {
            imagePaint.alpha = if (isPressed) 220 else 255
            imagePaint.colorFilter = null
            canvas.drawBitmap(source, null, backgroundDst, imagePaint)
        }

        when (id) {
            R.id.entryButton -> drawCelestialIcon(canvas, cx, cy, radius, mirror = false)
            R.id.exitButton -> drawCelestialIcon(canvas, cx, cy, radius, mirror = true)
            R.id.pauseButton -> drawPauseGlyph(canvas, cx, cy, radius)
        }

        imagePaint.alpha = 255
        super.onDraw(canvas)
    }

    private fun drawCelestialIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        mirror: Boolean
    ) {
        val icon = iconBitmap ?: return

        // Laisse bien voir le fond translucide autour du pictogramme.
        val iconRadius = radius * 0.55f
        iconDst.set(
            cx - iconRadius,
            cy - iconRadius,
            cx + iconRadius,
            cy + iconRadius
        )

        imagePaint.alpha = if (isPressed) 205 else 245
        canvas.save()
        if (mirror) {
            // Le même calque PNG indépendant sert à Sortie, simplement retourné.
            canvas.scale(-1f, 1f, cx, cy)
        }
        canvas.drawBitmap(icon, null, iconDst, imagePaint)
        canvas.restore()
    }

    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        pauseGlyphPaint.style = Paint.Style.FILL
        pauseGlyphPaint.color = Color.argb(
            if (isPressed) 205 else 245,
            224,
            170,
            72
        )

        val barW = radius * 0.15f
        val barH = radius * 0.58f
        val gap = radius * 0.11f
        val top = cy - barH * 0.50f
        val bottom = cy + barH * 0.50f
        val corner = barW * 0.28f

        canvas.drawRoundRect(
            cx - gap - barW,
            top,
            cx - gap,
            bottom,
            corner,
            corner,
            pauseGlyphPaint
        )
        canvas.drawRoundRect(
            cx + gap,
            top,
            cx + gap + barW,
            bottom,
            corner,
            corner,
            pauseGlyphPaint
        )
    }

    protected fun shortestDelta(a: Float, b: Float): Float =
        ((b - a + 540f) % 360f) - 180f

    private companion object {
        const val ViewIdNone = -1
    }
}
