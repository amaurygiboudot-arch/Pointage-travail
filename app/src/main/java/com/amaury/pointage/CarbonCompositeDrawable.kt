package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
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
    private val buttonBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_button_complete_b64)
    private var globalAlpha = 255

    init { synchronized(CarbonCompositeDrawable::class.java) { instances[this] = Unit } }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val bitmap = buttonBitmap ?: return
        dst.set(bounds)

        // L'image fournie contient déjà le fond carbone ET le cadre métallique.
        // On prélève la zone utile du bouton et on l'affiche comme une seule couche,
        // sans reconstruire le carbone, le cadre ou un reflet en code.
        val source = usefulButtonRect(bitmap)
        paint.alpha = globalAlpha
        paint.colorFilter = null
        canvas.drawBitmap(bitmap, source, dst, paint)
    }

    private fun usefulButtonRect(bitmap: Bitmap): Rect {
        // Image source 3:2 : le bouton occupe approximativement la bande centrale.
        // Ces valeurs sont volontairement simples pour le premier test visuel ;
        // elles pourront être ajustées après contrôle sur téléphone.
        val left = (bitmap.width * 0.095f).toInt().coerceIn(0, bitmap.width - 1)
        val right = (bitmap.width * 0.905f).toInt().coerceIn(left + 1, bitmap.width)
        val top = (bitmap.height * 0.255f).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bitmap.height * 0.735f).toInt().coerceIn(top + 1, bitmap.height)
        return Rect(left, top, right, bottom)
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
