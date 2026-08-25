package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Base64

/**
 * Fond + cadre carbone. Les bitmaps sont décodés une seule fois par processus :
 * créer un nouveau drawable ne relit plus les gros Base64 à chaque restylage.
 */
class CarbonCompositeDrawable(context: Context) : Drawable() {
    companion object {
        @Volatile private var cachedFill: Bitmap? = null
        @Volatile private var cachedFrame: Bitmap? = null
        private val cacheLock = Any()

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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val fillBitmap: Bitmap? = bitmap(context, R.raw.carbon_fill_b64, frame = false)
    private val frameBitmap: Bitmap? = bitmap(context, R.raw.carbon_frame_b64, frame = true)
    private var globalAlpha = 255
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        destination.set(bounds)
        paint.alpha = globalAlpha
        paint.colorFilter = filter
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
        if (filter === colorFilter) return
        filter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
