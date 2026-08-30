package com.amaury.pointage

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class DiamondFacetRegion {
    TABLE,
    CROWN_INNER,
    CROWN_MIDDLE,
    CROWN_OUTER,
    GIRDLE,
    PAVILION_UPPER,
    PAVILION_LOWER
}

data class DiamondFacetState(
    val id: Int,
    var region: DiamondFacetRegion,
    var baseTone: Float,
    var transparencyReference: Float,
    var displayTone: Float,
    var brightness: Float,
    var reflection: Float,
    var internalReturn: Float,
    var exitTransmission: Float,
    var lastOrientation: Float,
    var normalX: Float = 0f,
    var normalY: Float = 0f,
    var normalZ: Float = 1f
)

class DiamondFacetMemory {
    private val states = ArrayList<DiamondFacetState>()
    private var rgbaUploadBytes = ByteArray(0)
    private var internalReturnUploadBytes = ByteArray(0)
    private var internalLightBuffer = FloatArray(0)
    private var pavilionWeightsBuffer = FloatArray(0)

    fun size(): Int = states.size

    fun resetTopology(facetCount: Int) {
        val wanted = facetCount.coerceAtLeast(0)
        if (states.size == wanted) return
        states.clear()
        repeat(wanted) { id ->
            val region = DiamondFacetRegion.CROWN_OUTER
            val tone = facetTone(id, region)
            states += DiamondFacetState(
                id = id,
                region = region,
                baseTone = tone,
                transparencyReference = facetTransparency(id, region),
                displayTone = tone,
                brightness = facetInitialBrightness(id, region, tone),
                reflection = 0f,
                internalReturn = 0f,
                exitTransmission = 0f,
                lastOrientation = 0f
            )
        }
    }

    fun defineFacet(id: Int, region: DiamondFacetRegion, x: Float, y: Float, z: Float) {
        if (id !in states.indices) return
        val s = states[id]
        val tone = facetTone(id, region)
        s.region = region
        s.baseTone = tone
        s.transparencyReference = facetTransparency(id, region)
        if (s.displayTone !in 0f..1f) s.displayTone = tone
        val n = normalize3(x, y, z)
        s.normalX = n[0]
        s.normalY = n[1]
        s.normalZ = n[2]
    }

    fun state(id: Int): DiamondFacetState = states[id.coerceIn(0, states.lastIndex)]

