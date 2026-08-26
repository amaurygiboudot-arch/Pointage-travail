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
    var lastOrientation: Float,
    var normalX: Float = 0f,
    var normalY: Float = 0f,
    var normalZ: Float = 1f
)

class DiamondFacetMemory {
    private val states = ArrayList<DiamondFacetState>()

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

        for (s in states) {
            val n = rotatedNormals[s.id]
            val diffuse = max(0f, dot3(n, lightToSurface))
            val returnedLight = internalLight[s.id]

            // Dedicated persistent optical channel. Its only source is the ray transport
            // calculated above; it is no longer reconstructed from brightness/reflection.
            val internalReturnSpeed = if (returnedLight > s.internalReturn) 0.34f else 0.12f
            s.internalReturn += (returnedLight - s.internalReturn) * internalReturnSpeed

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
                0.065f + regionDepth +
                    diffuse * (0.49f + refr * 0.17f) +
                    returnedLight * (0.42f + refr * 0.34f) +
                    (s.baseTone - 0.5f) * 0.16f +
                    colorContrast * 0.030f
                ).coerceIn(0.030f, 1f)

            val directGate = ((diffuse - 0.70f) / 0.30f).coerceIn(0f, 1f)
            val returnGate = ((returnedLight - 0.12f) / 0.68f).coerceIn(0f, 1f)
            val highlightGate = max(directGate, returnGate * (0.72f + refr * 0.28f))
            val targetReflection = (
                highlightGate *
                    (0.34f + spark * 0.66f) *
                    (0.78f + angularMemory * 0.22f) *
                    (0.88f + colorContrast * 0.12f)
                ).coerceIn(0f, 1f)

            val phase = Math.toRadians(
                (orientation * 1.65f + s.id * 47f + lightAngleDegrees * 0.72f).toDouble()
            )
            val spectralWave = sin(phase).toFloat()
            val spectralGate = (
                0.12f + targetReflection * 0.58f + returnedLight * 0.30f
                ).coerceIn(0f, 1f) * refr
            val dispersionAmplitude = (0.018f + spark * 0.020f) * spectralGate
            val targetDisplayTone =
                (s.baseTone + spectralWave * dispersionAmplitude).coerceIn(0.04f, 0.96f)

            val brightnessSpeed = if (targetBrightness > s.brightness) 0.25f else 0.115f
            val reflectionSpeed = if (targetReflection > s.reflection) 0.31f else 0.095f
            val toneSpeed = if (targetDisplayTone > s.displayTone) 0.27f else 0.12f
            s.brightness += (targetBrightness - s.brightness) * brightnessSpeed
            s.reflection += (targetReflection - s.reflection) * reflectionSpeed
            s.displayTone += (targetDisplayTone - s.displayTone) * toneSpeed
            s.lastOrientation = orientation
        }
    }

    private fun computeInternalTransport(
        rotatedNormals: Array<FloatArray>,
        lightToSurface: FloatArray,
        refractionControl: Float
    ): FloatArray {
        val out = FloatArray(states.size)
        val entryIds = states.indices.filter { isEntryRegion(states[it].region) }
        val pavilionIds = states.indices.filter { isPavilionRegion(states[it].region) }
        if (entryIds.isEmpty() || pavilionIds.isEmpty()) return out

        val diamondIor = 2.42f
        val criticalCos = cos(asin((1f / diamondIor).coerceIn(0f, 1f)))
        val incomingWorld = normalize3(
            -lightToSurface[0], -lightToSurface[1], -lightToSurface[2]
        )

        for (entryId in entryIds) {
            val entryN = rotatedNormals[entryId]
            val entryFacing = max(0f, dot3(entryN, lightToSurface))
            if (entryFacing < 0.025f) continue

            val internalRay = refract3(
                incomingWorld,
                orientAgainst(entryN, incomingWorld),
                1f / diamondIor
            ) ?: continue

            val entryEnergy = entryFacing * (0.30f + refractionControl * 0.70f)
            val entryAzimuth = azimuth(internalRay)
            val weights = FloatArray(pavilionIds.size)
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
        if (pavilionAverage > 0f) {
            pavilionIds.forEach { out[it] += pavilionAverage * 0.035f }
        }

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
        val out = ByteArray(states.size * 4)
        states.forEachIndexed { index, s ->
            val p = index * 4
            out[p] = unitByte(s.displayTone)
            out[p + 1] = unitByte(s.transparencyReference)
            out[p + 2] = unitByte(s.brightness)
            out[p + 3] = unitByte(s.reflection)
        }
        return out
    }

    fun toInternalReturnRgbaBytes(): ByteArray {
        val out = ByteArray(states.size * 4)
        states.forEachIndexed { index, s ->
            val p = index * 4
            val value = unitByte(s.internalReturn)
            out[p] = value
            out[p + 1] = value
            out[p + 2] = value
            out[p + 3] = 0xFF.toByte()
        }
        return out
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
