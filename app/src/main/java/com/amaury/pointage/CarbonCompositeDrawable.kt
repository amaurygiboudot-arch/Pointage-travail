package com.amaury.pointage

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.Base64
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bouton Carbone composé de deux couches indépendantes :
 * 1. le fond fibre de carbone ;
 * 2. le cadre métallique validé, conservé au-dessus du fond.
 *
 * Le chrome et le fond carbone reçoivent un éclairage dynamique piloté par
 * la direction réelle Soleil/Lune fournie par LightDirectionController.
 */
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val clipPath = Path()
    private val frameBand = Path()
    private val innerPath = Path()
    private val fillBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)?.let(::makeBlackTransparent)
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
        val radius = bounds.height() * 0.48f

        clipPath.reset()
        clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        fillBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackCarbon(canvas, dst)
        drawCarbonSurfaceRelief(canvas, dst)
        canvas.restore()

        frameBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackFrame(canvas, dst, radius)
        drawCelestialMetalHighlight(canvas, dst, radius)
    }

    /**
     * Donne au fond carbone une vraie forme bombée :
     * - un éclat large du côté Soleil/Lune ;
     * - une zone centrale douce pour simuler une surface convexe ;
     * - une ombre opposée qui accentue l'épaisseur du bouton.
     */
    private fun drawCarbonSurfaceRelief(canvas: Canvas, target: RectF) {
        val radians = Math.toRadians(lightAngle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val cx = target.centerX()
        val cy = target.centerY()
        val radius = sqrt(target.width() * target.width() + target.height() * target.height()) * .62f

        paint.style = Paint.Style.FILL
        paint.colorFilter = null

        val lightColor = if (nightLight) Color.rgb(170, 205, 238) else Color.rgb(255, 246, 220)
        val lightAlpha = if (nightLight) 54 else 108
        val midAlpha = if (nightLight) 22 else 48

        // Éclairage convexe principal : point lumineux décalé vers la source.
        val lx = cx + dx * target.width() * .24f
        val ly = cy + dy * target.height() * .28f
        paint.shader = RadialGradient(
            lx, ly, radius,
            intArrayOf(
                Color.argb(lightAlpha, Color.red(lightColor), Color.green(lightColor), Color.blue(lightColor)),
                Color.argb(midAlpha, Color.red(lightColor), Color.green(lightColor), Color.blue(lightColor)),
                Color.argb(8, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .27f, .58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)

        // Ombre opposée : elle donne le volume bombé sans masquer la fibre carbone.
        val sx = cx - dx * target.width() * .30f
        val sy = cy - dy * target.height() * .34f
        paint.shader = RadialGradient(
            sx, sy, radius * .92f,
            intArrayOf(
                Color.argb(if (nightLight) 72 else 95, 0, 0, 0),
                Color.argb(if (nightLight) 42 else 62, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .43f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)

        // Fin liseré intérieur clair côté lumière et sombre à l'opposé.
        val edge = (target.height() * .065f).coerceAtLeast(2f)
        paint.shader = LinearGradient(
            cx - dx * target.width() * .55f,
            cy - dy * target.height() * .55f,
            cx + dx * target.width() * .55f,
            cy + dy * target.height() * .55f,
            intArrayOf(
                Color.argb(85, 0, 0, 0),
                Color.TRANSPARENT,
                Color.argb(if (nightLight) 75 else 132, 255, 255, 255)
            ),
            floatArrayOf(0f, .54f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = edge
        val inset = edge * .72f
        canvas.drawRoundRect(
            RectF(target.left + inset, target.top + inset, target.right - inset, target.bottom - inset),
            target.height() * .41f,
            target.height() * .41f,
            paint
        )

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
    }

    private fun drawCelestialMetalHighlight(canvas: Canvas, target: RectF, radius: Float) {
        val band = (target.height() * 0.145f).coerceAtLeast(4f)
        val inner = RectF(target.left + band, target.top + band, target.right - band, target.bottom - band)
        if (inner.width() <= 0f || inner.height() <= 0f) return

        frameBand.reset()
        frameBand.addRoundRect(target, radius, radius, Path.Direction.CW)
        innerPath.reset()
        innerPath.addRoundRect(inner, (radius - band).coerceAtLeast(1f), (radius - band).coerceAtLeast(1f), Path.Direction.CW)
        frameBand.op(innerPath, Path.Op.DIFFERENCE)

        val radians = Math.toRadians(lightAngle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val half = sqrt(target.width() * target.width() + target.height() * target.height()) * .58f
        val cx = target.centerX()
        val cy = target.centerY()

        val strong = if (nightLight) 150 else 245
        val medium = if (nightLight) 78 else 165
        val cool = if (nightLight) Color.rgb(190, 220, 255) else Color.rgb(255, 252, 238)

        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
        paint.colorFilter = null
        paint.shader = LinearGradient(
            cx - dx * half, cy - dy * half,
            cx + dx * half, cy + dy * half,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(18, 25, 30, 35),
                Color.argb(medium, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(strong, 255, 255, 255),
                Color.argb(medium, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(28, 20, 24, 28),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .28f, .43f, .50f, .57f, .74f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipPath(frameBand)
        canvas.drawRect(target, paint)

        val hotX = cx + dx * target.width() * .43f
        val hotY = cy + dy * target.height() * .43f
        paint.shader = RadialGradient(
            hotX, hotY, (target.height() * if (nightLight) .34f else .46f).coerceAtLeast(8f),
            intArrayOf(
                Color.argb(if (nightLight) 135 else 255, 255, 255, 255),
                Color.argb(if (nightLight) 55 else 145, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .28f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)
        canvas.restore()

        paint.shader = null
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
        paint.shader = null
        canvas.drawBitmap(bitmap, src, target, paint)
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
        val encoded = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }.trim()
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

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
        paint.shader = null
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
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
