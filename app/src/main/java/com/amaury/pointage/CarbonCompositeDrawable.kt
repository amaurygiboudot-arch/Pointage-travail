package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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

        // Fond carbone : limité à la zone intérieure du bouton.
        fillBitmap?.let { bitmap ->
            canvas.save()
            canvas.clipPath(innerPath)
            canvas.drawBitmap(bitmap, null, inner, paint)
            canvas.restore()
        }

        // Le fichier du cadre possède déjà son centre et son extérieur transparents.
        // Il ne faut donc PAS le re-découper avec frameBand : cela supprimait son rendu.
        // On le pose directement au-dessus du fond sur la totalité du bouton.
        frameBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, dst, paint)
        }
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
