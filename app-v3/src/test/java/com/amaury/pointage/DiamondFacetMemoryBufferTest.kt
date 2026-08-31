package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class DiamondFacetMemoryBufferTest {
    @Test
    fun rgbaUploadBufferIsReusedWhileTopologyIsStable() {
        val memory = DiamondFacetMemory()
        memory.resetTopology(80)

        val first = memory.toRgbaBytes()
        val second = memory.toRgbaBytes()

        assertEquals(80 * 4, first.size)
        assertSame(first, second)
    }

    @Test
    fun internalReturnUploadBufferIsReusedWhileTopologyIsStable() {
        val memory = DiamondFacetMemory()
        memory.resetTopology(80)

        val first = memory.toInternalReturnRgbaBytes()
        val second = memory.toInternalReturnRgbaBytes()

        assertEquals(80 * 4, first.size)
        assertSame(first, second)
    }

    @Test
    fun uploadBuffersAreResizedOnlyWhenTopologyChanges() {
        val memory = DiamondFacetMemory()
        memory.resetTopology(80)
        val rgba80 = memory.toRgbaBytes()
        val internal80 = memory.toInternalReturnRgbaBytes()

        memory.resetTopology(57)
        val rgba57 = memory.toRgbaBytes()
        val internal57 = memory.toInternalReturnRgbaBytes()

        assertEquals(57 * 4, rgba57.size)
        assertEquals(57 * 4, internal57.size)
        assertNotSame(rgba80, rgba57)
        assertNotSame(internal80, internal57)
    }
}
