package com.amaury.pointage.diamond2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canonical topology for HoraTrack Diamond 2.
 *
 * This model deliberately lives beside Diamond 1. Nothing in the existing
 * renderer depends on it yet: integration happens only after the geometry has
 * been validated.
 *
 * Facet plan (round brilliant, pointed culet):
 *  1 table + 8 stars + 8 bezels + 16 upper halves
 *  + 8 pavilion mains + 16 lower halves = 57 facets.
 */
object DiamondMesh57 {
    const val FACET_COUNT = 57
    const val SECTORS = 8

    enum class Family {
        TABLE, STAR, BEZEL, UPPER_HALF, PAVILION_MAIN, LOWER_HALF
    }

    data class V3(val x: Float, val y: Float, val z: Float) {
        operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
        fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun normalized(): V3 {
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-7f)
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
        val facetB: Int?
    )

    data class Mesh(val facets: List<Facet>, val edges: List<Edge>) {
        init { require(facets.size == FACET_COUNT) }
    }

    /* Relative brilliant proportions. Z is deliberately physical rather than
       a screen-space bulge: table/crown sit above the girdle and pavilion ends
       at a pointed culet. */
    private const val GIRDLE_RADIUS = 1.0f
    private const val TABLE_RADIUS = 0.56f
    private const val STAR_RADIUS = 0.73f
    private const val CROWN_Z = 0.16f
    private const val TABLE_Z = 0.31f
    private const val GIRDLE_Z = 0.0f
    private const val PAVILION_BREAK_RADIUS = 0.52f
    private const val PAVILION_BREAK_Z = -0.37f
    private const val CULET_Z = -0.73f

    fun build(): Mesh {
        val table = ring(SECTORS, TABLE_RADIUS, TABLE_Z, 22.5f)
        val star = ring(SECTORS, STAR_RADIUS, CROWN_Z, 22.5f)
        val girdle = ring(SECTORS * 2, GIRDLE_RADIUS, GIRDLE_Z, 0f)
        val pavilionBreak = ring(SECTORS, PAVILION_BREAK_RADIUS, PAVILION_BREAK_Z, 22.5f)
        val culet = V3(0f, 0f, CULET_Z)

        val facets = ArrayList<Facet>(FACET_COUNT)
        fun add(family: Family, points: List<V3>) {
            require(points.size >= 3)
            val n = (points[1] - points[0]).cross(points[2] - points[0]).normalized()
            facets += Facet(facets.size, family, points, if (n.z < 0f && family != Family.PAVILION_MAIN && family != Family.LOWER_HALF) V3(-n.x,-n.y,-n.z) else n)
        }

        // One octagonal table.
        add(Family.TABLE, table.toList())

        // Crown: 8 stars, 8 bezel/kite facets, 16 upper halves.
        for (i in 0 until SECTORS) {
            val next = (i + 1) % SECTORS
            val g0 = girdle[(2 * i) % 16]
            val g1 = girdle[(2 * i + 1) % 16]
            val g2 = girdle[(2 * i + 2) % 16]
            add(Family.STAR, listOf(table[i], table[next], star[i]))
            add(Family.BEZEL, listOf(table[i], star[i], g1, star[(i - 1 + SECTORS) % SECTORS]))
            add(Family.UPPER_HALF, listOf(star[i], g1, g2))
            add(Family.UPPER_HALF, listOf(star[i], g0, g1))
        }

        // Pavilion: 8 mains meeting at the pointed culet and 16 lower halves.
        for (i in 0 until SECTORS) {
            val next = (i + 1) % SECTORS
            val g0 = girdle[(2 * i) % 16]
            val g1 = girdle[(2 * i + 1) % 16]
            val g2 = girdle[(2 * i + 2) % 16]
            add(Family.PAVILION_MAIN, listOf(g1, pavilionBreak[i], culet, pavilionBreak[next]))
            add(Family.LOWER_HALF, listOf(g0, pavilionBreak[i], g1))
            add(Family.LOWER_HALF, listOf(g1, pavilionBreak[next], g2))
        }

        check(facets.size == FACET_COUNT) { "Diamond 2 topology must contain exactly 57 facets, got ${facets.size}" }
        return Mesh(facets, sharedEdges(facets))
    }

    private fun ring(count: Int, radius: Float, z: Float, offsetDegrees: Float): Array<V3> =
        Array(count) { i ->
            val a = (offsetDegrees + i * 360f / count) * (PI / 180.0)
            V3((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat(), z)
        }

    /** Build an explicit adjacency map: every geometric edge knows the one or
        two facets sharing it. This will drive edge brilliance and optical
        continuity in the renderer instead of drawing decorative lines. */
    private fun sharedEdges(facets: List<Facet>): List<Edge> {
        data class Key(val ax:Int,val ay:Int,val az:Int,val bx:Int,val by:Int,val bz:Int)
        data class Pending(val a:V3,val b:V3,val facet:Int)
        fun q(v:Float)=(v*100000f).toInt()
        fun key(a:V3,b:V3):Key {
            val aa=listOf(q(a.x),q(a.y),q(a.z)); val bb=listOf(q(b.x),q(b.y),q(b.z))
            val swap = aa.joinToString(",") > bb.joinToString(",")
            val p=if(swap)bb else aa; val r=if(swap)aa else bb
            return Key(p[0],p[1],p[2],r[0],r[1],r[2])
        }
        val pending=LinkedHashMap<Key,Pending>()
        val result=ArrayList<Edge>()
        for(f in facets) for(i in f.vertices.indices){
            val a=f.vertices[i]; val b=f.vertices[(i+1)%f.vertices.size]; val k=key(a,b)
            val old=pending.remove(k)
            if(old==null) pending[k]=Pending(a,b,f.id) else result += Edge(old.a,old.b,old.facet,f.id)
        }
        pending.values.forEach { result += Edge(it.a,it.b,it.facet,null) }
        return result
    }
}
