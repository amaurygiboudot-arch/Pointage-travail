package com.amaury.pointage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max

/** Decodes custom application backgrounds near their actual display size with a bounded pixel budget. */
object BackgroundImageSampling {
    private const val MIN_DECODE_PIXELS = 8_000_000L
    private const val TARGET_PIXEL_MULTIPLIER = 2L

    fun decode(file: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, safeWidth, safeHeight)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    internal fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1

        // Memory safety is authoritative: keep increasing the power-of-two sample until the
        // decoded bitmap fits the pixel budget. This also bounds very wide/tall panoramas whose
        // short edge would otherwise prevent sampling and leave a huge ARGB_8888 allocation.
        val targetPixels = targetWidth.toLong() * targetHeight.toLong()
        val pixelBudget = max(MIN_DECODE_PIXELS, targetPixels * TARGET_PIXEL_MULTIPLIER)
        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        if (sourcePixels <= pixelBudget) return 1

        var sample = 1
        while (true) {
            val next = sample * 2
            val nextWidth = sourceWidth / next
            val nextHeight = sourceHeight / next
            if (nextWidth <= 0 || nextHeight <= 0) break

            sample = next
            val nextPixels = nextWidth.toLong() * nextHeight.toLong()
            if (nextPixels <= pixelBudget) break
        }
        return sample.coerceAtLeast(1)
    }
}
