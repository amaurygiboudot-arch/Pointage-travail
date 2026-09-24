package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import com.amaury.pointage.v2.engine.CelestialHeadingPolicyV2
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.LocalStarPositionV2
import com.amaury.pointage.v2.engine.StarSkyProjectionV2
import com.amaury.pointage.v2.ui.ConstellationPathV2
import com.amaury.pointage.v2.ui.StarSkyCatalogLoaderV2
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Ciel réel plein écran réservé à l'onglet Accueil.
 *
 * Les étoiles proviennent exclusivement du catalogue embarqué BSC5P et les
 * traits de constellations de ConstellationLines. Aucun point décoratif aléatoire
 * n'est ajouté. Les constellations restent volontairement secondaires :
 * étoiles d'abord, traits fins ensuite, aucun libellé permanent.
 */
class CelestialHomeSkyBackgroundRendererV2(
    context: Context,
    private val onInvalidated: () -> Unit
) {
    private data class LocalStar(
        val hr: Int,
        val magnitude: Double,
        val position: LocalStarPositionV2
    )

    private data class LocalSky(
        val key: String,
        val stars: List<LocalStar>,
        val paths: List<ConstellationPathV2>
    )

    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val generation = AtomicLong(0)
    @Volatile private var localSky: LocalSky? = null
    private var requestedKey: String? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(0.45f * density, 0.8f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(state: CelestialTrackerV2.State) {
        val snapshot = state.snapshot ?: return
        if (state.locationQuality != CelestialLocationQualityV2.VALID) return

        CelestialWeatherContextV2.refreshIfNeeded(snapshot) {
            onInvalidated()
        }

        val bucket = snapshot.atMs / LOCAL_SKY_REFRESH_MS
        val key = "%.4f:%.4f:%d".format(snapshot.latitudeDeg, snapshot.longitudeDeg, bucket)
        if (key == requestedKey) return
        requestedKey = key
        val requestGeneration = generation.incrementAndGet()

        executor.execute {
            val catalog = runCatching { StarSkyCatalogLoaderV2.load(appContext) }.getOrNull()
                ?: return@execute
            val prepared = catalog.stars.mapNotNull { (hr, star) ->
                runCatching {
                    val position = StarSkyProjectionV2.horizontal(
                        star = star,
                        latitudeDeg = snapshot.latitudeDeg,
                        longitudeDeg = snapshot.longitudeDeg,
                        timeMs = snapshot.atMs
                    )
                    if (!position.aboveApparentHorizon) return@runCatching null
                    LocalStar(hr = hr, magnitude = star.visualMagnitude, position = position)
                }.getOrNull()
            }
            val result = LocalSky(
                key = key,
                stars = prepared,
                paths = catalog.constellationPaths
            )
            if (requestGeneration == generation.get()) {
                localSky = result
                onInvalidated()
            }
        }
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        state: CelestialTrackerV2.State?
    ) {
        if (width <= 1f || height <= 1f) return
        val snapshot = state?.snapshot
        val nightOpacity = snapshot?.let {
            StarSkyProjectionV2.nightSkyOpacity(it.sun.altitudeDeg)
        } ?: 1.0

        drawAtmosphericBase(canvas, width, height, nightOpacity)

        val current = state ?: return
        if (current.locationQuality != CelestialLocationQualityV2.VALID) return

        val weather = CelestialWeatherContextV2.currentStateFor(snapshot)
        val cloudTransmission = weather?.cloudTransmission ?: 1.0

        // Jour réel : aucune étoile ni constellation artificiellement visible.
        // La couverture nuageuse réelle atténue ensuite le ciel nocturne.
        val starOpacity = nightOpacity * cloudTransmission
        val constellationOpacity = 0.18 * nightOpacity * cloudTransmission
        val sky = localSky

        val centerAzimuthDeg = if (
            CelestialHeadingPolicyV2.isUsable(current.headingQuality)
        ) {
            current.deviceAzimuthDeg.toDouble()
        } else {
            0.0
        }

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val scaleX = width * 0.5f
        val scaleY = height * 0.5f

        if (sky != null) {
            val projected = HashMap<Int, PointF>(sky.stars.size)
            val visibleStars = ArrayList<Pair<LocalStar, PointF>>(sky.stars.size)
            for (star in sky.stars) {
            val p = StarSkyProjectionV2.projectToPanorama(
                position = star.position,
                centerAzimuthDeg = centerAzimuthDeg
            ) ?: continue
            val point = PointF(
                centerX + (p.x * scaleX).toFloat(),
                centerY + (p.y * scaleY).toFloat()
            )
            if (point.x < -24f || point.x > width + 24f ||
                point.y < -24f || point.y > height + 24f
            ) continue
            projected[star.hr] = point
            visibleStars += star to point
        }

            linePaint.alpha = (255.0 * constellationOpacity).toInt().coerceIn(0, 255)
            for (path in sky.paths) {
            var previous: PointF? = null
            for (hr in path.hrNumbers) {
                val point = projected[hr]
                if (point == null) {
                    previous = null
                    continue
                }
                previous?.let {
                    if (kotlin.math.abs(it.x - point.x) <= width * 0.50f) {
                        canvas.drawLine(it.x, it.y, point.x, point.y, linePaint)
                    }
                }
                previous = point
            }
        }

            for ((star, point) in visibleStars) {
                val brightness = ((6.6 - star.magnitude) / 7.5).coerceIn(0.08, 1.0)
                starPaint.alpha = (255.0 * starOpacity * (0.34 + 0.66 * brightness))
                    .toInt().coerceIn(0, 255)
                val radius = (0.65 + brightness * 2.25).toFloat() * density
                canvas.drawCircle(point.x, point.y, radius, starPaint)
            }
        }

        weather?.let {
            drawCloudLayer(
                canvas = canvas,
                width = width,
                height = height,
                cloudCover = it.cloudCover,
                nightOpacity = nightOpacity,
                weatherCode = it.weatherCode,
                precipitationMm = it.precipitationMm
            )
        }
    }

    /**
     * Représentation atmosphérique de la couverture réelle.
     *
     * Le fournisseur donne un pourcentage de couverture, pas la géométrie exacte
     * des nuages au-dessus du téléphone. Les formes sont donc une visualisation
     * stable de cette couverture et ne sont jamais présentées comme une image
     * satellite ou la position exacte des nuages.
     */
    private fun drawCloudLayer(
        canvas: Canvas,
        width: Float,
        height: Float,
        cloudCover: Double,
        nightOpacity: Double,
        weatherCode: Int?,
        precipitationMm: Double?
    ) {
        val cover = cloudCover.coerceIn(0.0, 1.0).toFloat()
        if (cover < 0.03f) return

        val now = System.currentTimeMillis()
        val drift = ((now % 3_600_000L).toFloat() / 3_600_000f) * width
        val code = weatherCode ?: -1
        val rainy = (precipitationMm ?: 0.0) > 0.05 || code in 51..67 || code in 80..82
        val foggy = code in 45..48
        val snowy = code in 71..77 || code in 85..86
        val stormy = code in 95..99

        val dayColor = when {
            stormy -> Color.rgb(118, 126, 138)
            rainy -> Color.rgb(154, 164, 174)
            snowy -> Color.rgb(226, 232, 236)
            foggy -> Color.rgb(210, 216, 220)
            else -> Color.rgb(240, 244, 247)
        }
        val nightColor = when {
            stormy -> Color.rgb(48, 54, 66)
            rainy -> Color.rgb(62, 70, 82)
            else -> Color.rgb(92, 100, 114)
        }
        cloudPaint.color = blend(
            dayColor,
            nightColor,
            nightOpacity.toFloat().coerceIn(0f, 1f)
        )

        // Nuages d'Accueil : bandes irrégulières, larges et douces, concentrées
        // dans le ciel supérieur. On évite volontairement les "boules" régulières
        // de type cartoon et on garde le bas de l'écran dégagé autour de l'horloge.
        val clusters = (2 + cover * 9f).toInt().coerceIn(2, 11)
        val baseAlpha = (
            18f + cover * when {
                stormy -> 74f
                rainy -> 62f
                else -> 50f
            }
        ).toInt().coerceIn(16, 104)

        for (index in 0 until clusters) {
            val seed = index * 1.931f + 0.37f
            val wave = ((sin(seed.toDouble()) + 1.0) * 0.5).toFloat()
            val baseX = (
                (index.toFloat() / clusters) * width +
                    drift * (0.13f + (index % 5) * 0.035f)
                ) % (width * 1.32f)
            val x = baseX - width * 0.16f
            val y = height * (0.075f + wave * 0.27f + (index % 3) * 0.018f)
            val clusterWidth = width * (
                0.18f + cover * 0.09f + (index % 4) * 0.025f
                )
            val clusterHeight = clusterWidth * (
                0.16f + (index % 3) * 0.025f
                )

            cloudPaint.alpha = (
                baseAlpha * (0.72f + 0.08f * (index % 4))
                ).toInt().coerceIn(0, 118)
            drawCloudWisp(canvas, x, y, clusterWidth, clusterHeight, seed)
        }

        // Brouillard et ciel totalement couvert agissent comme un voile
        // atmosphérique, pas comme une rangée de nuages dessinés.
        if (foggy) {
            cloudPaint.alpha = (24f + cover * 40f).toInt().coerceIn(0, 70)
            canvas.drawRect(0f, 0f, width, height, cloudPaint)
        } else if (cover > 0.82f) {
            cloudPaint.alpha = (
                (cover - 0.82f) / 0.18f * if (stormy || rainy) 68f else 52f
                ).toInt().coerceIn(0, 72)
            canvas.drawRect(0f, 0f, width, height, cloudPaint)
        }
        cloudPaint.alpha = 255
    }

    private fun drawCloudWisp(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        seed: Float
    ) {
        val alpha = cloudPaint.alpha
        fun oval(
            centerX: Float,
            centerY: Float,
            w: Float,
            h: Float,
            alphaFactor: Float
        ) {
            cloudPaint.alpha = (alpha * alphaFactor).toInt().coerceIn(0, 128)
            canvas.drawOval(
                RectF(
                    centerX - w * 0.5f,
                    centerY - h * 0.5f,
                    centerX + w * 0.5f,
                    centerY + h * 0.5f
                ),
                cloudPaint
            )
        }

        // Base très aplatie + volumes asymétriques : le contour n'est jamais
        // une répétition de trois cercles identiques.
        oval(cx, cy, width, height * 0.78f, 0.42f)
        oval(
            cx - width * 0.27f,
            cy - height * (0.10f + 0.05f * cos(seed.toDouble()).toFloat()),
            width * 0.48f,
            height * 0.82f,
            0.56f
        )
        oval(
            cx - width * 0.06f,
            cy - height * 0.24f,
            width * 0.45f,
            height * 1.02f,
            0.68f
        )
        oval(
            cx + width * 0.20f,
            cy - height * (0.17f + 0.05f * sin(seed.toDouble()).toFloat()),
            width * 0.53f,
            height * 0.92f,
            0.62f
        )
        oval(
            cx + width * 0.39f,
            cy + height * 0.02f,
            width * 0.36f,
            height * 0.62f,
            0.38f
        )
        oval(
            cx - width * 0.38f,
            cy + height * 0.08f,
            width * 0.32f,
            height * 0.52f,
            0.30f
        )
        cloudPaint.alpha = alpha
    }

    private fun drawAtmosphericBase(
        canvas: Canvas,
        width: Float,
        height: Float,
        nightOpacity: Double
    ) {
        val night = nightOpacity.toFloat().coerceIn(0f, 1f)
        val top = blend(Color.rgb(54, 139, 224), Color.rgb(1, 5, 14), night)
        val bottom = blend(Color.rgb(176, 222, 248), Color.rgb(0, 1, 7), night)
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, height,
            top, bottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        backgroundPaint.shader = null
    }

    private fun blend(day: Int, night: Int, amount: Float): Int {
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
        val inverse = 1f - amount
        val r = (channel(day, 16) * inverse + channel(night, 16) * amount).toInt()
        val g = (channel(day, 8) * inverse + channel(night, 8) * amount).toInt()
        val b = (channel(day, 0) * inverse + channel(night, 0) * amount).toInt()
        return Color.rgb(r, g, b)
    }

    fun clear() {
        generation.incrementAndGet()
        requestedKey = null
        localSky = null
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-HomeSky").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
