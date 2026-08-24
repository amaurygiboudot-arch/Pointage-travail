package com.amaury.pointage

import android.content.Context
import android.graphics.*
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
            instances.keys.toList().forEach {
                it.applyCelestialLight(sharedLightAngle, sharedNight)
            }
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val fillClipPath = Path()
    private val frameBand = Path()
    private val innerPath = Path()

    private val fillBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_fill_b64)
    private val frameBitmap: Bitmap? = decodeRawBase64(context, R.raw.carbon_frame_b64)

    private var globalAlpha = 255
    private var lightAngle = sharedLightAngle
    private var nightLight = sharedNight

    init {
        synchronized(CarbonCompositeDrawable::class.java) {
            instances[this] = Unit
        }
    }

    private fun applyCelestialLight(angle: Float, night: Boolean) {
        lightAngle = angle
        nightLight = night
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return

        dst.set(bounds)
        val radius = bounds.height() * .48f
        val frameThickness = (dst.height() * .145f).coerceAtLeast(4f)

        // 1) Matière intérieure : uniquement la texture carbone.
        fillClipPath.reset()
        fillClipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(fillClipPath)
        fillBitmap?.let { drawCenterCrop(canvas, it, dst) } ?: drawFallbackCarbon(canvas, dst)
        canvas.restore()

        // 2) Cadre : vraie couronne géométrique. Le centre du cadre n'existe pas.
        val inner = ButtonFrameGeometry.buildBand(
            target = dst,
            outerRadius = radius,
            thickness = frameThickness,
            outPath = frameBand,
            innerPath = innerPath
        )

        if (inner != null) {
            canvas.save()
            canvas.clipPath(frameBand)
            frameBitmap?.let { drawCenterCrop(canvas, it, dst) }
                ?: drawFallbackFrameBand(canvas, dst, radius, frameThickness)
            canvas.restore()

            // 3) Reflets métal : limités à exactement la même couronne.
            drawMetalSpecularReflection(canvas, dst)
        }
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
            cx - dx * half,
            cy - dy * half,
            cx + dx * half,
            cy + dy * half,
            intArrayOf(
                Color.argb(48, 5, 7, 9),
                Color.argb(12, 20, 24, 28),
                Color.argb(shoulder, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(peak, 255, 255, 255),
                Color.argb(shoulder, Color.red(cool), Color.green(cool), Color.blue(cool)),
                Color.argb(18, 18, 22, 26),
                Color.argb(58, 0, 0, 0)
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
            hotX,
            hotY,
            (target.height() * if (nightLight) .38f else .48f).coerceAtLeast(9f),
            intArrayOf(
                Color.argb(if (nightLight) 100 else 145, 255, 255, 255),
                Color.argb(
                    if (nightLight) 35 else 65,
                    Color.red(cool),
                    Color.green(cool),
                    Color.blue(cool)
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .35f, 1f),
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

        val sourceAspect = bw / bh
        val targetAspect = tw / th
        if (sourceAspect > targetAspect) {
            val wanted = bh * targetAspect
            val left = ((bw - wanted) / 2f).toInt().coerceAtLeast(0)
            src.set(
                left,
                0,
                (left + wanted.toInt()).coerceAtMost(bitmap.width),
                bitmap.height
            )
        } else {
            val wanted = bw / targetAspect
            val top = ((bh - wanted) / 2f).toInt().coerceAtLeast(0)
            src.set(
                0,
                top,
                bitmap.width,
                (top + wanted.toInt()).coerceAtMost(bitmap.height)
            )
        }

        paint.alpha = globalAlpha
        paint.colorFilter = null
        paint.shader = null
        canvas.drawBitmap(bitmap, src, target, paint)
    }

    private fun decodeRawBase64(context: Context, resId: Int): Bitmap? = runCatching {
        val encoded = context.resources.openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
            .trim()
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun drawFallbackCarbon(canvas: Canvas, target: RectF) {
        paint.alpha = globalAlpha
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.rgb(13, 15, 17)
        canvas.drawRoundRect(
            target,
            target.height() * .48f,
            target.height() * .48f,
            paint
        )
    }

    private fun drawFallbackFrameBand(
        canvas: Canvas,
        target: RectF,
        radius: Float,
        thickness: Float
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness
        paint.alpha = globalAlpha
        paint.shader = LinearGradient(
            target.left,
            target.top,
            target.right,
            target.bottom,
            intArrayOf(
                Color.WHITE,
                Color.rgb(75, 80, 86),
                Color.rgb(235, 238, 241),
                Color.rgb(55, 59, 64)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        val inset = thickness / 2f
        canvas.drawRoundRect(
            RectF(
                target.left + inset,
                target.top + inset,
                target.right - inset,
                target.bottom - inset
            ),
            (radius - inset).coerceAtLeast(1f),
            (radius - inset).coerceAtLeast(1f),
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
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
