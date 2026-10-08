package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class GpsPolygonGeometryV2Test {
    private val square = listOf(
        GpsPolygonGeometryV2.Vertex(46.0, -1.0),
        GpsPolygonGeometryV2.Vertex(46.0, -0.999),
        GpsPolygonGeometryV2.Vertex(46.001, -0.999),
        GpsPolygonGeometryV2.Vertex(46.001, -1.0)
    )

    @Test fun `valid zone supports draggable three or more vertices`() {
        assertTrue(GpsPolygonGeometryV2.valid(square))
        assertTrue(GpsPolygonGeometryV2.valid(square.dropLast(1)))
        assertFalse(GpsPolygonGeometryV2.valid(square.take(2)))
    }

    @Test fun `inside outside and border are distinguished`() {
        assertTrue(GpsPolygonGeometryV2.contains(square, GpsPolygonGeometryV2.Vertex(46.0005, -0.9995)))
        assertTrue(GpsPolygonGeometryV2.contains(square, square[0]))
        assertFalse(GpsPolygonGeometryV2.contains(square, GpsPolygonGeometryV2.Vertex(46.002, -0.9995)))
    }

    @Test fun `self intersection is rejected`() {
        assertFalse(GpsPolygonGeometryV2.valid(listOf(square[0], square[2], square[1], square[3])))
    }
}