    fun update(
        lightAngleDegrees: Float,
        pitchDegrees: Float,
        rollDegrees: Float,
        yawDegrees: Float,
        refraction: Float,
        sparkle: Float
    ) {
        if (states.isEmpty()) return

        val sourceIntensity = CelestialLightingState.opticalIntensity.coerceIn(0.12f, 1f)
        val sourceWarmth = CelestialLightingState.opticalWarmth.coerceIn(-0.5f, 0.5f)
        val lightRad = Math.toRadians(lightAngleDegrees.toDouble())
        val lightToSurface = normalize3(
            cos(lightRad).toFloat(),
            sin(lightRad).toFloat(),
            1.35f
        )

        val modelX = -8.2f + pitchDegrees * 0.20f
        val modelY = 2.4f - rollDegrees * 0.24f
        val modelZ = yawDegrees * 0.025f
        val orientation = normalizeDegrees(yawDegrees + rollDegrees * 0.55f - pitchDegrees * 0.22f)
        val refr = refraction.coerceIn(0f, 1f)
        val spark = sparkle.coerceIn(0f, 1f)

        val rotatedNormals = Array(states.size) { id ->
            val s = states[id]
            rotateLikeOpenGlModel(s.normalX, s.normalY, s.normalZ, modelX, modelY, modelZ)
        }
        val internalLight = computeInternalTransport(rotatedNormals, lightToSurface, refr)
        val viewerDirection = normalize3(0f, -2.72f, 4.30f)
        val halfVector = normalize3(
            lightToSurface[0] + viewerDirection[0],
            lightToSurface[1] + viewerDirection[1],
            lightToSurface[2] + viewerDirection[2]
        )

        for (s in states) {
            val n = rotatedNormals[s.id]
            val geometricFacing = max(0f, dot3(n, lightToSurface))
            val diffuse = geometricFacing * sourceIntensity
            val specularAlignment = max(0f, dot3(n, halfVector))
            val sharpAlignment = ((specularAlignment - 0.78f) / 0.22f).coerceIn(0f, 1f)
            val sparkleAlignment = sharpAlignment * sharpAlignment
            val returnedLight = internalLight[s.id]

            val internalReturnSpeed = if (returnedLight > s.internalReturn) 0.34f else 0.12f
            s.internalReturn += (returnedLight - s.internalReturn) * internalReturnSpeed

            val targetExitTransmission = if (isEntryRegion(s.region)) {
                diamondToAirTransmission(n, viewerDirection)
            } else {
                0f
            }
            val exitSpeed = if (targetExitTransmission > s.exitTransmission) 0.38f else 0.18f
            s.exitTransmission += (targetExitTransmission - s.exitTransmission) * exitSpeed

            val angularMemory = 1f -
                (abs(shortestDelta(s.lastOrientation, orientation)) / 180f).coerceIn(0f, 1f)
            val colorContrast = abs(s.baseTone - 0.5f) * 2f

            val regionDepth = when (s.region) {
                DiamondFacetRegion.TABLE -> 0.04f
                DiamondFacetRegion.CROWN_INNER -> 0.02f
                DiamondFacetRegion.CROWN_MIDDLE -> 0f
                DiamondFacetRegion.CROWN_OUTER -> -0.01f
                DiamondFacetRegion.GIRDLE -> -0.03f
                DiamondFacetRegion.PAVILION_UPPER -> -0.04f
                DiamondFacetRegion.PAVILION_LOWER -> -0.055f
            }

            val targetBrightness = (
                0.055f + regionDepth +
                    diffuse * (0.60f + refr * 0.18f) +
                    sparkleAlignment * (0.18f + spark * 0.16f) +
                    returnedLight * (0.42f + refr * 0.34f) +
                    (s.baseTone - 0.5f) * 0.10f +
                    colorContrast * 0.025f
                ).coerceIn(0.025f, 1f)

            val directGate = ((diffuse - 0.42f) / 0.48f).coerceIn(0f, 1f)
            val returnGate = ((returnedLight - 0.10f) / 0.62f).coerceIn(0f, 1f)
            val highlightGate = max(
                sparkleAlignment,
                max(directGate * 0.74f, returnGate * (0.72f + refr * 0.28f))
            )
            val targetReflection = (
                highlightGate *
                    (0.46f + spark * 0.84f) *
                    (0.90f + angularMemory * 0.10f) *
                    (0.92f + colorContrast * 0.08f)
                ).coerceIn(0f, 1f)

            val phase = Math.toRadians(
                (orientation * 1.65f + s.id * 47f + lightAngleDegrees * 0.72f).toDouble()
            )
            val spectralWave = sin(phase).toFloat()
            val spectralGate = (
                0.08f + targetReflection * 0.68f + returnedLight * 0.24f
                ).coerceIn(0f, 1f) * refr
            val dispersionAmplitude = (0.012f + spark * 0.018f) * spectralGate
            val celestialToneShift = sourceWarmth * (0.012f + returnedLight * 0.018f)
            val targetDisplayTone =
                (s.baseTone + spectralWave * dispersionAmplitude + celestialToneShift).coerceIn(0.05f, 0.95f)

            val brightnessSpeed = if (targetBrightness > s.brightness) 0.72f else 0.20f
            val reflectionSpeed = if (targetReflection > s.reflection) 0.82f else 0.17f
            val toneSpeed = if (targetDisplayTone > s.displayTone) 0.34f else 0.18f
            s.brightness += (targetBrightness - s.brightness) * brightnessSpeed
            s.reflection += (targetReflection - s.reflection) * reflectionSpeed
            s.displayTone += (targetDisplayTone - s.displayTone) * toneSpeed
            s.lastOrientation = orientation
        }
    }

