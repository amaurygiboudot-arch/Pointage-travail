package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Base64
import java.util.WeakHashMap

class CarbonCompositeDrawable(context: Context) : Drawable() {
    companion object {
        private val instances = WeakHashMap<CarbonCompositeDrawable, Unit>()
        private var sharedLightAngle = -55f
        private var sharedNight = false

        @Synchronized
        fun updateGlobalLight(angle: Float, night: Boolean) {
            sharedLightAngle = ((angle % 360f) + 360f) % 360f
            sharedNight = night
            instances.keys.toList().forEach { it.applyCelestialLight(sharedLightAngle, sharedNight) }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 255
        colorFilter = null
    }
    private val dst = RectF()
    private val frameBand = Path()
    private val innerPath = Path()
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)

    private var globalAlpha = 255
    private var lightAngle = sharedLightAngle
    private var nightLight = sharedNight

    init { synchronized(CarbonCompositeDrawable::class.java) { instances[this] = Unit } }

    private fun applyCelestialLight(angle: Float, night: Boolean) {
        lightAngle = angle
        nightLight = night
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        dst.set(bounds)

        val outerRadius = dst.height() * .48f
        val frameThickness = (dst.height() * .145f).coerceAtLeast(4f)
        val inner = ButtonFrameGeometry.buildBand(
            target = dst,
            outerRadius = outerRadius,
            thickness = frameThickness,
            outPath = frameBand,
            innerPath = innerPath
        ) ?: return

        // Centre : texture carbone générée directement, sans image, sans marge noire.
        canvas.save()
        canvas.clipPath(innerPath)
        drawProceduralCarbon(canvas, inner)
        canvas.restore()

        // Cadre : image dédiée, strictement limitée à la couronne.
        canvas.save()
        canvas.clipPath(frameBand)
        frameBitmap?.let { OriginalButtonImageRenderer.draw(canvas, it, dst) }
            ?: drawFallbackFrameBand(canvas, dst, outerRadius, frameThickness)
        canvas.restore()

        // Éclairage dynamique toujours désactivé pendant le test.
    }

    private fun drawProceduralCarbon(canvas: Canvas, target: RectF) {
        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
        paint.shader = LinearGradient(
            target.left, target.top, target.left, target.bottom,
            intArrayOf(Color.rgb(26, 27, 29), Color.rgb(8, 9, 10), Color.rgb(18, 19, 21)),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)
        paint.shader = null

        val cell = (target.height() / 6.2f).coerceAtLeast(5f)
        val strandW = cell * .58f
        val strandH = cell * .34f
        val rows = (target.height() / strandH).toInt() + 4
        val cols = (target.width() / strandW).toInt() + 6

        for (row in -2..rows) {
            for (col in -3..cols) {
                val x = target.left + col * strandW + if (row % 2 == 0) 0f else strandW * .5f
                val y = target.top + row * strandH
                val bright = (row + col) % 2 == 0
                paint.color = if (bright) Color.rgb(58, 61, 66) else Color.rgb(15, 16, 18)
                paint.alpha = (globalAlpha * if (bright) .88f else .96f).toInt().coerceIn(0, 255)

                val p = Path().apply {
                    moveTo(x, y + strandH)
                    lineTo(x + strandW * .45f, y)
                    lineTo(x + strandW, y)
                    lineTo(x + strandW * .55f, y + strandH)
                    close()
                }
                canvas.drawPath(p, paint)
            }
        }

        // Finition résine légère pour donner du relief sans masquer le tressage.
        paint.alpha = globalAlpha
        paint.shader = LinearGradient(
            target.left, target.top, target.left, target.bottom,
            intArrayOf(Color.argb(75, 255, 255, 255), Color.TRANSPARENT, Color.argb(60, 0, 0, 0)),
            floatArrayOf(0f, .28f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)
        paint.shader = null
        paint.alpha = globalAlpha
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
        val raw = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        val encoded = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun drawFallbackFrameBand(canvas: Canvas, target: RectF, radius: Float, thickness: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness
        paint.alpha = globalAlpha
        paint.colorFilter = null
        paint.shader = LinearGradient(
            target.left, target.top, target.right, target.bottom,
            intArrayOf(Color.WHITE, Color.rgb(75, 80, 86), Color.rgb(235, 238, 241), Color.rgb(55, 59, 64)),
            null, Shader.TileMode.CLAMP
        )
        val inset = thickness / 2f
        canvas.drawRoundRect(
            RectF(target.left + inset, target.top + inset, target.right - inset, target.bottom - inset),
            (radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f), paint
        )
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) { invalidateSelf() }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
