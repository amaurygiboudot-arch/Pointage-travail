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
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.v2.CelestialTrackerV2
import com.amaury.pointage.v2.CelestialWeatherContextV2
import com.amaury.pointage.v2.engine.CelestialHeadingPolicyV2
import com.amaury.pointage.v2.engine.CelestialLocationQualityV2
import com.amaury.pointage.v2.engine.CelestialPanoramaGeometryV2
import com.amaury.pointage.v2.engine.CelestialRenderQualityProviderV2
import com.amaury.pointage.v2.engine.CelestialRenderQualityV2
import com.amaury.pointage.v2.engine.CelestialRenderStateV2
import com.amaury.pointage.v2.engine.LocalStarPositionV2
import com.amaury.pointage.v2.engine.StarSkyProjectionV2
import com.amaury.pointage.v2.ui.ConstellationPathV2
import com.amaury.pointage.v2.ui.StarSkyCatalogLoaderV2
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val cloudTextures = CelestialCloudTextureRendererV2(onInvalidated)
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
    private val panoramaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    fun update(state: CelestialTrackerV2.State) {
        val snapshot = state.snapshot
        if (snapshot == null || state.locationQuality != CelestialLocationQualityV2.VALID) {
            cloudTextures.clear()
            return
        }

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
                replacePanoramaCache(null)
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

        val current = state
        if (current == null || current.locationQuality != CelestialLocationQualityV2.VALID) {
            cloudTextures.clear()
            return
        }

        // Les coefficients de visibilité proviennent exclusivement de
        // CelestialRenderStateV2 : le renderer n'invente plus sa propre météo.
        val starOpacity = renderState.starsVisibility.coerceIn(0.0, 1.0)
        val quality = CelestialRenderQualityProviderV2.current(appContext)
        val sky = localSky

        // La texture module le fond, avant les astres : ne pas atténuer une
        // seconde fois les étoiles déjà qualifiées par le propriétaire canonique.
        cloudTextures.draw(canvas, width, height, renderState, quality)

        val centerAzimuthDeg = CelestialHeadingPolicyV2.renderingHeadingDeg(
            headingDeg = current.deviceAzimuthDeg.toDouble(),
            quality = current.headingQuality
        )

        if (sky != null) {
            ensurePanoramaCache(
                sky = sky,
                viewportWidth = width,
                viewportHeight = height,
                quality = quality
            )

            val cache = panoramaCache
            if (cache != null &&
                cache.key == panoramaCacheKey(sky.key, width, height, quality) &&
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
        viewportHeight: Float,
        quality: CelestialRenderQualityV2
    ) {
        val cacheKey = panoramaCacheKey(sky.key, viewportWidth, viewportHeight, quality)
        if (panoramaCache?.key == cacheKey || panoramaRequestedKey == cacheKey) return

        panoramaRequestedKey = cacheKey
        val requestGeneration = generation.get()
        val viewportW = viewportWidth.toInt().coerceAtLeast(1)
        val viewportH = viewportHeight.toInt().coerceAtLeast(1)
        val scale = minOf(
            1f,
            quality.maxPanoramaWidthPx.toFloat() / viewportW.toFloat(),
            quality.maxPanoramaHeightPx.toFloat() / viewportH.toFloat()
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
                val coordinate = CelestialPanoramaGeometryV2.normalized(star.position)
                    ?: continue
                val x = (coordinate.x01 * renderW).toFloat()
                val y = (coordinate.y01 * renderH).toFloat()
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
                // Le catalogue BSC5P reste complet pour la géométrie des
                // constellations, mais le fond n'affiche que les étoiles
                // suffisamment brillantes pour éviter un ciel artificiellement
                // saturé sur un écran de téléphone.
                if (star.magnitude > HOME_MAX_VISUAL_MAGNITUDE) continue
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
                replacePanoramaCache(
                    PanoramaCache(
                        key = cacheKey,
                        renderWidth = renderW,
                        renderHeight = renderH,
                        bitmap = bitmap
                    )
                )
                panoramaRequestedKey = null
                onInvalidated()
            } else {
                bitmap.recycle()
                if (panoramaRequestedKey == cacheKey) panoramaRequestedKey = null
            }
        }
    }

    /**
     * Remplace atomiquement le bitmap visible puis recycle l'ancien avec un délai
     * sur le thread UI. Cela évite à la fois l'accumulation de bitmaps 360° et
     * le risque de recycler une texture pendant qu'un frame Canvas la dessine.
     */
    private fun replacePanoramaCache(next: PanoramaCache?) {
        val previous = panoramaCache
        panoramaCache = next
        if (previous != null && previous.bitmap !== next?.bitmap) {
            val oldBitmap = previous.bitmap
            mainHandler.postDelayed(
                {
                    if (panoramaCache?.bitmap !== oldBitmap && !oldBitmap.isRecycled) {
                        oldBitmap.recycle()
                    }
                },
                CACHE_RECYCLE_DELAY_MS
            )
        }
    }

    private fun drawWrappedPanorama(
        canvas: Canvas,
        cache: PanoramaCache,
        viewportWidth: Float,
        viewportHeight: Float,
        centerAzimuthDeg: Double
    ) {
        val baseLeft = (
            CelestialPanoramaGeometryV2.baseLeftFraction(centerAzimuthDeg) *
                viewportWidth
            ).toFloat()

        fun drawAt(left: Float) {
            val dst = RectF(
                left,
                0f,
                left + viewportWidth,
                viewportHeight
            )
            canvas.drawBitmap(cache.bitmap, null, dst, panoramaPaint)
        }

        // Une ou deux copies suffisent : baseLeft reste toujours dans
        // [-largeur/2 ; +largeur/2]. Éviter une troisième texture réduit
        // l'overdraw GPU lors des mouvements du téléphone.
        drawAt(baseLeft)
        when {
            baseLeft > 0f -> drawAt(baseLeft - viewportWidth)
            baseLeft < 0f -> drawAt(baseLeft + viewportWidth)
        }
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
        viewportHeight: Float,
        quality: CelestialRenderQualityV2
    ): String = buildString {
        append(skyKey)
        append(':')
        append(viewportWidth.toInt().coerceAtLeast(1))
        append('x')
        append(viewportHeight.toInt().coerceAtLeast(1))
        append(':')
        append(quality.name)
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

    fun clear() {
        cloudTextures.clear()
        generation.incrementAndGet()
        requestedKey = null
        localSky = null
        replacePanoramaCache(null)
        panoramaRequestedKey = null
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private const val CONSTELLATION_BASE_ALPHA = 46
        private const val HOME_MAX_VISUAL_MAGNITUDE = 4.2
        private const val CACHE_RECYCLE_DELAY_MS = 1_000L
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-HomeSky").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