    private fun diamondToAirTransmission(normal: FloatArray, viewerDirection: FloatArray): Float {
        val diamondIor = 2.42f
        val airIor = 1f
        val cosAir = abs(dot3(normal, viewerDirection)).coerceIn(0f, 1f)
        val sinAir = sqrt(max(0f, 1f - cosAir * cosAir))
        val sinInside = (airIor * sinAir / diamondIor).coerceIn(0f, 1f)
        val cosInside = sqrt(max(0f, 1f - sinInside * sinInside))
        val criticalSin = (airIor / diamondIor).coerceIn(0f, 1f)
        val criticalCos = sqrt(max(0f, 1f - criticalSin * criticalSin))
        if (cosInside <= criticalCos) return 0f
        val rsDen = diamondIor * cosInside + airIor * cosAir
        val rpDen = diamondIor * cosAir + airIor * cosInside
        if (abs(rsDen) < 0.00001f || abs(rpDen) < 0.00001f) return 0f
        val rs = ((diamondIor * cosInside - airIor * cosAir) / rsDen)
        val rp = ((diamondIor * cosAir - airIor * cosInside) / rpDen)
        val fresnelReflectance = ((rs * rs + rp * rp) * 0.5f).coerceIn(0f, 1f)
        val escapeCone = ((cosInside - criticalCos) / (1f - criticalCos).coerceAtLeast(0.00001f))
            .coerceIn(0f, 1f)
        return ((1f - fresnelReflectance) * escapeCone).coerceIn(0f, 1f)
    }

    private fun computeInternalTransport(
        rotatedNormals: Array<FloatArray>,
        lightToSurface: FloatArray,
        refractionControl: Float
    ): FloatArray {
        val requiredSize = states.size
        if (internalLightBuffer.size != requiredSize) {
            internalLightBuffer = FloatArray(requiredSize)
        } else {
            internalLightBuffer.fill(0f)
        }
        val out = internalLightBuffer
        val entryIds = states.indices.filter { isEntryRegion(states[it].region) }
        val pavilionIds = states.indices.filter { isPavilionRegion(states[it].region) }
        if (entryIds.isEmpty() || pavilionIds.isEmpty()) return out

        val sourceIntensity = CelestialLightingState.opticalIntensity.coerceIn(0.12f, 1f)
        val diamondIor = 2.42f
        val criticalCos = cos(asin((1f / diamondIor).coerceIn(0f, 1f)))
        val incomingWorld = normalize3(
            -lightToSurface[0], -lightToSurface[1], -lightToSurface[2]
        )

        if (pavilionWeightsBuffer.size != pavilionIds.size) {
            pavilionWeightsBuffer = FloatArray(pavilionIds.size)
        }
        val weights = pavilionWeightsBuffer

        for (entryId in entryIds) {
            val entryN = rotatedNormals[entryId]
            val entryFacing = max(0f, dot3(entryN, lightToSurface))
            if (entryFacing < 0.025f) continue

            val internalRay = refract3(
                incomingWorld,
                orientAgainst(entryN, incomingWorld),
                1f / diamondIor
            ) ?: continue

            val entryEnergy = entryFacing * sourceIntensity * (0.30f + refractionControl * 0.70f)
            val entryAzimuth = azimuth(internalRay)
            var weightSum = 0f

            pavilionIds.forEachIndexed { index, pavilionId ->
                val pavilionN = orientAgainst(rotatedNormals[pavilionId], internalRay)
                val approach = max(0f, -dot3(internalRay, pavilionN))
                val sector = angularMatch(entryAzimuth, azimuth(pavilionN), 150f)
                val w = (0.035f + sector * 0.965f) * (0.025f + approach * 0.975f)
                weights[index] = w
                weightSum += w
            }
            if (weightSum <= 0.00001f) continue

            pavilionIds.forEachIndexed { index, pavilionId ->
                val pavilionN = orientAgainst(rotatedNormals[pavilionId], internalRay)
                val normalizedWeight = weights[index] / weightSum
                val cosIncidence = abs(dot3(internalRay, pavilionN)).coerceIn(0f, 1f)
                val tir = ((criticalCos - cosIncidence) / criticalCos).coerceIn(0f, 1f)
                val reflectivity =
                    (0.22f + tir * 0.70f + refractionControl * 0.08f).coerceIn(0f, 1f)
                val hitEnergy = entryEnergy * normalizedWeight * reflectivity * 5.4f
                out[pavilionId] += hitEnergy * 0.82f

                val reflected = reflect3(internalRay, pavilionN)
                val upward = reflected[2].coerceIn(0f, 1f)
                if (upward <= 0.01f) return@forEachIndexed

                val returnAzimuth = azimuth(reflected)
                for (exitId in entryIds) {
                    val exitN = rotatedNormals[exitId]
                    val directionMatch = angularMatch(returnAzimuth, azimuth(exitN), 105f)
                    if (directionMatch <= 0f) continue
                    val exitFacing = max(0f, dot3(reflected, exitN))
                    out[exitId] += hitEnergy * upward * directionMatch *
                        (0.20f + exitFacing * 0.80f)
                }
            }
        }

        scatterPavilion(out, DiamondFacetRegion.PAVILION_UPPER, 0.055f)
        scatterPavilion(out, DiamondFacetRegion.PAVILION_LOWER, 0.070f)

        val pavilionAverage = pavilionIds.sumOf { out[it].toDouble() }.toFloat() / pavilionIds.size
        if (pavilionAverage > 0f) pavilionIds.forEach { out[it] += pavilionAverage * 0.035f }

        for (i in out.indices) {
            out[i] = (out[i] / (1f + out[i] * 0.72f)).coerceIn(0f, 1f)
        }
        return out
    }

