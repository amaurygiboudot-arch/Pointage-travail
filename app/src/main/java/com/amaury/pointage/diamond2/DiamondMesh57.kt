package com.amaury.pointage.diamond2

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canonical topology for HoraTrack Diamond 2.
 *
 * 57-facet pointed round brilliant:
 * 1 table + 8 stars + 8 bezels + 16 upper halves
 * + 8 pavilion mains + 16 lower halves.
 *
 * This model stays fully isolated from Diamond 1 until integration.
 */
object DiamondMesh57 {
    const val FACET_COUNT = 57
    const val SECTORS = 8
    private const val EDGE_EPS = 1e-5f
    private const val PLANAR_EPS = 2e-4f

    enum class Family {
        TABLE, STAR, BEZEL, UPPER_HALF, PAVILION_MAIN, LOWER_HALF
    }

    data class V3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        operator fun unaryMinus() = V3(-x, -y, -z)
        fun dot(o: V3) = x * o.x + y * o.y + z * o.z
        fun cross(o: V3) = V3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x
        )
        fun length() = sqrt(x * x + y * y + z * z)
        fun normalized(): V3 {
            val l = length().coerceAtLeast(1e-7f)
            return V3(x / l, y / l, z / l)
        }
    }

    data class Facet(
        val id: Int,
        val family: Family,
        val vertices: List<V3>,
        val normal: V3
    )

    data class Edge(
        val a: V3,
        val b: V3,
        val facetA: Int,
        val facetB: Int
    )

    data class ValidationReport(
        val facetCount: Int,
        val edgeCount: Int,
        val boundaryEdges: Int,
        val nonManifoldEdges: Int,
        val nonPlanarFacets: Int,
        val inwardNormals: Int
    ) {
        val isValid: Boolean
            get() = facetCount == FACET_COUNT &&
                boundaryEdges == 0 &&
                nonManifoldEdges == 0 &&
                nonPlanarFacets == 0 &&
                inwardNormals == 0
    }

    data class Mesh(
        val facets: List<Facet>,
        val edges: List<Edge>,
        val validation: ValidationReport
    ) {
        init {
            require(validation.isValid) { "Invalid Diamond 2 mesh: $validation" }
        }
    }

    /*
     * Relative brilliant proportions. The coordinates are real 3D planes,
     * not a screen-space bulge. The star and pavilion-break Z values are
     * derived so the 4-point bezel and pavilion-main facets are coplanar.
     */
    private const val GIRDLE_RADIUS = 1.0f
    private const val TABLE_RADIUS = 0.56f
    private const val STAR_RADIUS = 0.73f
    private const val TABLE_Z = 0.31f
    private const val GIRDLE_Z = 0.0f
    private const val PAVILION_BREAK_RADIUS = 0.52f
    private const val CULET_Z = -0.73f

    fun build(): Mesh {
        val halfSectorRad = (PI / SECTORS).toFloat() / 2f
        val starProjectedRadius = STAR_RADIUS * cos(halfSectorRad)
        val starZ = interpolatePlaneHeight(
            innerRadius = TABLE_RADIUS,
            innerZ = TABLE_Z,
            outerRadius = GIRDLE_RADIUS,
            outerZ = GIRDLE_Z,
            sampleRadius = starProjectedRadius
        )

        val pavilionProjectedRadius = PAVILION_BREAK_RADIUS * cos(halfSectorRad)
        val pavilionBreakZ = interpolatePlaneHeight(
            innerRadius = 0f,
            innerZ = CULET_Z,
            outerRadius = GIRDLE_RADIUS,
            outerZ = GIRDLE_Z,
            sampleRadius = pavilionProjectedRadius
        )

        // Table corners align with main girdle points. Star tips sit halfway
        // between neighbouring table corners, matching brilliant topology.
        val table = ring(SECTORS, TABLE_RADIUS, TABLE_Z, 0f)
        val starTips = ring(SECTORS, STAR_RADIUS, starZ, 22.5f)
        val girdle = ring(SECTORS * 2, GIRDLE_RADIUS, GIRDLE_Z, 0f)
        val pavilionBreak = ring(SECTORS, PAVILION_BREAK_RADIUS, pavilionBreakZ, 22.5f)
        val culet = V3(0f, 0f, CULET_Z)

        val facets = ArrayList<Facet>(FACET_COUNT)
        val inside = V3(0f, 0f, -0.08f)

        fun add(family: Family, points: List<V3>) {
            require(points.size >= 3)
            var normal = polygonNormal(points)
            val centroid = centroid(points)
            val outward = centroid - inside
            if (normal.dot(outward) < 0f) normal = -normal
            facets += Facet(facets.size, family, points, normal)
        }

        // 1 table.
        add(Family.TABLE, table.toList())

        // Crown. This topology is a closed annulus from the table to girdle:
        // 8 stars + 8 bezel kites + 16 upper halves.
        for (i in 0 until SECTORS) {
            val prev = (i - 1 + SECTORS) % SECTORS
            val next = (i + 1) % SECTORS
            val gMain = girdle[2 * i]
            val gMid = girdle[(2 * i + 1) % 16]
            val gNextMain = girdle[(2 * i + 2) % 16]

            add(Family.STAR, listOf(table[i], table[next], starTips[i]))
            add(Family.BEZEL, listOf(table[i], starTips[i], gMain, starTips[prev]))
            add(Family.UPPER_HALF, listOf(starTips[i], gMain, gMid))
            add(Family.UPPER_HALF, listOf(starTips[i], gMid, gNextMain))
        }

        // Pavilion. Mirrored manifold topology:
        // 8 pavilion mains + 16 lower halves converging to a pointed culet.
        for (i in 0 until SECTORS) {
            val prev = (i - 1 + SECTORS) % SECTORS
            val gMain = girdle[2 * i]
            val gMid = girdle[(2 * i + 1) % 16]
            val gNextMain = girdle[(2 * i + 2) % 16]

            add(Family.PAVILION_MAIN, listOf(gMain, pavilionBreak[i], culet, pavilionBreak[prev]))
            add(Family.LOWER_HALF, listOf(pavilionBreak[i], gMain, gMid))
            add(Family.LOWER_HALF, listOf(pavilionBreak[i], gMid, gNextMain))
        }

        check(facets.size == FACET_COUNT) {
            "Diamond 2 topology must contain exactly 57 facets, got ${facets.size}"
        }

        val topology = buildEdges(facets)
        val report = validate(facets, topology)
        return Mesh(facets, topology.edges, report)
    }

    private data class EdgeBuild(
        val edges: List<Edge>,
        val boundaryEdges: Int,
        val nonManifoldEdges: Int
    )

    private fun buildEdges(facets: List<Facet>): EdgeBuild {
        data class Key(val ax: Int, val ay: Int, val az: Int, val bx: Int, val by: Int, val bz: Int)
        data class Ref(val a: V3, val b: V3, val facet: Int)

        fun q(v: Float) = (v / EDGE_EPS).toInt()
        fun key(a: V3, b: V3): Key {
            val av = intArrayOf(q(a.x), q(a.y), q(a.z))
            val bv = intArrayOf(q(b.x), q(b.y), q(b.z))
            val aFirst = when {
                av[0] != bv[0] -> av[0] < bv[0]
                av[1] != bv[1] -> av[1] < bv[1]
                else -> av[2] <= bv[2]
            }
            val p = if (aFirst) av else bv
            val r = if (aFirst) bv else av
            return Key(p[0], p[1], p[2], r[0], r[1], r[2])
        }

        val refs = LinkedHashMap<Key, MutableList<Ref>>()
        for (facet in facets) {
            for (i in facet.vertices.indices) {
                val a = facet.vertices[i]
                val b = facet.vertices[(i + 1) % facet.vertices.size]
                refs.getOrPut(key(a, b)) { ArrayList(2) }.add(Ref(a, b, facet.id))
            }
        }

        var boundary = 0
        var nonManifold = 0
        val edges = ArrayList<Edge>()
        for (list in refs.values) {
            when (list.size) {
                1 -> boundary++
                2 -> edges += Edge(list[0].a, list[0].b, list[0].facet, list[1].facet)
                else -> nonManifold++
            }
        }
        return EdgeBuild(edges, boundary, nonManifold)
    }

    private fun validate(facets: List<Facet>, topology: EdgeBuild): ValidationReport {
        val inside = V3(0f, 0f, -0.08f)
        var nonPlanar = 0
        var inward = 0

        for (facet in facets) {
            val p0 = facet.vertices.first()
            for (p in facet.vertices.drop(3)) {
                val distance = abs((p - p0).dot(facet.normal))
                if (distance > PLANAR_EPS) {
                    nonPlanar++
                    break
                }
            }
            if (facet.normal.dot(centroid(facet.vertices) - inside) <= 0f) inward++
        }

        return ValidationReport(
            facetCount = facets.size,
            edgeCount = topology.edges.size,
            boundaryEdges = topology.boundaryEdges,
            nonManifoldEdges = topology.nonManifoldEdges,
            nonPlanarFacets = nonPlanar,
            inwardNormals = inward
        )
    }

    private fun interpolatePlaneHeight(
        innerRadius: Float,
        innerZ: Float,
        outerRadius: Float,
        outerZ: Float,
        sampleRadius: Float
    ): Float {
        val t = ((sampleRadius - innerRadius) / (outerRadius - innerRadius)).coerceIn(0f, 1f)
        return innerZ + (outerZ - innerZ) * t
    }

    private fun ring(count: Int, radius: Float, z: Float, offsetDegrees: Float): Array<V3> =
        Array(count) { i ->
            val a = (offsetDegrees + i * 360f / count) * (PI / 180.0)
            V3((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat(), z)
        }

    private fun polygonNormal(points: List<V3>): V3 {
        for (i in 1 until points.lastIndex) {
            val n = (points[i] - points[0]).cross(points[i + 1] - points[0])
            if (n.length() > 1e-6f) return n.normalized()
        }
        error("Degenerate Diamond 2 facet")
    }

    private fun centroid(points: List<V3>): V3 {
        var x = 0f
        var y = 0f
        var z = 0f
        for (p in points) {
            x += p.x
            y += p.y
            z += p.z
        }
        val n = points.size.toFloat()
        return V3(x / n, y / n, z / n)
    }
}
