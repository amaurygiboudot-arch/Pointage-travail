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
        DiamondFacetState(
            id = id,
            baseTone = stable(id, 17),
            transparencyReference = 0.82f + stable(id, 43) * 0.16f,
            brightness = 0.20f + stable(id, 71) * 0.18f,
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

            val targetBrightness = (
                0.10f +
                    diffuse * (0.56f + refraction.coerceIn(0f, 1f) * 0.18f) +
                    (s.baseTone - 0.5f) * 0.12f
                ).coerceIn(0.04f, 1f)

            val highlightGate = ((diffuse - 0.72f) / 0.28f).coerceIn(0f, 1f)
            val targetReflection = (
                highlightGate * (0.40f + sparkle.coerceIn(0f, 1f) * 0.60f) *
                    (0.80f + angularMemory * 0.20f)
                ).coerceIn(0f, 1f)

            // Lissage asymétrique : un reflet apparaît vite mais disparaît plus lentement.
            val brightnessSpeed = if (targetBrightness > s.brightness) 0.24f else 0.12f
            val reflectionSpeed = if (targetReflection > s.reflection) 0.30f else 0.10f
            s.brightness += (targetBrightness - s.brightness) * brightnessSpeed
            s.reflection += (targetReflection - s.reflection) * reflectionSpeed
            s.lastOrientation = orientation
        }
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
     * R = variation chromatique stable
     * G = transparence de référence
     * B = luminosité mémorisée
     * A = reflet mémorisé
     */
    fun toRgbaBytes(): ByteArray {
        val out = ByteArray(states.size * 4)
        states.forEachIndexed { index, s ->
            val p = index * 4
            out[p] = unitByte(s.baseTone)
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