    private fun scatterPavilion(out: FloatArray, region: DiamondFacetRegion, amount: Float) {
        val ids = states.indices.filter { states[it].region == region }
        if (ids.size < 2) return
        val before = out.copyOf()
        ids.forEachIndexed { index, id ->
            val prev = ids[(index - 1 + ids.size) % ids.size]
            val next = ids[(index + 1) % ids.size]
            out[id] += (before[prev] + before[next]) * amount
        }
    }

    private fun isEntryRegion(region: DiamondFacetRegion): Boolean = when (region) {
        DiamondFacetRegion.TABLE,
        DiamondFacetRegion.CROWN_INNER,
        DiamondFacetRegion.CROWN_MIDDLE,
        DiamondFacetRegion.CROWN_OUTER -> true
        else -> false
    }

    private fun isPavilionRegion(region: DiamondFacetRegion): Boolean =
        region == DiamondFacetRegion.PAVILION_UPPER || region == DiamondFacetRegion.PAVILION_LOWER

    private fun facetTone(id: Int, region: DiamondFacetRegion): Float {
        val noise = stable(id, 17) - 0.5f
        val base = when (region) {
            DiamondFacetRegion.TABLE -> 0.54f
            DiamondFacetRegion.CROWN_INNER -> if (id % 2 == 0) 0.34f else 0.70f
            DiamondFacetRegion.CROWN_MIDDLE -> if (id % 2 == 0) 0.24f else 0.79f
            DiamondFacetRegion.CROWN_OUTER -> listOf(0.16f, 0.40f, 0.86f, 0.64f)[id % 4]
            DiamondFacetRegion.GIRDLE -> if (id % 2 == 0) 0.46f else 0.58f
            DiamondFacetRegion.PAVILION_UPPER -> listOf(0.12f, 0.31f, 0.76f, 0.91f)[id % 4]
            DiamondFacetRegion.PAVILION_LOWER -> if (id % 2 == 0) 0.20f else 0.82f
        }
        return (base + noise * 0.08f).coerceIn(0.06f, 0.94f)
    }

    private fun facetTransparency(id: Int, region: DiamondFacetRegion): Float {
        val base = when (region) {
            DiamondFacetRegion.TABLE -> 0.94f
            DiamondFacetRegion.CROWN_INNER -> 0.90f
            DiamondFacetRegion.CROWN_MIDDLE -> 0.87f
            DiamondFacetRegion.CROWN_OUTER -> 0.84f
            DiamondFacetRegion.GIRDLE -> 0.86f
            DiamondFacetRegion.PAVILION_UPPER -> 0.80f
            DiamondFacetRegion.PAVILION_LOWER -> 0.82f
        }
        return (base + (stable(id, 43) - 0.5f) * 0.10f).coerceIn(0.74f, 0.97f)
    }

    private fun facetInitialBrightness(id: Int, region: DiamondFacetRegion, tone: Float): Float {
        val regionBase = when (region) {
            DiamondFacetRegion.TABLE -> 0.34f
            DiamondFacetRegion.CROWN_INNER -> 0.29f
            DiamondFacetRegion.CROWN_MIDDLE -> 0.27f
            DiamondFacetRegion.CROWN_OUTER -> 0.24f
            DiamondFacetRegion.GIRDLE -> 0.20f
            DiamondFacetRegion.PAVILION_UPPER -> 0.18f
            DiamondFacetRegion.PAVILION_LOWER -> 0.16f
        }
        return (regionBase + (tone - 0.5f) * 0.10f + stable(id, 71) * 0.06f)
            .coerceIn(0.10f, 0.42f)
    }

