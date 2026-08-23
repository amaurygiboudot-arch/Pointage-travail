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
 * Aucun effet "diamant" n'est utilise pour le theme Carbone.
 */
class CarbonCompositeDrawable(context: Context) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val clipPath = Path()
    private val fillBitmap: Bitmap = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap = makeBlackTransparent(
        decodeRawBase64(context, R.raw.carbon_frame_b64)
    )
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        dst.set(bounds)
        val radius = bounds.height() * 0.48f

        clipPath.reset()
        clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        drawCenterCrop(canvas, fillBitmap, dst)
        canvas.restore()

        // Le cadre reste par-dessus le carbone et conserve son relief metallique.
        drawCenterCrop(canvas, frameBitmap, dst)
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val tw = target.width()
        val th = target.height()
        val srcAspect = bw / bh
        val dstAspect = tw / th

        if (srcAspect > dstAspect) {
            val wanted = bh * dstAspect
            val left = ((bw - wanted) / 2f).toInt()
            src.set(left, 0, left + wanted.toInt(), bitmap.height)
        } else {
            val wanted = bw / dstAspect
            val top = ((bh - wanted) / 2f).toInt()
            src.set(0, top, bitmap.width, top + wanted.toInt())
        }
        paint.alpha = globalAlpha
        canvas.drawBitmap(bitmap, src, target, paint)
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap {
        val encoded = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }.trim()
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

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
            // Sous ~18 : totalement transparent. Au-dessus, transition douce
            // pour garder les ombres du chrome sans rectangle noir visible.
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
