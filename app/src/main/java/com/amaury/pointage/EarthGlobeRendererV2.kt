package com.amaury.pointage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.v2.engine.CelestialGlobeModeV2
import com.amaury.pointage.v2.engine.CelestialGlobeSceneResolverV2
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import com.amaury.pointage.v2.engine.EarthGlobeProjectionV2
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Globe terrestre V2 de l'horloge.
 *
 * Le globe est une vraie projection orthographique : la latitude/longitude GPS
 * de l'utilisateur devient le point au centre de la sphère. Ainsi, en France la
 * France est face à l'utilisateur ; au Japon, le Japon l'est automatiquement.
 *
 * Le globe ne dépend volontairement pas du cap du téléphone. Le ciel tourne avec
 * la boussole, mais la Terre centrale reste une référence géographique stable.
 *
 * La projection par pixel est calculée hors du thread UI. Le dernier bitmap valide
 * reste affiché pendant la reconstruction puis est remplacé atomiquement sur main.
 */
internal fun earthSunBrightnessV2(sunDot: Double, depth: Double): Double {
    val normalized = sunDot.coerceIn(-1.0, 1.0)
    val direct = normalized.coerceAtLeast(0.0)

    // Bande crépusculaire douce autour du terminateur réel. Elle ne déplace jamais
    // la frontière physique : elle évite seulement un passage visuel trop brutal
    // entre nuit et jour sur un globe de quelques dizaines de pixels.
    val twilightT = ((normalized + 0.12) / 0.22).coerceIn(0.0, 1.0)
    val twilight = twilightT * twilightT * (3.0 - 2.0 * twilightT)
    val illumination = max(direct.pow(0.62), twilight * 0.28)

    // La face éclairée peut dépasser légèrement la texture native pour rester
    // lisible ; la face nocturne conserve assez de détail pour reconnaître le globe.
    val daylight = 0.16 + 0.96 * illumination
    val limb = 0.68 + 0.32 * depth.coerceIn(0.0, 1.0)
    return (daylight * limb).coerceIn(0.14, 1.12)
}

