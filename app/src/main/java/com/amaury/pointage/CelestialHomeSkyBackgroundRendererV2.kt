package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
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
import com.amaury.pointage.v2.engine.CelestialRenderStateV2
import com.amaury.pointage.v2.engine.CelestialWeatherTypeV2
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

    private data class PanoramaCache(
        val key: String,
        val renderWidth: Int,
        val renderHeight: Int,
        val bitmap: Bitmap
    )

    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val generation = AtomicLong(0)
    @Volatile private var localSky: LocalSky? = null
    @Volatile private var panoramaCache: PanoramaCache? = null
    @Volatile private var panoramaRequestedKey: String? = null
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
    private val panoramaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
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
                panoramaCache = null
                panoramaRequestedKey = null
                onInvalidated()
            }
        }
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        state: CelestialTrackerV2.State?,
        renderState: CelestialRenderStateV2
    ) {
        if (width <= 1f || height <= 1f) return

        drawAtmosphericBase(canvas, width, height, renderState)

        val current = state ?: return
        if (current.locationQuality != CelestialLocationQualityV2.VALID) return

        // Les coefficients de visibilité proviennent exclusivement de
        // CelestialRenderStateV2 : le renderer n'invente plus sa propre météo.
        val starOpacity = renderState.starsVisibility.coerceIn(0.0, 1.0)
        val sky = localSky

        val centerAzimuthDeg = if (
            CelestialHeadingPolicyV2.isUsable(current.headingQuality)
        ) {
            current.deviceAzimuthDeg.toDouble()
        } else {
            0.0
        }

        if (sky != null) {
            ensurePanoramaCache(
                sky = sky,
                viewportWidth = width,
                viewportHeight = height
            )

            val cache = panoramaCache
            if (cache != null &&
                cache.key == panoramaCacheKey(sky.key, width, height) &&
                starOpacity > 0.005
            ) {
                panoramaPaint.alpha = (255.0 * starOpacity).toInt().coerceIn(0, 255)
                drawWrappedPanorama(
                    canvas = canvas,
                    cache = cache,
                    viewportWidth = width,
                    viewportHeight = height,
                    centerAzimuthDeg = centerAzimuthDeg
                )
                panoramaPaint.alpha = 255
            }
        }

        renderState.cloudCoverage?.let { cover ->
            drawCloudLayer(
                canvas = canvas,
                width = width,
                height = height,
                cloudCover = cover,
                nightOpacity = renderState.nightLevel,
                weatherType = renderState.weatherType
            )
        }
    }

    /**
     * Construit hors thread UI une texture 360° préprojetée.
     *
     * Le déplacement du téléphone ne reprojette plus ~4 500 étoiles à chaque
     * événement capteur : le bitmap est simplement décalé horizontalement.
     * Le cache n'est reconstruit que lorsque le ciel local (30 s) ou la taille
     * du viewport change.
     */
    private fun ensurePanoramaCache(
        sky: LocalSky,
        viewportWidth: Float,
        viewportHeight: Float
    ) {
        val cacheKey = panoramaCacheKey(sky.key, viewportWidth, viewportHeight)
        if (panoramaCache?.key == cacheKey || panoramaRequestedKey == cacheKey) return

        panoramaRequestedKey = cacheKey
        val requestGeneration = generation.get()
        val viewportW = viewportWidth.toInt().coerceAtLeast(1)
        val viewportH = viewportHeight.toInt().coerceAtLeast(1)
        val scale = minOf(
            1f,
            MAX_CACHE_WIDTH_PX.toFloat() / viewportW.toFloat(),
            MAX_CACHE_HEIGHT_PX.toFloat() / viewportH.toFloat()
        )
        val renderW = (viewportW * scale).toInt().coerceAtLeast(1)
        val renderH = (viewportH * scale).toInt().coerceAtLeast(1)
        val renderDensityScale = scale.coerceAtLeast(0.01f)

        executor.execute {
            val bitmap = runCatching {
                Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: run {
                if (panoramaRequestedKey == cacheKey) panoramaRequestedKey = null
                return@execute
            }
            val bitmapCanvas = Canvas(bitmap)
            val points = HashMap<Int, PointF>(sky.stars.size)

            // Le bitmap de référence est Nord=0° au bord gauche, 360° au bord
            // droit. Le centrage sur le cap se fait ensuite par translation/wrap.
            for (star in sky.stars) {
                val altitude = star.position.apparentAltitudeDeg
                if (!altitude.isFinite() || altitude !in 0.0..90.0) continue
                val azimuth = normalizeDegrees(star.position.azimuthDeg)
                val x = (azimuth / 360.0 * renderW).toFloat()
                val y = ((1.0 - altitude / 90.0) * renderH).toFloat()
                points[star.hr] = PointF(x, y)
            }

            val cacheLinePaint = Paint(linePaint).apply {
                strokeWidth = max(0.45f * density * renderDensityScale, 0.55f)
                alpha = CONSTELLATION_BASE_ALPHA
            }
            for (path in sky.paths) {
                var previous: PointF? = null
                for (hr in path.hrNumbers) {
                    val point = points[hr]
                    if (point == null) {
                        previous = null
                        continue
                    }
                    previous?.let {
                        drawWrappedLine(
                            canvas = bitmapCanvas,
                            x1 = it.x,
                            y1 = it.y,
                            x2 = point.x,
                            y2 = point.y,
                            width = renderW.toFloat(),
                            paint = cacheLinePaint
                        )
                    }
                    previous = point
                }
            }

            val cacheStarPaint = Paint(starPaint)
            for (star in sky.stars) {
                val point = points[star.hr] ?: continue
                val brightness = ((6.6 - star.magnitude) / 7.5).coerceIn(0.08, 1.0)
                cacheStarPaint.alpha = (
                    255.0 * (0.34 + 0.66 * brightness)
                    ).toInt().coerceIn(0, 255)
                val radius = (
                    (0.65 + brightness * 2.25).toFloat() *
                        density * renderDensityScale
                    ).coerceAtLeast(0.45f)

                // Dupliquer aux deux bords afin qu'une étoile proche de 0° reste
                // entière lorsque le panorama est recollé à 360°.
                bitmapCanvas.drawCircle(point.x, point.y, radius, cacheStarPaint)
                bitmapCanvas.drawCircle(
                    point.x - renderW.toFloat(),
                    point.y,
                    radius,
                    cacheStarPaint
                )
                bitmapCanvas.drawCircle(
                    point.x + renderW.toFloat(),
                    point.y,
                    radius,
                    cacheStarPaint
                )
            }

            if (requestGeneration == generation.get() &&
                localSky?.key == sky.key &&
                panoramaRequestedKey == cacheKey
            ) {
                panoramaCache = PanoramaCache(
                    key = cacheKey,
                    renderWidth = renderW,
                    renderHeight = renderH,
                    bitmap = bitmap
                )
                panoramaRequestedKey = null
                onInvalidated()
            } else {
                bitmap.recycle()
                if (panoramaRequestedKey == cacheKey) panoramaRequestedKey = null
            }
        }
    }

    private fun drawWrappedPanorama(
        canvas: Canvas,
        cache: PanoramaCache,
        viewportWidth: Float,
        viewportHeight: Float,
        centerAzimuthDeg: Double
    ) {
        val normalizedHeading = normalizeDegrees(centerAzimuthDeg)
        val baseLeft = viewportWidth * 0.5f -
            (normalizedHeading / 360.0 * viewportWidth).toFloat()

        fun drawAt(left: Float) {
            val dst = RectF(
                left,
                0f,
                left + viewportWidth,
                viewportHeight
            )
            canvas.drawBitmap(cache.bitmap, null, dst, panoramaPaint)
        }

        // Trois copies suffisent toujours à couvrir le viewport après wrap.
        drawAt(baseLeft - viewportWidth)
        drawAt(baseLeft)
        drawAt(baseLeft + viewportWidth)
    }

    private fun drawWrappedLine(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        width: Float,
        paint: Paint
    ) {
        var adjustedX2 = x2
        val delta = adjustedX2 - x1
        if (delta > width * 0.5f) adjustedX2 -= width
        if (delta < -width * 0.5f) adjustedX2 += width

        canvas.drawLine(x1, y1, adjustedX2, y2, paint)
        canvas.drawLine(x1 - width, y1, adjustedX2 - width, y2, paint)
        canvas.drawLine(x1 + width, y1, adjustedX2 + width, y2, paint)
    }

    private fun panoramaCacheKey(
        skyKey: String,
        viewportWidth: Float,
        viewportHeight: Float
    ): String = buildString {
        append(skyKey)
        append(':')
        append(viewportWidth.toInt().coerceAtLeast(1))
        append('x')
        append(viewportHeight.toInt().coerceAtLeast(1))
    }

    private fun normalizeDegrees(value: Double): Double =
        ((value % 360.0) + 360.0) % 360.0

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
        weatherType: CelestialWeatherTypeV2
    ) {
        val cover = cloudCover.coerceIn(0.0, 1.0).toFloat()
        if (cover < 0.03f) return

        val now = System.currentTimeMillis()
        val drift = ((now % 3_600_000L).toFloat() / 3_600_000f) * width
        val rainy = weatherType == CelestialWeatherTypeV2.DRIZZLE ||
            weatherType == CelestialWeatherTypeV2.RAIN
        val foggy = weatherType == CelestialWeatherTypeV2.FOG
        val snowy = weatherType == CelestialWeatherTypeV2.SNOW
        val stormy = weatherType == CelestialWeatherTypeV2.THUNDERSTORM

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
        renderState: CelestialRenderStateV2
    ) {
        val day = renderState.solarLightLevel.toFloat().coerceIn(0f, 1f)
        val twilight = renderState.twilightLevel.toFloat().coerceIn(0f, 1f)
        val night = renderState.nightLevel.toFloat().coerceIn(0f, 1f)

        val dayTop = Color.rgb(54, 139, 224)
        val dayBottom = Color.rgb(176, 222, 248)
        val twilightTop = Color.rgb(52, 65, 116)
        val twilightBottom = Color.rgb(235, 137, 92)
        val nightTop = Color.rgb(1, 5, 14)
        val nightBottom = Color.rgb(0, 1, 7)

        fun weighted(dayColor: Int, twilightColor: Int, nightColor: Int): Int {
            val total = (day + twilight + night).coerceAtLeast(0.0001f)
            val d = day / total
            val t = twilight / total
            val n = night / total
            fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
            return Color.rgb(
                (channel(dayColor, 16) * d + channel(twilightColor, 16) * t +
                    channel(nightColor, 16) * n).toInt().coerceIn(0, 255),
                (channel(dayColor, 8) * d + channel(twilightColor, 8) * t +
                    channel(nightColor, 8) * n).toInt().coerceIn(0, 255),
                (channel(dayColor, 0) * d + channel(twilightColor, 0) * t +
                    channel(nightColor, 0) * n).toInt().coerceIn(0, 255)
            )
        }

        val top = weighted(dayTop, twilightTop, nightTop)
        val bottom = weighted(dayBottom, twilightBottom, nightBottom)
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
        panoramaCache?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        panoramaCache = null
        panoramaRequestedKey = null
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private const val MAX_CACHE_WIDTH_PX = 1080
        private const val MAX_CACHE_HEIGHT_PX = 1920
        private const val CONSTELLATION_BASE_ALPHA = 46
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-HomeSky").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
