package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsEnablePreservesCanonicalZonesV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/amaury/pointage/MainActivity.kt").isFile }

    private val source = File(
        root,
        "app/src/main/java/com/amaury/pointage/MainActivity.kt"
    ).readText()

    @Test
    fun `enabling automatic gps does not rebuild canonical zones from legacy addresses`() {
        val listener = source.substringAfter("autoGpsSwitch.setOnCheckedChangeListener")
            .substringBefore("settingsButton?.setOnClickListener")

        assertTrue(listener.contains("enableAutomaticGpsFromCanonicalZones()"))
        assertFalse(listener.contains("saveGpsSettings()"))
    }

    @Test
    fun `canonical activation reads stored zones and only resyncs platform geofences`() {
        val method = source.substringAfter("private fun enableAutomaticGpsFromCanonicalZones()")
            .substringBefore("private fun requestLocationAccess()")

        assertTrue(method.contains("readPersistedGpsZones(gpsPrefs)"))
        assertTrue(method.contains("GeofenceManager.resyncStoredZones(this)"))
        assertFalse(method.contains("putString(\"zones\""))
        assertFalse(method.contains("workplaceAddress"))
        assertFalse(method.contains("Geocoder("))
    }
}