class EarthGlobeRendererV2(
    private val onBitmapReady: () -> Unit = {}
) {
    private data class RenderRequest(
        val diameter: Int,
        val viewLatitudeDeg: Double,
        val viewLongitudeDeg: Double,
        val sunLatitudeDeg: Double,
        val sunLongitudeDeg: Double
    )

    private data class CachedGlobe(
        val request: RenderRequest,
        val bitmap: Bitmap
    )

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val markerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 255, 255, 255)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(235, 62, 62)
    }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = Color.argb(225, 255, 255, 255)
    }
    private val limbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        color = Color.argb(150, 220, 236, 255)
    }
    private val destination = RectF()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)

    /** Etat confiné au thread principal; seul le calcul de pixels part sur [renderExecutor]. */
    private var cachedGlobe: CachedGlobe? = null
    private var retiredBitmap: Bitmap? = null
    private var desiredRequest: RenderRequest? = null
    private var workerRunning = false

    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        snapshot: CelestialSnapshotV2?,
        mode: CelestialGlobeModeV2 = CelestialGlobeModeV2.LOCAL
    ): Boolean {
        if (radius <= 1f) return false

        // Un snapshot peut devenir brièvement null lors d'une transition de
        // lifecycle ou pendant le renouvellement des capteurs. Dans ce cas,
        // conserver le dernier globe V2 valide au lieu de retomber sur le PNG
        // historique clair. Aucune nouvelle acquisition n'est déclenchée ici.
        if (snapshot == null) {
            return drawCachedGlobe(canvas, cx, cy, radius, null)
        }

        val scene = CelestialGlobeSceneResolverV2.resolve(snapshot, mode)

        // Le globe est calculé à 2x sa taille affichée puis réduit par Canvas.
        // C'est du supersampling uniquement : taille, forme et couleurs restent identiques.
        val displayDiameter = max(24, (radius * 2f).roundToInt())
        val request = RenderRequest(
            diameter = displayDiameter * GLOBE_SUPERSAMPLE,
            viewLatitudeDeg = scene.viewLatitudeDeg,
            viewLongitudeDeg = scene.viewLongitudeDeg,
            sunLatitudeDeg = scene.sunLatitudeDeg,
            sunLongitudeDeg = scene.sunLongitudeDeg
        )
        requestRenderIfNeeded(request)

        // Une reconstruction ne retire jamais le dernier résultat valide.
        return drawCachedGlobe(canvas, cx, cy, radius, scene)
    }

    private fun drawCachedGlobe(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        scene: com.amaury.pointage.v2.engine.EarthGlobeSceneV2?
    ): Boolean {
        val cached = cachedGlobe ?: return false
        val bitmap = cached.bitmap.takeUnless { it.isRecycled } ?: return false
        destination.set(cx - radius, cy - radius, cx + radius, cy + radius)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)

        // En mode Local le marqueur reste exactement au centre. En mode Monde il
        // suit sa vraie position projetée et disparaît naturellement derrière la Terre.
        if (scene != null) {
            val marker = EarthGlobeProjectionV2.project(
                latitudeDeg = scene.userLatitudeDeg,
                longitudeDeg = scene.userLongitudeDeg,
                observerLatitudeDeg = cached.request.viewLatitudeDeg,
                observerLongitudeDeg = cached.request.viewLongitudeDeg
            )
            if (marker.visible) {
                val markerX = cx + (marker.x * radius).toFloat()
                val markerY = cy + (marker.y * radius).toFloat()
                val markerRadius = max(1.6f, radius * 0.065f)
                canvas.drawCircle(markerX, markerY, markerRadius * 2.05f, markerHaloPaint)
                canvas.drawCircle(markerX, markerY, markerRadius, markerPaint)
                markerRingPaint.strokeWidth = max(1f, radius * 0.025f)
                canvas.drawCircle(markerX, markerY, markerRadius * 2.25f, markerRingPaint)
            }
        }

        limbPaint.strokeWidth = max(1f, radius * 0.022f)
        canvas.drawCircle(cx, cy, radius - limbPaint.strokeWidth * 0.5f, limbPaint)
        return true
    }

    fun clearCache() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { clearCacheOnMain() }
            return
        }
        clearCacheOnMain()
    }

    private fun clearCacheOnMain() {
        generation.incrementAndGet()
        desiredRequest = null
        cachedGlobe?.bitmap?.takeUnless { it.isRecycled }?.recycle()
        cachedGlobe = null
        retiredBitmap?.takeUnless { it.isRecycled }?.recycle()
        retiredBitmap = null
        // Un worker déjà lancé terminera ou s'interrompra en observant la génération.
        // Son résultat ne pourra plus être installé et sera recyclé à la livraison.
    }

    private fun requestRenderIfNeeded(request: RenderRequest) {
        val cached = cachedGlobe
        if (cached != null && equivalent(cached.request, request)) {
            if (desiredRequest != null) {
                desiredRequest = null
                generation.incrementAndGet()
            }
            return
        }

        if (desiredRequest?.let { equivalent(it, request) } != true) {
            desiredRequest = request
            generation.incrementAndGet()
        }
        startDesiredRenderIfIdle()
    }

    private fun startDesiredRenderIfIdle() {
        if (workerRunning) return
        val request = desiredRequest ?: return
        val requestGeneration = generation.get()
        workerRunning = true

        renderExecutor.execute {
            val bitmap = runCatching {
                buildGlobe(request) { generation.get() == requestGeneration }
            }.getOrNull()
            mainHandler.post {
                deliverRender(request, requestGeneration, bitmap)
            }
        }
    }

    private fun deliverRender(
        request: RenderRequest,
        requestGeneration: Long,
        bitmap: Bitmap?
    ) {
        workerRunning = false
        val stillWanted = requestGeneration == generation.get() &&
            desiredRequest?.let { equivalent(it, request) } == true

        if (bitmap != null && stillWanted) {
            val previous = cachedGlobe
            cachedGlobe = CachedGlobe(request, bitmap)
            desiredRequest = null
            // Garde une génération retirée afin de ne pas recycler un bitmap qui
            // pourrait encore être référencé par le RenderThread du frame précédent.
            retiredBitmap?.takeUnless { it.isRecycled }?.recycle()
            retiredBitmap = previous?.bitmap?.takeIf { it !== bitmap && !it.isRecycled }
            onBitmapReady()
        } else {
            bitmap?.takeUnless { it.isRecycled }?.recycle()
            if (stillWanted) desiredRequest = null
        }

        // Si la demande a changé pendant le calcul, la génération obsolète est
        // abandonnée et seule la demande la plus récente repart en arrière-plan.
        startDesiredRenderIfIdle()
    }

    private fun buildGlobe(
        request: RenderRequest,
        isCurrent: () -> Boolean
    ): Bitmap? {
        val texture = EarthGlobeMapAssetV2.texture
        val textureWidth = texture.width
        val textureHeight = texture.height
        val diameter = request.diameter
        val pixels = IntArray(diameter * diameter)
        val radius = diameter / 2.0
        val center = (diameter - 1) / 2.0

        val observerLat = Math.toRadians(request.viewLatitudeDeg)
        val observerLon = Math.toRadians(request.viewLongitudeDeg)
        val sinObserverLat = sin(observerLat)
        val cosObserverLat = cos(observerLat)
        val sinObserverLon = sin(observerLon)
        val cosObserverLon = cos(observerLon)

        // Base locale Est/Nord/Haut calculée une seule fois par globe, et non par pixel.
        val eastX = -sinObserverLon
        val eastY = cosObserverLon
        val northX = -sinObserverLat * cosObserverLon
        val northY = -sinObserverLat * sinObserverLon
        val northZ = cosObserverLat
        val upX = cosObserverLat * cosObserverLon
        val upY = cosObserverLat * sinObserverLon
        val upZ = sinObserverLat

        val sunLat = Math.toRadians(request.sunLatitudeDeg)
        val sunLon = Math.toRadians(request.sunLongitudeDeg)
        val cosSunLat = cos(sunLat)
        val sunWorldX = cosSunLat * cos(sunLon)
        val sunWorldY = cosSunLat * sin(sunLon)
        val sunWorldZ = sin(sunLat)

        for (py in 0 until diameter) {
            if (!isCurrent()) return null
            val yScreen = (py - center) / radius
            val yNorth = -yScreen
            for (px in 0 until diameter) {
                val xEast = (px - center) / radius
                val rho2 = xEast * xEast + yNorth * yNorth
                if (rho2 > 1.0) continue

                val depth = sqrt((1.0 - rho2).coerceAtLeast(0.0))
                val worldX = xEast * eastX + yNorth * northX + depth * upX
                val worldY = xEast * eastY + yNorth * northY + depth * upY
                val worldZ = yNorth * northZ + depth * upZ
                val latitudeDeg = Math.toDegrees(asin(worldZ.coerceIn(-1.0, 1.0)))
                val longitudeDeg = normalizeLongitude(Math.toDegrees(atan2(worldY, worldX)))

                val tx = (
                    ((longitudeDeg + 180.0) / 360.0) * (textureWidth - 1)
                    ).roundToInt().coerceIn(0, textureWidth - 1)
                val ty = (
                    ((90.0 - latitudeDeg) / 180.0) * (textureHeight - 1)
                    ).roundToInt().coerceIn(0, textureHeight - 1)
                val source = texture.pixels[ty * textureWidth + tx]

                // Lambert simplifié avec le vrai Soleil local. Le contraste est
                // volontairement renforcé pour que l'éclairage reste visible sur le
                // petit globe, sans déplacer ni élargir artificiellement la zone jour.
                val sunDot = worldX * sunWorldX + worldY * sunWorldY + worldZ * sunWorldZ
                val brightness = earthSunBrightnessV2(sunDot, depth)

                val edgePixels = (1.0 - sqrt(rho2)) * radius
                val alpha = (255.0 * edgePixels.coerceIn(0.0, 1.0)).roundToInt()
                val red = (Color.red(source) * brightness).roundToInt().coerceIn(0, 255)
                val green = (Color.green(source) * brightness).roundToInt().coerceIn(0, 255)
                val blue = (Color.blue(source) * brightness).roundToInt().coerceIn(0, 255)
                pixels[py * diameter + px] = Color.argb(alpha, red, green, blue)
            }
        }

        if (!isCurrent()) return null
        return Bitmap.createBitmap(pixels, diameter, diameter, Bitmap.Config.ARGB_8888)
    }

    private fun equivalent(a: RenderRequest, b: RenderRequest): Boolean =
        a.diameter == b.diameter &&
            angularDifference(a.viewLatitudeDeg, b.viewLatitudeDeg) <= LOCATION_CACHE_EPSILON_DEG &&
            angularDifferenceLongitude(a.viewLongitudeDeg, b.viewLongitudeDeg) <= LOCATION_CACHE_EPSILON_DEG &&
            angularDifference(a.sunLatitudeDeg, b.sunLatitudeDeg) <= SUN_CACHE_EPSILON_DEG &&
            angularDifferenceLongitude(a.sunLongitudeDeg, b.sunLongitudeDeg) <= SUN_CACHE_EPSILON_DEG

    private fun angularDifference(a: Double, b: Double): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.POSITIVE_INFINITY
        return abs(a - b)
    }

    private fun angularDifferenceLongitude(a: Double, b: Double): Double {
        if (!a.isFinite() || !b.isFinite()) return Double.POSITIVE_INFINITY
        return abs(((b - a + 540.0) % 360.0) - 180.0)
    }

    private fun normalizeLongitude(value: Double): Double =
        ((value + 540.0) % 360.0) - 180.0

    companion object {
        private val renderExecutor: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HoraTrack-EarthGlobe").apply {
                priority = Thread.NORM_PRIORITY - 1
            }
        }

        private const val GLOBE_SUPERSAMPLE = 2
        // Quelques kilomètres : assez stable pour éviter que le globe ne tremble
        // sur le bruit GPS, tout en se réorientant réellement lors d'un déplacement.
        private const val LOCATION_CACHE_EPSILON_DEG = 0.04
        private const val SUN_CACHE_EPSILON_DEG = 0.20
    }
}
