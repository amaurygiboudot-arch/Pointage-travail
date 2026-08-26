package com.amaury.pointage

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mémoire visuelle indépendante de chaque vraie facette du squelette diamant.
 *
 * Une facette garde son identité et son état d'une frame à l'autre. Les
 * triangles OpenGL utilisés pour dessiner une même facette ne créent jamais
 * de nouvel état : ils réutilisent l'identifiant de la facette réelle.
 */
data class DiamondFacetState(
    val id: Int,
    val baseTone: Float,
    val transparencyReference: Float,
    var displayTone: Float,
    var brightness: Float,
    var reflection: Float,
    var lastOrientation: Float,
    var normalX: Float = 0f,
    var normalY: Float = 0f,
    var normalZ: Float = 1f
)

class DiamondFacetMemory(
    private val facetCount: Int = 57
) {
    private val states = Array(facetCount) { id ->
        val tone = facetTone(id)
        DiamondFacetState(
            id = id,
            baseTone = tone,
            transparencyReference = facetTransparency(id),
            displayTone = tone,
            brightness = facetInitialBrightness(id, tone),
            reflection = 0f,
            lastOrientation = 0f
        )
    }

    fun size(): Int = states.size
    fun state(id: Int): DiamondFacetState = states[id.coerceIn(0, states.lastIndex)]

    fun setNormal(id: Int, x: Float, y: Float, z: Float) {
        if (id !in states.indices) return
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.00001f)
        states[id].normalX = x / length
        states[id].normalY = y / length
        states[id].normalZ = z / length
    }

    /**
     * Lumière directe + transport interne simplifié.
     *
     * Le transport interne suit cette chaîne :
     * couronne/table -> réfraction air/diamant -> pavillon -> réflexion interne
     * -> remontée vers la couronne. Il ne s'agit pas encore d'un ray tracer avec
     * intersection géométrique exacte, mais les 24 facettes du pavillon
     * participent réellement au calcul et réinjectent de l'énergie vers le haut.
     */
    fun update(
        lightAngleDegrees: Float,
        pitchDegrees: Float,
        rollDegrees: Float,
        yawDegrees: Float,
        refraction: Float,
        sparkle: Float
    ) {
        val lightRad = Math.toRadians(lightAngleDegrees.toDouble())
        val lx = cos(lightRad).toFloat()
        val ly = sin(lightRad).toFloat()
        val lz = 1.35f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val nlx = lx / ll
        val nly = ly / ll
        val nlz = lz / ll

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

        // Eclairage renvoyé par l'intérieur du diamant. Chaque case correspond
        // à une vraie facette et s'ajoute ensuite au calcul direct de cette facette.
        val internalLight = computeInternalTransport(
            rotatedNormals = rotatedNormals,
            lightToSurface = floatArrayOf(nlx, nly, nlz),
            refractionControl = refr
        )

        for (s in states) {
            val rotated = rotatedNormals[s.id]
            val diffuse = max(0f, rotated[0] * nlx + rotated[1] * nly + rotated[2] * nlz)
            val returnedLight = internalLight[s.id]
            val angularMemory = 1f -
                (abs(shortestDelta(s.lastOrientation, orientation)) / 180f).coerceIn(0f, 1f)

            val colorContrast = abs(s.baseTone - 0.5f) * 2f
            val targetBrightness = (
                0.065f +
                    diffuse * (0.49f + refr * 0.17f) +
                    returnedLight * (0.42f + refr * 0.34f) +
                    (s.baseTone - 0.5f) * 0.16f +
                    colorContrast * 0.030f
                ).coerceIn(0.030f, 1f)

            // Un reflet peut maintenant venir de la lumière directe OU d'une
            // remontée interne depuis le pavillon.
            val directGate = ((diffuse - 0.70f) / 0.30f).coerceIn(0f, 1f)
            val returnGate = ((returnedLight - 0.18f) / 0.62f).coerceIn(0f, 1f)
            val highlightGate = max(directGate, returnGate * (0.72f + refr * 0.28f))
            val targetReflection = (
                highlightGate *
                    (0.34f + spark * 0.66f) *
                    (0.78f + angularMemory * 0.22f) *
                    (0.88f + colorContrast * 0.12f)
                ).coerceIn(0f, 1f)

            val phase = Math.toRadians(
                (orientation * 1.65f + s.id * 47.0f + lightAngleDegrees * 0.72f).toDouble()
            )
            val spectralWave = sin(phase).toFloat()
            // Le feu coloré augmente aussi lorsqu'une lumière remonte du pavillon.
            val spectralGate = (
                0.12f + targetReflection * 0.58f + returnedLight * 0.30f
                ).coerceIn(0f, 1f) * refr
            val dispersionAmplitude = (0.018f + spark * 0.020f) * spectralGate
            val targetDisplayTone = (
                s.baseTone + spectralWave * dispersionAmplitude
                ).coerceIn(0.04f, 0.96f)

            val brightnessSpeed = if (targetBrightness > s.brightness) 0.25f else 0.115f
            val reflectionSpeed = if (targetReflection > s.reflection) 0.31f else 0.095f
            val toneSpeed = if (targetReflection > s.reflection) 0.27f else 0.12f
            s.brightness += (targetBrightness - s.brightness) * brightnessSpeed
            s.reflection += (targetReflection - s.reflection) * reflectionSpeed
            s.displayTone += (targetDisplayTone - s.displayTone) * toneSpeed
            s.lastOrientation = orientation
        }
    }

    /**
     * Approximation optique légère compatible avec un calcul par frame :
     * - IDs 0..32 : table/couronne = facettes d'entrée et de sortie ;
     * - IDs 33..56 : pavillon/culasse = facettes de réflexion interne.
     *
     * L'indice du diamant est fixé à 2.42. La réflexion totale interne est
     * favorisée au-delà de l'angle critique air/diamant (~24.4 degrés).
     */
    private fun computeInternalTransport(
        rotatedNormals: Array<FloatArray>,
        lightToSurface: FloatArray,
        refractionControl: Float
    ): FloatArray {
        val out = FloatArray(states.size)
        if (states.size < 57) return out

        val diamondIor = 2.42f
        val criticalAngle = asin((1f / diamondIor).coerceIn(0f, 1f))
        val criticalCos = cos(criticalAngle)
        val incomingWorld = normalize3(
            -lightToSurface[0],
            -lightToSurface[1],
            -lightToSurface[2]
        )

        // Chaque facette supérieure peut faire entrer une partie de la lumière.
        for (entryId in 0..32) {
            val entryN = rotatedNormals[entryId]
            val entryFacing = max(
                0f,
                entryN[0] * lightToSurface[0] +
                    entryN[1] * lightToSurface[1] +
                    entryN[2] * lightToSurface[2]
            )
            if (entryFacing < 0.05f) continue

            val internalRay = refract3(
                incomingWorld,
                orientAgainst(entryN, incomingWorld),
                1f / diamondIor
            ) ?: continue

            val entryEnergy = entryFacing * (0.32f + refractionControl * 0.68f)
            val entryAzimuth = azimuth(internalRay)

            // Le rayon réfracté cherche les facettes de culasse qui se trouvent
            // dans sa direction angulaire. Sans intersection X/Y exacte, cette
            // sélection par azimut + incidence garde un coût très faible.
            for (pavilionId in 33..56) {
                val pavilionN0 = rotatedNormals[pavilionId]
                val pavilionN = orientAgainst(pavilionN0, internalRay)
                val approach = max(0f, -(dot3(internalRay, pavilionN)))
                if (approach < 0.08f) continue

                val pavilionAzimuth = azimuth(pavilionN)
                val sectorMatch = angularMatch(entryAzimuth, pavilionAzimuth, 92f)
                if (sectorMatch <= 0f) continue

                // Incidence mesurée à l'intérieur. Si l'angle est supérieur à
                // l'angle critique, la réflexion interne devient très efficace.
                val cosIncidence = abs(dot3(internalRay, pavilionN)).coerceIn(0f, 1f)
                val tir = ((criticalCos - cosIncidence) / criticalCos).coerceIn(0f, 1f)
                val reflectivity = (0.24f + tir * 0.70f + refractionControl * 0.06f)
                    .coerceIn(0f, 1f)
                val hitEnergy = entryEnergy * approach * sectorMatch * reflectivity
                if (hitEnergy < 0.002f) continue

                // La facette du pavillon elle-même devient lumineuse : c'est ce
                // qui manquait visuellement dans la simulation précédente.
                out[pavilionId] += hitEnergy * 0.78f

                val reflected = reflect3(internalRay, pavilionN)
                if (reflected[2] <= -0.15f) continue

                val returnAzimuth = azimuth(reflected)
                val upward = reflected[2].coerceIn(0f, 1f)

                // Le rayon remonte et alimente les facettes supérieures orientées
                // dans la direction de sortie. Plusieurs peuvent recevoir une
                // fraction du même trajet, comme dans un cristal réel vu en petit.
                for (exitId in 0..32) {
                    val exitN = rotatedNormals[exitId]
                    val exitAzimuth = azimuth(exitN)
                    val directionMatch = angularMatch(returnAzimuth, exitAzimuth, 78f)
                    if (directionMatch <= 0f) continue
                    val exitFacing = max(0f, dot3(reflected, exitN))
                    val exitEnergy = hitEnergy * upward * directionMatch *
                        (0.28f + exitFacing * 0.72f)
                    out[exitId] += exitEnergy
                }
            }
        }

        // Normalisation douce : plusieurs rayons peuvent converger vers la même
        // facette sans provoquer une saturation immédiate à 1.
        for (i in out.indices) {
            out[i] = (out[i] / (1f + out[i] * 0.72f)).coerceIn(0f, 1f)
        }
        return out
    }

    private fun facetTone(id: Int): Float {
        val noise = stable(id, 17) - 0.5f
        return when (id) {
            0 -> 0.54f
            in 1..8 -> (if ((id - 1) % 2 == 0) 0.34f else 0.70f) + noise * 0.08f
            in 9..16 -> (if ((id - 9) % 2 == 0) 0.24f else 0.79f) + noise * 0.10f
            in 17..32 -> {
                val pattern = when ((id - 17) % 4) {
                    0 -> 0.16f
                    1 -> 0.40f
                    2 -> 0.86f
                    else -> 0.64f
                }
                pattern + noise * 0.08f
            }
            in 33..48 -> {
                val pattern = when ((id - 33) % 4) {
                    0 -> 0.12f
                    1 -> 0.31f
                    2 -> 0.76f
                    else -> 0.91f
                }
                pattern + noise * 0.06f
            }
            else -> (if ((id - 49) % 2 == 0) 0.20f else 0.82f) + noise * 0.07f
        }.coerceIn(0.06f, 0.94f)
    }

    private fun facetTransparency(id: Int): Float {
        val noise = stable(id, 43)
        val base = when (id) {
            0 -> 0.94f
            in 1..16 -> 0.88f
            in 17..32 -> 0.84f
            in 33..48 -> 0.80f
            else -> 0.82f
        }
        return (base + (noise - 0.5f) * 0.10f).coerceIn(0.74f, 0.97f)
    }

    private fun facetInitialBrightness(id: Int, tone: Float): Float {
        val regionBase = when (id) {
            0 -> 0.34f
            in 1..16 -> 0.28f
            in 17..32 -> 0.24f
            else -> 0.18f
        }
        return (regionBase + (tone - 0.5f) * 0.10f + stable(id, 71) * 0.06f)
            .coerceIn(0.12f, 0.42f)
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
        val cz = cos(rz).toFloat()
        val sz = sin(rz).toFloat()
        val zx = x * cz - y * sz
        val zy = x * sz + y * cz
        val zz = z

        val ry = Math.toRadians(yDegrees.toDouble())
        val cy = cos(ry).toFloat()
        val sy = sin(ry).toFloat()
        val yx = zx * cy + zz * sy
        val yy = zy
        val yz = -zx * sy + zz * cy

        val rx = Math.toRadians(xDegrees.toDouble())
        val cx = cos(rx).toFloat()
        val sx = sin(rx).toFloat()
        val xx = yx
        val xy = yy * cx - yz * sx
        val xz = yy * sx + yz * cx

        return normalize3(xx, xy, xz)
    }

    private fun refract3(i: FloatArray, n: FloatArray, eta: Float): FloatArray? {
        val cosI = (-dot3(i, n)).coerceIn(-1f, 1f)
        val k = 1f - eta * eta * (1f - cosI * cosI)
        if (k < 0f) return null
        val a = eta
        val b = eta * cosI - sqrt(k)
        return normalize3(
            a * i[0] + b * n[0],
            a * i[1] + b * n[1],
            a * i[2] + b * n[2]
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
        if (dot3(n, direction) <= 0f) n
        else floatArrayOf(-n[0], -n[1], -n[2])

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.00001f)
        return floatArrayOf(x / length, y / length, z / length)
    }

    private fun azimuth(v: FloatArray): Float =
        normalizeDegrees(Math.toDegrees(kotlin.math.atan2(v[1], v[0]).toDouble()).toFloat())

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
