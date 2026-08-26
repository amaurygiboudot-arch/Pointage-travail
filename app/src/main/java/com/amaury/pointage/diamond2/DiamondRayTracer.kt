package com.amaury.pointage.diamond2

import kotlin.math.abs

/**
 * Small real-time ray router for Diamond 2.
 *
 * It traces only a tiny, controlled number of physically meaningful internal
 * bounces against the validated 57-facet mesh. This is intentionally not a
 * heavyweight path tracer: it gives the GPU deterministic optical events that
 * can be rendered at mobile frame rates.
 */
object DiamondRayTracer {
    private const val HIT_EPS = 1e-4f
    private const val INSIDE_EPS = 2e-4f
    private const val MIN_ENERGY = 0.006f

    data class Hit(
        val facet: DiamondMesh57.Facet,
        val point: DiamondMesh57.V3,
        val distance: Float
    )

    data class Bounce(
        val index: Int,
        val facetId: Int,
        val point: DiamondMesh57.V3,
        val incoming: DiamondMesh57.V3,
        val reflected: DiamondMesh57.V3,
        val reflectedEnergy: DiamondOptics.Rgb,
        val exited: DiamondOptics.Ray?,
        val totalInternalReflection: Boolean
    )

    data class TraceResult(
        val bounces: List<Bounce>,
        val escaped: List<DiamondOptics.Ray>,
        val remainingEnergy: DiamondOptics.Rgb
    )

    fun traceInside(
        mesh: DiamondMesh57.Mesh,
        origin: DiamondMesh57.V3,
        direction: DiamondMesh57.V3,
        energy: DiamondOptics.Rgb,
        bounceBudget: Int = DiamondOptics.DEFAULT_BOUNCE_BUDGET,
        ignoreFirstFacet: Int? = null
    ): TraceResult {
        var o = origin
        var d = direction.normalized()
        var e = energy
        var ignore = ignoreFirstFacet
        val events = ArrayList<Bounce>(bounceBudget.coerceAtLeast(0))
        val escaped = ArrayList<DiamondOptics.Ray>()

        repeat(bounceBudget.coerceAtLeast(0)) { bounceIndex ->
            if (e.maxComponent() < MIN_ENERGY) return@repeat
            val hit = nearestHit(mesh, o, d, ignore) ?: return@repeat
            val sample = DiamondOptics.sampleDiamondToAir(d, hit.facet.normal, e)
            sample.transmitted?.let { escaped += it }

            val reflected = sample.reflected
            events += Bounce(
                index = bounceIndex,
                facetId = hit.facet.id,
                point = hit.point,
                incoming = d,
                reflected = reflected.direction,
                reflectedEnergy = reflected.energy,
                exited = sample.transmitted,
                totalInternalReflection = sample.totalInternalReflection
            )

            e = reflected.energy
            d = reflected.direction
            o = hit.point + d * HIT_EPS
            ignore = hit.facet.id
        }

        return TraceResult(events, escaped, e)
    }

    fun nearestHit(
        mesh: DiamondMesh57.Mesh,
        origin: DiamondMesh57.V3,
        direction: DiamondMesh57.V3,
        ignoreFacet: Int? = null
    ): Hit? {
        val d = direction.normalized()
        var best: Hit? = null

        for (facet in mesh.facets) {
            if (facet.id == ignoreFacet) continue
            val p0 = facet.vertices[0]
            val denom = facet.normal.dot(d)
            if (abs(denom) < 1e-6f) continue
            val t = facet.normal.dot(p0 - origin) / denom
            if (t <= HIT_EPS) continue

            val p = origin + d * t
            if (!insideConvexFacet(p, facet)) continue
            if (best == null || t < best.distance) best = Hit(facet, p, t)
        }
        return best
    }

    /** Convex polygon containment directly in the facet plane. */
    private fun insideConvexFacet(p: DiamondMesh57.V3, facet: DiamondMesh57.Facet): Boolean {
        var sign = 0
        val n = facet.normal
        val vertices = facet.vertices
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            val side = (b - a).cross(p - a).dot(n)
            if (abs(side) <= INSIDE_EPS) continue
            val current = if (side > 0f) 1 else -1
            if (sign == 0) sign = current else if (current != sign) return false
        }
        return true
    }

    private operator fun DiamondMesh57.V3.plus(o: DiamondMesh57.V3) =
        DiamondMesh57.V3(x + o.x, y + o.y, z + o.z)

    private operator fun DiamondMesh57.V3.times(s: Float) =
        DiamondMesh57.V3(x * s, y * s, z * s)
}
