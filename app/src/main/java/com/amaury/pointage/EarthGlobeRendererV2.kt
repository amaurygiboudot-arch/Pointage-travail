package com.amaury.pointage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import com.amaury.pointage.v2.engine.EarthGlobeProjectionV2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Globe terrestre V2 de l'horloge.
 *
 * Le globe est une vraie projection orthographique : la latitude/longitude GPS
 * de l'utilisateur devient le point au centre de la sphère. Ainsi, en France la
 * France est face à l'utilisateur ; au Japon, le Japon l'est automatiquement.
 *
 * Le globe ne dépend volontairement pas du cap du téléphone. Le ciel tourne avec
 * la boussole, mais la Terre centrale reste une référence géographique stable.
 */
class EarthGlobeRendererV2 {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val markerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 255, 255, 255)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(235, 62, 62)
    }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.argb(225, 255, 255, 255)
    }
    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        color = Color.argb(150, 220, 236, 255)
    }

    private var cachedBitmap: Bitmap? = null
    private var cachedDiameter = 0
    private var cachedLatitude = Double.NaN
    private var cachedLongitude = Double.NaN
    private var cachedSunAzimuth = Double.NaN
    private var cachedSunAltitude = Double.NaN

    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        snapshot: CelestialSnapshotV2?
    ): Boolean {
        if (snapshot == null || radius <= 1f) return false

        val diameter = max(24, (radius * 2f).roundToInt())
        val needsRebuild =
            cachedBitmap == null ||
                cachedDiameter != diameter ||
                angularDifference(cachedLatitude, snapshot.latitudeDeg) > LOCATION_CACHE_EPSILON_DEG ||
                angularDifferenceLongitude(cachedLongitude, snapshot.longitudeDeg) > LOCATION_CACHE_EPSILON_DEG ||
                angularDifference(cachedSunAzimuth, snapshot.sun.azimuthDeg) > SUN_CACHE_EPSILON_DEG ||
                angularDifference(cachedSunAltitude, snapshot.sun.altitudeDeg) > SUN_CACHE_EPSILON_DEG

        if (needsRebuild) {
            cachedBitmap?.recycle()
            cachedBitmap = buildGlobe(
                diameter = diameter * GLOBE_SUPERSAMPLE_FACTOR,
                observerLatitudeDeg = snapshot.latitudeDeg,
                observerLongitudeDeg = snapshot.longitudeDeg,
                sunAzimuthDeg = snapshot.sun.azimuthDeg,
                sunAltitudeDeg = snapshot.sun.altitudeDeg
            )
            cachedDiameter = diameter
            cachedLatitude = snapshot.latitudeDeg
            cachedLongitude = snapshot.longitudeDeg
            cachedSunAzimuth = snapshot.sun.azimuthDeg
            cachedSunAltitude = snapshot.sun.altitudeDeg
        }

        val bitmap = cachedBitmap ?: return false
        val dst = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)

        // Le point GPS projeté est mathématiquement au centre du globe.
        val markerRadius = max(1.6f, radius * 0.065f)
        canvas.drawCircle(cx, cy, markerRadius * 2.05f, markerHaloPaint)
        canvas.drawCircle(cx, cy, markerRadius, markerPaint)
        markerRingPaint.strokeWidth = max(1f, radius * 0.025f)
        canvas.drawCircle(cx, cy, markerRadius * 2.25f, markerRingPaint)

        limbPaint.strokeWidth = max(1f, radius * 0.022f)
        canvas.drawCircle(cx, cy, radius - limbPaint.strokeWidth * 0.5f, limbPaint)
        return true
    }

    fun clearCache() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedDiameter = 0
        cachedLatitude = Double.NaN
        cachedLongitude = Double.NaN
        cachedSunAzimuth = Double.NaN
        cachedSunAltitude = Double.NaN
    }

    private fun buildGlobe(
        diameter: Int,
        observerLatitudeDeg: Double,
        observerLongitudeDeg: Double,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double
    ): Bitmap {
        val texture = EarthGlobeMapAssetV2.bitmap
        val textureWidth = texture.width
        val textureHeight = texture.height
        val texturePixels = IntArray(textureWidth * textureHeight)
        texture.getPixels(
            texturePixels,
            0,
            textureWidth,
            0,
            0,
            textureWidth,
            textureHeight
        )

        val output = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(diameter * diameter)
        val radius = diameter / 2.0
        val center = (diameter - 1) / 2.0

        val sunAz = Math.toRadians(sunAzimuthDeg)
        val sunAlt = Math.toRadians(sunAltitudeDeg)
        val cosSunAlt = cos(sunAlt)
        val sunEast = cosSunAlt * sin(sunAz)
        val sunNorth = cosSunAlt * cos(sunAz)
        val sunUp = sin(sunAlt)

        for (py in 0 until diameter) {
            val yScreen = (py - center) / radius
            val yNorth = -yScreen
            for (px in 0 until diameter) {
                val xEast = (px - center) / radius
                val rho2 = xEast * xEast + yNorth * yNorth
                if (rho2 > 1.0) continue

                val depth = sqrt((1.0 - rho2).coerceAtLeast(0.0))
                val geo = EarthGlobeProjectionV2.unproject(
                    x = xEast,
                    y = yScreen,
                    observerLatitudeDeg = observerLatitudeDeg,
                    observerLongitudeDeg = observerLongitudeDeg
                ) ?: continue

                val source = sampleTextureBilinear(
                    pixels = texturePixels,
                    width = textureWidth,
                    height = textureHeight,
                    longitudeDeg = geo.longitudeDeg,
                    latitudeDeg = geo.latitudeDeg
                )

                // Lambert simplifié avec le vrai Soleil local. La face nocturne
                // reste volontairement lisible : ce globe est aussi un repère GPS.
                val sunDot = xEast * sunEast + yNorth * sunNorth + depth * sunUp
                val daylight = 0.26 + 0.74 * sunDot.coerceAtLeast(0.0)
                val limb = 0.68 + 0.32 * depth
                val brightness = (daylight * limb).coerceIn(0.20, 1.0)

                val edgePixels = (1.0 - sqrt(rho2)) * radius
                val alpha = (255.0 * edgePixels.coerceIn(0.0, 1.0)).roundToInt()

                val red = (Color.red(source) * brightness).roundToInt().coerceIn(0, 255)
                val green = (Color.green(source) * brightness).roundToInt().coerceIn(0, 255)
                val blue = (Color.blue(source) * brightness).roundToInt().coerceIn(0, 255)
                pixels[py * diameter + px] = Color.argb(alpha, red, green, blue)
            }
        }

        output.setPixels(pixels, 0, diameter, 0, 0, diameter, diameter)
        return output
    }

    private fun sampleTextureBilinear(
        pixels: IntArray,
        width: Int,
        height: Int,
        longitudeDeg: Double,
        latitudeDeg: Double
    ): Int {
        var x = ((longitudeDeg + 180.0) / 360.0) * width
        x %= width.toDouble()
        if (x < 0.0) x += width
        val y = (((90.0 - latitudeDeg) / 180.0) * (height - 1))
            .coerceIn(0.0, (height - 1).toDouble())

        val xFloor = floor(x)
        val yFloor = floor(y)
        val x0 = xFloor.toInt().coerceIn(0, width - 1)
        val x1 = (x0 + 1) % width
        val y0 = yFloor.toInt().coerceIn(0, height - 1)
        val y1 = (y0 + 1).coerceIn(0, height - 1)
        val fx = x - xFloor
        val fy = y - yFloor

        val c00 = pixels[y0 * width + x0]
        val c10 = pixels[y0 * width + x1]
        val c01 = pixels[y1 * width + x0]
        val c11 = pixels[y1 * width + x1]

        return Color.argb(
            bilinearChannel(Color.alpha(c00), Color.alpha(c10), Color.alpha(c01), Color.alpha(c11), fx, fy),
            bilinearChannel(Color.red(c00), Color.red(c10), Color.red(c01), Color.red(c11), fx, fy),
            bilinearChannel(Color.green(c00), Color.green(c10), Color.green(c01), Color.green(c11), fx, fy),
            bilinearChannel(Color.blue(c00), Color.blue(c10), Color.blue(c01), Color.blue(c11), fx, fy)
        )
    }

    private fun bilinearChannel(
        c00: Int,
        c10: Int,
        c01: Int,
        c11: Int,
        fx: Double,
        fy: Double
    ): Int {
        val top = c00 + (c10 - c00) * fx
        val bottom = c01 + (c11 - c01) * fx
        return (top + (bottom - top) * fy).roundToInt().coerceIn(0, 255)
    }

    private fun angularDifference(a: Double, b: Double): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.POSITIVE_INFINITY
        return abs(a - b)
    }

    private fun angularDifferenceLongitude(a: Double, b: Double): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.POSITIVE_INFINITY
        return abs(((b - a + 540.0) % 360.0) - 180.0)
    }

    companion object {
        // Quelques kilomètres : assez stable pour éviter que le globe ne tremble
        // sur le bruit GPS, tout en se réorientant réellement lors d'un déplacement.
        private const val LOCATION_CACHE_EPSILON_DEG = 0.04
        private const val SUN_CACHE_EPSILON_DEG = 0.20
        private const val GLOBE_SUPERSAMPLE_FACTOR = 2
    }
}
