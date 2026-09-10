package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimeReaderTest {
    @Test
    fun `lecture fiable vide reste une vraie liste vide`() {
        val read = V2RuntimeReader.SessionsRead(
            sessions = emptyList(),
            reliable = true,
            warnings = emptyList()
        )

        assertTrue(read.requireReliable().isEmpty())
    }

    @Test
    fun `lecture non fiable ne peut pas etre consommee comme liste vide`() {
        val read = V2RuntimeReader.SessionsRead(
            sessions = emptyList(),
            reliable = false,
            warnings = listOf("runtime corrompu")
        )

        val error = runCatching { read.requireReliable() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("runtime corrompu", error?.message)
    }

    @Test
    fun `message de secours reste explicite sans avertissement detaille`() {
        val read = V2RuntimeReader.SessionsRead(
            sessions = emptyList(),
            reliable = false,
            warnings = emptyList()
        )

        val error = runCatching { read.requireReliable() }.exceptionOrNull()
        assertEquals(V2RuntimeReader.UNRELIABLE_MESSAGE, error?.message)
    }
}
