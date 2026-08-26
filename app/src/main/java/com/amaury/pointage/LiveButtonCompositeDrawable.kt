package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.min

/**
 * Rendu maître des boutons standards pilotés par le labo Live.
 * Une seule chaîne : fond -> image fond -> image cadre -> trait cadre.
 * Aucun étirement n'est effectué sauf si le mode STRETCH est explicitement choisi.
 */
class LiveButtonCompositeDrawable(
    private val config: StandardButtonLiveConfig,
    private val density: Float,
    private val night: Boolean,
    private val backgroundImage: Drawable?,
    private val frameImage: Drawable?
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clipPath = Path()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds)
        val radius = effectiveRadius(rect)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(config.backgroundAlpha, config.backgroundR, config.backgroundG, config.backgroundB)
        canvas.drawRoundRect(rect, radius, radius, paint)

        drawImage(canvas, backgroundImage, config.backgroundImageAlpha, radius)
        drawImage(canvas, frameImage, config.frameImageAlpha, radius)

        if (config.frameWidthDp > 0f && config.frameAlpha > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = config.frameWidthDp * density
            paint.color = Color.argb(config.frameAlpha, config.frameR, config.frameG, config.frameB)
            val inset = paint.strokeWidth / 2f
            val r = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            canvas.drawRoundRect(r, max(0f, radius - inset), max(0f, radius - inset), paint)
        }
    }

    private fun drawImage(canvas: Canvas, drawable: Drawable?, alpha: Int, buttonRadius: Float) {
        drawable ?: return
        if (alpha <= 0) return

        val target = imageTarget(drawable, rect)
        val save = canvas.save()
        buildClipPath(target, rect, buttonRadius)
        canvas.clipPath(clipPath)

        drawable.alpha = alpha.coerceIn(0, 255)
        drawable.colorFilter = null
        drawable.setBounds(target.left.toInt(), target.top.toInt(), target.right.toInt(), target.bottom.toInt())
        drawable.draw(canvas)

        if (night && config.nightImageDimPercent > 0) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((255f * (config.nightImageDimPercent.coerceIn(0, 90) / 100f)).toInt(), 0, 0, 0)
            canvas.drawRect(target, paint)
        }
        canvas.restoreToCount(save)
    }

    private fun imageTarget(drawable: Drawable, button: RectF): RectF {
        val widthScale = (config.imageWidthPercent.coerceIn(5f, 200f) / 100f)
        val heightScale = (config.imageHeightPercent.coerceIn(5f, 200f) / 100f)
        var boxW = button.width() * widthScale
        var boxH = button.height() * heightScale

        val iw = drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: boxW
        val ih = drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: boxH
        val mode = config.imageScaleMode.uppercase()

        if (config.keepImageAspect && mode != "STRETCH" && iw > 0f && ih > 0f) {
            val sx = boxW / iw
            val sy = boxH / ih
            val s = if (mode == "FILL") max(sx, sy) else min(sx, sy)
            boxW = iw * s
            boxH = ih * s
        }

        val cx = button.centerX() + button.width() * (config.imageOffsetXPercent.coerceIn(-100f, 100f) / 100f)
        val cy = button.centerY() + button.height() * (config.imageOffsetYPercent.coerceIn(-100f, 100f) / 100f)
        return RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
    }

    private fun buildClipPath(imageRect: RectF, buttonRect: RectF, buttonRadius: Float) {
        clipPath.reset()
        when (config.imageShape.uppercase()) {
            "RECT" -> clipPath.addRect(imageRect, Path.Direction.CW)
            "ROUNDED" -> {
                val r = config.imageCornerRadiusDp.coerceAtLeast(0f) * density
                clipPath.addRoundRect(imageRect, r, r, Path.Direction.CW)
            }
            "CAPSULE" -> {
                val r = min(imageRect.width(), imageRect.height()) / 2f
                clipPath.addRoundRect(imageRect, r, r, Path.Direction.CW)
            }
            "CIRCLE" -> clipPath.addCircle(imageRect.centerX(), imageRect.centerY(), min(imageRect.width(), imageRect.height()) / 2f, Path.Direction.CW)
            else -> clipPath.addRoundRect(buttonRect, buttonRadius, buttonRadius, Path.Direction.CW)
        }
    }

    private fun effectiveRadius(r: RectF): Float = when (config.buttonShape.uppercase()) {
        "RECT" -> 0f
        "CAPSULE" -> min(r.width(), r.height()) / 2f
        "CIRCLE" -> min(r.width(), r.height()) / 2f
        else -> config.cornerRadiusDp.coerceAtLeast(0f) * density
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
