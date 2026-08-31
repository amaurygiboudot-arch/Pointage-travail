package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Base64

/**
 * Fond + cadre carbone.
 * - mode sombre : texture carbone originale ;
 * - mode clair : polarisation/inversion de la même texture.
 *
 * Les bitmaps sont décodés une seule fois par processus.
 */
class CarbonCompositeDrawable(
    context: Context,
    val lightMode: Boolean = !AppThemeCatalog.useDarkPalette(context)
) : Drawable() {
    companion object {
        @Volatile private var cachedFill: Bitmap? = null
        @Volatile private var cachedFrame: Bitmap? = null
        private val cacheLock = Any()

        private val lightFilter: ColorFilter by lazy {
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        private fun bitmap(context: Context, resId: Int, frame: Boolean): Bitmap? {
            val cached = if (frame) cachedFrame else cachedFill
            if (cached != null && !cached.isRecycled) return cached
            synchronized(cacheLock) {
                val second = if (frame) cachedFrame else cachedFill
                if (second != null && !second.isRecycled) return second
                val decoded = decodeRawBase64(context.applicationContext, resId)
                if (frame) cachedFrame = decoded else cachedFill = decoded
                return decoded
            }
        }

        private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
            val raw = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
            val encoded = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private val appContext = context.applicationContext
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val fillBitmap: Bitmap? = bitmap(context, R.raw.carbon_fill_b64, frame = false)
    private val frameBitmap: Bitmap? = bitmap(context, R.raw.carbon_frame_b64, frame = true)
    private var globalAlpha = 255
    private var customFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        destination.set(bounds)
        paint.alpha = globalAlpha

        // Relit uniquement l'état clair/sombre au moment du dessin. Ainsi un changement
        // dans les réglages est visible immédiatement, même si le drawable existait déjà.
        val currentlyLight = !AppThemeCatalog.useDarkPalette(appContext)
        paint.colorFilter = customFilter ?: if (currentlyLight) lightFilter else null

        fillBitmap?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, null, destination, paint) }
        frameBitmap?.takeIf { !it.isRecycled }?.let { canvas.drawBitmap(it, null, destination, paint) }
    }

    override fun setAlpha(alpha: Int) {
        val value = alpha.coerceIn(0, 255)
        if (value == globalAlpha) return
        globalAlpha = value
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (customFilter === colorFilter) return
        customFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
