package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.Base64
import kotlin.math.max

/**
 * Bouton Carbone compose avec les deux visuels valides ensemble :
 * - fond fibre de carbone
 * - cadre metallique, dont le noir est rendu transparent a l'execution
 *
 * Le chargement des images est volontairement fail-safe : une ressource
 * invalide ne doit jamais faire planter toute l'application au demarrage.
 */
class CarbonCompositeDrawable(context: Context) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val clipPath = Path()
    private val fillBitmap: Bitmap? = decodeRawBase64OrNull(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64OrNull(context, R.raw.carbon_frame_b64)?.let(::makeBlackTransparent)
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        dst.set(bounds)
        val radius = bounds.height() * 0.48f

        clipPath.reset()
        clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        val fill = fillBitmap
        if (fill != null) drawCenterCrop(canvas, fill, dst) else drawSafeCarbonFallback(canvas, dst)
        canvas.restore()

        // Le cadre reste par-dessus le carbone et conserve son relief metallique.
        frameBitmap?.let { drawCenterCrop(canvas, it, dst) }
    }

    private fun drawSafeCarbonFallback(canvas: Canvas, target: RectF) {
        val oldStyle = paint.style
        val oldColor = paint.color
        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
        paint.color = Color.rgb(18, 20, 22)
        canvas.drawRect(target, paint)

        // Trame carbone simple de secours : uniquement utilisée si l'image source
        // n'a pas pu etre decodee. Elle evite tout crash et garde le bouton lisible.
        val step = (target.height() / 7f).coerceAtLeast(6f)
        paint.strokeWidth = (step * 0.42f).coerceAtLeast(2f)
        var x = target.left - target.height()
        paint.color = Color.rgb(48, 51, 54)
        while (x < target.right + target.height()) {
            canvas.drawLine(x, target.bottom, x + target.height(), target.top, paint)
            x += step * 1.5f
        }
        x = target.left - target.height() + step * 0.75f
        paint.color = Color.rgb(8, 9, 10)
        while (x < target.right + target.height()) {
            canvas.drawLine(x, target.bottom, x + target.height(), target.top, paint)
            x += step * 1.5f
        }
        paint.style = oldStyle
        paint.color = oldColor
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || target.width() <= 0f || target.height() <= 0f) return
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val tw = target.width()
        val th = target.height()
        val srcAspect = bw / bh
        val dstAspect = tw / th

        if (srcAspect > dstAspect) {
            val wanted = bh * dstAspect
            val left = ((bw - wanted) / 2f).toInt().coerceAtLeast(0)
            src.set(left, 0, (left + wanted.toInt()).coerceAtMost(bitmap.width), bitmap.height)
        } else {
            val wanted = bw / dstAspect
            val top = ((bh - wanted) / 2f).toInt().coerceAtLeast(0)
            src.set(0, top, bitmap.width, (top + wanted.toInt()).coerceAtMost(bitmap.height))
        }
        paint.alpha = globalAlpha
        canvas.drawBitmap(bitmap, src, target, paint)
    }

    private fun decodeRawBase64OrNull(context: Context, resId: Int): Bitmap? = runCatching {
        val encoded = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }.trim()
        if (encoded.isBlank()) return@runCatching null
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        if (bytes.isEmpty()) return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /**
     * Le cadre source a ete sauvegarde sur fond noir. On convertit uniquement
     * les pixels noirs en transparence pour retrouver un vrai cadre superposable.
     */
    private fun makeBlackTransparent(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = max(r, max(g, b))
            val a = ((lum - 14) * 8).coerceIn(0, 255)
            pixels[i] = Color.argb(a, r, g, b)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

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
