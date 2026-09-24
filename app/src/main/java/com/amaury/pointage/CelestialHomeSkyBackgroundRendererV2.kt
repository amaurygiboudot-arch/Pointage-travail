package com.amaury.pointage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
    private val mainHandler = Handler(Looper.getMainLooper())
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

        val current = state ?: return
        if (current.locationQuality != CelestialLocationQualityV2.VALID) return

        // Les coefficients de visibilité proviennent exclusivement de
        // CelestialRenderStateV2 : le renderer n'invente plus sa propre météo.
        val starOpacity = renderState.starsVisibility.coerceIn(0.0, 1.0)
        val quality = CelestialRenderQualityProviderV2.current(appContext)
        val sky = localSky

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

        renderState.cloudCoverage?.let { cover ->
            drawCloudLayer(
                canvas = canvas,
                width = width,
                height = height,
                cloudCover = cover,
                nightOpacity = renderState.nightLevel,
                weatherType = renderState.weatherType,
                quality = quality
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
        weatherType: CelestialWeatherTypeV2,
        quality: CelestialRenderQualityV2
    ) {
        val cover = cloudCover.coerceIn(0.0, 1.0).toFloat()
        if (cover < 0.03f) return

        val night = nightOpacity.toFloat().coerceIn(0f, 1f)
        val rainy = weatherType == CelestialWeatherTypeV2.DRIZZLE ||
            weatherType == CelestialWeatherTypeV2.RAIN
        val foggy = weatherType == CelestialWeatherTypeV2.FOG
        val stormy = weatherType == CelestialWeatherTypeV2.THUNDERSTORM
        val snowy = weatherType == CelestialWeatherTypeV2.SNOW

        val dayTop = when {
            stormy -> Color.rgb(124, 132, 146)
            rainy -> Color.rgb(174, 182, 192)
            snowy -> Color.rgb(242, 245, 247)
            else -> Color.rgb(238, 242, 246)
        }
        val dayBottom = when {
            stormy -> Color.rgb(82, 90, 104)
            rainy -> Color.rgb(132, 143, 156)
            snowy -> Color.rgb(210, 218, 225)
            else -> Color.rgb(190, 201, 212)
        }
        val nightTop = when {
            stormy -> Color.rgb(66, 73, 87)
            rainy -> Color.rgb(78, 87, 101)
            else -> Color.rgb(102, 112, 128)
        }
        val nightBottom = when {
            stormy -> Color.rgb(37, 43, 54)
            rainy -> Color.rgb(48, 56, 68)
            else -> Color.rgb(69, 78, 92)
        }

        val topColor = blend(dayTop, nightTop, night)
        val bottomColor = blend(dayBottom, nightBottom, night)

        if (foggy) {
            drawFogVeil(canvas, width, height, topColor, bottomColor, cover)
            return
        }

        if (!rainy && !stormy && cover < 0.50f) {
            drawCirrusLayer(
                canvas = canvas,
                width = width,
                height = height,
                color = topColor,
                cover = cover,
                night = night
            )
        }

        // Open-Meteo donne une couverture, pas la géométrie exacte des nuages.
        // On préfère donc de larges nappes atmosphériques semi-transparentes
        // à de faux nuages isolés qui ressemblent à des autocollants.
        val layerCount = when (quality) {
            CelestialRenderQualityV2.REDUCED -> 2
            CelestialRenderQualityV2.BALANCED -> 3
            CelestialRenderQualityV2.HIGH -> 4
        }
        val visibleLayers = (1 + cover * (layerCount - 1)).toInt().coerceIn(1, layerCount)
        val now = System.currentTimeMillis()

        repeat(visibleLayers) { index ->
            val seed = 31.0 + index * 19.37 + weatherType.ordinal * 2.71
            val phase = (
                (now % CLOUD_DRIFT_PERIOD_MS).toFloat() /
                    CLOUD_DRIFT_PERIOD_MS.toFloat()
                )
            val xShift = width * (
                phase * (0.035f + index * 0.012f) +
                    (cloudNoise(seed + 3.1) - 0.5f) * 0.08f
                )
            val centerY = height * (
                0.10f +
                    index * 0.105f +
                    cloudNoise(seed + 7.7) * 0.055f
                )
            val sheetHeight = height * (
                0.075f +
                    cover * 0.045f +
                    cloudNoise(seed + 9.2) * 0.025f
                )
            val alpha = (
                when {
                    stormy -> 0.26f + cover * 0.13f
                    rainy -> 0.20f + cover * 0.12f
                    else -> 0.11f + cover * 0.11f
                } * (0.92f - index * 0.08f)
                ).coerceIn(0.07f, 0.40f)

            drawAtmosphericCloudSheet(
                canvas = canvas,
                width = width,
                centerY = centerY,
                sheetHeight = sheetHeight,
                xShift = xShift,
                seed = seed,
                topColor = topColor,
                bottomColor = bottomColor,
                alpha = alpha
            )
        }

        if (cover > 0.78f) {
            val veil = ((cover - 0.78f) / 0.22f).coerceIn(0f, 1f)
            cloudPaint.shader = LinearGradient(
                0f, 0f, 0f, height * 0.68f,
                Color.argb(
                    (veil * if (stormy || rainy) 42f else 30f).toInt(),
                    Color.red(topColor),
                    Color.green(topColor),
                    Color.blue(topColor)
                ),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height * 0.68f, cloudPaint)
            cloudPaint.shader = null
        }
    }

    private fun drawAtmosphericCloudSheet(
        canvas: Canvas,
        width: Float,
        centerY: Float,
        sheetHeight: Float,
        xShift: Float,
        seed: Double,
        topColor: Int,
        bottomColor: Int,
        alpha: Float
    ) {
        val left = -width * 0.18f + xShift
        val right = width * 1.18f + xShift
        val span = right - left
        val segments = 8
        val top = ArrayList<PointF>(segments + 1)
        val bottom = ArrayList<PointF>(segments + 1)

        for (i in 0..segments) {
            val t = i.toFloat() / segments.toFloat()
            val x = left + span * t
            val wave = sin((t * Math.PI * 2.0 + seed).toDouble()).toFloat()
            val topNoise = cloudNoise(seed + i * 4.17) - 0.5f
            val bottomNoise = cloudNoise(seed + i * 6.83) - 0.5f
            top += PointF(
                x,
                centerY - sheetHeight * (0.46f + wave * 0.08f + topNoise * 0.16f)
            )
            bottom += PointF(
                x,
                centerY + sheetHeight * (0.38f + wave * 0.04f + bottomNoise * 0.12f)
            )
        }

        val path = Path()
        path.moveTo(top.first().x, top.first().y)
        for (i in 1 until top.size) {
            val p0 = top[i - 1]
            val p1 = top[i]
            val midX = (p0.x + p1.x) * 0.5f
            path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
        }
        for (i in bottom.indices.reversed()) {
            val p = bottom[i]
            if (i == bottom.lastIndex) {
                path.lineTo(p.x, p.y)
            } else {
                val prev = bottom[i + 1]
                val midX = (prev.x + p.x) * 0.5f
                path.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
            }
        }
        path.close()

        cloudPaint.shader = LinearGradient(
            0f,
            centerY - sheetHeight,
            0f,
            centerY + sheetHeight,
            Color.argb(
                (alpha * 255f).toInt().coerceIn(0, 122),
                Color.red(topColor),
                Color.green(topColor),
                Color.blue(topColor)
            ),
            Color.argb(
                (alpha * 165f).toInt().coerceIn(0, 98),
                Color.red(bottomColor),
                Color.green(bottomColor),
                Color.blue(bottomColor)
            ),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, cloudPaint)
        cloudPaint.shader = null
    }

    private fun drawCloudBank(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        seed: Double,
        topColor: Int,
        bottomColor: Int,
        alpha: Float,
        dense: Boolean
    ) {
        if (width <= 1f || height <= 1f) return

        val left = cx - width * 0.5f
        val right = cx + width * 0.5f
        val baseY = cy + height * 0.26f
        val topBase = cy - height * 0.28f
        val segments = 7
        val path = Path()
        path.moveTo(left, baseY)

        var previousX = left
        var previousY = baseY
        for (segment in 1..segments) {
            val t = segment.toFloat() / segments.toFloat()
            val endX = left + width * t
            val envelope = sin(Math.PI * t.toDouble()).toFloat()
            val crest = 0.26f + cloudNoise(seed + segment * 2.73) * 0.62f
            val endY = topBase - height * envelope * crest
            val dx = endX - previousX
            val c1x = previousX + dx * 0.38f
            val c2x = previousX + dx * 0.72f
            val c1y = previousY - height * (0.10f + cloudNoise(seed + segment * 5.11) * 0.18f)
            val c2y = endY - height * (0.02f + cloudNoise(seed + segment * 7.91) * 0.10f)
            path.cubicTo(c1x, c1y, c2x, c2y, endX, endY)
            previousX = endX
            previousY = endY
        }

        val lowerRight = baseY + height * (0.10f + cloudNoise(seed + 31.0) * 0.12f)
        val lowerLeft = baseY + height * (0.06f + cloudNoise(seed + 37.0) * 0.10f)
        path.cubicTo(
            right - width * 0.08f,
            lowerRight,
            cx + width * 0.20f,
            baseY + height * 0.22f,
            cx,
            baseY + height * 0.15f
        )
        path.cubicTo(
            cx - width * 0.24f,
            baseY + height * 0.20f,
            left + width * 0.08f,
            lowerLeft,
            left,
            baseY
        )
        path.close()

        // Ombre basse douce, surtout visible sous pluie/orage : elle donne du
        // volume sans contour noir ni effet "sticker".
        if (dense) {
            cloudPaint.shader = LinearGradient(
                0f, cy - height,
                0f, cy + height,
                Color.TRANSPARENT,
                Color.argb(
                    (alpha * 84f).toInt().coerceIn(0, 92),
                    Color.red(bottomColor),
                    Color.green(bottomColor),
                    Color.blue(bottomColor)
                ),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(0f, height * 0.08f)
            canvas.drawPath(path, cloudPaint)
            canvas.restore()
            cloudPaint.shader = null
        }

        val topAlpha = (alpha * 255f).toInt().coerceIn(0, 190)
        val bottomAlpha = (alpha * 220f).toInt().coerceIn(0, 170)
        cloudPaint.shader = LinearGradient(
            0f, cy - height * 0.85f,
            0f, cy + height * 0.70f,
            Color.argb(topAlpha, Color.red(topColor), Color.green(topColor), Color.blue(topColor)),
            Color.argb(bottomAlpha, Color.red(bottomColor), Color.green(bottomColor), Color.blue(bottomColor)),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, cloudPaint)
        cloudPaint.shader = null

        // Éclat diffus uniquement sur la partie haute : jamais une bordure.
        val highlight = Path(path)
        cloudPaint.shader = LinearGradient(
            0f, cy - height,
            0f, cy + height * 0.15f,
            Color.argb(
                (alpha * 58f).toInt().coerceIn(0, 54),
                255, 255, 255
            ),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(highlight, cloudPaint)
        cloudPaint.shader = null
        cloudPaint.alpha = 255
    }

    private fun drawCirrusLayer(
        canvas: Canvas,
        width: Float,
        height: Float,
        color: Int,
        cover: Float,
        night: Float
    ) {
        val oldStyle = cloudPaint.style
        val oldStroke = cloudPaint.strokeWidth
        val oldAlpha = cloudPaint.alpha
        cloudPaint.style = Paint.Style.STROKE
        cloudPaint.strokeCap = Paint.Cap.ROUND
        cloudPaint.strokeWidth = max(1.2f * density, width * 0.0045f)
        cloudPaint.color = color
        cloudPaint.alpha = (
            18f + cover * 36f - night * 8f
            ).toInt().coerceIn(10, 52)

        val drift = (
            (System.currentTimeMillis() % (CLOUD_DRIFT_PERIOD_MS * 2L)).toFloat() /
                (CLOUD_DRIFT_PERIOD_MS * 2L).toFloat()
            ) * width * 0.10f

        repeat(3) { index ->
            val seed = 71.0 + index * 13.7
            val y = height * (0.08f + index * 0.075f + cloudNoise(seed) * 0.035f)
            val x = -width * 0.15f + drift + index * width * 0.31f
            val w = width * (0.48f + cloudNoise(seed + 3.0) * 0.22f)
            val p = Path()
            p.moveTo(x, y)
            p.cubicTo(
                x + w * 0.22f,
                y - height * (0.010f + cloudNoise(seed + 5.0) * 0.012f),
                x + w * 0.62f,
                y + height * (0.012f + cloudNoise(seed + 8.0) * 0.014f),
                x + w,
                y - height * 0.006f
            )
            canvas.drawPath(p, cloudPaint)
        }

        cloudPaint.style = oldStyle
        cloudPaint.strokeWidth = oldStroke
        cloudPaint.alpha = oldAlpha
    }

    private fun drawFogVeil(
        canvas: Canvas,
        width: Float,
        height: Float,
        topColor: Int,
        bottomColor: Int,
        cover: Float
    ) {
        val alpha = (56f + cover * 84f).toInt().coerceIn(48, 138)
        cloudPaint.shader = LinearGradient(
            0f, 0f, 0f, height,
            Color.argb(
                (alpha * 0.55f).toInt(),
                Color.red(topColor),
                Color.green(topColor),
                Color.blue(topColor)
            ),
            Color.argb(
                alpha,
                Color.red(bottomColor),
                Color.green(bottomColor),
                Color.blue(bottomColor)
            ),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, cloudPaint)
        cloudPaint.shader = null
    }

    private fun cloudNoise(value: Double): Float {
        val raw = sin(value * 12.9898 + 78.233) * 43758.5453
        return (raw - kotlin.math.floor(raw)).toFloat().coerceIn(0f, 1f)
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
        replacePanoramaCache(null)
        panoramaRequestedKey = null
    }

    companion object {
        private const val LOCAL_SKY_REFRESH_MS = 30_000L
        private const val CONSTELLATION_BASE_ALPHA = 46
        private const val CACHE_RECYCLE_DELAY_MS = 1_000L
        private const val CLOUD_DRIFT_PERIOD_MS = 5_400_000L
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-HomeSky").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}
