package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.Base64
import kotlin.math.max

/**
 * Bouton Carbone composé de deux couches indépendantes :
 * 1. le fond fibre de carbone ;
 * 2. le cadre métallique validé, conservé au-dessus du fond.
 *
 * Si une ressource ne peut pas être décodée, on garde un rendu de secours
 * au lieu de faire tomber l'application.
 */
class CarbonCompositeDrawable(context: Context) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val clipPath = Path()
    private val fillBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)?.let(::makeBlackTransparent)
    private var globalAlpha = 255

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        dst.set(bounds)
        val radius = bounds.height() * 0.48f

        clipPath.reset()
        clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        fillBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackCarbon(canvas, dst)
        canvas.restore()

        // IMPORTANT : le cadre est dessiné après le carbone et n'est pas teinté.
        // Les gris très sombres du chrome sont volontairement conservés pour
        // que le relief complet soit visible, pas uniquement les reflets blancs.
        frameBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackFrame(canvas, dst, radius)
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val tw = target.width()
        val th = target.height()
        if (bw <= 0f || bh <= 0f || tw <= 0f || th <= 0f) return
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
        paint.colorFilter = null
        canvas.drawBitmap(bitmap, src, target, paint)
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
        val encoded = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }.trim()
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /**
     * Le visuel du cadre possède un intérieur et un extérieur noirs. Les anciens
     * seuils rendaient aussi transparents les gris foncés du métal : il ne restait
     * presque que les reflets blancs. Ici seul le vrai noir disparaît ; dès qu'un
     * pixel appartient au chrome, on lui conserve une opacité minimale.
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
            val alpha = when {
                lum <= 3 -> 0
                lum <= 12 -> ((lum - 3) * 14).coerceIn(0, 126)
                else -> (105 + (lum - 12) * 3).coerceIn(105, 255)
            }
            pixels[i] = Color.argb((alpha * globalAlpha) / 255, r, g, b)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun drawFallbackCarbon(canvas: Canvas, target: RectF) {
        paint.alpha = globalAlpha
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(13, 15, 17)
        canvas.drawRoundRect(target, target.height() * .48f, target.height() * .48f, paint)
        paint.strokeWidth = (target.height() / 12f).coerceAtLeast(2f)
        paint.color = Color.rgb(45, 49, 53)
        var x = target.left - target.height()
        while (x < target.right + target.height()) {
            canvas.drawLine(x, target.bottom, x + target.height() * .9f, target.top, paint)
            x += target.height() * .36f
        }
    }

    private fun drawFallbackFrame(canvas: Canvas, target: RectF, radius: Float) {
        val inset = (target.height() * .055f).coerceAtLeast(2f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = inset
        paint.alpha = globalAlpha
        paint.shader = LinearGradient(
            target.left, target.top, target.right, target.bottom,
            intArrayOf(Color.WHITE, Color.rgb(75, 80, 86), Color.rgb(235, 238, 241), Color.rgb(55, 59, 64)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(
            RectF(target.left + inset / 2f, target.top + inset / 2f, target.right - inset / 2f, target.bottom - inset / 2f),
            radius - inset / 2f,
            radius - inset / 2f,
            paint
        )
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Le cadre métallique doit garder ses couleurs d'origine.
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
