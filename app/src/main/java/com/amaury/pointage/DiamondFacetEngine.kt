package com.amaury.pointage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Couronnes physiques du diamant : 16 + 32 + 32 facettes. */
enum class DiamondRing(val count: Int, val radialPosition: Float) {
    INNER(16, 0.28f),
    MIDDLE(32, 0.63f),
    OUTER(32, 0.96f)
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun normalized(): Vec3 {
        val length = sqrt((x * x + y * y + z * z).coerceAtLeast(1e-10f))
        return Vec3(x / length, y / length, z / length)
    }
}

data class DiamondFacetGeometry(
    val id: Int,
    val ring: DiamondRing,
    val azimuthDeg: Float,
    val tiltDeg: Float,
    val radialPosition: Float,
    val referenceTranslucency: Float,
    val normal: Vec3,
    val oppositeFacetId: Int
)

data class DiamondFacetOpticalState(
    val facetId: Int,
    val referenceTranslucency: Float,
    var directLight: Float = 0f,
    var internalLight: Float = 0f,
    var specular: Float = 0f,
    var luminance: Float = 0.20f,
    var lastNormalDotLight: Float = 0f
)

data class DiamondOpticalFrame(
    val timestampNanos: Long,
    val facets: List<DiamondFacetOpticalState>,
    val sunDirectionDevice: Vec3?,
    val moonDirectionDevice: Vec3?
)

object DiamondGeometry80 {
    val facets: List<DiamondFacetGeometry> by lazy { buildGeometry() }

    private fun buildGeometry(): List<DiamondFacetGeometry> {
        val result = ArrayList<DiamondFacetGeometry>(80)
        var id = 0

        fun addRing(ring: DiamondRing, baseTilt: Float, halfStep: Boolean) {
            val count = ring.count
            val step = 360f / count
            val bulge = 6.0f * ring.radialPosition.toDouble().pow(1.65).toFloat()
            repeat(count) { index ->
                val azimuth = normalizeDeg(-90f + index * step + if (halfStep) step * 0.5f else 0f)
                val cutSignature = (((id * 37 + ring.ordinal * 53) % 17) - 8) * 0.32f
                val tilt = (baseTilt + bulge + cutSignature).coerceIn(4f, 68f)
                val translucencySignature = (((id * 13 + ring.ordinal * 19) % 9) - 4) * 0.006f
                val translucency = (0.65f + translucencySignature + ring.ordinal * 0.012f)
                    .coerceIn(0.58f, 0.74f)
                result += DiamondFacetGeometry(
                    id = id,
                    ring = ring,
                    azimuthDeg = azimuth,
                    tiltDeg = tilt,
                    radialPosition = ring.radialPosition,
                    referenceTranslucency = translucency,
                    normal = normalFromAngles(azimuth, tilt),
                    oppositeFacetId = -1
                )
                id++
            }
        }

        addRing(DiamondRing.INNER, baseTilt = 11f, halfStep = false)
        addRing(DiamondRing.MIDDLE, baseTilt = 30f, halfStep = true)
        addRing(DiamondRing.OUTER, baseTilt = 47f, halfStep = true)
        require(result.size == 80) { "Diamond geometry must contain exactly 80 facets" }

        return result.map { facet ->
            val targetAzimuth = normalizeDeg(facet.azimuthDeg + 180f)
            val opposite = result.asSequence()
                .filter { it.ring == facet.ring }
                .minByOrNull { angularDistanceDeg(it.azimuthDeg, targetAzimuth) }
                ?: facet
            facet.copy(oppositeFacetId = opposite.id)
        }
    }

    private fun normalFromAngles(azimuthDeg: Float, tiltDeg: Float): Vec3 {
        val az = Math.toRadians(azimuthDeg.toDouble())
        val tilt = Math.toRadians(tiltDeg.toDouble())
        val s = sin(tilt).toFloat()
        return Vec3(
            cos(az).toFloat() * s,
            sin(az).toFloat() * s,
            cos(tilt).toFloat()
        ).normalized()
    }

