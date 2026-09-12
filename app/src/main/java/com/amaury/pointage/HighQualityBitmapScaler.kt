package com.amaury.pointage

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Redimensionnement haute qualité des petits PNG historiques de l'horloge.
 *
 * Lanczos 3 en deux passes, avec interpolation en alpha prémultiplié pour ne pas
 * créer de halo sur les zones transparentes. Aucun changement de forme, couleur
 * ou géométrie n'est appliqué : seuls les pixels intermédiaires sont reconstruits.
 */
object HighQualityBitmapScaler {

    fun scaleBy(source: Bitmap, factor: Int): Bitmap {
        require(factor >= 1)
        if (factor == 1) return source
        return scale(source, source.width * factor, source.height * factor)
    }

    fun scale(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0)
        if (source.width == targetWidth && source.height == targetHeight) return source

        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)

        val horizontal = IntArray(targetWidth * source.height)
        for (y in 0 until source.height) {
            val sourceRow = y * source.width
            val targetRow = y * targetWidth
            for (x in 0 until targetWidth) {
                val sourceX = ((x + 0.5) * source.width / targetWidth) - 0.5
                horizontal[targetRow + x] = sampleHorizontal(
                    pixels = sourcePixels,
                    rowOffset = sourceRow,
                    width = source.width,
                    x = sourceX
                )
            }
        }

        val outputPixels = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = ((y + 0.5) * source.height / targetHeight) - 0.5
            for (x in 0 until targetWidth) {
                outputPixels[y * targetWidth + x] = sampleVertical(
                    pixels = horizontal,
                    width = targetWidth,
                    height = source.height,
                    x = x,
                    y = sourceY
                )
            }
        }

        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outputPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        }
    }

    private fun sampleHorizontal(
        pixels: IntArray,
        rowOffset: Int,
        width: Int,
        x: Double
    ): Int {
        val base = floor(x).toInt()
        var sumWeight = 0.0
        var sumAlpha = 0.0
        var sumRed = 0.0
        var sumGreen = 0.0
        var sumBlue = 0.0

        for (sampleX in (base - 2)..(base + 3)) {
            val weight = lanczos3(x - sampleX)
            if (weight == 0.0) continue
            val pixel = pixels[rowOffset + sampleX.coerceIn(0, width - 1)]
            val alpha = Color.alpha(pixel).toDouble()
            sumWeight += weight
            sumAlpha += weight * alpha
            sumRed += weight * alpha * Color.red(pixel)
            sumGreen += weight * alpha * Color.green(pixel)
            sumBlue += weight * alpha * Color.blue(pixel)
        }
        return compose(sumWeight, sumAlpha, sumRed, sumGreen, sumBlue)
    }

    private fun sampleVertical(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Double
    ): Int {
        val base = floor(y).toInt()
        var sumWeight = 0.0
        var sumAlpha = 0.0
        var sumRed = 0.0
        var sumGreen = 0.0
        var sumBlue = 0.0

        for (sampleY in (base - 2)..(base + 3)) {
            val weight = lanczos3(y - sampleY)
            if (weight == 0.0) continue
            val clampedY = sampleY.coerceIn(0, height - 1)
            val pixel = pixels[clampedY * width + x]
            val alpha = Color.alpha(pixel).toDouble()
            sumWeight += weight
            sumAlpha += weight * alpha
            sumRed += weight * alpha * Color.red(pixel)
            sumGreen += weight * alpha * Color.green(pixel)
            sumBlue += weight * alpha * Color.blue(pixel)
        }
        return compose(sumWeight, sumAlpha, sumRed, sumGreen, sumBlue)
    }

    private fun compose(
        sumWeight: Double,
        sumAlpha: Double,
        sumRed: Double,
        sumGreen: Double,
        sumBlue: Double
    ): Int {
        if (abs(sumWeight) < EPSILON || sumAlpha <= EPSILON) return Color.TRANSPARENT
        val alpha = (sumAlpha / sumWeight).roundToInt().coerceIn(0, 255)
        if (alpha == 0) return Color.TRANSPARENT
        val red = (sumRed / sumAlpha).roundToInt().coerceIn(0, 255)
        val green = (sumGreen / sumAlpha).roundToInt().coerceIn(0, 255)
        val blue = (sumBlue / sumAlpha).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }

    private fun lanczos3(value: Double): Double {
        val x = abs(value)
        if (x < EPSILON) return 1.0
        if (x >= LANCZOS_RADIUS) return 0.0
        val pix = PI * x
        return (sin(pix) / pix) * (sin(pix / LANCZOS_RADIUS) / (pix / LANCZOS_RADIUS))
    }

    private const val LANCZOS_RADIUS = 3.0
    private const val EPSILON = 1e-9
}
