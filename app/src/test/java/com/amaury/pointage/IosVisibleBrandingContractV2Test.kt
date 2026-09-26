package com.amaury.pointage

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosVisibleBrandingContractV2Test {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "ios/HPTravail/HPTravail/ContentView.swift").isFile }

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `ios user visible branding is AGKGMG while technical identifiers stay stable`() {
        val contentView = source("ios/HPTravail/HPTravail/ContentView.swift")
        val plist = source("ios/HPTravail/HPTravail/Info.plist")
        val project = source("ios/HPTravail/project.yml")

        assertFalse(contentView.contains("\"HP Travail\""))
        assertTrue(contentView.contains("\"AGKGMG\""))

        assertTrue(plist.contains("<string>AGKGMG</string>"))
        assertTrue(plist.contains("AGKGMG utilise votre position"))
        assertFalse(plist.contains(">HP Travail<"))

        assertTrue(project.contains("CFBundleDisplayName: AGKGMG"))
        assertTrue(project.contains("PRODUCT_BUNDLE_IDENTIFIER: com.amaury.hptravail"))
        assertTrue(project.contains("PRODUCT_MODULE_NAME: HPTravail"))
    }
}
