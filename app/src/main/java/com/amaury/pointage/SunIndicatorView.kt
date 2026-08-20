package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Couche astronomique HP : Soleil et Lune autour de l'horloge. */
class SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val moonShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val earthShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunBitmap: Bitmap by lazy { HpDesignAssets.sun }
    private val moonBitmap: Bitmap by lazy { HpDesignAssets.moon }

    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var visibleCelestial = false
    private var nightMode = false
    private var sunPosition: CelestialEphemeris.Position? = null
    private var moonPosition: CelestialEphemeris.Position? = null
    private var deviceAzimuth = 0f
    private var devicePitch = 0f

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshAstronomy()
            if (isAttachedToWindow && visibleCelestial) handler.postDelayed(this, 30_000L)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateLightAngle(newAngle: Float) = Unit

    fun setDeviceOrientation(azimuth: Float, pitch: Float) {
        deviceAzimuth = normalize(azimuth)
        devicePitch = pitch.coerceIn(-90f, 90f)
        invalidate()
    }

    fun setSunVisible(visible: Boolean) {
        val dynamicEnabled = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            .getBoolean("solar_lighting_enabled", false)
        visibleCelestial = visible || dynamicEnabled
        visibility = if (visibleCelestial) VISIBLE else GONE
        handler.removeCallbacks(refreshTask)
        updateSensorRegistration()
        if (visibleCelestial) {
            refreshAstronomy()
            handler.postDelayed(refreshTask, 30_000L)
        }
        invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (nightMode == night) return
        nightMode = night
        AppThemeCatalog.setCelestialNight(context, night)
        contentDescription = "Soleil et Lune"
        (context as? Activity)?.let { activity ->
            AppearanceManager.apply(activity)
            PointageWidgetProvider.updateAll(activity)
            QuickActionsWidgetProvider.updateAll(activity)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSensorRegistration()
        if (visibleCelestial) {
            handler.removeCallbacks(refreshTask)
            refreshAstronomy()
            handler.postDelayed(refreshTask, 30_000L)
        }
    }

    override fun onDetachedFromWindow() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(refreshTask)
        super.onDetachedFromWindow()
    }

    private fun updateSensorRegistration() {
        sensorManager.unregisterListener(this)
        if (isAttachedToWindow && visibleCelestial && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        deviceAzimuth = normalize(Math.toDegrees(orientation[0].toDouble()).toFloat())
        devicePitch = Math.toDegrees(orientation[1].toDouble()).toFloat().coerceIn(-90f, 90f)
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun refreshAstronomy() {
        val location = lastKnownLocation()
        val now = System.currentTimeMillis()
        if (location != null) {
            sunPosition = CelestialEphemeris.sun(location.latitude, location.longitude, now)
            moonPosition = CelestialEphemeris.moon(location.latitude, location.longitude, now)
        } else {
            sunPosition = null
            moonPosition = null
        }
        invalidate()
    }

    private fun lastKnownLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleCelestial || width <= 0 || height <= 0) return

        val base = min(width, height).toFloat()
        val earthX = width * 0.50f
        val earthY = height * 0.55f
        val orbitRadius = base * 0.43f
        val activeRadius = max(base * 0.078f, 22f)
        val inactiveRadius = activeRadius * 0.82f
        val sun = sunPosition
        val moon = moonPosition
        val sunScreen = sun?.let { mapToWatchOrbit(it, earthX, earthY, orbitRadius) }
        val moonScreen = moon?.let { mapToWatchOrbit(it, earthX, earthY, orbitRadius) }

        if (sun != null && sunScreen != null) {
            CelestialLightingState.updateSunDirection(sunScreen.first - earthX, sunScreen.second - earthY)
            drawCelestialPng(
                canvas, sunBitmap, sunScreen.first, sunScreen.second,
                (if (!nightMode) activeRadius else inactiveRadius) * sun.apparentScale.toFloat(),
                !nightMode, false
            )
        }

        if (moon != null && moonScreen != null) {
            val moonRadius = (if (nightMode) activeRadius * 0.94f else inactiveRadius * 0.94f) * moon.apparentScale.toFloat()
            drawCelestialPng(canvas, moonBitmap, moonScreen.first, moonScreen.second, moonRadius, nightMode, true)

            if (sun != null && sunScreen != null) {
                val illumination = lunarIllumination(sun, moon)
                drawMoonSunlight(
                    canvas,
                    moonScreen.first,
                    moonScreen.second,
                    moonRadius,
                    sunScreen.first,
                    sunScreen.second,
                    illumination
                )
                drawEarthShadowOnMoon(
                    canvas,
                    earthX,
                    earthY,
                    sunScreen.first,
                    sunScreen.second,
                    moonScreen.first,
                    moonScreen.second,
                    moonRadius,
                    eclipseStrength(sun, moon)
                )
            }
        }

        if (sun == null && moon == null) {
            val sunFallback = orbitFallback(false, earthX, earthY, orbitRadius)
            val moonFallback = orbitFallback(true, earthX, earthY, orbitRadius)
            CelestialLightingState.updateSunDirection(sunFallback.first - earthX, sunFallback.second - earthY)
            drawCelestialPng(canvas, sunBitmap, sunFallback.first, sunFallback.second,
                if (!nightMode) activeRadius else inactiveRadius, !nightMode, false)
            val fallbackMoonRadius = if (nightMode) activeRadius * 0.94f else inactiveRadius * 0.94f
            drawCelestialPng(canvas, moonBitmap, moonFallback.first, moonFallback.second,
                fallbackMoonRadius, nightMode, true)
            drawMoonSunlight(canvas, moonFallback.first, moonFallback.second, fallbackMoonRadius,
                sunFallback.first, sunFallback.second, 0.62f)
        }
    }

    private fun drawCelestialPng(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, radius: Float, active: Boolean, brightenMoon: Boolean) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val diameter = radius * 2f
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstWidth: Float
        val dstHeight: Float
        if (aspect >= 1f) {
            dstWidth = diameter
            dstHeight = diameter / aspect
        } else {
            dstHeight = diameter
            dstWidth = diameter * aspect
        }
        bitmapPaint.alpha = if (active) 255 else 215
        bitmapPaint.colorFilter = if (brightenMoon) {
            ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.20f, 0f, 0f, 0f, 14f,
                0f, 1.20f, 0f, 0f, 14f,
                0f, 0f, 1.20f, 0f, 14f,
                0f, 0f, 0f, 1f, 0f
            )))
        } else null
        val dst = RectF(cx - dstWidth / 2f, cy - dstHeight / 2f, cx + dstWidth / 2f, cy + dstHeight / 2f)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmapPaint.alpha = 255
        bitmapPaint.colorFilter = null
    }

    /** Éclairage direct de la Lune par le Soleil. */
    private fun drawMoonSunlight(canvas: Canvas, moonX: Float, moonY: Float, moonRadius: Float, sunX: Float, sunY: Float, illumination: Float) {
        val dx = sunX - moonX
        val dy = sunY - moonY
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / length
        val uy = dy / length
        val startX = moonX + ux * moonRadius
        val startY = moonY + uy * moonRadius
        val endX = moonX - ux * moonRadius
        val endY = moonY - uy * moonRadius
        val darkness = (215f * (1f - illumination).coerceIn(0f, 1f) + 45f).toInt().coerceIn(45, 235)
        moonShadePaint.shader = LinearGradient(
            startX, startY, endX, endY,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb((darkness * 0.35f).toInt(), 0, 0, 0), Color.argb(darkness, 0, 0, 0)),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        val oval = RectF(moonX - moonRadius, moonY - moonRadius, moonX + moonRadius, moonY + moonRadius)
        val moonClip = Path().apply { addOval(oval, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(moonClip)
        canvas.drawRect(oval, moonShadePaint)
        canvas.restore()
        moonShadePaint.shader = null
    }

    /**
     * Ombre de la Terre sur la Lune. Elle apparaît quand la Lune passe près de
     * l'axe opposé au Soleil (configuration d'éclipse) et glisse sur le disque
     * lunaire selon le défaut d'alignement.
     */
    private fun drawEarthShadowOnMoon(
        canvas: Canvas,
        earthX: Float,
        earthY: Float,
        sunX: Float,
        sunY: Float,
        moonX: Float,
        moonY: Float,
        moonRadius: Float,
        strength: Float
    ) {
        if (strength <= 0.01f) return

        val sx = sunX - earthX
        val sy = sunY - earthY
        val sl = sqrt(sx * sx + sy * sy).coerceAtLeast(1f)
        val antiX = -sx / sl
        val antiY = -sy / sl

        val mx = moonX - earthX
        val my = moonY - earthY
        val ml = sqrt(mx * mx + my * my).coerceAtLeast(1f)
        val moonDirX = mx / ml
        val moonDirY = my / ml

        // Erreur signée perpendiculaire à l'axe de l'ombre : elle déplace la tache
        // sur la Lune au lieu de la garder figée au centre.
        val cross = antiX * moonDirY - antiY * moonDirX
        val tangentX = -antiY
        val tangentY = antiX
        val offset = (cross * moonRadius * 2.4f).coerceIn(-moonRadius * 0.95f, moonRadius * 0.95f)
        val shadowX = moonX + tangentX * offset
        val shadowY = moonY + tangentY * offset

        val shadowRadius = moonRadius * (0.88f + 0.28f * strength)
        val alpha = (225f * strength).toInt().coerceIn(0, 225)
        earthShadowPaint.shader = RadialGradient(
            shadowX,
            shadowY,
            shadowRadius,
            intArrayOf(
                Color.argb(alpha, 8, 8, 12),
                Color.argb((alpha * 0.72f).toInt(), 18, 10, 12),
                Color.argb(0, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )

        val moonOval = RectF(moonX - moonRadius, moonY - moonRadius, moonX + moonRadius, moonY + moonRadius)
        val clip = Path().apply { addOval(moonOval, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(moonOval, earthShadowPaint)
        canvas.restore()
        earthShadowPaint.shader = null
    }

    private fun lunarIllumination(sun: CelestialEphemeris.Position, moon: CelestialEphemeris.Position): Float {
        val separation = angularSeparation(sun, moon)
        return ((1.0 - cos(separation)) * 0.5).toFloat().coerceIn(0.05f, 1f)
    }

    /** Intensité d'ombre terrestre près de l'opposition Soleil-Lune. */
    private fun eclipseStrength(sun: CelestialEphemeris.Position, moon: CelestialEphemeris.Position): Float {
        val separation = angularSeparation(sun, moon)
        val start = Math.toRadians(158.0)
        return ((separation - start) / (PI - start)).toFloat().coerceIn(0f, 1f)
    }

    private fun angularSeparation(sun: CelestialEphemeris.Position, moon: CelestialEphemeris.Position): Double {
        val sunAz = Math.toRadians(sun.azimuth)
        val sunAlt = Math.toRadians(sun.altitude)
        val moonAz = Math.toRadians(moon.azimuth)
        val moonAlt = Math.toRadians(moon.altitude)
        val cosSeparation = (
            sin(sunAlt) * sin(moonAlt) +
                cos(sunAlt) * cos(moonAlt) * cos(sunAz - moonAz)
            ).coerceIn(-1.0, 1.0)
        return acos(cosSeparation)
    }

    /** Orbite visuelle strictement centrée sur la Terre. */
    private fun mapToWatchOrbit(position: CelestialEphemeris.Position, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val angle = Math.toRadians(shortestDelta(deviceAzimuth, position.azimuth.toFloat()).toDouble())
        val x = cx + sin(angle).toFloat() * radius
        val y = cy - cos(angle).toFloat() * radius
        return x to y
    }

    private fun orbitFallback(night: Boolean, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY) + calendar.get(java.util.Calendar.MINUTE) / 60f
        val angle = if (night) hour / 24f * 360f + 180f else hour / 24f * 360f
        val a = Math.toRadians((angle - deviceAzimuth).toDouble())
        return cx + sin(a).toFloat() * radius to cy - cos(a).toFloat() * radius
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
