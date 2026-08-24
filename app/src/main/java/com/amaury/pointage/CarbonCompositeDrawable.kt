package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
            instances.keys.toList().forEach { it.invalidateSelf() }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val frameBand = Path()
    private val innerPath = Path()

    private val fillBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)
    private val fillSource: Rect? = fillBitmap?.let { visibleContentBounds(it) }
    private val frameSource: Rect? = frameBitmap?.let { visibleContentBounds(it) }

    private var globalAlpha = 255

    init { synchronized(CarbonCompositeDrawable::class.java) { instances[this] = Unit } }

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

        paint.alpha = globalAlpha
        paint.colorFilter = null

        // Le fond Base64 peut contenir du vide/noir autour du bouton.
        // On n'étire que sa zone réellement visible pour conserver toute sa hauteur.
        fillBitmap?.let { bitmap ->
            val src = fillSource ?: Rect(0, 0, bitmap.width, bitmap.height)
            canvas.save()
            canvas.clipPath(innerPath)
            canvas.drawBitmap(bitmap, src, inner, paint)
            canvas.restore()
        }

        // Même principe pour le cadre : on retire automatiquement ses marges externes,
        // puis on le pose sur toute la capsule. Son centre transparent laisse voir le carbone.
        frameBitmap?.let { bitmap ->
            val src = frameSource ?: Rect(0, 0, bitmap.width, bitmap.height)
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
    }

    /**
     * Cherche la vraie emprise graphique de l'image.
     * Compatible avec un extérieur transparent OU noir, sans recadrage en pourcentage.
     */
    private fun visibleContentBounds(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 1 || h <= 1) return Rect(0, 0, w, h)

        var left = w
        var top = h
        var right = -1
        var bottom = -1
        val pixels = IntArray(w)

        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = pixels[x]
                val a = Color.alpha(c)
                val peak = maxOf(Color.red(c), Color.green(c), Color.blue(c))
                if (a > 16 && peak > 12) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }

        if (right < left || bottom < top) return Rect(0, 0, w, h)

        // Petite marge de sécurité pour ne pas rogner l'anti-aliasing/reflet du bord.
        val padX = (w * .004f).toInt().coerceAtLeast(2)
        val padY = (h * .008f).toInt().coerceAtLeast(2)
        return Rect(
            (left - padX).coerceAtLeast(0),
            (top - padY).coerceAtLeast(0),
            (right + padX + 1).coerceAtMost(w),
            (bottom + padY + 1).coerceAtMost(h)
        )
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
        val raw = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        val encoded = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
