package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.CelestialBodyV2
import com.amaury.pointage.v2.engine.CelestialDeviceFrameV2
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import com.amaury.pointage.v2.engine.LunarEclipseStageV2
import com.amaury.pointage.v2.engine.LunarEclipseV2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Couche astronomique de l'horloge.
 *
 * La vue ne calcule ni GPS, ni capteurs, ni astronomie. Elle rend uniquement
 * l'état V2 qualifié. La position Soleil/Lune utilise le repère 3D réel de
 * l'écran : azimut, inclinaison, roulis, rotation d'écran et Nord vrai.
 */
class SunIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val moonLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonShadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val terminatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val earthPenumbraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val earthUmbraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunBitmap: Bitmap by lazy { HpDesignAssets.sun }
    private val moonBitmap: Bitmap by lazy { HpDesignAssets.moon }

    private var visibleCelestial = false
    private var trackerSubscribed = false
    private var nightMode = false
    private var celestialSnapshot: CelestialSnapshotV2? = null
    private var deviceFrame: CelestialDeviceFrameV2? = null
    private var deviceAzimuth = 0f
    private var devicePitch = 0f

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Conservé pour compatibilité avec l'installateur de relief historique. */
    fun updateLightAngle(newAngle: Float) = Unit

    /** Conservé pour compatibilité avec d'anciens appelants de l'UI. */
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
        updateTrackingSubscription()
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
        updateTrackingSubscription()
    }

    override fun onDetachedFromWindow() {
        if (trackerSubscribed) {
            CelestialTrackerV2.unsubscribe(this)
            trackerSubscribed = false
        }
        super.onDetachedFromWindow()
    }

    private fun updateTrackingSubscription() {
        val shouldSubscribe = isAttachedToWindow && visibleCelestial
        if (shouldSubscribe && !trackerSubscribed) {
            trackerSubscribed = true
            CelestialTrackerV2.subscribe(context, this) { tracking ->
                celestialSnapshot = tracking.snapshot
                deviceFrame = tracking.deviceFrame
                deviceAzimuth = normalize(tracking.deviceAzimuthDeg)
                devicePitch = tracking.devicePitchDeg.coerceIn(-90f, 90f)
                tracking.snapshot?.let { setNightMode(it.night) }
                invalidate()
            }
        } else if (!shouldSubscribe && trackerSubscribed) {
            CelestialTrackerV2.unsubscribe(this)
            trackerSubscribed = false
            celestialSnapshot = null
            deviceFrame = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleCelestial || width <= 0 || height <= 0) return
        val snapshot = celestialSnapshot ?: return
        val frame = deviceFrame ?: return

        val base = min(width, height).toFloat()
        val earthX = width * 0.50f
        val earthY = height * 0.55f
        val horizonRadius = base * 0.43f
        val activeRadius = max(base * 0.078f, 22f)
        val inactiveRadius = activeRadius * 0.82f
        val sun = snapshot.sun
        val moon = snapshot.moon
        val sunScreen = mapToDeviceSky(sun, frame, earthX, earthY, horizonRadius)
        val moonScreen = mapToDeviceSky(moon, frame, earthX, earthY, horizonRadius)

        // Le Soleil n'est dessiné que s'il est réellement visible dans
        // l'hémisphère regardé par l'écran et au-dessus de l'horizon civil.
        if (sunScreen != null) {
            CelestialLightingState.updateSunDirection(sunScreen.first - earthX, sunScreen.second - earthY)
            drawCelestialPng(
                canvas,
                sunBitmap,
                sunScreen.first,
                sunScreen.second,
                (if (!nightMode) activeRadius else inactiveRadius) * sun.apparentScale.toFloat(),
                !nightMode
            )
        }

        if (moonScreen != null) {
            val moonRadius = (
                if (nightMode) activeRadius * 0.94f else inactiveRadius * 0.94f
                ) * moon.apparentScale.toFloat()
            drawCelestialPng(canvas, moonBitmap, moonScreen.first, moonScreen.second, moonRadius, nightMode)

            val lunarLightDirection = CelestialScreenGeometryV2.directionToward(
                from = moon,
                to = sun,
                frame = frame
            )
            drawMoonSunlight(
                canvas = canvas,
                moonX = moonScreen.first,
                moonY = moonScreen.second,
                moonRadius = moonRadius,
                lightDirX = lunarLightDirection?.x?.toFloat() ?: 1f,
                lightDirY = lunarLightDirection?.y?.toFloat() ?: 0f,
                illumination = snapshot.moonPhase.illuminatedFraction.toFloat()
            )

            val eclipseDirection = CelestialScreenGeometryV2.directionTowardAntiSun(
                moon = moon,
                sun = sun,
                frame = frame
            )
            drawEarthShadowOnMoon(
                canvas = canvas,
                moonX = moonScreen.first,
                moonY = moonScreen.second,
                moonRadius = moonRadius,
                shadowDirX = eclipseDirection?.x?.toFloat() ?: 1f,
                shadowDirY = eclipseDirection?.y?.toFloat() ?: 0f,
                eclipse = snapshot.lunarEclipse
            )
        }
    }

    private fun drawCelestialPng(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        radius: Float,
        active: Boolean
    ) {
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
        val dst = RectF(
            cx - dstWidth / 2f,
            cy - dstHeight / 2f,
            cx + dstWidth / 2f,
            cy + dstHeight / 2f
        )
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    /**
     * Phase lunaire V2.
     *
     * La fraction éclairée vient du véritable angle de phase. La direction
     * d'éclairage vient de la tangente réelle Lune -> Soleil sur la sphère
     * céleste, projetée dans le repère physique de l'écran.
     */
    private fun drawMoonSunlight(
        canvas: Canvas,
        moonX: Float,
        moonY: Float,
        moonRadius: Float,
        lightDirX: Float,
        lightDirY: Float,
        illumination: Float
    ) {
        var dx = lightDirX
        var dy = lightDirY
        var length = sqrt(dx * dx + dy * dy)
        if (length < 0.0001f) {
            dx = 1f
            dy = 0f
            length = 1f
        }
        val ux = dx / length
        val uy = dy / length
        val vx = -uy
        val vy = ux
        val lit = illumination.coerceIn(0f, 1f)

        val oval = RectF(moonX - moonRadius, moonY - moonRadius, moonX + moonRadius, moonY + moonRadius)
        val moonClip = Path().apply { addOval(oval, Path.Direction.CW) }
        val frontX = moonX + ux * moonRadius * 0.72f
        val frontY = moonY + uy * moonRadius * 0.72f

        moonLightPaint.shader = RadialGradient(
            frontX,
            frontY,
            moonRadius * 1.55f,
            intArrayOf(
                Color.argb((62 + 72 * lit).toInt().coerceIn(0, 134), 255, 248, 218),
                Color.argb((24 + 34 * lit).toInt().coerceIn(0, 58), 255, 245, 220),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.clipPath(moonClip)
        canvas.drawRect(oval, moonLightPaint)

        // +1 à nouvelle Lune, 0 au quartier, -1 à pleine Lune.
        val phaseCos = (1f - 2f * lit).coerceIn(-1f, 1f)
        val shadowPath = Path()
        val terminatorPath = Path()
        val steps = 72

        for (i in 0..steps) {
            val yLocal = -moonRadius + (2f * moonRadius * i / steps)
            val halfWidth = sqrt(max(0f, moonRadius * moonRadius - yLocal * yLocal))
            val xLocal = -halfWidth
            val sx = moonX + ux * xLocal + vx * yLocal
            val sy = moonY + uy * xLocal + vy * yLocal
            if (i == 0) shadowPath.moveTo(sx, sy) else shadowPath.lineTo(sx, sy)
        }

        for (i in steps downTo 0) {
            val yLocal = -moonRadius + (2f * moonRadius * i / steps)
            val halfWidth = sqrt(max(0f, moonRadius * moonRadius - yLocal * yLocal))
            val xLocal = phaseCos * halfWidth
            val tx = moonX + ux * xLocal + vx * yLocal
            val ty = moonY + uy * xLocal + vy * yLocal
            shadowPath.lineTo(tx, ty)
        }
        shadowPath.close()

        for (i in 0..steps) {
            val yLocal = -moonRadius + (2f * moonRadius * i / steps)
            val halfWidth = sqrt(max(0f, moonRadius * moonRadius - yLocal * yLocal))
            val xLocal = phaseCos * halfWidth
            val tx = moonX + ux * xLocal + vx * yLocal
            val ty = moonY + uy * xLocal + vy * yLocal
            if (i == 0) terminatorPath.moveTo(tx, ty) else terminatorPath.lineTo(tx, ty)
        }

        val shadeAlpha = (232f - 28f * lit).toInt().coerceIn(190, 232)
        moonShadePaint.shader = LinearGradient(
            moonX + ux * moonRadius,
            moonY + uy * moonRadius,
            moonX - ux * moonRadius,
            moonY - uy * moonRadius,
            intArrayOf(
                Color.argb((shadeAlpha * 0.80f).toInt(), 2, 3, 6),
                Color.argb(shadeAlpha, 0, 0, 2)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(shadowPath, moonShadePaint)

        terminatorPaint.strokeWidth = max(1.0f, moonRadius * 0.075f)
        terminatorPaint.color = Color.argb(72, 0, 0, 0)
        canvas.drawPath(terminatorPath, terminatorPaint)

        canvas.restore()
        moonLightPaint.shader = null
        moonShadePaint.shader = null
    }

    /**
     * Ombre terrestre lors d'une éclipse lunaire.
     *
     * V2 fournit les rayons physiques de l'umbra et de la pénombre à la
     * distance actuelle de la Lune. L'axe anti-solaire est projeté dans le
     * même repère 3D que le disque lunaire.
     */
    private fun drawEarthShadowOnMoon(
        canvas: Canvas,
        moonX: Float,
        moonY: Float,
        moonRadius: Float,
        shadowDirX: Float,
        shadowDirY: Float,
        eclipse: LunarEclipseV2
    ) {
        if (eclipse.stage == LunarEclipseStageV2.NONE) return

        var dx = shadowDirX
        var dy = shadowDirY
        var length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) {
            val angle = Math.toRadians(eclipse.shadowPositionAngleDeg)
            dx = sin(angle).toFloat()
            dy = -kotlin.math.cos(angle).toFloat()
            length = 1f
        }
        val ux = dx / length
        val uy = dy / length

        val centreOffset = eclipse.shadowAxisOffsetMoonRadii.toFloat() * moonRadius
        val shadowX = moonX + ux * centreOffset
        val shadowY = moonY + uy * centreOffset
        val penumbraRadius = eclipse.penumbraRadiusMoonRadii.toFloat() * moonRadius
        val umbraRadius = eclipse.umbraRadiusMoonRadii.toFloat() * moonRadius

        val moonOval = RectF(
            moonX - moonRadius,
            moonY - moonRadius,
            moonX + moonRadius,
            moonY + moonRadius
        )
        val clip = Path().apply { addOval(moonOval, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)

        earthPenumbraPaint.shader = RadialGradient(
            shadowX,
            shadowY,
            penumbraRadius.coerceAtLeast(moonRadius),
            intArrayOf(
                Color.argb(58, 38, 18, 16),
                Color.argb(42, 22, 12, 14),
                Color.argb(0, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(moonOval, earthPenumbraPaint)

        if (eclipse.stage == LunarEclipseStageV2.PARTIAL || eclipse.stage == LunarEclipseStageV2.TOTAL) {
            val magnitude = eclipse.umbralMagnitude.toFloat().coerceIn(0f, 1.5f)
            val coreAlpha = (178f + 34f * magnitude).toInt().coerceIn(0, 225)
            earthUmbraPaint.shader = RadialGradient(
                shadowX,
                shadowY,
                umbraRadius.coerceAtLeast(moonRadius),
                intArrayOf(
                    Color.argb(coreAlpha, 30, 5, 8),
                    Color.argb((coreAlpha * 0.92f).toInt(), 7, 3, 6),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.91f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(moonOval, earthUmbraPaint)
        }

        canvas.restore()
        earthPenumbraPaint.shader = null
        earthUmbraPaint.shader = null
    }

    private fun mapToDeviceSky(
        position: CelestialBodyV2,
        frame: CelestialDeviceFrameV2,
        cx: Float,
        cy: Float,
        horizonRadius: Float
    ): Pair<Float, Float>? {
        val projected = CelestialScreenGeometryV2.projectInDeviceSky(position, frame) ?: return null
        val x = cx + projected.xRadiusFraction.toFloat() * horizonRadius
        val y = cy + projected.yRadiusFraction.toFloat() * horizonRadius
        return x to y
    }

    private fun normalize(value: Float): Float = ((value % 360f) + 360f) % 360f
}
