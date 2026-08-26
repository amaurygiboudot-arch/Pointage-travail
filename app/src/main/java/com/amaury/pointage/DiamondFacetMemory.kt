package com.amaury.pointage

import kotlin.math.abs
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

    /** Associe la normale géométrique locale permanente à l'ID de la facette. */
    fun setNormal(id: Int, x: Float, y: Float, z: Float) {
        if (id !in states.indices) return
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(0.00001f)
        states[id].normalX = x / length
        states[id].normalY = y / length
        states[id].normalZ = z / length
    }

    /**
     * Fait évoluer chaque facette vers sa nouvelle réponse lumineuse sans
     * repartir de zéro.
     *
     * Important : avant le calcul lumineux, la normale locale de chaque
     * facette reçoit exactement les mêmes rotations que le modèle OpenGL :
     * X(-8.2 + pitch*0.20), Y(2.4 - roll*0.24), Z(yaw*0.025).
     * Android Matrix.rotateM post-multiplie les matrices ; avec des vecteurs
     * colonnes, l'application effective est donc Z puis Y puis X.
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

        for (s in states) {
            val rotated = rotateLikeOpenGlModel(
                s.normalX,
                s.normalY,
                s.normalZ,
                modelX,
                modelY,
                modelZ
            )

            val diffuse = max(0f, rotated[0] * nlx + rotated[1] * nly + rotated[2] * nlz)
            val angularMemory = 1f -
                (abs(shortestDelta(s.lastOrientation, orientation)) / 180f).coerceIn(0f, 1f)

            // Les tons extrêmes reçoivent volontairement davantage de contraste :
            // les facettes profondes restent colorées dans l'ombre et les facettes
            // claires peuvent monter franchement sans toutes devenir blanches.
            val colorContrast = abs(s.baseTone - 0.5f) * 2f
            val targetBrightness = (
                0.075f +
                    diffuse * (0.54f + refr * 0.20f) +
                    (s.baseTone - 0.5f) * 0.18f +
                    colorContrast * 0.035f
                ).coerceIn(0.035f, 1f)

            val highlightGate = ((diffuse - 0.70f) / 0.30f).coerceIn(0f, 1f)
            val targetReflection = (
                highlightGate *
                    (0.36f + spark * 0.64f) *
                    (0.78f + angularMemory * 0.22f) *
                    (0.88f + colorContrast * 0.12f)
                ).coerceIn(0f, 1f)

            // Dispersion chromatique : la teinte visible d'une facette se décale
            // légèrement quand elle accroche la lumière. Le décalage dépend de son
            // orientation, de son ID et de la réfraction, mais reste centré autour
            // de sa couleur de base afin d'éviter un effet arc-en-ciel permanent.
            val phase = Math.toRadians(
                (orientation * 1.65f + s.id * 47.0f + lightAngleDegrees * 0.72f).toDouble()
            )
            val spectralWave = sin(phase).toFloat()
            val spectralGate = (0.18f + targetReflection * 0.82f) * refr
            val dispersionAmplitude = (0.018f + spark * 0.018f) * spectralGate
            val targetDisplayTone = (
                s.baseTone + spectralWave * dispersionAmplitude
                ).coerceIn(0.04f, 0.96f)

            // Lissage asymétrique : un reflet apparaît vite mais disparaît plus lentement.
            val brightnessSpeed = if (targetBrightness > s.brightness) 0.24f else 0.12f
            val reflectionSpeed = if (targetReflection > s.reflection) 0.30f else 0.10f
            val toneSpeed = if (targetReflection > s.reflection) 0.26f else 0.13f
            s.brightness += (targetBrightness - s.brightness) * brightnessSpeed
            s.reflection += (targetReflection - s.reflection) * reflectionSpeed
            s.displayTone += (targetDisplayTone - s.displayTone) * toneSpeed
            s.lastOrientation = orientation
        }
    }

    /**
     * Palette chromatique structurée selon les vraies familles du squelette :
     * 0 table, 1..8 première couronne, 9..16 deuxième couronne,
     * 17..32 couronne extérieure, 33..48 pavillon supérieur,
     * 49..56 grandes facettes finales du pavillon.
     *
     * La valeur ne remplace jamais la couleur principale du bouton : elle sert
     * seulement de variation stable au shader pour créer des rouges/verts/oranges
     * plus profonds et plus riches sans effet arc-en-ciel.
     */
    private fun facetTone(id: Int): Float {
        val noise = stable(id, 17) - 0.5f
        return when (id) {
            0 -> 0.54f
            in 1..8 -> {
                val i = id - 1
                (if (i % 2 == 0) 0.34f else 0.70f) + noise * 0.08f
            }
            in 9..16 -> {
                val i = id - 9
                (if (i % 2 == 0) 0.24f else 0.79f) + noise * 0.10f
            }
            in 17..32 -> {
                val i = id - 17
                val pattern = when (i % 4) {
                    0 -> 0.16f
                    1 -> 0.40f
                    2 -> 0.86f
                    else -> 0.64f
                }
                pattern + noise * 0.08f
            }
            in 33..48 -> {
                val i = id - 33
                val pattern = when (i % 4) {
                    0 -> 0.12f
                    1 -> 0.31f
                    2 -> 0.76f
                    else -> 0.91f
                }
                pattern + noise * 0.06f
            }
            else -> {
                val i = id - 49
                (if (i % 2 == 0) 0.20f else 0.82f) + noise * 0.07f
            }
        }.coerceIn(0.06f, 0.94f)
    }

    /**
     * Les facettes du pavillon sont volontairement un peu plus denses : elles
     * gardent davantage de couleur dans les zones sombres au lieu de devenir
     * uniformément transparentes.
     */
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

    /**
     * Reproduit la rotation du modèle OpenGL pour une normale (sans translation).
     * L'ordre effectif est Z -> Y -> X, correspondant aux appels rotateM X, Y, Z.
     */
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

        val length = sqrt(xx * xx + xy * xy + xz * xz).coerceAtLeast(0.00001f)
        return floatArrayOf(xx / length, xy / length, xz / length)
    }

    /**
     * RGBA compact pour une texture d'état 57x1 :
     * R = teinte visible (base + dispersion chromatique mémorisée)
     * G = transparence de référence
     * B = luminosité mémorisée
     * A = reflet mémorisé
     */
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
