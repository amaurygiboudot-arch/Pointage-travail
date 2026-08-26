package com.amaury.pointage.diamond2

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Physically-inspired optical core for Diamond 2.
 *
 * The goal is not a fake animated highlight: the renderer will feed real facet
 * normals and the current sun/view directions into these equations.
 *
 * IOR values are representative visible-spectrum values for diamond and are
 * intentionally split per RGB channel so white light can disperse slightly.
 */
object DiamondOptics {
    const val IOR_AIR = 1.000293f
    const val IOR_RED = 2.407f
    const val IOR_GREEN = 2.417f
    const val IOR_BLUE = 2.451f

    /** Number of internal bounces we allow in the real-time approximation. */
    const val DEFAULT_BOUNCE_BUDGET = 3

    data class Rgb(val r: Float, val g: Float, val b: Float) {
        operator fun times(s: Float) = Rgb(r * s, g * s, b * s)
        operator fun plus(o: Rgb) = Rgb(r + o.r, g + o.g, b + o.b)
        fun clamped() = Rgb(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        fun maxComponent() = max(r, max(g, b))
    }

    data class Ray(val direction: DiamondMesh57.V3, val energy: Rgb)

    data class InterfaceSample(
        val reflected: Ray,
        val transmitted: Ray?,
        val fresnel: Rgb,
        val totalInternalReflection: Boolean
    )

    data class FacetLighting(
        val direct: Rgb,
        val reflectedTowardView: Rgb,
        val transmittedInside: Rgb,
        val fresnelTowardView: Rgb,
        val edgeEnergy: Float
    )

    fun reflect(i: DiamondMesh57.V3, n: DiamondMesh57.V3): DiamondMesh57.V3 {
        val nn = orientedNormal(i, n)
        return (i - nn * (2f * i.dot(nn))).normalized()
    }

    /**
     * Snell refraction. Returns null when the ray is in total internal
     * reflection. `incident` points in the direction travelled by the ray.
     */
    fun refract(
        incident: DiamondMesh57.V3,
        normal: DiamondMesh57.V3,
        etaIncident: Float,
        etaTransmit: Float
    ): DiamondMesh57.V3? {
        var n = normal.normalized()
        val i = incident.normalized()
        var cosI = (-i).dot(n).coerceIn(-1f, 1f)
        var n1 = etaIncident
        var n2 = etaTransmit

        if (cosI < 0f) {
            cosI = -cosI
            n = -n
            val tmp = n1
            n1 = n2
            n2 = tmp
        }

        val eta = n1 / n2
        val k = 1f - eta * eta * (1f - cosI * cosI)
        if (k < 0f) return null
        return (i * eta + n * (eta * cosI - sqrt(k))).normalized()
    }

    /** Schlick Fresnel approximation evaluated per spectral channel. */
    fun fresnelSchlick(cosTheta: Float, etaIncident: Float, etaTransmit: Float): Float {
        val c = cosTheta.coerceIn(0f, 1f)
        val r0 = ((etaIncident - etaTransmit) / (etaIncident + etaTransmit)).pow(2)
        return (r0 + (1f - r0) * (1f - c).pow(5)).coerceIn(0f, 1f)
    }

    fun sampleAirToDiamond(
        incident: DiamondMesh57.V3,
        normal: DiamondMesh57.V3,
        energy: Rgb = Rgb(1f, 1f, 1f)
    ): InterfaceSample = sampleInterface(
        incident = incident,
        normal = normal,
        energy = energy,
        from = IOR_AIR,
        toR = IOR_RED,
        toG = IOR_GREEN,
        toB = IOR_BLUE
    )

    fun sampleDiamondToAir(
        incident: DiamondMesh57.V3,
        normal: DiamondMesh57.V3,
        energy: Rgb = Rgb(1f, 1f, 1f)
    ): InterfaceSample = sampleInterface(
        incident = incident,
        normal = normal,
        energy = energy,
        from = IOR_GREEN,
        toR = IOR_AIR,
        toG = IOR_AIR,
        toB = IOR_AIR,
        sourceR = IOR_RED,
        sourceG = IOR_GREEN,
        sourceB = IOR_BLUE
    )

