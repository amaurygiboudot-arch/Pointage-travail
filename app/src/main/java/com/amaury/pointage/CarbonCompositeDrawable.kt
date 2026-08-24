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
import android.graphics.Rect
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 255
        colorFilter = null
    }
    private val dst = RectF()
    private val frameBand = Path()
    private val innerPath = Path()

    private val fillBitmap: Bitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.carbon_button_fill)
    }.getOrNull()
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)

    private var globalAlpha = 255
    private var lightAngle = sharedLightAngle
    private var nightLight = sharedNight

    init {
        synchronized(CarbonCompositeDrawable::class.java) { instances[this] = Unit }
    }

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

        // CADRE CARBONE/METAL SANS ECLAIRAGE DYNAMIQUE (test demandé).
        canvas.save()
        canvas.clipPath(frameBand)
        frameBitmap?.let { OriginalButtonImageRenderer.draw(canvas, it, dst) }
            ?: drawFallbackFrameBand(canvas, dst, outerRadius, frameThickness)
        canvas.restore()

        // FOND CARBONE EN DERNIER : il remplit toute la zone intérieure utile.
        canvas.save()
        canvas.clipPath(innerPath)
        fillBitmap?.let { drawCarbonFill(canvas, it, inner) }
        canvas.restore()
    }

    private fun drawCarbonFill(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || target.width() <= 0f || target.height() <= 0f) return

        val left = (bitmap.width * 0.010f).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * 0.105f).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * 0.990f).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * 0.930f).toInt().coerceIn(top + 1, bitmap.height)
        val source = Rect(left, top, right, bottom)

        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
        paint.colorFilter = null
        paint.shader = null
        canvas.drawBitmap(bitmap, source, target, paint)
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

    override fun setColorFilter(colorFilter: ColorFilter?) {
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
