package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Couche astronomique HP.
 *
 * Le Soleil et la Lune sont désormais des PNG indépendants.
 * La Terre reste volontairement dessinée par le code jusqu'à création
 * d'un asset dédié.
 */
class SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val earthClipPath = Path()
    private val earthLandPath = Path()

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
            if (isAttachedToWindow && visibleCelestial) {
                handler.postDelayed(this, 30_000L)
            }
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
        val dynamicEnabled = context
            .getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
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
        val orbitRadius = base * 0.31f
        val activeRadius = max(base * 0.085f, 24f)
        val inactiveRadius = activeRadius * 0.62f
        val sun = sunPosition
        val moon = moonPosition
        val sunScreen = sun?.let { mapToWatchOrbit(it, earthX, earthY, orbitRadius) }
        val moonScreen = moon?.let { mapToWatchOrbit(it, earthX, earthY, orbitRadius) }

        if (sun != null && sunScreen != null) {
            val active = !nightMode
            drawCelestialPng(canvas, sunBitmap, sunScreen.first, sunScreen.second,
                (if (active) activeRadius else inactiveRadius) * sun.apparentScale.toFloat(), active)
        }

        if (moon != null && moonScreen != null) {
            val active = nightMode
            drawCelestialPng(canvas, moonBitmap, moonScreen.first, moonScreen.second,
                (if (active) activeRadius * 0.90f else inactiveRadius * 0.86f) * moon.apparentScale.toFloat(), active)
        }

        // Terre inchangée : aucun PNG de Terre n'est créé ni inventé.
        drawEarth(canvas, earthX, earthY, max(base * 0.050f, 13f), sun)

        if (sun == null && moon == null) {
            val sunFallback = orbitFallback(false, earthX, earthY, orbitRadius)
            val moonFallback = orbitFallback(true, earthX, earthY, orbitRadius)
            drawCelestialPng(canvas, sunBitmap, sunFallback.first, sunFallback.second,
                if (!nightMode) activeRadius else inactiveRadius, !nightMode)
            drawCelestialPng(canvas, moonBitmap, moonFallback.first, moonFallback.second,
                if (nightMode) activeRadius * 0.90f else inactiveRadius * 0.86f, nightMode)
            drawEarth(canvas, earthX, earthY, max(base * 0.050f, 13f), null)
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
        bitmapPaint.alpha = if (active) 255 else 145
        val dst = RectF(cx - dstWidth / 2f, cy - dstHeight / 2f, cx + dstWidth / 2f, cy + dstHeight / 2f)
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    private fun mapToWatchOrbit(position: CelestialEphemeris.Position, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val relativeAz = Math.toRadians(shortestDelta(deviceAzimuth, position.azimuth.toFloat()).toDouble())
        val altitude = Math.toRadians(position.altitude.coerceIn(-90.0, 90.0))
        val pitch = Math.toRadians(devicePitch.toDouble())
        val perspective = (0.72 + 0.28 * cos(altitude - pitch)).toFloat()
        val x = cx + sin(relativeAz).toFloat() * radius * perspective
        val y = cy - cos(relativeAz).toFloat() * radius * 0.72f - sin(altitude - pitch).toFloat() * radius * 0.24f
        return x.coerceIn(width * 0.18f, width * 0.82f) to y.coerceIn(height * 0.18f, height * 0.88f)
    }

    private fun orbitFallback(night: Boolean, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY) + calendar.get(java.util.Calendar.MINUTE) / 60f
        val angle = if (night) hour / 24f * 360f + 180f else hour / 24f * 360f
        val a = Math.toRadians((angle - deviceAzimuth).toDouble())
        return cx + sin(a).toFloat() * radius to cy - cos(a).toFloat() * radius * 0.72f
    }

    private fun drawEarth(canvas: Canvas, cx: Float, cy: Float, r: Float, sun: CelestialEphemeris.Position?) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, r * 1.42f,
            intArrayOf(Color.argb(85,105,190,255), Color.argb(35,75,145,235), Color.TRANSPARENT),
            floatArrayOf(0.58f,0.78f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.42f, paint)
        paint.shader = null

        earthClipPath.reset()
        earthClipPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(earthClipPath)

        val sunPoint = sun?.let { mapToWatchOrbit(it, cx, cy, min(width,height)*0.31f) }
        var lx = -0.55f
        var ly = -0.35f
        if (sunPoint != null) {
            val dx = sunPoint.first - cx
            val dy = sunPoint.second - cy
            val len = sqrt(dx*dx + dy*dy).coerceAtLeast(1f)
            lx = dx / len
            ly = dy / len
        }

        paint.shader = RadialGradient(cx + lx*r*0.42f, cy + ly*r*0.42f, r*1.35f,
            intArrayOf(Color.rgb(58,154,226), Color.rgb(18,92,170), Color.rgb(5,30,75)),
            floatArrayOf(0f,0.56f,1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        val dayFraction = ((System.currentTimeMillis()/1000L)%86164L)/86164f
        canvas.save()
        canvas.rotate(dayFraction*360f, cx, cy)
        earthLandPath.reset()
        earthLandPath.moveTo(cx-r*.70f,cy-r*.18f)
        earthLandPath.cubicTo(cx-r*.50f,cy-r*.64f,cx-r*.08f,cy-r*.55f,cx-r*.05f,cy-r*.25f)
        earthLandPath.cubicTo(cx-r*.20f,cy-r*.02f,cx-r*.04f,cy+r*.22f,cx-r*.34f,cy+r*.48f)
        earthLandPath.cubicTo(cx-r*.60f,cy+r*.38f,cx-r*.76f,cy+r*.08f,cx-r*.70f,cy-r*.18f)
        earthLandPath.close()
        earthLandPath.moveTo(cx+r*.18f,cy-r*.50f)
        earthLandPath.cubicTo(cx+r*.52f,cy-r*.58f,cx+r*.78f,cy-r*.20f,cx+r*.55f,cy+r*.02f)
        earthLandPath.cubicTo(cx+r*.38f,cy+r*.15f,cx+r*.48f,cy+r*.48f,cx+r*.18f,cy+r*.58f)
        earthLandPath.cubicTo(cx-r*.02f,cy+r*.32f,cx+r*.02f,cy-r*.20f,cx+r*.18f,cy-r*.50f)
        earthLandPath.close()
        paint.color = Color.rgb(72,148,82)
        canvas.drawPath(earthLandPath, paint)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f,r*.10f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(105,245,250,255)
        canvas.drawArc(RectF(cx-r*.76f,cy-r*.48f,cx+r*.45f,cy+r*.15f),198f,82f,false,paint)
        canvas.drawArc(RectF(cx-r*.30f,cy-r*.08f,cx+r*.82f,cy+r*.60f),22f,74f,false,paint)
        paint.style = Paint.Style.FILL

        val shadowCx = cx-lx*r*.86f
        val shadowCy = cy-ly*r*.86f
        paint.shader = RadialGradient(shadowCx,shadowCy,r*1.30f,
            intArrayOf(Color.argb(180,0,7,25),Color.argb(55,0,8,30),Color.TRANSPARENT),
            floatArrayOf(0f,.65f,1f),Shader.TileMode.CLAMP)
        canvas.drawCircle(shadowCx,shadowCy,r*1.18f,paint)
        paint.shader = null
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f,r*.08f)
        paint.color = Color.argb(185,145,215,255)
        canvas.drawCircle(cx,cy,r,paint)
        paint.style = Paint.Style.FILL
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f
}