    /**
     * Per-facet response used by the renderer before internal-bounce routing.
     * `toSun` and `toView` point away from the facet, toward sun and viewer.
     */
    fun evaluateFacet(
        facet: DiamondMesh57.Facet,
        toSun: DiamondMesh57.V3,
        toView: DiamondMesh57.V3,
        sunColor: Rgb = Rgb(1f, 1f, 1f),
        neighborNormal: DiamondMesh57.V3? = null
    ): FacetLighting {
        val n = facet.normal.normalized()
        val l = toSun.normalized()
        val v = toView.normalized()

        val ndl = max(0f, n.dot(l))
        val incident = -l
        val reflected = reflect(incident, n)
        val viewAlignment = max(0f, reflected.dot(v)).pow(96)
        val nv = abs(n.dot(v)).coerceIn(0f, 1f)

        val fr = fresnelSchlick(nv, IOR_AIR, IOR_RED)
        val fg = fresnelSchlick(nv, IOR_AIR, IOR_GREEN)
        val fb = fresnelSchlick(nv, IOR_AIR, IOR_BLUE)
        val fres = Rgb(fr, fg, fb)

        val direct = sunColor * (0.045f + ndl * 0.12f)
        val reflectedToView = Rgb(
            sunColor.r * viewAlignment * fr,
            sunColor.g * viewAlignment * fg,
            sunColor.b * viewAlignment * fb
        )

        val entry = sampleAirToDiamond(incident, n, sunColor * ndl)
        val transmitted = entry.transmitted?.energy ?: Rgb(0f, 0f, 0f)

        val edge = if (neighborNormal == null) 0f else {
            val bend = (1f - n.dot(neighborNormal.normalized()).coerceIn(-1f, 1f)) * 0.5f
            (bend * (0.30f + 0.70f * max(ndl, nv))).coerceIn(0f, 1f)
        }

        return FacetLighting(
            direct = direct.clamped(),
            reflectedTowardView = reflectedToView.clamped(),
            transmittedInside = transmitted.clamped(),
            fresnelTowardView = fres,
            edgeEnergy = edge
        )
    }

    private fun sampleInterface(
        incident: DiamondMesh57.V3,
        normal: DiamondMesh57.V3,
        energy: Rgb,
        from: Float,
        toR: Float,
        toG: Float,
        toB: Float,
        sourceR: Float = from,
        sourceG: Float = from,
        sourceB: Float = from
    ): InterfaceSample {
        val i = incident.normalized()
        val n = orientedNormal(i, normal)
        val cosTheta = abs((-i).dot(n)).coerceIn(0f, 1f)

        val rr = refract(i, n, sourceR, toR)
        val rg = refract(i, n, sourceG, toG)
        val rb = refract(i, n, sourceB, toB)

        val fr = if (rr == null) 1f else fresnelSchlick(cosTheta, sourceR, toR)
        val fg = if (rg == null) 1f else fresnelSchlick(cosTheta, sourceG, toG)
        val fb = if (rb == null) 1f else fresnelSchlick(cosTheta, sourceB, toB)
        val fres = Rgb(fr, fg, fb)

        val reflectedEnergy = Rgb(energy.r * fr, energy.g * fg, energy.b * fb)
        val reflected = Ray(reflect(i, n), reflectedEnergy)

        val tir = rr == null && rg == null && rb == null
        val transmitted = if (tir) null else {
            // One representative direction is used for routing between facets;
            // RGB energy remains spectrally separated and the shader can offset
            // exit sparkle positions per channel for visible fire.
            val direction = rg ?: rr ?: rb!!
            Ray(
                direction,
                Rgb(
                    if (rr == null) 0f else energy.r * (1f - fr),
                    if (rg == null) 0f else energy.g * (1f - fg),
                    if (rb == null) 0f else energy.b * (1f - fb)
                )
            )
        }

        return InterfaceSample(reflected, transmitted, fres, tir)
    }

    private fun orientedNormal(i: DiamondMesh57.V3, n: DiamondMesh57.V3): DiamondMesh57.V3 {
        val nn = n.normalized()
        return if (i.dot(nn) > 0f) -nn else nn
    }

    private operator fun DiamondMesh57.V3.plus(o: DiamondMesh57.V3) = DiamondMesh57.V3(x + o.x, y + o.y, z + o.z)
    private operator fun DiamondMesh57.V3.times(s: Float) = DiamondMesh57.V3(x * s, y * s, z * s)
}