    private fun rotateLikeOpenGlModel(
        x: Float,
        y: Float,
        z: Float,
        xDegrees: Float,
        yDegrees: Float,
        zDegrees: Float
    ): FloatArray {
        val rz = Math.toRadians(zDegrees.toDouble())
        val cz = cos(rz).toFloat(); val sz = sin(rz).toFloat()
        val zx = x * cz - y * sz; val zy = x * sz + y * cz; val zz = z

        val ry = Math.toRadians(yDegrees.toDouble())
        val cy = cos(ry).toFloat(); val sy = sin(ry).toFloat()
        val yx = zx * cy + zz * sy; val yy = zy; val yz = -zx * sy + zz * cy

        val rx = Math.toRadians(xDegrees.toDouble())
        val cx = cos(rx).toFloat(); val sx = sin(rx).toFloat()
        return normalize3(yx, yy * cx - yz * sx, yy * sx + yz * cx)
    }

    private fun refract3(i: FloatArray, n: FloatArray, eta: Float): FloatArray? {
        val cosI = (-dot3(i, n)).coerceIn(-1f, 1f)
        val k = 1f - eta * eta * (1f - cosI * cosI)
        if (k < 0f) return null
        val b = eta * cosI - sqrt(k)
        return normalize3(
            eta * i[0] + b * n[0],
            eta * i[1] + b * n[1],
            eta * i[2] + b * n[2]
        )
    }

    private fun reflect3(i: FloatArray, n: FloatArray): FloatArray {
        val d = dot3(i, n)
        return normalize3(
            i[0] - 2f * d * n[0],
            i[1] - 2f * d * n[1],
            i[2] - 2f * d * n[2]
        )
    }

    private fun orientAgainst(n: FloatArray, direction: FloatArray): FloatArray =
        if (dot3(n, direction) <= 0f) n else floatArrayOf(-n[0], -n[1], -n[2])

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.00001f)
        return floatArrayOf(x / length, y / length, z / length)
    }

    private fun azimuth(v: FloatArray): Float =
        normalizeDegrees(Math.toDegrees(atan2(v[1], v[0]).toDouble()).toFloat())

    private fun angularMatch(a: Float, b: Float, width: Float): Float =
        (1f - abs(shortestDelta(a, b)) / width).coerceIn(0f, 1f)

    fun toRgbaBytes(): ByteArray {
        val requiredSize = states.size * 4
        if (rgbaUploadBytes.size != requiredSize) rgbaUploadBytes = ByteArray(requiredSize)
        states.forEachIndexed { index, s ->
            val p = index * 4
            rgbaUploadBytes[p] = unitByte(s.displayTone)
            rgbaUploadBytes[p + 1] = unitByte(s.transparencyReference)
            rgbaUploadBytes[p + 2] = unitByte(s.brightness)
            rgbaUploadBytes[p + 3] = unitByte(s.reflection)
        }
        return rgbaUploadBytes
    }

    fun toInternalReturnRgbaBytes(): ByteArray {
        val requiredSize = states.size * 4
        if (internalReturnUploadBytes.size != requiredSize) internalReturnUploadBytes = ByteArray(requiredSize)
        states.forEachIndexed { index, s ->
            val p = index * 4
            internalReturnUploadBytes[p] = unitByte(s.internalReturn * s.exitTransmission)
            internalReturnUploadBytes[p + 1] = unitByte(s.internalReturn)
            internalReturnUploadBytes[p + 2] = unitByte(s.exitTransmission)
            internalReturnUploadBytes[p + 3] = 0xFF.toByte()
        }
        return internalReturnUploadBytes
    }

    private fun unitByte(value: Float): Byte =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    private fun stable(id: Int, salt: Int): Float {
        var x = id * 1103515245 + salt * 12345 + 0x45d9f3b
        x = x xor (x ushr 16)
        x *= 0x45d9f3b
        x = x xor (x ushr 16)
        return (x and 0x7fffffff) / 2147483647f
    }

    private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun shortestDelta(a: Float, b: Float): Float = ((b - a + 540f) % 360f) - 180f
}