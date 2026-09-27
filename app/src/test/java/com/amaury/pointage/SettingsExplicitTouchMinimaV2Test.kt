package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExplicitTouchMinimaV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "app/src/main/res/layout/activity_main.xml").isFile }

    @Test
    fun `public settings actions have explicit 48dp minima`() {
        val source = File(root, "app/src/main/res/layout/activity_main.xml").readText()
        val location = source.substringAfter("@+id/locationPermissionButton").substringBefore("/>")
        val account = source.substringAfter("com.amaury.pointage.FirebaseAccountButtonView").substringBefore("/>")
        assertTrue(location.contains("android:minHeight=\"48dp\""))
        assertTrue(account.contains("android:minHeight=\"48dp\""))
    }
}
