package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Base64
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private val dst = RectF()
    private val frameBand = Path()
    private val innerPath = Path()

    // Une seule source vérifiée pour le fond : aucune reconstruction en plusieurs morceaux.
    // Le bitmap source n'est jamais modifié ni recoloré.
    private val fillBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)

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

        val outerRadius = dst.height() * .48f
        val frameThickness = (dst.height() * .145f).coerceAtLeast(4f)
        val inner = ButtonFrameGeometry.buildBand(
            target = dst,
            outerRadius = outerRadius,
            thickness = frameThickness,
            outPath = frameBand,
            innerPath = innerPath
        ) ?: return

        // CENTRE : l'image carbone uniquement. Aucun cadre ni reflet n'entre dans cette zone.
        canvas.save()
        canvas.clipPath(innerPath)
        if (fillBitmap != null) {
            OriginalButtonImageRenderer.draw(canvas, fillBitmap, inner)
        } else {
            // Secours visible : si le décodage échoue, on ne laisse plus un centre invisible.
            paint.shader = null
            paint.colorFilter = null
            paint.alpha = globalAlpha
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(13, 15, 17)
            canvas.drawPath(innerPath, paint)
        }
        canvas.restore()

        // CADRE : vraie couronne, centre géométriquement absent.
        canvas.save()
        canvas.clipPath(frameBand)
        frameBitmap?.let { OriginalButtonImageRenderer.draw(canvas, it, dst) }
            ?: drawFallbackFrameBand(canvas, dst, outerRadius, frameThickness)
        canvas.restore()

        // Lumière uniquement sur le cadre.
        drawMetalSpecularReflection(canvas, dst)
    }

    private fun drawMetalSpecularReflection(canvas: Canvas, target: RectF) {
        val rad = Math.toRadians(lightAngle.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val cx = target.centerX()
        val cy = target.centerY()
        val half = sqrt(target.width() * target.width() + target.height() * target.height()) * .66f
        val cool = if (nightLight) Color.rgb(188, 220, 255) else Color.rgb(255, 250, 232)
        val peak = if (nightLight) 145 else 205
        val shoulder = if (nightLight) 55 else 105

        paint.style = Paint.Style.FILL
        paint.alpha = globalAlpha
        paint.colorFilter = null
        paint.shader = LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            intArrayOf(
                Color.argb(48, 5, 7, 9), Color.argb(12, 20, 24, 28),
                Color.argb(shoulder, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(peak, 255, 255, 255),
                Color.argb(shoulder, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(18, 18, 22, 26), Color.argb(58, 0, 0, 0)
            ),
            floatArrayOf(0f, .25f, .39f, .50f, .61f, .75f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipPath(frameBand)
        canvas.drawRect(target, paint)

        val hotX = cx + dx * target.width() * .42f
        val hotY = cy + dy * target.height() * .42f
        paint.shader = RadialGradient(
            hotX, hotY,
            (target.height() * if (nightLight) .38f else .48f).coerceAtLeast(9f),
            intArrayOf(
                Color.argb(if (nightLight) 100 else 145, 255, 255, 255),
                Color.argb(if (nightLight) 35 else 65, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(target, paint)
        canvas.restore()
        paint.shader = null
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
        val raw = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        val encoded = raw.substringAfter("base64,", raw).filterNot { it.isWhitespace() }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun drawFallbackFrameBand(canvas: Canvas, target: RectF, radius: Float, thickness: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness
        paint.alpha = globalAlpha
        paint.colorFilter = null
        paint.shader = LinearGradient(
            target.left, target.top, target.right, target.bottom,
            intArrayOf(Color.WHITE, Color.rgb(75, 80, 86), Color.rgb(235, 238, 241), Color.rgb(55, 59, 64)),
            null, Shader.TileMode.CLAMP
        )
        val inset = thickness / 2f
        canvas.drawRoundRect(
            RectF(target.left + inset, target.top + inset, target.right - inset, target.bottom - inset),
            (radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f), paint
        )
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {
        globalAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Jamais de filtre sur les images importées.
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
