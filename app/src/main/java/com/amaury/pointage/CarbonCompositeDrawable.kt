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

        // Fond carbone d'abord : il couvre réellement toute la capsule intérieure.
        // On ne dépend plus des marges noires contenues dans l'image source.
        canvas.save()
        canvas.clipPath(innerPath)
        fillBitmap?.let { drawCarbonFillCover(canvas, it, inner) }
        canvas.restore()

        // Cadre ensuite, mais strictement limité à sa couronne.
        canvas.save()
        canvas.clipPath(frameBand)
        frameBitmap?.let { OriginalButtonImageRenderer.draw(canvas, it, dst) }
            ?: drawFallbackFrameBand(canvas, dst, outerRadius, frameThickness)
        canvas.restore()

        // Éclairage dynamique volontairement désactivé pendant le diagnostic.
    }

    private fun drawCarbonFillCover(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || target.width() <= 0f || target.height() <= 0f) return

        // L'image fournie possède une capsule carbone entourée de noir.
        // On prélève uniquement la matière utile puis on applique un vrai CENTER_CROP
        // pour remplir 100 % de l'intérieur, quelle que soit la hauteur du bouton.
        val usefulLeft = (bitmap.width * 0.015f).toInt().coerceIn(0, bitmap.width - 1)
        val usefulTop = (bitmap.height * 0.105f).toInt().coerceIn(0, bitmap.height - 1)
        val usefulRight = (bitmap.width * 0.985f).toInt().coerceIn(usefulLeft + 1, bitmap.width)
        val usefulBottom = (bitmap.height * 0.925f).toInt().coerceIn(usefulTop + 1, bitmap.height)

        val usefulW = usefulRight - usefulLeft
        val usefulH = usefulBottom - usefulTop
        val targetRatio = target.width() / target.height()
        val sourceRatio = usefulW.toFloat() / usefulH.toFloat()

        val source = if (sourceRatio > targetRatio) {
            // Source trop large : coupe uniquement les côtés.
            val wantedW = (usefulH * targetRatio).toInt().coerceAtLeast(1)
            val x = usefulLeft + (usefulW - wantedW) / 2
            Rect(x, usefulTop, (x + wantedW).coerceAtMost(usefulRight), usefulBottom)
        } else {
            // Source trop haute : coupe uniquement haut/bas, jamais de bande noire ajoutée.
            val wantedH = (usefulW / targetRatio).toInt().coerceAtLeast(1)
            val y = usefulTop + (usefulH - wantedH) / 2
            Rect(usefulLeft, y, usefulRight, (y + wantedH).coerceAtMost(usefulBottom))
        }

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

    override fun setColorFilter(colorFilter: ColorFilter?) { invalidateSelf() }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
