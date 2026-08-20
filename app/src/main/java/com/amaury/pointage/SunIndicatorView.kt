package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonLitPath = Path()
    private val earthClipPath = Path()
    private val earthLandPath = Path()
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
    private var lunarPhase = 0.0

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshAstronomy()
            if (isAttachedToWindow && visibleCelestial) handler.postDelayed(this, 60_000L)
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
            handler.postDelayed(refreshTask, 60_000L)
        }
        invalidate()
    }

    fun setNightMode(night: Boolean) {
        if (nightMode == night) return
        nightMode = night
        AppThemeCatalog.setCelestialNight(context, night)
        contentDescription = "Soleil, Terre et Lune"
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
            handler.postDelayed(refreshTask, 60_000L)
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
        lunarPhase = computeLunarPhase(now)
        if (location != null) {
            sunPosition = CelestialEphemeris.sun(location.latitude, location.longitude, now)
            moonPosition = CelestialEphemeris.moon(location.latitude, location.longitude, now)
        } else {
            sunPosition = null
            moonPosition = null
        }
        invalidate()
    }

    private fun computeLunarPhase(timeMs: Long): Double {
        val synodicMonth = 29.530588853
        val knownNewMoonJd = 2451550.1
        val jd = 2440587.5 + timeMs / 86400000.0
        val age = ((jd - knownNewMoonJd) % synodicMonth + synodicMonth) % synodicMonth
        return age / synodicMonth
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
        val activeGlow = max(base * 0.10f, 26f)
        val inactiveGlow = activeGlow * 0.68f
        val activeCore = activeGlow * 0.30f
        val inactiveCore = activeCore * 0.78f

        val sun = sunPosition
        val moon = moonPosition
        val sunScreen = sun?.let { mapSkyPosition(it) }

        drawEarth(canvas, width * 0.5f, height * 0.5f, max(base * 0.043f, 12f), sun)

        if (sun != null && sunScreen != null) {
            val active = !nightMode
            val size = sun.apparentScale.toFloat()
            drawSun(canvas, sunScreen.first, sunScreen.second,
                (if (active) activeGlow else inactiveGlow) * size,
                (if (active) activeCore else inactiveCore) * size,
                active)
        }

        if (moon != null) {
            val p = mapSkyPosition(moon)
            val active = nightMode
            val size = moon.apparentScale.toFloat()
            drawMoon(canvas, p.first, p.second,
                (if (active) activeGlow * 0.95f else inactiveGlow * 0.92f) * size,
                (if (active) activeCore else inactiveCore) * size,
                active,
                sunScreen)
        }

        if (sun == null && moon == null) {
            val sunFallback = fallbackPosition(false)
            val moonFallback = fallbackPosition(true)
            drawSun(canvas, sunFallback.first, sunFallback.second, if (!nightMode) activeGlow else inactiveGlow, if (!nightMode) activeCore else inactiveCore, !nightMode)
            drawMoon(canvas, moonFallback.first, moonFallback.second, if (nightMode) activeGlow * 0.95f else inactiveGlow * 0.92f, if (nightMode) activeCore else inactiveCore, nightMode, sunFallback)
        }
    }

    private fun drawEarth(canvas: Canvas, cx: Float, cy: Float, r: Float, sun: CelestialEphemeris.Position?) {
        paint.shader = RadialGradient(
            cx, cy, r * 1.42f,
            intArrayOf(Color.argb(85, 105, 190, 255), Color.argb(35, 75, 145, 235), Color.TRANSPARENT),
            floatArrayOf(0.58f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.42f, paint)
        paint.shader = null

        earthClipPath.reset()
        earthClipPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(earthClipPath)

        val sunPoint = sun?.let { mapSkyPosition(it) }
        var lx = -0.55f
        var ly = -0.35f
        if (sunPoint != null) {
            val dx = sunPoint.first - cx
            val dy = sunPoint.second - cy
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            lx = dx / len
            ly = dy / len
        }

        paint.shader = RadialGradient(
            cx + lx * r * 0.42f, cy + ly * r * 0.42f, r * 1.35f,
            intArrayOf(Color.rgb(58, 154, 226), Color.rgb(18, 92, 170), Color.rgb(5, 30, 75)),
            floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        val dayFraction = ((System.currentTimeMillis() / 1000L) % 86164L) / 86164f
        canvas.save()
        canvas.rotate(dayFraction * 360f, cx, cy)
        earthLandPath.reset()
        earthLandPath.moveTo(cx - r * 0.70f, cy - r * 0.18f)
        earthLandPath.cubicTo(cx - r * 0.50f, cy - r * 0.64f, cx - r * 0.08f, cy - r * 0.55f, cx - r * 0.05f, cy - r * 0.25f)
        earthLandPath.cubicTo(cx - r * 0.20f, cy - r * 0.02f, cx - r * 0.04f, cy + r * 0.22f, cx - r * 0.34f, cy + r * 0.48f)
        earthLandPath.cubicTo(cx - r * 0.60f, cy + r * 0.38f, cx - r * 0.76f, cy + r * 0.08f, cx - r * 0.70f, cy - r * 0.18f)
        earthLandPath.close()
        earthLandPath.moveTo(cx + r * 0.18f, cy - r * 0.50f)
        earthLandPath.cubicTo(cx + r * 0.52f, cy - r * 0.58f, cx + r * 0.78f, cy - r * 0.20f, cx + r * 0.55f, cy + r * 0.02f)
        earthLandPath.cubicTo(cx + r * 0.38f, cy + r * 0.15f, cx + r * 0.48f, cy + r * 0.48f, cx + r * 0.18f, cy + r * 0.58f)
        earthLandPath.cubicTo(cx - r * 0.02f, cy + r * 0.32f, cx + r * 0.02f, cy - r * 0.20f, cx + r * 0.18f, cy - r * 0.50f)
        earthLandPath.close()
        paint.color = Color.rgb(72, 148, 82)
        canvas.drawPath(earthLandPath, paint)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, r * 0.10f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(105, 245, 250, 255)
        canvas.drawArc(RectF(cx - r * 0.76f, cy - r * 0.48f, cx + r * 0.45f, cy + r * 0.15f), 198f, 82f, false, paint)
        canvas.drawArc(RectF(cx - r * 0.30f, cy - r * 0.08f, cx + r * 0.82f, cy + r * 0.60f), 22f, 74f, false, paint)
        paint.style = Paint.Style.FILL

        val shadowCx = cx - lx * r * 0.86f
        val shadowCy = cy - ly * r * 0.86f
        paint.shader = RadialGradient(
            shadowCx, shadowCy, r * 1.30f,
            intArrayOf(Color.argb(180, 0, 7, 25), Color.argb(55, 0, 8, 30), Color.TRANSPARENT),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(shadowCx, shadowCy, r * 1.18f, paint)
        paint.shader = null
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, r * 0.08f)
        paint.color = Color.argb(185, 145, 215, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun fallbackPosition(night: Boolean): Pair<Float, Float> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val progress = if (night) {
            if (hour >= 20) ((hour - 20) / 11f).coerceIn(0f, 1f) else ((hour + 4) / 11f).coerceIn(0f, 1f)
        } else {
            ((hour - 7) / 13f).coerceIn(0f, 1f)
        }
        val baseX = width * (0.10f + progress * 0.80f)
        val rotatedX = ((baseX / width) - deviceAzimuth / 360f)
        val wrapped = ((rotatedX % 1f) + 1f) % 1f
        val x = width * (0.08f + wrapped * 0.84f)
        val arc = 1f - abs(progress * 2f - 1f)
        val pitchShift = (devicePitch / 90f) * height * 0.22f
        val y = (height * (0.78f - arc * 0.52f) + pitchShift).coerceIn(height * 0.08f, height * 0.92f)
        return x to y
    }

    private fun mapSkyPosition(position: CelestialEphemeris.Position): Pair<Float, Float> {
        val relative = shortestDelta(deviceAzimuth, position.azimuth.toFloat())
        val x = width * (0.50f + relative / 220f).coerceIn(0.06f, 0.94f)
        val altitude = position.altitude.toFloat().coerceIn(-35f, 90f)
        val apparentAltitude = altitude + devicePitch * 0.70f
        val normalizedAltitude = ((apparentAltitude + 35f) / 125f).coerceIn(0f, 1f)
        val y = height * (0.90f - normalizedAltitude * 0.78f)
        return x to y
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, coreRadius: Float, active: Boolean) {
        val boost = if (active) 1f else 0.48f
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(Color.argb((235 * boost).toInt(), 255, 246, 190), Color.argb((175 * boost).toInt(), 255, 205, 85), Color.argb((75 * boost).toInt(), 255, 155, 30), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.64f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null
        paint.color = Color.argb(if (active) 250 else 150, 255, 226, 120)
        canvas.drawCircle(cx, cy, coreRadius, paint)
    }

    private fun drawMoon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        glowRadius: Float,
        coreRadius: Float,
        active: Boolean,
        sunScreen: Pair<Float, Float>?
    ) {
        val boost = if (active) 1f else 0.46f
        paint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(Color.argb((205 * boost).toInt(), 235, 244, 255), Color.argb((125 * boost).toInt(), 165, 195, 235), Color.argb((45 * boost).toInt(), 100, 130, 190), Color.TRANSPARENT),
            floatArrayOf(0f, 0.30f, 0.64f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null

        val r = coreRadius * 1.12f
        paint.color = Color.argb(if (active) 92 else 48, 68, 80, 102)
        canvas.drawCircle(cx, cy, r, paint)

        val phaseAngle = lunarPhase * 2.0 * PI
        val illuminatedFraction = (1.0 - cos(phaseAngle)) * 0.5
        val terminator = cos(phaseAngle).toFloat().coerceIn(-1f, 1f)

        // Le côté +X du repère local pointe toujours vers le Soleil réel affiché.
        val lightAngleDeg = if (sunScreen != null) {
            Math.toDegrees(atan2((sunScreen.second - cy).toDouble(), (sunScreen.first - cx).toDouble())).toFloat()
        } else 0f

        moonLitPath.reset()
        val steps = 48
        for (i in 0..steps) {
            val yNorm = -1f + 2f * i / steps.toFloat()
            val limb = sqrt((1f - yNorm * yNorm).coerceAtLeast(0f))
            val x = r * limb
            val y = r * yNorm
            if (i == 0) moonLitPath.moveTo(cx + x, cy + y) else moonLitPath.lineTo(cx + x, cy + y)
        }
        for (i in steps downTo 0) {
            val yNorm = -1f + 2f * i / steps.toFloat()
            val limb = sqrt((1f - yNorm * yNorm).coerceAtLeast(0f))
            val x = r * terminator * limb
            val y = r * yNorm
            moonLitPath.lineTo(cx + x, cy + y)
        }
        moonLitPath.close()

        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        canvas.rotate(lightAngleDeg, cx, cy)

        val alpha = (45 + illuminatedFraction * (if (active) 210 else 120)).toInt().coerceIn(35, 255)
        paint.color = Color.argb(alpha, 232, 240, 255)
        canvas.drawPath(moonLitPath, paint)

        // Le reflet principal est lui aussi déplacé vers le Soleil.
        paint.shader = RadialGradient(
            cx + r * 0.34f, cy - r * 0.10f, r * 1.15f,
            intArrayOf(Color.argb(if (active) 78 else 34, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1.2f, coreRadius * 0.08f)
        paint.color = Color.argb(if (active) 190 else 110, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
