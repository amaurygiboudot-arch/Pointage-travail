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
 * Bouton Carbone = deux matériaux distincts :
 * 1. fond fibre carbone : réponse diffuse, douce, bombée ;
 * 2. cadre métal : réponse spéculaire, dure, brillante, très contrastée.
 *
 * Les deux utilisent la même direction Soleil/Lune, mais PAS le même modèle
 * d'éclairage afin que le carbone ne ressemble jamais à du métal.
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

        // MATÉRIAU 1 : carbone. Toujours sous le métal.
        canvas.save()
        canvas.clipPath(clipPath)
        fillBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackCarbon(canvas, dst)
        drawCarbonDiffuseRelief(canvas, dst)
        canvas.restore()

        // MATÉRIAU 2 : métal. Rendu séparé et beaucoup plus spéculaire.
        frameBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackFrame(canvas, dst, radius)
        drawMetalSpecularReflection(canvas, dst, radius)
    }

    /**
     * Carbone : éclairage DIFFUS.
     * Large halo doux + ombre opposée + liseré intérieur léger.
     * Aucun point blanc brûlé, aucune bande miroir.
     */
    private fun drawCarbonDiffuseRelief(canvas: Canvas, target: RectF) {
        val r = Math.toRadians(lightAngle.toDouble())
        val dx = cos(r).toFloat()
        val dy = sin(r).toFloat()
        val cx = target.centerX()
        val cy = target.centerY()
        val radius = sqrt(target.width() * target.width() + target.height() * target.height()) * .72f

        paint.style = Paint.Style.FILL
        paint.colorFilter = null
        paint.alpha = globalAlpha

        // La fibre reçoit une lumière large et mate, légèrement chaude le jour.
        val tint = if (nightLight) Color.rgb(150, 176, 204) else Color.rgb(224, 216, 196)
        val hi = if (nightLight) 30 else 62
        val mid = if (nightLight) 14 else 30
        val lx = cx + dx * target.width() * .18f
        val ly = cy + dy * target.height() * .20f

        paint.shader = RadialGradient(
            lx, ly, radius,
            intArrayOf(
                Color.argb(hi, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(mid, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(5, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .34f, .68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)

        // Ombre très large du côté opposé : c'est elle qui donne la bosse.
        val sx = cx - dx * target.width() * .24f
        val sy = cy - dy * target.height() * .26f
        paint.shader = RadialGradient(
            sx, sy, radius * .96f,
            intArrayOf(
                Color.argb(if (nightLight) 58 else 72, 0, 0, 0),
                Color.argb(if (nightLight) 28 else 42, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)

        // Relief de bord du carbone : discret, sans aspect chrome.
        val edge = (target.height() * .045f).coerceAtLeast(1.5f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = edge
        paint.shader = LinearGradient(
            cx - dx * target.width() * .58f, cy - dy * target.height() * .58f,
            cx + dx * target.width() * .58f, cy + dy * target.height() * .58f,
            intArrayOf(
                Color.argb(62, 0, 0, 0),
                Color.TRANSPARENT,
                Color.argb(if (nightLight) 42 else 70, 210, 214, 216)
            ),
            floatArrayOf(0f, .54f, 1f),
            Shader.TileMode.CLAMP
        )
        val inset = edge * .9f
        canvas.drawRoundRect(
            RectF(target.left + inset, target.top + inset, target.right - inset, target.bottom - inset),
            target.height() * .42f,
            target.height() * .42f,
            paint
        )

        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    /**
     * Métal : éclairage SPÉCULAIRE.
     * Bande miroir très étroite + point chaud + contre-reflet sombre.
     * Le métal peut donc flasher franchement alors que le carbone reste mat.
     */
    private fun drawMetalSpecularReflection(canvas: Canvas, target: RectF, radius: Float) {
        val band = (target.height() * .145f).coerceAtLeast(4f)
        val inner = RectF(target.left + band, target.top + band, target.right - band, target.bottom - band)
        if (inner.width() <= 0f || inner.height() <= 0f) return

        frameBand.reset()
        frameBand.addRoundRect(target, radius, radius, Path.Direction.CW)
        innerPath.reset()
        innerPath.addRoundRect(inner, (radius - band).coerceAtLeast(1f), (radius - band).coerceAtLeast(1f), Path.Direction.CW)
        frameBand.op(innerPath, Path.Op.DIFFERENCE)

        val r = Math.toRadians(lightAngle.toDouble())
        val dx = cos(r).toFloat()
        val dy = sin(r).toFloat()
        val cx = target.centerX()
        val cy = target.centerY()
        val half = sqrt(target.width() * target.width() + target.height() * target.height()) * .66f

        val cool = if (nightLight) Color.rgb(188, 220, 255) else Color.rgb(255, 250, 232)
        val hard = if (nightLight) 170 else 255
        val shoulder = if (nightLight) 70 else 178

        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
        paint.colorFilter = null

        // Très étroit : un vrai trait miroir qui se déplace sur le chrome.
        paint.shader = LinearGradient(
            cx - dx * half, cy - dy * half,
            cx + dx * half, cy + dy * half,
            intArrayOf(
                Color.argb(72, 5, 7, 9),
                Color.argb(20, 20, 24, 28),
                Color.argb(shoulder, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(hard, 255, 255, 255),
                Color.argb(shoulder, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(36, 18, 22, 26),
                Color.argb(95, 0, 0, 0)
            ),
            floatArrayOf(0f, .39f, .465f, .50f, .535f, .62f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipPath(frameBand)
        canvas.drawRect(target, paint)

        // Point chaud indépendant du fond carbone.
        val hotX = cx + dx * target.width() * .44f
        val hotY = cy + dy * target.height() * .44f
        paint.shader = RadialGradient(
            hotX, hotY,
            (target.height() * if (nightLight) .24f else .32f).coerceAtLeast(7f),
            intArrayOf(
                Color.argb(if (nightLight) 175 else 255, 255, 255, 255),
                Color.argb(if (nightLight) 65 else 170, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .22f, 1f),
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
