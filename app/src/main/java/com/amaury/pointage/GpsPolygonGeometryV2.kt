package com.amaury.pointage

import kotlin.math.*

/** Géométrie partagée des zones multipoints. Le GPS circulaire existant reste le préfiltre. */
internal object GpsPolygonGeometryV2 {
    data class Vertex(val latitude: Double, val longitude: Double)

    fun valid(vertices: List<Vertex>): Boolean {
        if (vertices.size !in 3..64 || vertices.any {
                !it.latitude.isFinite() || !it.longitude.isFinite() ||
                    it.latitude !in -90.0..90.0 || it.longitude !in -180.0..180.0
            }) return false
        if (vertices.distinct().size < 3) return false
        val origin = vertices.first()
        val points = vertices.map { project(it, origin) }
        val area = points.indices.sumOf { i ->
            val a = points[i]
            val b = points[(i + 1) % points.size]
            a.first * b.second - b.first * a.second
        }
        if (abs(area) < 1.0) return false
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                if ((i + 1) % points.size == j || (j + 1) % points.size == i) continue
                if (segmentsIntersect(points[i], points[(i + 1) % points.size],
                        points[j], points[(j + 1) % points.size])) return false
            }
        }
        return true
    }

    fun contains(vertices: List<Vertex>, point: Vertex): Boolean {
        if (!valid(vertices) || !point.latitude.isFinite() || !point.longitude.isFinite()) return false
        val origin = vertices.first()
        val polygon = vertices.map { project(it, origin) }
        val p = project(point, origin)
        var inside = false
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (onSegment(a, b, p)) return true
            if ((a.second > p.second) != (b.second > p.second) &&
                p.first < (b.first - a.first) * (p.second - a.second) /
                    (b.second - a.second) + a.first) inside = !inside
        }
        return inside
    }

    private fun project(v: Vertex, origin: Vertex): Pair<Double, Double> {
        val rad = Math.PI / 180.0
        val delta = ((v.longitude - origin.longitude + 540.0) % 360.0) - 180.0
        return Pair(delta * rad * 6371000.0 * cos(origin.latitude * rad),
            (v.latitude - origin.latitude) * rad * 6371000.0)
    }

    private fun cross(a: Pair<Double, Double>, b: Pair<Double, Double>,
                      c: Pair<Double, Double>): Double =
        (b.first - a.first) * (c.second - a.second) -
            (b.second - a.second) * (c.first - a.first)

    private fun onSegment(a: Pair<Double, Double>, b: Pair<Double, Double>,
                          p: Pair<Double, Double>): Boolean =
        abs(cross(a, b, p)) < 0.01 &&
            p.first >= min(a.first, b.first) - 0.01 &&
            p.first <= max(a.first, b.first) + 0.01 &&
            p.second >= min(a.second, b.second) - 0.01 &&
            p.second <= max(a.second, b.second) + 0.01

    private fun segmentsIntersect(a: Pair<Double, Double>, b: Pair<Double, Double>,
                                  c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean {
        val abC = cross(a, b, c)
        val abD = cross(a, b, d)
        val cdA = cross(c, d, a)
        val cdB = cross(c, d, b)
        return (abC * abD < 0 && cdA * cdB < 0) ||
            onSegment(a, b, c) || onSegment(a, b, d) ||
            onSegment(c, d, a) || onSegment(c, d, b)
    }
}
