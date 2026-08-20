package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    private val moonLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            drawCelestialPng(canvas, sunBitmap, sunScreen.first, sunScreen.second,
                (if (!nightMode) activeRadius else inactiveRadius) * sun.apparentScale.toFloat(), !nightMode)
        }

        if (moon != null && moonScreen != null) {
            val moonRadius = (if (nightMode) activeRadius * 0.94f else inactiveRadius * 0.94f) * moon.apparentScale.toFloat()

            // 1) PNG de la Lune.
            drawCelestialPng(canvas, moonBitmap, moonScreen.first, moonScreen.second, moonRadius, nightMode)

            // 2) Les effets sont volontairement dessinés APRES le PNG : ils sont donc
            // réellement au-dessus de la Lune, jamais derrière elle.
            if (sun != null && sunScreen != null) {
                val illumination = lunarIllumination(sun, moon)
                drawMoonSunlight(canvas, moonScreen.first, moonScreen.second, moonRadius,
                    sunScreen.first, sunScreen.second, illumination)
                drawEarthShadowOnMoon(canvas, earthX, earthY, sunScreen.first, sunScreen.second,
                    moonScreen.first, moonScreen.second, moonRadius, eclipseStrength(sun, moon))
            }
        }

        if (sun == null && moon == null) {
            val sunFallback = orbitFallback(false, earthX, earthY, orbitRadius)
            val moonFallback = orbitFallback(true, earthX, earthY, orbitRadius)
            CelestialLightingState.updateSunDirection(sunFallback.first - earthX, sunFallback.second - earthY)
            drawCelestialPng(canvas, sunBitmap, sunFallback.first, sunFallback.second,
                if (!nightMode) activeRadius else inactiveRadius, !nightMode)
            val fallbackMoonRadius = if (nightMode) activeRadius * 0.94f else inactiveRadius * 0.94f
            drawCelestialPng(canvas, moonBitmap, moonFallback.first, moonFallback.second, fallbackMoonRadius, nightMode)
            drawMoonSunlight(canvas, moonFallback.first, moonFallback.second, fallbackMoonRadius,
                sunFallback.first, sunFallback.second, 0.62f)
        }
    }

    private fun drawCelestialPng(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, radius: Float, active: Boolean) {
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
        bitmapPaint.colorFilter = null
        val dst = RectF(cx - dstWidth / 2f, cy - dstHeight / 2f, cx + dstWidth / 2f, cy + dstHeight / 2f)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    /**
     * Éclairage visible de la Lune. Le PNG est d'abord affiché, puis cette couche
     * est peinte au-dessus. La face Soleil est claire et la face arrière est sombre.
     */
    private fun drawMoonSunlight(
        canvas: Canvas,
        moonX: Float,
        moonY: Float,
        moonRadius: Float,
        sunX: Float,
        sunY: Float,
        illumination: Float
    ) {
        val dx = sunX - moonX
        val dy = sunY - moonY
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / length
        val uy = dy / length

        val frontX = moonX + ux * moonRadius
        val frontY = moonY + uy * moonRadius
        val backX = moonX - ux * moonRadius
        val backY = moonY - uy * moonRadius
        val lit = illumination.coerceIn(0.02f, 1f)

        // Plus la Lune est éclairée, plus le terminateur recule vers le bord sombre.
        // Il reste toutefois franchement visible aux phases intermédiaires.
        val terminator = (0.22f + 0.68f * lit).coerceIn(0.22f, 0.90f)
        val softEnd = (terminator + 0.08f).coerceAtMost(0.98f)

        val lightAlpha = (125f * lit).toInt().coerceIn(10, 125)
        moonLightPaint.shader = LinearGradient(
            frontX, frontY, backX, backY,
            intArrayOf(
                Color.argb(lightAlpha, 255, 248, 215),
                Color.argb((lightAlpha * 0.55f).toInt(), 255, 246, 220),
                Color.argb(0, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, (terminator * 0.72f).coerceAtMost(0.70f), terminator, 1f),
            Shader.TileMode.CLAMP
        )

        // Ombre très lisible, toujours AU-DESSUS du PNG. À phase intermédiaire,
        // l'arrière devient réellement noir/gris au lieu de rester presque blanc.
        val backAlpha = (245f - 35f * lit).toInt().coerceIn(205, 245)
        moonShadePaint.shader = LinearGradient(
            frontX, frontY, backX, backY,
            intArrayOf(
                Color.argb(0, 0, 0, 0),
                Color.argb(0, 0, 0, 0),
                Color.argb((backAlpha * 0.72f).toInt(), 0, 0, 0),
                Color.argb(backAlpha, 0, 0, 0)
            ),
            floatArrayOf(0f, terminator, softEnd, 1f),
            Shader.TileMode.CLAMP
        )

        val oval = RectF(moonX - moonRadius, moonY - moonRadius, moonX + moonRadius, moonY + moonRadius)
        val moonClip = Path().apply { addOval(oval, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(moonClip)
        canvas.drawRect(oval, moonLightPaint)
        canvas.drawRect(oval, moonShadePaint)
        canvas.restore()
        moonLightPaint.shader = null
        moonShadePaint.shader = null
    }

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

        val cross = antiX * moonDirY - antiY * moonDirX
        val tangentX = -antiY
        val tangentY = antiX
        val offset = (cross * moonRadius * 2.4f).coerceIn(-moonRadius * 0.95f, moonRadius * 0.95f)
        val shadowX = moonX + tangentX * offset
        val shadowY = moonY + tangentY * offset

        val shadowRadius = moonRadius * (0.88f + 0.28f * strength)
        val alpha = (235f * strength).toInt().coerceIn(0, 235)
        earthShadowPaint.shader = RadialGradient(
            shadowX, shadowY, shadowRadius,
            intArrayOf(
                Color.argb(alpha, 5, 5, 8),
                Color.argb((alpha * 0.78f).toInt(), 18, 8, 10),
                Color.argb(0, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.70f, 1f),
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
        return ((1.0 - cos(separation)) * 0.5).toFloat().coerceIn(0.02f, 1f)
    }

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
