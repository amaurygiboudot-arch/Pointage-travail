package com.amaury.pointage

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rééchantillonnage bicubique destiné uniquement aux petits PNG du cadran.
 *
 * Il ne redessine rien et ne modifie ni forme, ni palette : il remplace seulement
 * l'agrandissement bilinéaire Android lorsque l'asset source est beaucoup plus
 * petit que sa taille d'affichage.
 */
object HighQualityBitmapScalerV2 {

    fun scale(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0)
        if (source.width == targetWidth && source.height == targetHeight) return source

        val srcWidth = source.width
        val srcHeight = source.height
        val src = IntArray(srcWidth * srcHeight)
        source.getPixels(src, 0, srcWidth, 0, 0, srcWidth, srcHeight)

        val out = IntArray(targetWidth * targetHeight)
        val scaleX = srcWidth.toDouble() / targetWidth.toDouble()
        val scaleY = srcHeight.toDouble() / targetHeight.toDouble()

        for (dy in 0 until targetHeight) {
            val sy = (dy + 0.5) * scaleY - 0.5
            val syBase = floor(sy).toInt()

            for (dx in 0 until targetWidth) {
                val sx = (dx + 0.5) * scaleX - 0.5
                val sxBase = floor(sx).toInt()

                var sumWeight = 0.0
                var sumAlpha = 0.0
                var sumPremulRed = 0.0
                var sumPremulGreen = 0.0
                var sumPremulBlue = 0.0

                for (oy in -1..2) {
                    val srcY = (syBase + oy).coerceIn(0, srcHeight - 1)
                    val wy = cubicWeight(sy - (syBase + oy))
                    if (wy == 0.0) continue

                    for (ox in -1..2) {
                        val srcX = (sxBase + ox).coerceIn(0, srcWidth - 1)
                        val wx = cubicWeight(sx - (sxBase + ox))
                        val weight = wx * wy
                        if (weight == 0.0) continue

                        val color = src[srcY * srcWidth + srcX]
                        val alpha = Color.alpha(color).toDouble()
                        val alphaUnit = alpha / 255.0

                        sumWeight += weight
                        sumAlpha += alpha * weight
                        sumPremulRed += Color.red(color) * alphaUnit * weight
                        sumPremulGreen += Color.green(color) * alphaUnit * weight
                        sumPremulBlue += Color.blue(color) * alphaUnit * weight
                    }
                }

                val normalizer = if (abs(sumWeight) < 1e-9) 1.0 else sumWeight
                val alpha = (sumAlpha / normalizer).coerceIn(0.0, 255.0)
                if (alpha <= 0.5) {
                    out[dy * targetWidth + dx] = Color.TRANSPARENT
                } else {
                    val alphaUnit = alpha / 255.0
                    val red = ((sumPremulRed / normalizer) / alphaUnit).roundToInt().coerceIn(0, 255)
                    val green = ((sumPremulGreen / normalizer) / alphaUnit).roundToInt().coerceIn(0, 255)
                    val blue = ((sumPremulBlue / normalizer) / alphaUnit).roundToInt().coerceIn(0, 255)
                    out[dy * targetWidth + dx] = Color.argb(alpha.roundToInt(), red, green, blue)
                }
            }
        }

        return Bitmap.createBitmap(out, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    fun upscale(source: Bitmap, factor: Int, maxDimension: Int = 1536): Bitmap {
        if (factor <= 1) return source
        val largest = maxOf(source.width, source.height).coerceAtLeast(1)
        val allowedFactor = min(factor, (maxDimension / largest).coerceAtLeast(1))
        if (allowedFactor <= 1) return source
        return scale(source, source.width * allowedFactor, source.height * allowedFactor)
    }

    /** Catmull-Rom : plus net qu'un bilinéaire tout en gardant les contours d'origine. */
    private fun cubicWeight(distance: Double): Double {
        val x = abs(distance)
        return when {
            x <= 1.0 -> 1.5 * x * x * x - 2.5 * x * x + 1.0
            x < 2.0 -> -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0
            else -> 0.0
        }
    }
}
