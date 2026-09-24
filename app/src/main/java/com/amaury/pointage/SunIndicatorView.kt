package com.amaury.pointage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.engine.CelestialBodyV2
import com.amaury.pointage.v2.engine.CelestialDeviceFrameV2
import com.amaury.pointage.v2.engine.CelestialHeadingQualityV2
import com.amaury.pointage.v2.engine.CelestialHorizonTransitionV2
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.CelestialScreenGeometryV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import com.amaury.pointage.v2.engine.LunarEclipseStageV2
import com.amaury.pointage.v2.engine.LunarEclipseV2
import com.amaury.pointage.v2.engine.SolarEclipseGeometryV2
import com.amaury.pointage.v2.engine.SolarEclipseV2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Couche astronomique de l'horloge.
 *
 * La vue ne calcule ni GPS, ni capteurs, ni éphémérides. Elle rend uniquement
 * l'état V2 qualifié dans la carte topocentrique 360° centrée sur la Terre.
 * Les tailles des PNG restent des symboles de lecture et ne doivent jamais être
 * utilisées comme géométrie physique d'une éclipse.
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
    private val solarOccultationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(248, 2, 3, 5)
        style = Paint.Style.FILL
    }
    private val sunTwilightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunBitmap: Bitmap by lazy { HpDesignAssets.sun }
    private val moonBitmap: Bitmap by lazy { HpDesignAssets.moon }

    private var visibleCelestial = false
    private var hostActivityVisible = false
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
        val homeVisible = (parent as? View)?.let {
            it.id == R.id.celestialHomePanel && it.visibility == VISIBLE
        } == true
        // L'écran Accueil est la destination dédiée au ciel : son rendu ne doit
        // jamais dépendre du réglage optionnel d'éclairage dynamique de l'UI.
        visibleCelestial = visible || dynamicEnabled || homeVisible
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

    /** Lie explicitement l'acquisition au cycle de vie reel de l'Activity. */
    fun setHostActivityVisible(visible: Boolean) {
        if (hostActivityVisible == visible) return
        hostActivityVisible = visible
        updateTrackingSubscription()
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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) post { updateTrackingSubscription() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) post { updateTrackingSubscription() }
    }

    private fun updateTrackingSubscription() {
        val shouldSubscribe = hostActivityVisible && isAttachedToWindow &&
            visibleCelestial && isShown && windowVisibility == VISIBLE
        if (shouldSubscribe && !trackerSubscribed) {
            trackerSubscribed = true
            CelestialTrackerV2.subscribe(context, this) { tracking ->
                val directionalSkyUsable = tracking.hasRealSky
                celestialSnapshot = tracking.snapshot?.takeIf { directionalSkyUsable }
                deviceFrame = tracking.deviceFrame?.takeIf { directionalSkyUsable }
                deviceAzimuth = normalize(tracking.deviceAzimuthDeg)
                devicePitch = tracking.devicePitchDeg.coerceIn(-90f, 90f)
                if (!directionalSkyUsable) {
                    CelestialLightingState.clearSunDirection()
                }
                // Le jour/nuit dépend de l'éphéméride et du GPS, pas de la qualité
                // de la boussole : il reste donc mis à jour même si le cap est bloqué.
                tracking.snapshot?.let { setNightMode(it.night) }
                updateStatus(tracking)
                invalidate()
            }
        } else if (!shouldSubscribe && trackerSubscribed) {
            CelestialTrackerV2.unsubscribe(this)
            trackerSubscribed = false
            celestialSnapshot = null
            deviceFrame = null
        }
    }

    private fun updateStatus(tracking: CelestialTrackerV2.State) {
        val status = when (tracking.locationQuality) {
            CelestialLocationQualityV2.NO_PERMISSION -> "Localisation refusée"
            CelestialLocationQualityV2.UNAVAILABLE -> "Localisation indisponible"
            CelestialLocationQualityV2.STALE -> "Localisation trop ancienne"
            CelestialLocationQualityV2.INACCURATE -> "Localisation imprécise"
            CelestialLocationQualityV2.VALID -> when (tracking.headingQuality) {
                CelestialHeadingQualityV2.UNAVAILABLE -> "Boussole indisponible"
                CelestialHeadingQualityV2.STALE -> "Boussole trop ancienne"
                CelestialHeadingQualityV2.UNRELIABLE -> "Boussole perturbée · éloigner le téléphone du métal"
                CelestialHeadingQualityV2.INACCURATE -> "Boussole à calibrer · faire un mouvement en 8"
                CelestialHeadingQualityV2.UNKNOWN_ACCURACY -> "Ciel réel · boussole active"
                CelestialHeadingQualityV2.VALID -> "Ciel réel · GPS et boussole fiables"
            }
        }
        rootView.findViewById<TextView>(R.id.celestialStatusText)?.let { statusView ->
            val healthy = tracking.locationQuality == CelestialLocationQualityV2.VALID &&
                CelestialHeadingPolicyV2.isUsable(tracking.headingQuality)

            if (healthy) {
                // Accueil propre : aucun bandeau quand GPS + boussole sont exploitables.
                statusView.visibility = GONE
                statusView.text = ""
                configureLocationRecovery(statusView, tracking.locationQuality)
            } else {
                statusView.visibility = VISIBLE
                statusView.text = if (
                    tracking.locationQuality == CelestialLocationQualityV2.NO_PERMISSION
                ) {
                    "$status · toucher pour autoriser"
                } else {
                    status
                }
                configureLocationRecovery(statusView, tracking.locationQuality)
            }
        }

        val sky = tracking.snapshot
        val detail = if (tracking.hasRealSky && sky != null) {
            val moment = if (sky.night) "nuit" else "jour"
            "$moment, Soleil ${sky.sun.altitudeDeg.toInt()} degrés, Lune ${sky.moon.altitudeDeg.toInt()} degrés"
        } else {
            "position exacte du Soleil et de la Lune masquée"
        }
        rootView.findViewById<View>(R.id.celestialHomePanel)?.contentDescription =
            "Accueil céleste. $status. $detail."
    }

    private fun configureLocationRecovery(
        statusView: TextView,
        quality: CelestialLocationQualityV2
    ) {
        val activity = context as? Activity
        val actionable = quality == CelestialLocationQualityV2.NO_PERMISSION && activity != null
        statusView.isClickable = actionable
        statusView.isFocusable = actionable
        statusView.importantForAccessibility = if (actionable) {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusView.contentDescription = if (actionable) {
            "Localisation refusee. Activer la localisation pour afficher le ciel reel."
        } else {
            null
        }
        if (actionable) {
            val owner = requireNotNull(activity)
            statusView.setOnClickListener { requestLocationRecovery(owner) }
        } else {
            statusView.setOnClickListener(null)
        }
    }

    private fun requestLocationRecovery(activity: Activity) {
        val preferences = activity.getSharedPreferences(
            CELESTIAL_PERMISSION_PREFS,
            Context.MODE_PRIVATE
        )
        val alreadyAttempted = preferences.getBoolean(KEY_PERMISSION_ATTEMPTED, false)
        val canExplain = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (alreadyAttempted && !canExplain) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }

        preferences.edit().putBoolean(KEY_PERMISSION_ATTEMPTED, true).apply()
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            CELESTIAL_LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visibleCelestial || width <= 0 || height <= 0) return
        val snapshot = celestialSnapshot ?: return
        val frame = deviceFrame ?: return

        // Le facteur vertical réserve la marge des disques Soleil/Lune sur les
        // écrans larges. Il évite tout rognage en paysage, tablette et multi-fenêtre.
        val base = CelestialScreenGeometryV2.safeRenderSpan(
            width.toDouble(),
            height.toDouble()
        ).toFloat()
        val earthX = width * 0.50f
        val earthY = screenAnchoredCenterY()
        val clockFaceRadius = base * 0.40f
        // Soleil et Lune sont centrés sur le même repère que l'horloge.
        // Le centre des disques suit le bord extérieur du cadran, sans décalage.
        val horizonRadius = clockFaceRadius
        val activeRadius = max(base * 0.078f, 22f)
        val inactiveRadius = activeRadius * 0.82f
        val sun = snapshot.sun
        val moon = snapshot.moon
        val sunScreen = mapToDeviceSky(sun, frame, earthX, earthY, horizonRadius)
        val moonScreen = mapToDeviceSky(moon, frame, earthX, earthY, horizonRadius)
        val sunDiskAlpha = CelestialHorizonTransitionV2.diskAlpha(sun.altitudeDeg).toFloat()
        val moonDiskAlpha = CelestialHorizonTransitionV2.diskAlpha(moon.altitudeDeg).toFloat()
        val sunGlowAlpha = CelestialHorizonTransitionV2.sunGlowAlpha(sun.altitudeDeg).toFloat()
        val sunScale = CelestialHorizonTransitionV2.diskScale(sun.altitudeDeg).toFloat()
        val moonScale = CelestialHorizonTransitionV2.diskScale(moon.altitudeDeg).toFloat()
        val sunRadius = (if (!nightMode) activeRadius else inactiveRadius) *
            sun.apparentScale.toFloat() * sunScale
        val moonRadius = (
            if (nightMode) activeRadius * 0.94f else inactiveRadius * 0.94f
            ) * moon.apparentScale.toFloat() * moonScale
        val solarEclipse = SolarEclipseGeometryV2.evaluate(sun, moon)

        val sunGlowScreen = if (sunGlowAlpha > 0f) {
            mapToDeviceSky(
                sun.copy(
                    altitudeDeg = CelestialHorizonTransitionV2.altitudeForHorizonGlow(sun.altitudeDeg)
                ),
                frame,
                earthX,
                earthY,
                horizonRadius
            )
        } else {
            null
        }
        if (sunGlowScreen != null) {
            drawSunTwilightGlow(
                canvas = canvas,
                cx = sunGlowScreen.first,
                cy = sunGlowScreen.second,
                radius = activeRadius * 2.8f,
                alpha = sunGlowAlpha
            )
        }

        (sunScreen ?: sunGlowScreen)?.let {
            CelestialLightingState.updateSunDirection(it.first - earthX, it.second - earthY)
        }

        if (solarEclipse.isEclipse && sunScreen != null && moonScreen != null) {
            // Une vraie éclipse reste physique, mais son apparition au ras de
            // l'horizon suit la même transition que les deux disques.
            val eclipseAlpha = minOf(sunDiskAlpha, moonDiskAlpha)
            if (eclipseAlpha > 0f) {
                val layer = canvas.saveLayerAlpha(
                    sunScreen.first - sunRadius * 1.6f,
                    sunScreen.second - sunRadius * 1.6f,
                    sunScreen.first + sunRadius * 1.6f,
                    sunScreen.second + sunRadius * 1.6f,
                    (255f * eclipseAlpha).toInt().coerceIn(0, 255)
                )
                drawCelestialPng(
                    canvas,
                    sunBitmap,
                    sunScreen.first,
                    sunScreen.second,
                    sunRadius,
                    !nightMode
                )
                val moonDirectionFromSun = CelestialScreenGeometryV2.directionToward(
                    from = sun,
                    to = moon,
                    frame = frame
                )
                drawPhysicalSolarOccultation(
                    canvas = canvas,
                    sunX = sunScreen.first,
                    sunY = sunScreen.second,
                    renderedSunRadius = sunRadius,
                    moonDirX = moonDirectionFromSun?.x?.toFloat() ?: 1f,
                    moonDirY = moonDirectionFromSun?.y?.toFloat() ?: 0f,
                    eclipse = solarEclipse
                )
                canvas.restoreToCount(layer)
            }
            return
        }

        // Hors éclipse physique, la Lune est dessinée avant le Soleil. Ainsi les
        // symboles surdimensionnés peuvent se toucher sans créer une fausse
        // occultation noire du disque solaire.
        if (moonScreen != null && moonDiskAlpha > 0f) {
            val moonLayer = canvas.saveLayerAlpha(
                moonScreen.first - moonRadius * 1.5f,
                moonScreen.second - moonRadius * 1.5f,
                moonScreen.first + moonRadius * 1.5f,
                moonScreen.second + moonRadius * 1.5f,
                (255f * moonDiskAlpha).toInt().coerceIn(0, 255)
            )
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
            canvas.restoreToCount(moonLayer)
        }

        if (sunScreen != null && sunDiskAlpha > 0f) {
            drawCelestialPng(
                canvas,
                sunBitmap,
                sunScreen.first,
                sunScreen.second,
                sunRadius,
                !nightMode,
                opacity = sunDiskAlpha
            )
        }
    }

    /**
     * Même ancrage vertical que HpAnalogClockView : la présence ou l'absence
     * des onglets Accueil ne change jamais le centre du système céleste.
     */
    private fun screenAnchoredCenterY(): Float {
        if (!isAttachedToWindow || height <= 0) return height * 0.50f

        val visibleFrame = Rect()
        getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        getLocationInWindow(location)

        val targetWindowY = visibleFrame.exactCenterY()
        val localY = targetWindowY - location[1]
        return localY.coerceIn(height * 0.32f, height * 0.68f)
    }

    private fun drawPhysicalSolarOccultation(
        canvas: Canvas,
        sunX: Float,
        sunY: Float,
        renderedSunRadius: Float,
        moonDirX: Float,
        moonDirY: Float,
        eclipse: SolarEclipseV2
    ) {
        if (!eclipse.isEclipse || renderedSunRadius <= 0f) return

        var dx = moonDirX
        var dy = moonDirY
        var directionLength = sqrt(dx * dx + dy * dy)
        if (directionLength < 0.0001f) {
            dx = 1f
            dy = 0f
            directionLength = 1f
        }
        val ux = dx / directionLength
        val uy = dy / directionLength

        val sunAngularRadius = eclipse.sunAngularRadiusDeg.toFloat()
        if (sunAngularRadius <= 0f) return
        val angularToPixel = renderedSunRadius / sunAngularRadius
        val centreOffset = eclipse.angularSeparationDeg.toFloat() * angularToPixel
        val physicalMoonRadius = eclipse.moonAngularRadiusDeg.toFloat() * angularToPixel

        canvas.drawCircle(
            sunX + ux * centreOffset,
            sunY + uy * centreOffset,
            physicalMoonRadius,
            solarOccultationPaint
        )
    }

    private fun drawSunTwilightGlow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        alpha: Float
    ) {
        if (alpha <= 0f || radius <= 0f) return
        val safeAlpha = alpha.coerceIn(0f, 1f)
        sunTwilightPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                Color.argb((135f * safeAlpha).toInt(), 255, 183, 82),
                Color.argb((72f * safeAlpha).toInt(), 255, 220, 148),
                Color.argb(0, 255, 220, 148)
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, sunTwilightPaint)
        sunTwilightPaint.shader = null
    }

    private fun drawCelestialPng(
        canvas: Canvas,
        bitmap: Bitmap,
        cx: Float,
        cy: Float,
        radius: Float,
        active: Boolean,
        opacity: Float = 1f
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
        val baseAlpha = if (active) 255 else 215
        bitmapPaint.alpha = (baseAlpha * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
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
     * céleste, projetée dans le référentiel du cadran 360°.
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
     * même référentiel 360° que le disque lunaire.
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

    companion object {
        private const val CELESTIAL_PERMISSION_PREFS = "celestial_permission_recovery"
        private const val KEY_PERMISSION_ATTEMPTED = "location_request_attempted"
        private const val CELESTIAL_LOCATION_PERMISSION_REQUEST = 8_204
    }
}