    private fun normalizeDeg(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun angularDistanceDeg(a: Float, b: Float): Float = abs(((b - a + 540f) % 360f) - 180f)
}

/** Moteur optique des 80 facettes, paramétrable en direct par le mode développeur. */
class DiamondFacetEngine(
    private val geometry: List<DiamondFacetGeometry> = DiamondGeometry80.facets
) {
    private val states = geometry.map {
        DiamondFacetOpticalState(
            facetId = it.id,
            referenceTranslucency = it.referenceTranslucency
        )
    }

    private var lastFrameNanos: Long = 0L

    fun update(
        snapshot: CelestialSnapshot,
        frameTimeNanos: Long,
        tuning: PrimaryDiamondLiveTuningConfig = PrimaryDiamondLiveTuningConfig()
    ): DiamondOpticalFrame {
        val dtSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
                .coerceIn(0f, 0.10f)
        }
        lastFrameNanos = frameTimeNanos

        val sunDirection = snapshot.sun.takeIf { it.available && it.opticalIntensity > 0f }
            ?.let { celestialDirectionInDevice(it, snapshot.orientation) }
        val moonDirection = snapshot.moon.takeIf { it.available && it.opticalIntensity > 0f }
            ?.let { celestialDirectionInDevice(it, snapshot.orientation) }

        val sunEnergy = (snapshot.sun.opticalIntensity * tuning.sunIntensityScale).coerceIn(0f, 2f)
        val moonEnergy = (snapshot.moon.opticalIntensity * tuning.moonIntensityScale).coerceIn(0f, 2f)

        val incoming = FloatArray(geometry.size)
        val specularTarget = FloatArray(geometry.size)

        geometry.forEach { facet ->
            val normal = facet.normal
            val sunDirect = sunDirection?.let { max(0f, normal.dot(it)) * sunEnergy } ?: 0f
            val moonDirect = moonDirection?.let { max(0f, normal.dot(it)) * moonEnergy } ?: 0f
            val direct = (sunDirect + moonDirect).coerceIn(0f, 1.5f)
            incoming[facet.id] = direct

            val view = Vec3(0f, 0f, 1f)
            val dominantLight = when {
                sunDirection != null && sunEnergy >= moonEnergy -> sunDirection
                moonDirection != null -> moonDirection
                else -> null
            }
            val exponent = if (snapshot.isNight) tuning.nightSpecularPower else tuning.daySpecularPower
            specularTarget[facet.id] = dominantLight?.let { light ->
                val half = (light + view).normalized()
                max(0f, normal.dot(half)).pow(exponent.coerceIn(4f, 300f)) * max(sunEnergy, moonEnergy)
            } ?: 0f
        }

        val internalTarget = FloatArray(geometry.size)
        geometry.forEach { source ->
            val transmitted = incoming[source.id] *
                (source.referenceTranslucency * tuning.translucencyScale).coerceIn(0f, 1.5f)
            val retained = transmitted * tuning.internalRetention.coerceIn(0f, 1f)
            internalTarget[source.oppositeFacetId] += retained
        }

        val response = responseFactor(dtSeconds, tuning.responseTau.coerceIn(0.005f, 1f))
        geometry.forEach { facet ->
            val state = states[facet.id]
            val directTarget = incoming[facet.id]
            val internal = internalTarget[facet.id].coerceIn(0f, 1.5f)
            val specular = specularTarget[facet.id].coerceIn(0f, 1.5f)

            state.directLight += (directTarget - state.directLight) * response
            state.internalLight += (internal - state.internalLight) * response
            state.specular += (specular - state.specular) * response
            state.lastNormalDotLight = directTarget

            val minimum = tuning.baseLuminance.coerceIn(0.02f, 0.95f)
            val targetLuminance = (
                minimum +
                    state.directLight * tuning.directWeight.coerceIn(0f, 2f) +
                    state.internalLight * tuning.internalWeight.coerceIn(0f, 2f)
                ).coerceIn(minimum, 1.25f)
            state.luminance += (targetLuminance - state.luminance) * response
        }

        return DiamondOpticalFrame(
            timestampNanos = frameTimeNanos,
            facets = states,
            sunDirectionDevice = sunDirection,
            moonDirectionDevice = moonDirection
        )
    }

    private fun celestialDirectionInDevice(body: CelestialBodyState, orientation: DeviceOrientationState): Vec3 {
        val az = Math.toRadians(body.azimuthDeg)
        val alt = Math.toRadians(body.altitudeDeg)
        val world = Vec3(
            (sin(az) * cos(alt)).toFloat(),
            (cos(az) * cos(alt)).toFloat(),
            sin(alt).toFloat()
        ).normalized()

        return Vec3(
            orientation.r00 * world.x + orientation.r10 * world.y + orientation.r20 * world.z,
            orientation.r01 * world.x + orientation.r11 * world.y + orientation.r21 * world.z,
            orientation.r02 * world.x + orientation.r12 * world.y + orientation.r22 * world.z
        ).normalized()
    }

    private fun responseFactor(dtSeconds: Float, tauSeconds: Float): Float {
        if (dtSeconds <= 0f) return 0f
        return (1.0 - exp((-dtSeconds / tauSeconds).toDouble())).toFloat().coerceIn(0f, 1f)
    }
}
