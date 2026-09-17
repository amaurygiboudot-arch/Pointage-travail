package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class V2SecuritySettingsViewTest {
    private val values = intArrayOf(1, 5, 15, 30, 60)

    @Test
    fun `une minute selectionne bien le premier choix`() {
        assertEquals(0, lockTimeoutSelectionIndex(values, 1))
    }

    @Test
    fun `une valeur inconnue utilise cinq minutes par defaut`() {
        assertEquals(1, lockTimeoutSelectionIndex(values, 7))
    }
}
